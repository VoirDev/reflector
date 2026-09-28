# How it works

The shape of the mechanism and the reasoning behind each choice. This is the page to read before
integrating: every rule the [server guide](server-guide.md) and the [client guide](client-guide.md)
ask you to follow comes from a decision made here. The precise mechanism — schemas, state machines,
the full contract — is in the specifications: [client](sync-client-design.md),
[server](sync-server-design.md), [files](sync-files-design.md).

## The problem

An application that writes only when it is online is a different application from one that writes
whenever the user wants. The second one has to answer questions the first never asks: what happens
to an edit made in a tunnel, what happens when two devices edit the same row, what happens when the
process dies between "sent" and "acknowledged", what happens when a device comes back after a month.

Most of those questions have more than one defensible answer. What follows is the answer this
library picked, and why.

## The model

```text
scope (usually a userId, may be shared)
└── collection (ledger)          — the unit of consistency, of the cursor and of the worker
    └── entity type (wallet, transaction)
        └── entity (UUID, v7 recommended)
```

A **collection** is the boundary of everything: its own cursor, its own worker, its own bootstrap,
its own push queue. Ordering between collections is not guaranteed and is not needed, because there
are no references between them.

A **scope** is whose data it is. The host resolves a request into a scope with its own access model;
the server module receives it already authorised and never checks it again.

## State, not operations

A change is a **full snapshot of an entity**, not a diff or an operation. The client asks the
adapter for the entity's current state at push time; the server stores it and appends it to a log.

This is the decision everything else rests on. Operation-based synchronisation (CRDTs, OT) buys
automatic merging and pays for it with a history the application has to keep forever, a merge
semantics the user cannot predict, and rows the application can no longer freely rewrite.
State-based synchronisation is dumber and honest: two devices that edited the same entity have a
conflict, and somebody has to decide.

Because the state is read lazily through the adapter, the library never stores a copy of your rows
and never holds a stale base snapshot. What it stores per entity is: which version the server
acknowledged, and whether the row is dirty.

## The unit of consistency is the group, not the row

Marking two entities inside one `mutate { }` block puts them in one **group**. A group is applied
by the server atomically — all of it, or none of it. Groups in one request are independent of each
other.

That is what lets an application keep an invariant across rows. "A transaction and the wallet
balance it changed" is one group; the server will never store one without the other.

Groups are merged when they overlap. If a group is in flight and the user edits an entity that
belongs to it, the edit stays with that group instead of racing it. If the server refuses a group
with `DEPENDENCY` — it depends on something in a group that has not landed yet — the client merges
the two groups under a new identifier and retries, up to a ceiling. The queue is FIFO per
collection, so causality inside a collection is preserved without the server having to model it.

## Conflicts are optimistic and explicit

Every push operation carries a `baseVersion` — the version the client last saw. The server compares
it to what it has and refuses the whole group if any of them is stale, answering with the server's
current version and data for each conflicting entity.

The client then parks the group, records both sides, and asks the adapter:

```kotlin
override suspend fun resolve(conflict: Conflict): Resolution? =
    when (conflict.entityType) {
        // A movement of money is a recorded fact rather than an opinion.
        TRANSACTION -> Resolution.TakeServer
        // A wallet's name is the user's choice, and choosing for them silently is
        // exactly what an offline-first application must not do.
        else -> null   // null → the user decides, via CollectionHandle.conflicts
    }
```

`KeepLocal` re-queues the local state on top of the server's version; `TakeServer` applies the
server's state and drops the local change; `Merged(data)` writes a new state locally and queues it.
A group with several conflicts waits for all of them before moving.

Conflicts also arise on the pull side, when somebody else's change arrives over a local edit that
has not been pushed yet. Same mechanism, different `ConflictOrigin`.

Nothing expires a conflict — discarding a user's edit because it has waited long enough is what
offline-first exists not to do. What does happen is that the collection stops calling itself healthy:
past a configurable number of open conflicts its published phase becomes `NEEDS_ATTENTION` instead of
`LIVE`, because a conflict blocks its group and the queue is FIFO, so a collection nobody answers has
quietly stopped synchronising.

## Versions are opaque, ordering is the server's job

The client never interprets a version or a cursor. It compares versions for equality and hands
cursors back as it received them. Everything about ordering lives on the server, where it can be
made true:

- inside a collection there is a **total order** of changes;
- the change log has **no gaps and no visibility holes** — a batch committed later can never get a
  sequence number below one already served to a client.

The second property is the one that is easy to get wrong and expensive to debug: with a plain
auto-increment column, a transaction that took a number early and committed late is invisible to a
reader that has already passed it, and that reader will never see it again. The server allocates
sequence numbers under a per-collection lock held to commit, and a dedicated test drives concurrent
writers against a live reader to prove that every push is seen exactly once.

## Echo suppression

Every push carries a stable `clientId` — generated on first access to a scope and kept in
`sync_meta`. The server puts it on the batch in the log. When a client pulls its own batch back, it
advances the version and touches no rows — otherwise every device would overwrite the row it just
wrote, with the data it just sent, and any concurrent local edit would be lost.

This is why changing the `clientId` — a reinstall, cleared data — has to be followed by a
bootstrap: the device's own changes would come back with somebody else's origin.

## Crash safety: the pull is two-phase

A pull writes incoming batches into an **inbox** first, then applies them. Applying a batch and
advancing the cursor happen in one transaction with the application's own rows, which is why the
library's tables live in the application's database rather than beside it.

The consequence is that a process killed mid-pull loses nothing and repeats nothing: whatever
reached the inbox is applied on the next run, and whatever did not is fetched again from the same
cursor.

## Bootstrap and resync

A new collection — or one whose cursor the server no longer recognises (`410`) — starts with a
snapshot. The snapshot's first page fixes a cursor, and every later page carries the same one, so
the client can start reading the log from a point it knows is consistent with what it downloaded.

The snapshot is applied with a **mark & sweep generation**: rows the snapshot no longer mentions are
removed, because a deletion that happened while the device was away leaves no tombstone to find. A
record with unsent local changes survives the sweep — the user's work is never collateral damage.

An interrupted bootstrap resumes from its page token and keeps the cursor the first page fixed.

## When the server erases a collection

The host can purge a scope or a collection outright — every row of it dropped, not tombstoned. The
devices that were synchronising it still hold their copy, and the worker pushes before it pulls, so
without help the first of them to reconnect would put that copy straight back.

So every answer names the collection's **incarnation**, a cursor names one as well as a position,
and a push names the one it was made against. A push naming an incarnation that no longer exists is
refused whole, before anything is written (`409`), and the client answers that by discarding the
collection — rows, cursor, queue, conflicts, unsent edits — and rebuilding from the new snapshot.

This is the one place the library throws away work nobody asked it to throw away, and it is
deliberate: after an erasure there is no other version for a local edit to be an edit *of*, and
keeping it would mean deciding that one device outvotes the erasure. It is distinct from `410`,
which also rebuilds but keeps what was never sent.

`requestResync()` is the same machinery, exposed on purpose: the library cannot notice that the
application's tables have drifted from what it synchronised — a migration, a repair after a bug, an
import from the side. When that happens, a bootstrap is cheaper than trusting a cursor whose data
changed underneath it. Unsent local changes survive a resync and are pushed afterwards.

## Files travel beside the log, never through it

A photograph is not a document. Base64 into one puts megabytes through a protocol built for
kilobytes and into a `jsonb` column, so files are a **blob**: an immutable sequence of bytes with a
client-generated identifier, which the document references by an ordinary field of its own.

Immutable is what makes the rest cheap. A blob cannot conflict — there is no version to compare and
no merge two JPEGs could have — so replacing a photograph is an ordinary document change that
happens to move a reference, and every operation on a blob is idempotent without being engineered
to be.

Neither SDK is ever in the data path. The client asks the server to register a file, the server asks
the host to presign a URL, and the bytes go from the device to the host's storage directly. What the
module keeps is metadata about an object it has never seen; what the library keeps is the same, plus
the decision about when each file may move.

```kotlin
override fun blobs(entityType: EntityType, id: EntityId, document: JsonObject): Set<BlobRef> =
    setOf(BlobRef(photoIdOf(document)))     // deferred by default
```

That is the whole coupling between business data and files, and it carries two editorial decisions.
The first: **a record may be published before its file**. A receipt is not a transaction, and a
library that holds up money because a photograph is stuck behind a hotel's captive portal has its
priorities backwards. `DEFERRED` — the default — waits only for the file to be *registered*, which
is a round trip rather than a transfer; `REQUIRED` waits for the bytes, and is for a record whose
file is its content.

The receiving side never waits at all: the document arrives, the cursor advances, and the bytes
follow. "Referenced and not here yet" is therefore a normal state a user interface has to render,
not a failure — and the library publishes it per file so that it can.

The second decision is that state's other reading: **a device need not hold every file it knows
about**. `EAGER` — the default, set on the engine and overridable per reference — fetches the bytes
as soon as a document names them, which is what a list of thumbnails wants. `ON_DEMAND` records the
reference and fetches nothing until the application asks, through `collection.fetch(blobId)`, which
is what a forty-megabyte original wants: the record is on the device, the reference is on the
device, and the bytes arrive when a screen opens them and stay until `evict` gives them up. Nothing
about the protocol changes between the two — a download ticket was always asked for one file at a
time by a device that decided it wanted it.

Deleting is the host's. The module knows which files nothing points at any more, because clients
declare their references on every push; it drops its own rows and hands over the keys. What disposal
means — delete now, a lifecycle rule, a retention period somebody legislated — is a policy the
module has no business holding.

## Real time is an alarm clock, not a data path

The WebSocket carries `invalidate`, `resync` and `revoked`. It never carries data. One application
path, and polling as a trivial fallback.

The channel emits **signals**, not just events: `Connected` matters as much as `Received`.
Notifications sent while the socket was down do not exist, and no later message will say "you missed
one" — so every reconnect is followed by an unconditional pull. A client that reacted only to events
would sit silently stale until the next timer.

## Triggers belong to the application

The library wakes up on: start, `invalidate`, a successful push, a local mutation (debounced), and a
timer. Foreground, connectivity and background tasks are platform events, and they arrive through a
port:

```kotlin
fun interface SyncTriggerSource {
    fun triggers(): Flow<SyncTrigger>   // FOREGROUND | NETWORK | PERIODIC | BACKGROUND
}
```

A library that subscribed to those itself would need the application's manifest entries, its
background modes and its initialisation order — and would still be wrong for anything that is not a
plain foreground app. Shipped implementations: `PeriodicTriggerSource` (a slow timer, the safety net
under everything else) and `ManualTriggerSource`, which the application fires from its own lifecycle
observers.

## Authentication and revocation

Tokens come from a `TokenProvider`. `401` is not a transport error and never causes backoff: the
client refreshes once, and if that fails the workers stop and the scope becomes `AuthRequired` with
data and queue intact — the user signs in again and the pending groups go out. `403` means the scope
was revoked, and is handled like the `revoked` event: the data goes away.

Revocation is the host's, and it is both of those together. `403` is what makes a client wipe a
scope; the `revoked` event is what makes it happen now rather than whenever that device next asks
for something. The server module holds no access state and can send neither on the host's behalf —
[`samples/ledger-server`](../samples/ledger-server) shows the pair.

## Numbers about all of it

Both sides report through a `SyncMetrics` port the application or host implements — a closed set of
events, no metrics dependency, and nothing at all when it is left out. Every failure mode here is
slow rather than loud, so the numbers are the difference between knowing and guessing: on the client,
how the queue drains and in what condition, what a bootstrap costs, how long it waits out a backoff;
on the server, how far behind its clients are running, and **how long the per-collection counter lock
is held** — the one measurement that says when the deliberate serialisation of writers has turned
into a queue.

## Limits are known before the envelope is built

`GET /v1/sync/config` publishes `maxOperationsPerGroup`, `maxDocumentBytes`, `maxChangesPageSize`
and `retentionDays`, and the client caches them at start. A group cannot be split — that would break
atomicity — so a group that does not fit has to be detected locally and handed to the application
through `onRejected`, rather than arriving as a network refusal after a long offline stretch. The
retention window lets a client decide to bootstrap *before* the server answers `410`.

## Sign-out is the application's decision

`signOut(discardPending = false)` refuses to sign out while the queue is not empty; with `true` it
wipes data and queue unconditionally. `CollectionSyncState.pendingCount` is what the application
asks the user with. The library will not silently lose work, and will not silently block a logout
either.
