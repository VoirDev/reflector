# Server guide

Embedding the server module in your backend. The module turns a PostgreSQL database into a change
log your clients can follow. It is embedded, not deployed: it contributes five operations and a
schema, and the host — your backend — owns the database, the transport, the access model and the
schedule.

Everything here is taken from the reference host, [`samples/ledger-server`](../samples/ledger-server),
which compiles and is covered by tests; when this page and the sample disagree, the sample is right.
Why the mechanism is shaped this way is in [How it works](concepts.md) and in the
[server specification](sync-server-design.md).

**Contents:** [Dependencies](#dependencies) · [Start-up order](#start-up-order) ·
[Registering collections](#registering-collections) · [Publishing the endpoints](#publishing-the-endpoints) ·
[Access control](#access-control-stays-in-the-host) · [Notifying clients](#notifying-clients) ·
[Projections](#projections-if-your-backend-needs-them) · [Metrics](#numbers-if-you-want-to-know-how-it-is-going) ·
[Logs](#logs-when-a-number-is-not-enough) · [Reading documents](#reading-documents-from-your-own-code) ·
[Erasing a scope](#erasing-a-scope) · [Files](#files) · [Checklist](#checklist)

## Dependencies

```kotlin
dependencies {
    implementation("dev.voir.reflector:server-sdk:<version>")

    // Your database. The module brings Exposed's JDBC layer with it: you create the `Database`
    // and hand it over, and the module never takes Exposed's global default.
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Your transport. The sample uses Ktor; nothing in the module depends on it.
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
}
```

`<version>` is the latest release on the
[releases page](https://github.com/VoirDev/reflector/releases); how to reach the GitHub Packages
repository is in the [README](../README.md#installation).

## Start-up order

Migrations, then a `Database` the host owns, then the module, then the transport. Nothing about
synchronisation is configured anywhere else.

```kotlin
fun main() {
    val dataSource = dataSource()
    SyncMigrations.migrate(dataSource)

    val events = ScopeEvents()
    val module = ledgerSyncModule(Database.connect(dataSource), events)

    // Retention is the host's schedule, not the module's: only the host knows what else
    // runs on this machine and when it is cheap to sweep.
    val maintenance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    maintenance.launch {
        while (true) {
            delay(RETENTION_INTERVAL_MILLIS)
            runCatching { module.maintenance.trim() }
        }
    }

    embeddedServer(Netty, port = port()) {
        syncEndpoints(module, TokenIsScopeAuthorizer(), events)
    }.start(wait = true)
}
```

`SyncMigrations.migrate` runs Flyway in an isolated `sync` schema with its own history table, so a
release of the library can never collide with a release of your application: two histories, two
version numbers, one database. Automatic schema alignment (Exposed's `SchemaUtils`) is never used —
a schema that differs from the migrations is a defect the tests should catch, not paper over.

## Registering collections

```kotlin
fun ledgerSyncModule(
    database: Database,
    events: ScopeEvents,
): SyncModule =
    SyncModule.create(
        database = database,
        config =
            syncConfig(
                collections =
                    setOf(
                        CollectionSpec(
                            id = LEDGER,
                            entityTypes = setOf(EntityType("wallet"), EntityType("transaction")),
                        ),
                    ),
            ),
        commitListeners =
            listOf(
                SyncCommitListener { scope, collection, seq -> events.publish(scope, collection, seq) },
            ),
    )
```

A collection is the unit of consistency, of the cursor and of the client's worker. Entity types are
declared per collection and enforced: a push of an unregistered type is refused with
`UNKNOWN_ENTITY_TYPE` rather than stored as unknown data.

What `syncConfig` lets you change, and what it costs:

| Setting | Default | What it decides |
|---|---|---|
| `retention` | 30 days | How much log a client may be behind before it must bootstrap |
| `maxOperationsPerGroup` | 500 | The largest atomic unit; a group cannot be split |
| `maxChangesPageSize` | 500 | Ceiling on what one pull can ask for |
| `CollectionSpec.maxDocumentBytes` | 256 KiB | Per-document size, per collection |

These are published verbatim at `GET /v1/sync/config` and the client caches them at start. Lowering
one on a live system makes clients that already queued a larger group fail locally rather than at
the server — which is the intended behaviour, but it is a behaviour, not a free change.

## Publishing the endpoints

The module is called through `module.service`, and the bundled client expects these routes over it:

| Route | Call |
|---|---|
| `GET /v1/sync/config` | `module.service.limits()` |
| `POST /v1/sync/{scope}/{collection}/push` | `module.service.push(scope, collection, request)` |
| `GET /v1/sync/{scope}/{collection}/changes?cursor=&limit=` | `module.service.changes(scope, collection, cursor, limit)` |
| `GET /v1/sync/{scope}/{collection}/snapshot?page=&limit=` | `module.service.snapshot(scope, collection, page, limit)` |
| `WS /v1/sync/{scope}/events` | your events — see [notifying clients](#notifying-clients) |

Serialise with `SyncProtocolJson.format` on both sides. The whole of it over Ktor, with authorisation
and revocation, is
[`SyncEndpoints.kt`](../samples/ledger-server/src/main/kotlin/dev/voir/reflector/sample/ledger/server/SyncEndpoints.kt)
in the sample — a good file to start from. The full wire contract is in [Wire protocol](protocol.md).

The host decides the routes and the status codes. The module raises typed exceptions; mapping them
is the host's job because the codes are the client's recovery instructions:

```kotlin
try {
    block(requested, collection)
} catch (failure: CursorTooOldException) {
    call.respond(HttpStatusCode.Gone, failure.message.orEmpty())        // 410 → bootstrap
} catch (failure: CollectionResetException) {
    call.respond(HttpStatusCode.Conflict, failure.message.orEmpty())    // 409 → discard and rebuild
} catch (failure: UnknownCollectionException) {
    call.respond(HttpStatusCode.NotFound, failure.message.orEmpty())
} catch (failure: SyncServerException) {
    call.respond(HttpStatusCode.BadRequest, failure.message.orEmpty())
} catch (failure: IllegalArgumentException) {
    // A malformed cursor or page token is the client's problem to fix, not a server fault.
    call.respond(HttpStatusCode.BadRequest, failure.message.orEmpty())
}
```

`401` means "get credentials" and costs the client nothing; `403` means "this scope is not yours any
more" and makes the client wipe it; `410` means "your cursor is gone, bootstrap"; `409` means "the
collection you were following was purged" and makes the client discard it, unsent changes included.
Collapsing any two of them costs the client one of its recoveries — and collapsing the last two
either loses a user's offline edits after an ordinary retention gap, or puts an erased collection
back after a purge.

## Access control stays in the host

```kotlin
fun interface ScopeAuthorizer {
    fun authorize(token: String?): ScopeId?
}
```

The module receives a `ScopeId` that is **already authorised** and never re-checks it. Putting the
same rules in two places is how they drift apart, and a synchronisation library is the last
component that should hold an opinion about who may read what. The sample's authorizer treats the
token as the scope — it exists to make the boundary visible, not to be copied.

The socket is authorised the same way, and a socket is not a place to negotiate access: a mismatch
closes it with `VIOLATED_POLICY` and the client reconnects once it has credentials again.

**Taking access away is two acts, and both are yours.** The module never learns about it:

```kotlin
revocations.revoke(scope)   // refuse it with 403 from now on, and tell whoever is connected
```

`403` and not `401`: the codes are the client's recovery instructions, and only `403` makes it wipe
the scope — `401` preserves the data and the queue for whoever signs in next. The `revoked` event on
the socket is the other half, and it is what makes the wipe happen now instead of whenever the
device next asks for something. Skip it and a phone in a drawer keeps a workspace its owner was
removed from. `ScopeRevocations` in the sample is the whole of it, in fifteen lines.

## Notifying clients

The module calls `SyncCommitListener` **after** the batch is durable. Turning that into a message on
a socket is the host's job, and so is spreading it between instances:

```kotlin
class ScopeEvents {
    private val committed = MutableSharedFlow<Committed>(extraBufferCapacity = BUFFER)

    fun publish(scope: ScopeId, collection: CollectionId, seq: BatchSeq) {
        committed.tryEmit(Committed(scope, collection, seq))
    }

    fun events(scope: ScopeId): Flow<SyncEvent> =
        committed
            .filter { it.scope == scope }
            .map { SyncEvent.Invalidate(it.collection, it.seq) }
}
```

`tryEmit` never suspends and never fails the caller: the data is already committed, and a dropped
notification costs a client latency and nothing else, because data always travels over the pull.
The sample keeps this in-process on purpose; a multi-instance host puts a bus here and nothing else
changes.

## Projections, if your backend needs them

`ProjectionListener` is called **inside** the batch's transaction, for hosts that keep their own
relational projection of the documents:

```kotlin
ProjectionListener { scope, collection, changes ->
    changes.forEach { change -> upsertProjection(scope, collection, change) }
}
```

A failure here rolls the batch back, which is the point: a projection that disagrees with the data
is worse than a refused write. Anything that may fail for unrelated reasons — a webhook, an email —
belongs in a commit listener instead.

## Numbers, if you want to know how it is going

```kotlin
SyncModule.create(
    database = database,
    config = config,
    metrics = SyncMetrics { event -> registry.record(event) },
)
```

Five events: `PushGroupServed`, `ChangesServed`, `SnapshotServed`, `CursorRefused`,
`HistoryTrimmed`. Two of them are worth wiring on the first day:

- `PushGroupServed.lockHeld` — how long the collection's counter lock was held. Writers into one
  collection are serialised by that lock by design; this is the number that tells you when the
  design has become your bottleneck, and there is no way to see it from outside the module.
- `ChangesServed.cursorLag` — how far behind the head a client was when it asked, in sequences. Only
  the server can compute it: to a client a cursor is an opaque token.

Called after the work is durable, never while the lock is held. It must not throw — a throw is
swallowed, and you lose the metric rather than the batch.

## Logs, when a number is not enough

Numbers say how the deployment is doing. When you need to know what happened to one request, wire
the log port — and wire it on the first day, because one thing the module reports is invisible in
every other channel: a commit listener of yours that throws leaves the batch committed and the
notification undelivered, so every client of that scope falls back on polling and synchronisation
merely looks slow.

`server-sdk` depends on no logging library. The adapter is yours, and it is about ten lines —
[`samples/ledger-server`](../samples/ledger-server) has it as `Slf4jSyncLog`:

```kotlin
SyncMigrations.migrate(dataSource, log)                // says how many migrations ran
SyncModule.create(database = database, config = config, log = log)
```

The point of the port is `isEnabled(level, source)`. Your adapter answers it from your own logging
framework, so what the module says is configured where everything else in your server is configured
— at runtime, per area, without a redeploy:

```xml
<logger name="dev.voir.reflector.sync" level="INFO"/>
<logger name="dev.voir.reflector.sync.push" level="DEBUG"/>
```

Per area, because turning one level up for everything is not practical on a running server: the read
paths outnumber the writes by orders of magnitude and would bury them. The module asks `isEnabled`
before it formats anything, so a level nobody switched on costs a comparison.

No record carries a stored document, at any level. Your users' data stays out of logs that get
shipped to aggregators and kept for months.

## Reading documents from your own code

```kotlin
public interface SyncQueries {
    public fun document(scope: ScopeId, collection: CollectionId, type: EntityType, id: EntityId): StoredDocument?
    public fun documents(scope: ScopeId, collection: CollectionId, type: EntityType, page: PageToken?, limit: Int): DocumentPage
}
```

This is the read side for host screens and reports. It reflects the same rows the clients
synchronise, without going through the protocol.

## Erasing a scope

```kotlin
// A user closed their account. Stop serving the scope first — a client that can still push will
// re-create the collection from its own copy on the next request.
authorizer.revoke(scope)
val removed = module.maintenance.purgeScope(scope)
log.info("erased {}: {} entities, {} batches", scope.value, removed.entities, removed.batches)
```

`purgeScope` and `purgeCollection` delete the rows outright — documents, log, tombstones, the
idempotency history and the collection row itself. This is not the protocol's `delete`, which leaves
a tombstone so that other clients can learn about the removal; there is nothing left to learn from
afterwards, and nothing in the module can bring it back. The `PurgeReport` it hands back is what you
put in your own audit trail.

**Other devices erase themselves.** Every answer names the collection's incarnation, and a purge
starts a new one. A device that was offline through the purge comes back, names the incarnation it
knew, and is answered `409` — on its **push**, before anything it queued can be written. It then
discards that collection: the rows, the cursor, the open conflicts and the changes it never managed
to send, and rebuilds from the new snapshot. That last part is why the refusal has to happen on the
push: a client learns about the purge before it has put any of its copy back, rather than after.

It is the one case where the library destroys unsent work without being asked to, and it reports
that at `WARN` with a count. If those edits matter more than the erasure does, do not let the device
reach the scope: revoke its access first, which is [taking access away](#access-control-stays-in-the-host).

What this does not do is make a purge stick against a host that keeps serving the scope. A refused
client rebuilds and syncs normally, and a user who writes afterwards has made new data.

## Files

Files are opt-in on both sides, and a deployment that configures none serves none. Storage, the
three file endpoints and the maintenance they add are in the [files guide](files-guide.md#server).

## Checklist

1. `SyncMigrations.migrate` before the first call to the module.
2. A `Database` you created; never Exposed's global default.
3. Every entity type registered in its `CollectionSpec`.
4. `401` / `403` / `409` / `410` distinguishable in your routes.
5. A commit listener wired to whatever delivers notifications.
6. `maintenance.trim()` on a schedule you own.
7. `maintenance.purgeScope` wired to whatever closes an account, after access is revoked.
8. If you serve files: the [files checklist](files-guide.md#checklist).
