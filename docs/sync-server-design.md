# Reflector — the server module (Kotlin + Exposed + PostgreSQL)

The specification of the server module: what it owns, how it orders changes and what it requires
from the host embedding it. The companion document:
[the client specification](sync-client-design.md).

---

## 1. The module's role and the boundaries of responsibility

The server side is an **embeddable library**, not a service. It is added to any Kotlin or
Java backend as an ordinary dependency, brings its own database schema and its own
migrations, and gives the host a plain Kotlin API.

| Responsibility | Whose |
| --- | --- |
| Authentication, authorisation, resolving access to a scope | The host |
| HTTP and WebSocket endpoints, connections, transport serialisation | The host |
| Fanning invalidate notifications out between instances | The host |
| The sync database schema and its migrations | The module |
| Ordering of changes, versions, cursors, conflicts, retention | The module |
| Storing entity documents | The module |
| Business rules on top of the documents | Nobody (by construction) |

A `ScopeId` arrives in the module **already authorised**. The module knows nothing about
tokens, does not call an identity service and does not decide whether a user has access to a
shared scope. This has to be repeated in the KDoc of every public operation: the temptation
to "let the module check as well" appears at the very first integration and leads to access
rules being duplicated in two places.

## 2. Decisions taken

| Question | Decision |
| --- | --- |
| Who stores entity bodies | The module, as opaque `jsonb` |
| Assigning order | A counter row per collection, `SELECT … FOR UPDATE` |
| Change notifications | An in-process listener after commit, fan-out on the host |
| Write semantics | Patch-merge by top-level keys |
| API style | Blocking, callable from Java |
| DBMS | PostgreSQL (deliberately, not "anything through Exposed") |
| Schema isolation | Its own `sync` schema with its own Flyway history |

### Why the module stores bodies

The decision looks like a contradiction of the rule "the module does not own business
entities", but it is not one: the module **stores entities without modelling them**. Not one
table for a concrete type, not one type in the code, not one rule. To the module it is
`jsonb`.

The deciding argument is patch-merge. Only whoever holds the document can merge an incoming
patch into it. If the bodies lived in the host's tables, every push operation would require a
transactional read-modify-write through the host's adapter, and the correctness of versioning
would depend on the care taken in someone else's implementation for every entity type. For a
"add it and it works" library that is an unbearable integration contract.

The symmetry with the client is false here. On the client the application must own the rows,
because the UI walks them with relational queries and the SDK cannot know the schema. On the
server, in a sync-first product, nobody reaches those rows relationally.

The only objection is that the host has no relational access to the data. That is closed by
the module's read API for administrative scenarios, and by `ProjectionListener`, which is
called **inside the same transaction**: the host can maintain its own relational projections
for reporting while keeping one source of truth. A projection is derived and can be rebuilt
from the changelog.

If some collection needs server-side business logic on top of its data, that is a signal that
it should not be a synchronised collection — not a reason to change how it is stored.

## 3. The public API

```kotlin
interface SyncService {
    fun push(scope: ScopeId, collection: CollectionId, request: PushRequest): PushResponse
    fun changes(scope: ScopeId, collection: CollectionId, cursor: Cursor?, limit: Int): ChangesPage
    fun snapshot(scope: ScopeId, collection: CollectionId, page: PageToken?, limit: Int): SnapshotPage
    fun head(scope: ScopeId, collection: CollectionId): Cursor
    fun limits(): SyncLimits
}
```

Five operations cover the whole protocol. The host builds the transport on top of them
itself: mapping onto Ktor, Spring MVC or gRPC is its business, and the module knows nothing
about it.

Every answer names the collection's **incarnation** — `epoch` on `ChangesPage`, `SnapshotPage` and
`PushResponse` — and a cursor carries it as well as a position. A push carries back the one the
client believes in, and a request naming an incarnation that no longer exists is refused with
`CollectionResetException` before anything is written. Section 10 says why.

`limits()` serves the effective limits (`maxOperationsPerGroup`, `maxDocumentBytes`,
`maxChangesPageSize`, the retention window) — the host publishes them through the
`GET /v1/sync/config` endpoint. The client must know the limits before it assembles the
envelope: it cannot split a group, so an overflow must be detected on its side rather than
arriving as a refusal after a long offline stretch. The values are read from `SyncConfig` and
require no database access.

The API is **blocking**. Exposed on JDBC is blocking by nature, and it also keeps the module
callable from Java without wrappers around `suspend`.

In addition, the module offers a read API for the host's administrative scenarios:

```kotlin
interface SyncQueries {
    fun document(scope: ScopeId, collection: CollectionId, type: EntityType, id: Uuid): StoredDocument?
    fun documents(scope: ScopeId, collection: CollectionId, type: EntityType, page: PageToken?, limit: Int): DocumentPage
}
```

And the removals the host schedules or orders, which are not protocol operations at all:

```kotlin
class SyncMaintenance {
    fun trim(): Int
    fun purgeScope(scope: ScopeId): PurgeReport
    fun purgeCollection(scope: ScopeId, collection: CollectionId): PurgeReport
}
```

## 4. The schema

All tables live in a separate `sync` schema.

```sql
sync.collections (
  id                   uuid        primary key,
  scope_id             varchar(200) not null,
  collection_id        varchar(100) not null,
  next_seq             bigint       not null,   -- the counter row, locked with FOR UPDATE
  retention_floor_seq  bigint       not null,   -- below it a cursor is considered stale
  created_at           timestamptz  not null,
  constraint uq_collections__scope_id__collection_id unique (scope_id, collection_id)
);

sync.batches (
  id                uuid        primary key,
  collection_id     uuid        not null references sync.collections,
  seq               bigint      not null,       -- monotonic within a collection
  origin_client_id  uuid        null,           -- echo suppression is impossible without it
  client_group_id   uuid        null,
  committed_at      timestamptz not null,
  constraint uq_batches__collection_id__seq unique (collection_id, seq)
);

sync.changes (
  id           uuid    primary key,
  batch_id     uuid    not null references sync.batches on delete cascade,
  ordinal      int     not null,
  entity_type  varchar(100) not null,
  entity_id    uuid    not null,
  op           varchar(10)  not null,           -- UPSERT | DELETE
  version      bigint  not null,
  data         jsonb   null,                    -- history within the retention window
  constraint uq_changes__batch_id__ordinal unique (batch_id, ordinal)
);

sync.entities (
  id             uuid        primary key,
  collection_id  uuid        not null references sync.collections,
  entity_type    varchar(100) not null,
  entity_id      uuid        not null,
  version        bigint      not null,          -- = the seq of the batch of the last change
  data           jsonb       null,              -- null for a deleted entity
  is_deleted     boolean     not null,
  last_seq       bigint      not null,
  created_at     timestamptz not null,
  updated_at     timestamptz not null,
  constraint uq_entities__collection_id__entity_type__entity_id
    unique (collection_id, entity_type, entity_id)
);
create index ix_entities__collection_id__entity_type__entity_id
  on sync.entities (collection_id, entity_type, entity_id);

sync.push_results (
  id             uuid        primary key,
  collection_id  uuid        not null references sync.collections,
  client_id      uuid        not null,
  group_id       uuid        not null,
  status         varchar(20) not null,
  response       jsonb       not null,          -- the stored answer for a re-sent envelope
  created_at     timestamptz not null,
  constraint uq_push_results__client_id__group_id unique (client_id, group_id)
);
```

A cursor is the **seq of a batch**, not of an individual change. One seq per server
transaction, with the changes inside it numbered by `ordinal`. Thanks to that a cursor
physically cannot point inside a batch, and the rule "pages are cut only at transaction
boundaries" holds by construction rather than by developer discipline.

**An entity's version is the seq of the batch** that changed it last. A separate version
counter is not needed: the client compares versions only for equality, and an entity cannot
change twice in one batch.

## 5. Invariants

1. The counter row's lock is taken in **the same** transaction that inserts the batch and is
   held to commit.
2. `sync.entities.version = seq` of the batch containing this entity's last change.
3. A batch is atomic: either all of its changes are visible or none of them are.
4. `retention_floor_seq` grows monotonically and is raised **before** old batches are deleted.
5. Listeners are called after the commit; their errors do not affect data already accepted.
6. `ProjectionListener` is called inside the transaction; its failure rolls the batch back.
7. A cursor names an incarnation as well as a position, and a cursor from another incarnation — or
   one beyond the head of its own — is refused rather than answered.
8. A push names the incarnation it was made against, and one naming any other is refused whole,
   before a single group of it is applied.

## 6. Assigning order

```sql
SELECT next_seq FROM sync.collections
 WHERE scope_id = ? AND collection_id = ? FOR UPDATE;
```

This is the core of the whole protocol's correctness. As long as the lock is held to the end
of the transaction, two transactions on one collection cannot commit in an order different
from the order of their seqs — which means a reader will never see an omission.

The classic trap this closes: a transaction took seq 5, a neighbouring transaction took seq 6
and committed first. A reader that got as far as 6 puts its cursor on 6 and loses change 5
**forever**. It surfaces weeks later as "a record disappeared for this user", and it cannot be
reproduced from the logs.

If anyone ever "optimises" this by taking a seq in a separate short transaction or from a
`SEQUENCE`, the mechanism breaks silently. The code must carry a comment explaining why the
lock cannot be released before the commit.

The price is that writers into one collection are serialised. For a collection whose scope is
usually a single user, that is insignificant. If parallel writes into one scope are ever
needed, the replacement is issuing seqs from a sequence plus a visibility watermark (readers
see only up to the boundary below which there are no unfinished transactions). That is why
order assignment is hidden behind an internal port, so that a replacement does not touch the
rest of the code.

## 7. Push

Each group gets **its own transaction and its own lock**, and groups are processed
sequentially. One transaction for the whole request would roll back accepted groups because of
a conflict in one of them, and we made them independent on purpose.

```
for each group in request.groups, in order:
  if a stored result exists for (client_id, group_id) — return it without applying
  tx:
    lock counter (see section 6) → seq
    for each operation:
      read sync.entities
      if op.baseVersion != current.version (or the entity exists while baseVersion = null)
        → conflict: roll the whole group back, collect the current state of the
          conflicting entities
    if there are no conflicts:
      apply the operations, insert the batch and the changes, store the result in push_results
```

**The patch-merge is done in Kotlin**: `JsonObject(current + patch)`, which merges by exactly the
top-level keys and replaces nested objects whole — precisely the semantics the client specification
fixes. The collection's counter is already locked at that point, so the read-modify-write is safe,
and the whole push path stays on the typed DSL instead of raw SQL with hand-written parameter
typing.

PostgreSQL's `jsonb ||` operator has the same semantics and would save the read of the current
document:

```sql
UPDATE sync.entities
   SET data = COALESCE(data, '{}'::jsonb) || :patch,
       version = :seq,
       last_seq = :seq,
       updated_at = :now
 WHERE collection_id = :collection
   AND entity_type = :type
   AND entity_id = :id
   AND version = :base_version;
```

It is the recorded fallback for the day a profiler shows the extra read is expensive — a local
replacement of one method, and the reason the merge is kept behind one.

**An operation against an entity the server does not have is not a conflict.** There is
nothing to argue about: there is no row and nothing to overwrite. Refusing here would trap a
client whose tombstone has already been cleaned up by retention — it would forever re-send a
change with a base version that no longer exists. Such an operation is applied as a creation.

An explicit `null` is stored as a JSON null rather than removing the key: clearing a field
stays visible, idempotent and distinguishable from "the client does not know about this
field".

The response mirrors the client's contract: per group, `applied` with the new versions,
`conflict` with the current server state, or `rejected` with an error code for the cases where
retrying is pointless (an unregistered entity type, exceeding the document size limit). A
refusal caused by a dependency between groups (`DEPENDENCY`) is served with its own code so
that the client merges the groups and retries rather than treating it as a conflict. That code
is the only `rejected` after which a retry makes sense, so it must not be used for anything
else: the client answers it by merging groups, and using it loosely would turn a deterministic
error into a merge loop bounded only by the attempt ceiling.

Idempotency by `group_id` is mandatory: the client re-sends when the response is lost, and
without a stored result the repeat would be applied a second time.

**A group too large for the limits is refused, and is not accepted in parts.** The ceiling is
the answer to "what happens after a long offline stretch": `maxOperationsPerGroup` and the
collection's `maxDocumentBytes` are published through `limits()`, the client checks them before
the envelope goes out, and a group that reaches the server oversized anyway is refused with
`TOO_LARGE`. Accepting the envelope in parts with a deferred commit would solve the same
problem at the cost of a stateful upload session and the garbage collection of abandoned parts;
it stays the recorded fallback until a real workload produces a group the ceiling refuses.

## 8. Changes

```
tx (one consistent read):
  if cursor's epoch ≠ this collection's   → CollectionReset  -- it was purged and began again
  read retention_floor_seq; if cursor < floor → CursorTooOld
  read next_seq;            if cursor > head  → CollectionReset  -- the log went backwards
  SELECT batches WHERE collection = ? AND seq > cursor ORDER BY seq LIMIT n
  read their changes
nextCursor = the largest seq on the page, hasMore = whether batches were cut off
```

Checking the floor and reading the batches happen in one transaction: beyond that, correctness
is provided by MVCC — the reader works on its own snapshot and cannot hit rows being deleted
by retention.

`data` is taken from `sync.changes` — that is, the state **at the moment of the batch** is
served, not the current one. This is exactly what makes the pull incremental and resumable and
keeps atomicity at the level of a transaction rather than of the whole sync cycle.

A client's own changes come back to it with an `originClientId` equal to its own — echo
suppression on the client is built on that.

**A scope is synchronised whole.** The log carries two operations, `upsert` and `delete`, and no
third. Server-side subscription filtering — a client subscribing to part of a collection, and being
told when an entity leaves that part — is deliberately not designed. The cheap-sounding versions are
not cheap: a predicate per subscription means every write asks, for every active predicate, whether
the entity has left it, and the cost grows with the number of distinct predicates rather than with
the writes.

Almost every reason to reach for a filter is a scope that is too large, and splitting the scope is a
better answer to that: it is bounded, it needs no protocol change, and it makes access a question
the host answers once per scope rather than per entity. If filtering is ever genuinely needed, the
shape to build is slices as materialised memberships that the host maintains through
`ProjectionListener` — the host usually knows cheaply which slice an entity belongs to, and the
module would only transport what it says changed. That keeps the hard part with the party that has
the answer.

Whatever the shape, adding an operation code to the log is a breaking protocol change: a client that
meets a code it does not know resynchronises the whole collection, so a new code cannot be rolled out
gradually and needs version negotiation first.

## 9. Snapshot

```
if page == null: cursor0 = head(collection)
keyset pagination by (entity_type, entity_id) over the non-deleted entities
→ {"cursor":"1100",
   "items":[{"entity":"wallet","id":"…","version":"42","data":{…}}],
   "nextPage":"…","hasMore":true}
```

A snapshot item must carry a `version`: the client puts it into `server_version`, and without
it the very first push after a bootstrap goes out with `baseVersion = null`, which the server
reads as the creation of a new entity and answers with a conflict out of nowhere. Deleted
entities do not appear in a snapshot — the client removes them with its sweep.

The snapshot is deliberately "dirty": the server does not hold repeatable read across all the
pages. An entity that changed between pages comes back in a newer state — the client applies
it idempotently and then catches up with the log from `cursor0`. Repeats are possible,
omissions are not.

Pagination is keyset rather than offset: collections can be large, offset degrades at large
displacements and does not give a stable order under concurrent writes.

**The page token carries `cursor0` inside it.** Otherwise the second page would return a
cursor computed anew, and a client that took it would silently jump over everything committed
while the snapshot was being transferred. The module keeps no state between requests, so the
token itself carries the position.

## 10. Retention and purge

The history retention window and the cursor's lifetime are **the same number**. A client whose
cursor fell out of the window goes to bootstrap anyway, so keeping history for longer is
pointless — and keeping it for less is not allowed.

The maintenance operation (its schedule is the host's concern):

```
floor := the seq of the oldest batch that must be kept
UPDATE sync.collections SET retention_floor_seq = floor …   -- the floor first
DELETE FROM sync.batches WHERE collection_id = ? AND seq < floor
DELETE FROM sync.entities WHERE … is_deleted AND last_seq < floor   -- tombstones
```

The order is mandatory: the floor is raised before the deletion, otherwise a reader could pass
the check and receive an incomplete range instead of an honest `CursorTooOld`.

**The log is not partitioned, and the first move when it has to be is time, not tenant.** One
`scope_id` column in shared tables is the whole of the multi-tenancy today. It is not a problem at
small scale and becomes one at large in a specific way: the log grows with the write volume of every
scope together, and this sweep walks every collection to delete rows one range at a time.

Partitioning `sync.batches` by range on `committed_at` is what pays for itself first, because it
turns exactly that into a metadata operation — retention drops whole partitions instead of deleting
rows, and the reads stay on the recent partitions where the cursors are. Hashing by `scope_id`
buys much less: reads and the sweep are already scoped by `collection_id`, and the imbalance between
a large tenant and a small one survives the hash. The price of either is that PostgreSQL requires
the partition key in every unique constraint, so `uq_batches__collection_id__seq` would have to
carry `committed_at` — which is a migration of the constraint that guarantees the ordering, and not
a change to make speculatively.

It is therefore recorded rather than built, and `SyncMetricEvent.HistoryTrimmed` is what says when to
build it: a `retainedSpan` that grows from one run to the next is history accumulating faster than
the window discards it.

Tombstones live for exactly the same window: a deleted entity is only needed by clients that
can still arrive with a cursor inside the window. Everybody else learns about the deletion
through the entity's absence from the snapshot and their own sweep.

### Purge

Retention is the window doing its work. **Purge is erasure**, and the two must not be confused: a
pushed deletion leaves a tombstone the log carries so that clients can learn about it, and a purge
leaves nothing at all. It exists because a host is eventually asked to state that a tenant's data is
gone, and "gone" cannot mean a tombstone with the document still in it.

```
tx:
  SELECT collections WHERE scope_id = ? [AND collection_id = ?] FOR UPDATE
  DELETE push_results, changes, entities, batches of each  -- children first, counted
  DELETE the collection row itself                          -- counter included
```

Four decisions are worth recording.

**The collection row goes too, so the counter restarts at one.** The alternative — keeping the row
with its `next_seq` and raising the floor to it — would preserve monotonic sequences for free, and
was rejected because the row carries `scope_id`, which for most hosts is the user or tenant
identifier they were asked to erase. A purge that leaves that behind is not one the host can stand
behind.

**Therefore a cursor beyond the head is refused.** That is the price of the decision above and it is
paid on the read path, in one comparison: after a purge the log starts again at one, and a client
that survived with a cursor from before it would be served an empty page for ever while the
collection filled up behind it. Refusing sends it to a bootstrap, which is the only thing that gets
it back — and it is the same refusal as a stale cursor, because the client's answer to both is the
same.

**The rows are deleted explicitly, not left to `ON DELETE CASCADE`.** The cascade would remove the
same rows and report nothing, and a purge whose extent cannot be stated is one the host cannot put
in its own audit trail. `PurgeReport` is what it says instead. The one index the schema lacked for
this — `ix_push_results__collection_id` — was added with it: that table's only constraint leads with
`client_id`, so both the explicit delete and the cascade would otherwise scan it whole.

**Unregistered collections are purged too, and no listener is told.** A collection removed from
`SyncConfig` can be reached by no other operation, so refusing to purge it would strand exactly the
rows a host most wants gone. And there is no sequence a commit listener could be notified at; the
host ordered the purge itself and knows it happened.

### Telling the survivors

A purge erases the server. What it cannot erase is the copy on every device that was synchronising,
and those devices go on pushing. The worker on a client pushes before it pulls, so a client that
only learned about the purge from a read would already have put its queue back into the empty
collection — and the server could not tell those writes from ordinary new ones.

So the collection's **incarnation** is part of the protocol. It is the collection row's own
identifier, which is random per row and which a purge deletes with the row, so a re-created
collection is a different incarnation without the schema carrying a counter anybody could forget to
bump. It is published on every answer, it is half of every cursor, and a push carries back the one
the client believes in:

```
push:      request.epoch  ≠ the collection row's id, or no such row → CollectionReset, nothing applied
changes:   cursor's epoch ≠ the collection row's id                 → CollectionReset
snapshot:  page token's epoch ≠ the collection row's id             → CollectionReset
```

The host answers `409`, distinct from the `410` of a stale cursor, because the two ask the client
for different things: `410` says rebuild and keep what you have not sent, `409` says rebuild and
keep nothing. Collapsing them either loses a user's offline edits after an ordinary retention gap,
or puts an erased collection back after a purge.

Three details are worth stating because each was the alternative:

**The epoch check reads the collection row rather than creating it.** A stale client arriving after
a purge must not bring the scope's row — and its `scope_id` — back into the database it was erased
from. Only a client that claims no incarnation at all, which is one that has never synchronised this
collection, is allowed to create it.

**A cursor carries the incarnation instead of the client carrying it separately.** A position is
only a position in the log it was taken from, and the two travelling together is what makes it
impossible for a caller to check one and forget the other. The client still treats the cursor as
opaque: the server now serves one per batch — `ChangeBatch.cursor` — rather than letting the client
build one from a sequence, which is what it used to do.

**A cursor ahead of the head, with a matching incarnation, is refused too.** That is not a purge —
it is a restore from a backup, the log having gone backwards under a client that is still ahead of
it. The client's situation is the same, so the answer is the same.

`SyncMetricEvent.CollectionResetRefused` counts these. It should spike after a purge and fall to
nothing: an erasure is not finished when the rows are gone but when the last device has stopped
carrying them, and this is the only place that is observable.

What none of this does is make the purge survive a host that keeps serving the scope. A client that
is refused simply rebuilds and starts again, and a user who then writes has made new data. Stopping
that is what revoking access is for, and it is stated in the API rather than implied.

## 11. Events and projections

```kotlin
/** Called after a batch is committed. Listener errors do not affect accepted data. */
fun interface SyncCommitListener {
    fun onCommitted(scope: ScopeId, collection: CollectionId, seq: Long)
}

/** Called inside the batch's transaction. A failure rolls the whole batch back. */
fun interface ProjectionListener {
    fun onBatch(scope: ScopeId, collection: CollectionId, changes: List<AppliedChange>)
}
```

```kotlin
/** Called after the work is durable, never while the counter lock is held. */
fun interface SyncMetrics {
    fun record(event: SyncMetricEvent)   // PushGroupServed | ChangesServed | SnapshotServed
}                                        // CursorRefused | HistoryTrimmed
```

```kotlin
/** The same guarantees; the level is the host's, answered from its own logging framework. */
fun interface SyncLog {
    fun log(record: SyncLogRecord)                 // level, event, message, context, cause
    fun isEnabled(level, source): Boolean = true   // asked before a record is built
}
```

The split is fundamental. `SyncCommitListener` is a notification for the WebSocket channel and
must be **after** the commit: a delivery failure must not roll back data that has already been
accepted, and in the worst case the client learns about the change from its timer.

`ProjectionListener` is the opposite — part of the transaction: a host projection that has
diverged from the module's data is worse than a refused write.

Fanning notifications out between instances is the host's concern. The module imposes neither
a bus nor `LISTEN/NOTIFY`, because any real backend already has a delivery mechanism.

**Revoking a scope is the host's, and it is two acts rather than one.** The module receives an
already authorised `ScopeId` and holds no access state at all; giving it a `revoke(scope, client)`
entry point would mean giving it that state, which is the one thing sections 1 and 15 refuse. What
the protocol contributes is the second half: a `revoked` event on the scope's socket, which a client
answers by wiping the scope's data. So a host that removes somebody from a shared workspace does
both:

1. refuse the scope from then on with `403` — not `401`, because the codes are the client's
   recovery instructions: `401` preserves the queued changes for whoever signs in next, `403` wipes;
2. publish `revoked` to that scope's connected sockets.

Only the first is required for correctness; without the second the data stays on the device until
its next request happens to be refused, which for a device nobody opens is however long that takes.
`samples/ledger-server` shows the pair as `ScopeRevocations`, which is fifteen lines and belongs to
the host in every one of them.

`SyncMetrics` is a third listener of the same kind, and a port for the same reason as the others:
this module has almost no dependencies, and no host will swap its metrics backend for the module's.
The events are the numbers a host cannot obtain from outside:

- `PushGroupServed` — operations, outcome (`APPLIED` | `CONFLICT` | `REJECTED` | `REPEATED`) and
  **how long the counter lock was held**. That last one is the reason this port exists. Writers into
  a collection are serialised by that lock, deliberately, and it is invisible until it is a queue;
  the day it becomes the bottleneck will be found either on a dashboard or by users. It is measured
  from taking the lock to the end of the transaction that held it, which is why the moment is
  carried out of the applier — the lock is released by the commit, not by the applier returning.
- `ChangesServed` — batches served and **the client's lag**, in sequences from its cursor to the
  head. It is measurable here and nowhere else: to a client a cursor is opaque.
- `SnapshotServed`, and `CursorRefused` with how far past the window the cursor was — together, how
  often clients are being sent back to a full transfer, and whether the retention window is too
  short for the population.
- `HistoryTrimmed` — per collection, what the sweep removed and what span of log is left.

Like the commit listener, it is called after the work is durable and never inside the transaction
that did it, and a failure in it is swallowed: a metric must not be able to undo a batch the
database has accepted.

`SyncLog` is the fourth, and it answers what the other three cannot. Metrics say how a deployment is
doing and are built to be aggregated; a log says what happened to one request, in order, and is
meant to be read. The module has one failure that is invisible in every other channel: a host commit
listener that throws leaves the batch durable and the notification undelivered, so every client of
that scope falls back on its own poll — synchronisation that looks slow rather than broken. The
listener's own contract has always said such errors "are logged and ignored", and until this port
existed there was nothing to log them to.

A port and not SLF4J, even though this module is JVM-only and SLF4J is the universal facade there.
The module's near-absence of dependencies is a property worth keeping, and the adapter is about ten
lines — `samples/ledger-server` has it as `Slf4jSyncLog`, alongside the `logback.xml` that
demonstrates what it buys.

**`isEnabled(level, source)` is where the level lives, and that is the point of the port.** An
adapter answers it from the host's own framework, so what the module says is configured wherever
every other level in the host is configured, at runtime and per area:

```xml
<logger name="dev.voir.reflector.sync" level="INFO"/>
<logger name="dev.voir.reflector.sync.push" level="DEBUG"/>
```

Per area matters on a server. Turning one level up for everything is not practical when the read
paths outnumber the writes by orders of magnitude and would bury them; being able to follow every
group the module applies while `changes` and `snapshot` stay quiet is the difference between a
usable investigation and a full disk.

Two things it reports deserve naming. `LOCK_HELD_LONG` fires when the counter lock was held past a
deliberately generous threshold — the same serialisation `PushGroupServed` measures, said in words
to somebody reading a log rather than a dashboard. And `GROUP_REPEATED` reports a group answered
from its stored result: idempotency working exactly as designed, invisible otherwise, and the
hardest thing to understand from the client's side, where it looks like a server replying about
content that was never sent.

Its records carry no stored document, at any level. The module holds its users' business data as
opaque `jsonb`; server logs are shipped to aggregators, retained for months, and read by people who
were never granted the scope.

## 12. Configuration and registration

```kotlin
SyncModule.create(
    database = database,
    config = SyncConfig(
        collections = setOf(
            CollectionSpec(
                id = CollectionId("ledger"),
                entityTypes = setOf(EntityType("wallet"), EntityType("transaction")),
                maxDocumentBytes = 256 * 1024,
            ),
        ),
        retention = 30.days,
        maxOperationsPerGroup = 500,
        maxChangesPageSize = 500,
    ),
    clock = clock,
)
```

Registering collections and entity types is mandatory. An unregistered type is refused with
`rejected`, otherwise a typo in a client would silently create a junk collection that in six
months nobody recognises and nobody dares to delete.

The limits are part of the contract, not idiot-proofing: without a ceiling on group size a
client will, after a long offline stretch, send a request that fits neither into memory nor
into the timeout.

## 13. Migrations and schema isolation

The module owns **its own PostgreSQL schema `sync`** and its own Flyway history inside it. The
module's migrations physically cannot collide with the host's, and the module's schema version
is updated together with the version of the dependency.

- the migrations live in the module's resources, on a path that does not overlap the host's;
- the module starts its own Flyway instance with the `sync` schema and its own history table;
- `SchemaUtils.create` and automatic schema alignment are never used — neither at start-up nor
  in tests against production migrations;
- the Exposed table declarations and the migrations describe the same schema and are reviewed
  together;
- the primary key's name is set by the migration: `UuidTable` declares its own and does not
  allow it to be overridden.

**There are no DAO entities in the module.** Every operation here is set-based: locking the
counter, a conditional update by version, batch inserts, keyset pagination, deletion by range.
Five DAO classes would be dead code, and none of them would make any of that clearer.

This used to be recorded as a deliberate departure, because the handbook of the day asked for a DAO
entity per table. It is no longer a departure: the `exposed-v1-5` skill asks for a DAO entity only
where entity navigation, dirty tracking or row lifecycle makes a use case clearer, and for the DSL
otherwise — which is what this module does. The note is kept rather than deleted because the
reasoning is the same either way, and because a reader comparing this file against an older revision
of the standard should find the question already answered.

Every column holding a `kotlin.time.Instant` is declared as `TIMESTAMPTZ`.

## 14. Transactions and concurrency

- The module owns the transaction boundary itself and receives a `Database` explicitly. The
  host must not wrap the module's calls in a transaction of its own: nesting would hold the
  counter lock longer than necessary and raise the risk of deadlocks.
- There is no remote I/O inside a transaction. Commit listeners are called after it finishes.
- The counter lock already serialises writes into a collection, so additional row locks on
  entities are unnecessary — the optimistic version check in the `WHERE` clause is enough.
- Transaction retries are acceptable only where the block is fully idempotent; a push is not,
  and it is retried by the client under its own `group_id`.

## 15. What the host must provide

1. Resolve the `ScopeId` from its own access model before calling the module.
2. Publish endpoints on top of the five operations and keep the WebSocket channel up.
3. Fan `onCommitted` out between its instances and send invalidate to the clients.
4. Run the retention maintenance operation on a schedule, and stop serving a scope before it
   purges one.
5. Provide a `Database` and a connection pool, and run the module's migrations at start-up.
6. Not wrap the module's calls in a transaction of its own.

## 16. Testing

- PostgreSQL Testcontainers is mandatory: the behaviour relies on `FOR UPDATE`, `jsonb ||`,
  MVCC and unique constraints — on an in-memory DBMS these tests are meaningless.
- A dedicated test for the absence of gaps: concurrent pushes into one collection, then a check
  that a sequential read of the log returns every batch with no omissions.
- A push idempotency test: re-sending the same group does not create a second batch.
- A retention race test: a read with a cursor at the edge of the window during maintenance
  gives either the full range or `CursorTooOld`, but never a partial range.
- A patch-merge test: a field the server does not know survives a write from an old client, and
  an explicit `null` clears a field.
- A purge test that counts rows in every table afterwards, one that checks a cursor issued before
  the purge is refused rather than answered with an empty page, and one that checks a push naming
  the purged incarnation writes nothing — including not re-creating the collection row.
- The migrations are run by Flyway from an empty database, and the operations are then verified
  against that schema.
