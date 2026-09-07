# Reflector

Offline-first data synchronisation between many clients and one server: a Kotlin Multiplatform
client SDK (Room, Ktor) and a JVM server module (Exposed, PostgreSQL) that embeds into any
Kotlin or Java backend.

The library does not own your data. It owns the *metadata* of synchronisation — what is dirty,
what is in flight, where the cursor is — and reaches into your tables through one adapter. Your
rows stay yours: your schema, your queries, your migrations.

- [EXAMPLE.md](EXAMPLE.md) — integrating the server and the client, step by step
- [docs/sync-client-design.md](docs/sync-client-design.md) — the client specification
- [docs/sync-server-design.md](docs/sync-server-design.md) — the server specification
- [TODO.md](TODO.md) — work that is known and not yet done
- [AGENTS.md](AGENTS.md) — repository rules and handbook reading order

The two specifications are the source of truth for the mechanism. What follows is the shape of it
and the reasoning behind the choices.

---

## The problem

An application that writes only when it is online is a different application from one that writes
whenever the user wants. The second one has to answer questions the first never asks: what happens
to an edit made in a tunnel, what happens when two devices edit the same row, what happens when the
process dies between "sent" and "acknowledged", what happens when a device comes back after a month.

Most of those questions have more than one defensible answer. What follows is the answer this
library picked, and why.

## How it works

### State, not operations

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

### The unit of consistency is the group, not the row

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

### Conflicts are optimistic and explicit

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

### Versions are opaque, ordering is the server's job

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

### Echo suppression

Every push carries a stable `clientId` — generated on first access to a scope and kept in
`sync_meta`. The server puts it on the batch in the log. When a client pulls its own batch back, it
advances the version and touches no rows — otherwise every device would overwrite the row it just
wrote, with the data it just sent, and any concurrent local edit would be lost.

This is why changing the `clientId` — a reinstall, cleared data — has to be followed by a
bootstrap: the device's own changes would come back with somebody else's origin.

### Crash safety: the pull is two-phase

A pull writes incoming batches into an **inbox** first, then applies them. Applying a batch and
advancing the cursor happen in one transaction with the application's own rows, which is why the
library's tables live in the application's database rather than beside it.

The consequence is that a process killed mid-pull loses nothing and repeats nothing: whatever
reached the inbox is applied on the next run, and whatever did not is fetched again from the same
cursor.

### Bootstrap and resync

A new collection — or one whose cursor the server no longer recognises (`410`) — starts with a
snapshot. The snapshot's first page fixes a cursor, and every later page carries the same one, so
the client can start reading the log from a point it knows is consistent with what it downloaded.

The snapshot is applied with a **mark & sweep generation**: rows the snapshot no longer mentions are
removed, because a deletion that happened while the device was away leaves no tombstone to find. A
record with unsent local changes survives the sweep — the user's work is never collateral damage.

An interrupted bootstrap resumes from its page token and keeps the cursor the first page fixed.

`requestResync()` is the same machinery, exposed on purpose: the library cannot notice that the
application's tables have drifted from what it synchronised — a migration, a repair after a bug, an
import from the side. When that happens, a bootstrap is cheaper than trusting a cursor whose data
changed underneath it. Unsent local changes survive a resync and are pushed afterwards.

### Real time is an alarm clock, not a data path

The WebSocket carries `invalidate`, `resync` and `revoked`. It never carries data. One application
path, and polling as a trivial fallback.

The channel emits **signals**, not just events: `Connected` matters as much as `Received`.
Notifications sent while the socket was down do not exist, and no later message will say "you missed
one" — so every reconnect is followed by an unconditional pull. A client that reacted only to events
would sit silently stale until the next timer.

### Triggers belong to the application

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

### Authentication and revocation

Tokens come from a `TokenProvider`. `401` is not a transport error and never causes backoff: the
client refreshes once, and if that fails the workers stop and the scope becomes `AuthRequired` with
data and queue intact — the user signs in again and the pending groups go out. `403` means the scope
was revoked, and is handled like the `revoked` event: the data goes away.

Revocation is the host's, and it is both of those together. `403` is what makes a client wipe a
scope; the `revoked` event is what makes it happen now rather than whenever that device next asks
for something. The server module holds no access state and can send neither on the host's behalf —
`samples/ledger-server` shows the pair.

### Numbers about all of it

Both sides report through a `SyncMetrics` port the application or host implements — a closed set of
events, no metrics dependency, and nothing at all when it is left out. Every failure mode here is
slow rather than loud, so the numbers are the difference between knowing and guessing: on the client,
how the queue drains and in what condition, what a bootstrap costs, how long it waits out a backoff;
on the server, how far behind its clients are running, and **how long the per-collection counter lock
is held** — the one measurement that says when the deliberate serialisation of writers has turned
into a queue.

### Limits are known before the envelope is built

`GET /v1/sync/config` publishes `maxOperationsPerGroup`, `maxDocumentBytes`, `maxChangesPageSize`
and `retentionDays`, and the client caches them at start. A group cannot be split — that would break
atomicity — so a group that does not fit has to be detected locally and handed to the application
through `onRejected`, rather than arriving as a network refusal after a long offline stretch. The
retention window lets a client decide to bootstrap *before* the server answers `410`.

### Sign-out is the application's decision

`signOut(discardPending = false)` refuses to sign out while the queue is not empty; with `true` it
wipes data and queue unconditionally. `CollectionSyncState.pendingCount` is what the application
asks the user with. The library will not silently lose work, and will not silently block a logout
either.

## The wire protocol

```
GET  /v1/sync/config                              → limits and retention window
POST /v1/sync/{scope}/{collection}/push           → {applied | conflict | rejected} per group
GET  /v1/sync/{scope}/{collection}/changes?cursor= → batches, nextCursor, hasMore
GET  /v1/sync/{scope}/{collection}/snapshot?page=  → cursor, items, nextPage, hasMore
WS   /v1/sync/{scope}/events                       → invalidate | resync | revoked
```

Rules the server must honour, in full, are listed in §11 of the
[client specification](docs/sync-client-design.md). The ones that bite hardest:

- `upsert` is a **patch-merge by present top-level keys**: a missing field is left alone, an
  explicit `null` clears it, and nested objects are replaced whole. The client's serializer must
  therefore write nulls out (`explicitNulls = true`, which `SyncProtocolJson` sets).
- A push is **idempotent by `groupId`**: re-sending returns the stored result instead of applying
  the group twice.
- Entity identifiers are the **application's** to generate, and the server stores them as it
  receives them. UUIDv7 is what the design assumes, because a time-ordered key keeps the server's
  indexes local; nothing enforces it.
- The set of operation codes (`upsert`, `delete`) is fixed. A client that meets an unknown code
  resyncs the collection, so adding one is a breaking protocol change.

## Modules

```
contracts/sync-protocol      Wire DTOs, identifiers, serializers — the shared vocabulary
client-sdk                   The client library, published as one artifact:
  …/sync/core                Public API and ports (adapter, transport, tokens, triggers)
  …/sync/persistence         The library's Room tables, DAOs and typed stores
  …/sync/engine              Mutations, push, pull, bootstrap, conflicts, workers
  …/sync/network             Ktor transport and event channel
server-sdk                   The server module, published as one artifact:
  …/sync/server              Server module API and its ports
  …/sync/server/postgres     Exposed + PostgreSQL implementation, Flyway migrations
samples/ledger/shared        A consumer application: its own tables, adapter, wiring
samples/ledger/android       A thin Android application: database, transport and platform triggers
samples/ledger-server        A reference host: routes, scope authorisation, events
```

The client is one artifact rather than four. An application that synchronises needs the engine, the
tables and a transport together — and the library's entities are declared in *your* `@Database`, so
a module boundary in front of them hid nothing while costing three dependency lines. The division
survives as packages. `SyncTransport` is still the extension point: implement it over your own stack
and the bundled Ktor client goes unused.

## Build

```bash
./gradlew build              # compile and test
./gradlew spotlessApply      # format
./gradlew spotlessCheck      # verify formatting
./gradlew publishToMavenLocal # publish the artifacts to ~/.m2 for a local consumer
```

The published coordinates are `dev.voir.reflector:client-sdk` and
`dev.voir.reflector:server-sdk`, with `dev.voir.reflector:sync-protocol` arriving transitively
with either. Nothing is published to a remote repository — see [TODO.md](TODO.md) §2 for what that
still needs.

The client is built and tested on JVM, Android and both iOS targets. Tests in `server-sdk` and
`samples/ledger-server` start PostgreSQL through Testcontainers and need a working Docker;
everything else runs without external dependencies.

### Building from a network-isolated environment

The environments the code is written in cannot reach Maven Central, so Gradle runs on the
developer's machine through a worker:

```bash
bash build/watch.sh          # once, in its own terminal
echo "build" > build/request # queue a task
cat build/status             # exit=0 means done
less build/last-build.log    # the whole output
```

The worker kills a build that exceeds `DEADLINE_SECONDS` (600 by default) so a hung test cannot
block the queue.

## Versions

`VERSION` holds the repository version (currently `0.1.0`) and every module takes it. Coordinates
are derived from the path — `dev.voir.reflector.client`, `dev.voir.reflector.server`,
`dev.voir.reflector` for the contracts — because identically named modules on both sides otherwise
collapse into one artifact during resolution.

Kotlin 2.4.10 / Gradle 9.7.1 / AGP 9.4.0 is a point inside the Kotlin Gradle Plugin's official
compatibility matrix, even though newer Gradle and AGP releases exist.

Static analysis is limited to formatting: detekt's stable 1.23.x is built against Kotlin 2.0.21, and
the 2.0 branch (group `dev.detekt`, built against 2.4.10) is still in alpha. The version is in the
catalogue, ready to be switched on — [TODO.md](TODO.md) §5.
