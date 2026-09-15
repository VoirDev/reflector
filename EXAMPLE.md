# Integration guide

Two sides, one contract. The server module turns a PostgreSQL database into a change log your
clients can follow; the client SDK keeps a device's tables in step with it. Neither side knows what
your documents contain.

Everything below is taken from the working sample: [`samples/ledger-server`](samples/ledger-server)
(the reference host) and [`samples/ledger/shared`](samples/ledger/shared) (a consumer application).
Both compile and are covered by tests, so when this document and the sample disagree, the sample is
right.

This is the integration guide. Why the mechanism is shaped this way is in the specifications:
[client](docs/sync-client-design.md), [server](docs/sync-server-design.md).

---

# Part 1 — The server

The module is embedded, not deployed. It contributes five operations and a schema; the host owns the
database, the transport, the access model and the schedule.

## 1.1 Dependencies

```kotlin
dependencies {
    implementation(projects.serverSdk)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    implementation(libs.postgresql.driver)
    implementation(libs.hikaricp)

    // The host creates the Database and hands it to the module: the module never
    // takes Exposed's global default.
    implementation(platform(libs.exposed.bom))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
}
```

## 1.2 Start-up order

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

## 1.3 Registering collections

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

## 1.4 Publishing the endpoints

The host decides the routes and the status codes. The module raises typed exceptions; mapping them
is the host's job because the codes are the client's recovery instructions:

```kotlin
try {
    block(requested, collection)
} catch (failure: CursorTooOldException) {
    call.respond(HttpStatusCode.Gone, failure.message.orEmpty())        // 410 → bootstrap
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
more" and makes the client wipe it; `410` means "your cursor is gone, bootstrap". Collapsing any two
of them costs the client one of its recoveries.

## 1.5 Access control stays in the host

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

## 1.6 Notifying clients

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

## 1.7 Projections, if your backend needs them

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

## 1.8 Numbers, if you want to know how it is going

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

## 1.8.1 Logs, when a number is not enough

Numbers say how the deployment is doing. When you need to know what happened to one request, wire
the log port — and wire it on the first day, because one thing the module reports is invisible in
every other channel: a commit listener of yours that throws leaves the batch committed and the
notification undelivered, so every client of that scope falls back on polling and synchronisation
merely looks slow.

`server-sdk` depends on no logging library. The adapter is yours, and it is about ten lines —
`samples/ledger-server` has it as `Slf4jSyncLog`:

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

## 1.9 Reading documents from your own code

```kotlin
public interface SyncQueries {
    public fun document(scope: ScopeId, collection: CollectionId, type: EntityType, id: EntityId): StoredDocument?
    public fun documents(scope: ScopeId, collection: CollectionId, type: EntityType, page: PageToken?, limit: Int): DocumentPage
}
```

This is the read side for host screens and reports. It reflects the same rows the clients
synchronise, without going through the protocol.

---

# Part 2 — The client

## 2.1 Dependencies

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // The whole client library is one artifact: the engine, the Ktor transport and
            // the Room tables the application declares in its own @Database all arrive with it.
            implementation(projects.clientSdk)

            // Room itself arrives with the SDK — your @Database is written against it, so it
            // is part of the contract rather than a detail. A SQLite driver does not: bundled,
            // Android and native are all valid choices and the SDK does not make yours.
            implementation(libs.sqlite.bundled)
        }
    }
}

dependencies {
    // Room runs KSP per target: there is no shared `ksp` configuration in KMP, and without
    // listing the targets the DAOs are simply not generated.
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
```

## 2.2 Your rows

Ordinary Room entities. The library never sees them.

```kotlin
@Entity(tableName = "wallet")
@ColumnTypeConverters(SyncColumnConverters::class)
data class Wallet(
    @PrimaryKey @ColumnInfo(name = "id") val id: Uuid,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "currency") val currency: String,
)
```

`SyncColumnConverters` is the library's `kotlin.uuid.Uuid` ↔ `ByteArray` converter, offered so that
your identifiers can be the same type the protocol uses.

One rule worth copying from the sample: a reference between entities is a **plain column, not a
foreign key**. A transaction may arrive before the wallet it belongs to, because collections are
synchronised independently and nothing orders them.

## 2.3 Your documents

The wire shape is a separate type from the row:

```kotlin
@Serializable
data class WalletDocument(
    val title: String,
    val currency: String,
)
```

They agree today and will not forever. A column renamed for the user interface must not silently
become a different field on the server — and since the server patch-merges by top-level key, a
renamed field is a *new* field, with the old one left behind untouched.

Note `comment: String?` in the sample's transaction document: an explicit `null` clears the field on
the server, while an omitted key means "leave whatever is stored". `SyncProtocolJson` sets
`explicitNulls = true` so that the distinction survives serialization.

## 2.4 One database for both

```kotlin
@Database(
    entities = [
        Wallet::class,
        LedgerTransaction::class,
        SyncCollectionEntity::class,
        SyncRecordEntity::class,
        SyncGroupEntity::class,
        SyncConflictEntity::class,
        SyncInboxBatchEntity::class,
        SyncInboxOpEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
)
@ConstructedBy(LedgerDatabaseConstructor::class)
abstract class LedgerDatabase :
    RoomDatabase(),
    SyncDatabase {
    abstract fun ledgerDao(): LedgerDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LedgerDatabaseConstructor : RoomDatabaseConstructor<LedgerDatabase> {
    override fun initialize(): LedgerDatabase
}
```

The seven `Sync*Entity` classes are the library's tables, and implementing `SyncDatabase` is what
gives it their DAOs. They live in *your* database because applying an incoming batch has to write
business rows and advance the cursor in one transaction.

The cost is visible and worth stating plainly: every version of the library's schema is a version of
your database, and migrating it is your job.

## 2.5 The adapter — the whole integration contract

```kotlin
class LedgerAdapter(private val dao: LedgerDao) : CollectionAdapter {

    override suspend fun snapshot(entityType: EntityType, id: EntityId): JsonObject? =
        when (entityType) {
            WALLET -> dao.wallet(id.value)?.let { encode(WalletDocument(it.title, it.currency)) }
            TRANSACTION -> ...
            else -> null
        }

    override suspend fun applyRemote(ops: List<RemoteOp>) {
        for (op in ops) {
            when (op) {
                is RemoteOp.Upsert -> upsert(op)
                is RemoteOp.Delete -> delete(op)
            }
        }
    }

    override suspend fun onRejected(entityType: EntityType?, id: EntityId?, rejection: SyncRejection) { ... }

    override suspend fun resolve(conflict: Conflict): Resolution? = ...
}
```

Four things about this class decide whether the integration is correct:

- **Every method runs inside the library's transaction**, together with the metadata it belongs to.
  None of them may open a transaction of their own, and none of them may wait on anything outside
  the database — a network call here deadlocks the writer connection.
- **`snapshot` returns `null` for an entity that no longer exists.** That is how a deletion is
  distinguished from an empty document.
- **`onRejected` takes nullable type and id**, because a rejection can belong to the group as a
  whole (an envelope over the limit), and attributing it to an arbitrary entity would be a lie.
- **An entity type this build does not know is a hard failure, not a skipped row.** The server would
  not send it unless it is registered, so meeting one means this client is older than the data.
  Dropping it silently leaves a device that looks synchronised while missing rows nobody notices.

`SyncRejection` is a closed set (`Validation`, `UnknownEntityType`, `TooLarge`, `Unknown`) rather
than the raw wire code, so the application can write an exhaustive `when`. `DEPENDENCY` never
reaches it — the library answers that one by merging groups.

## 2.6 Transport

```kotlin
val http = syncHttpClient(engine)                    // JSON = SyncProtocolJson, expectSuccess = false, WebSockets
val transport = KtorSyncTransport(http, baseUrl, tokens)
val channel = KtorSyncEventChannel(http, baseUrl, tokens)
```

`syncHttpClient` is not a convenience: a client that drops nulls turns "clear this field" into
"leave it alone" once the server merges the patch, and `expectSuccess = false` is required because
`409`, `410` and `429` are part of the protocol rather than failures to throw.

Tokens come from your session layer:

```kotlin
interface TokenProvider {
    suspend fun token(): String?     // null — not authenticated
    suspend fun refresh(): Boolean   // true — a new token was obtained
}
```

If you do not use Ktor, implement `SyncTransport` (four methods) and, optionally,
`SyncEventChannel`. Nothing else in the library assumes HTTP.

## 2.7 Wiring the engine

```kotlin
fun ledgerSync(
    database: LedgerDatabase,
    transport: SyncTransport,
    coroutineScope: CoroutineScope,
    eventChannel: SyncEventChannel? = null,
    triggerSources: List<SyncTriggerSource> = listOf(PeriodicTriggerSource()),
): SyncEngine =
    SyncEngine(
        database = database,
        transactions = RoomSyncTransactionRunner(database),
        transport = transport,
        adapters = mapOf(LEDGER to LedgerAdapter(database.ledgerDao())),
        coroutineScope = coroutineScope,
        eventChannel = eventChannel,
        triggerSources = triggerSources,
    )
```

The coroutine scope belongs to the application on purpose: stopping synchronisation is then a matter
of cancelling something it already owns, rather than a lifecycle method the library would have to be
trusted with.

A collection without an adapter cannot be opened — the library has no other way to read or write its
data, so it fails loudly instead of synchronising nothing.

## 2.8 Writing data

```kotlin
collection.mutate {
    dao.upsertWallet(Wallet(walletId, "Cash", "EUR"))
    markUpserted(LedgerAdapter.WALLET, EntityId(walletId))
}
```

`mutate` **is** the transaction — do not wrap it in one of your own. Everything marked inside one
block becomes one group and is applied by the server atomically. Marking is explicit rather than
inferred from triggers, so that "this edit belongs with that one" is something you state instead of
something the library guesses.

Deleting is `markDeleted(type, id)` next to your own `DELETE`. The library's metadata row for that
entity outlives your row: it carries the acknowledged version, which is what suppresses the echo of
your own deletion when it comes back through the log. It is cleared by the next bootstrap's sweep,
where the entity is simply absent from the snapshot.

## 2.9 Reading state

```kotlin
data class CollectionSyncState(
    val phase: SyncPhase,          // NEW | BOOTSTRAPPING | LIVE | NEEDS_ATTENTION | RESYNC_REQUIRED
    val pendingCount: Int,
    val conflictCount: Int,
    val lastFailure: SyncFailure?, // Network | Server | AuthRequired | Revoked | Rejected | Local
)
```

`collection.state` is a `StateFlow`; `scope.state` carries `Online | Offline | AuthRequired |
Revoked`. Between the two, a status line ("3 changes waiting", "sign in to sync", "1 conflict") is a
few lines of UI and no polling.

When that status line says "3 changes waiting" and has been saying it for an hour, ask the
collection what is going on:

```kotlin
val diagnostics = collection.diagnostics()
if (diagnostics.isQueueBlocked) {
    val head = diagnostics.queue.first()          // the group everything else is waiting behind
    println("${head.state}: ${head.lastError}")   // "FAILED: currency is required"
}
```

`diagnostics()` reads the database, so it is not something to call on every frame — but it is
durable, which `lastFailure` is not: a queue blocked since before the process started is exactly
the case where nobody was watching when it happened.

`lastFailure` keeps whatever the library actually knows rather than flattening it into a sentence.
`Network` and `Local` carry the throwable behind them — a fault in your adapter arrives with its
stack, which is the only part of it worth having — and the two failures the protocol names are
reported as themselves rather than as a status code standing in for them. `Rejected` is the one to
watch: it means the server refused a change permanently, the collection's queue is blocked on it,
and nothing will leave the device until you rewrite that data.

`NEEDS_ATTENTION` is the phase to notice. It replaces `LIVE` once open conflicts reach the engine's
`conflictThreshold` (`ConflictThreshold.Default`, 20), and it means what it says: a conflict blocks
its push group, the queue is FIFO, and nobody has answered enough of them for the collection to be
making progress. Resolving them clears it. Pass `conflictThreshold = ConflictThreshold(3)` to
`SyncEngine(...)` if your application wants to be told earlier.

Your rows are read the way they always were — a Room `Flow` from your own DAO. The library writes
into your tables inside its transaction, and Room's invalidation does the rest.

## 2.10 Conflicts in the user interface

```kotlin
collection.conflicts.collect { conflicts ->
    // Only the ones the adapter left to the user: Conflict(id, entityType, entityId, origin, local, server)
}

collection.resolve(conflict.id, Resolution.KeepLocal)
collection.resolve(conflict.id, Resolution.TakeServer)
collection.resolve(conflict.id, Resolution.Merged(mergedDocument))
```

`local` and `server` are the two documents, so the screen can show a real diff rather than "there
was a conflict".

## 2.11 Triggers

```kotlin
val triggers = ManualTriggerSource()
val engine = ledgerSync(database, transport, scope, channel, listOf(triggers, PeriodicTriggerSource()))
```

Then, from wherever your platform already observes these events — the library ships no platform
code, and [`samples/ledger/android`](samples/ledger/android) is this wired up in a real
application:

```kotlin
// Android — the process, not a screen: the question is "is anybody looking", which outlives an
// activity. `androidx.lifecycle` will do this too; the framework can do it with no dependency.
application.registerActivityLifecycleCallbacks(
    object : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) triggers.fire(SyncTrigger.FOREGROUND)
        }
        override fun onActivityStopped(activity: Activity) { started-- }
        // the other five are empty
    },
)
connectivityManager.registerDefaultNetworkCallback(
    object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = triggers.fire(SyncTrigger.NETWORK)
    },
)

// Android, while nobody is looking. The worker fires a trigger and waits for the collection to
// settle; it does not drive the queue itself, or it would have to repeat the engine's ordering,
// backoff and conflict handling.
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        triggers.fire(SyncTrigger.BACKGROUND)
        val settled = withTimeoutOrNull(30.seconds) {
            collection.state.first { it.phase == SyncPhase.LIVE && it.pendingCount == 0 }
        }
        return if (settled == null) Result.retry() else Result.success()
    }
}

// iOS, from the shared module or from Swift
// NotificationCenter willEnterForeground → triggers.fire(SyncTrigger.FOREGROUND)
// NWPathMonitor satisfied               → triggers.fire(SyncTrigger.NETWORK)
// BGAppRefreshTask                      → triggers.fire(SyncTrigger.BACKGROUND)
```

`fire` never suspends and never fails: blocking a lifecycle callback would be worse than being a
little late, and a dropped trigger only means the next one — or the timer — does the work. A trigger
fired before the engine finished starting is not lost.

## 2.12 Numbers, if you want to know how it is going

```kotlin
SyncEngine(
    // …
    metrics = SyncMetrics { event -> registry.record(event) },
)
```

Five events: `PushCompleted` (operations and outcome — count them and you have the conflict share
and the refusal rate), `PullCompleted`, `BootstrapCompleted`, `RetryScheduled` (the delay, that is,
the time the collection is deliberately idle) and `QueueObserved` (queue and conflict depth at the
end of each cycle). A `MutableStateFlow` behind it is a debug screen; your metrics client behind it
is a dashboard.

There is no cursor lag here, and there cannot be: a cursor is opaque to the client, so the distance
to the head is the server's arithmetic — `ChangesServed.cursorLag`, part 1.8. Implementations run on
the collection's worker and must not throw; a throw is swallowed and you lose the metric.

## 2.13 Sign-out and resync

```kotlin
val pending = collection.state.value.pendingCount
if (pending > 0) {
    // ask the user; the library will not decide this for you
}
engine.signOut(discardPending = false)   // throws while the queue is not empty
engine.signOut(discardPending = true)    // wipes data and queue unconditionally
```

```kotlin
collection.requestResync()   // after a schema migration, a repair, or an import from the side
```

A resync throws away the cursor and re-downloads the snapshot. Unsent local changes survive it.

Or declare the shape and stop having to remember:

```kotlin
class LedgerAdapter(private val database: LedgerDatabase) : CollectionAdapter {
    override val schema = SchemaFingerprint("ledger-v3")   // bump it in the same commit as the migration
}
```

The library stores the fingerprint with the collection and compares it at the start of every cycle;
a different value means the rows were rewritten underneath the cursor, and the collection is rebuilt
from a snapshot before anything else runs. The first run after you introduce the declaration only
records it — your installed base is not sent through a bootstrap for a question it never answered
before.

Leave it out and nothing changes: `requestResync()` after your own migration is still the supported
path. It is also the one that fails silently the day somebody forgets, which is the whole argument
for the fingerprint.

---

# Part 3 — Testing your integration

Two levels, both used in this repository.

**A scripted transport.** Implement `SyncTransport` with fields you set per test — this is how
`LedgerSyncTest` drives a real Room database and the real engine against a server that answers
exactly what the case needs:

```kotlin
val collection = ledgerSync(database, server, workers).scope(scopeId).collection(LEDGER)

collection.mutate {
    database.ledgerDao().upsertWallet(Wallet(walletId.value, "Cash", "EUR"))
    markUpserted(LedgerAdapter.WALLET, walletId)
}
collection.state.awaitQueueDrained()
```

Use `runBlocking` with an explicit timeout rather than `runTest`: the engine runs on real
dispatchers, and `runTest`'s virtual clock does not advance them.

**The real host.** `samples/ledger-server` boots the module against a Testcontainers PostgreSQL and
drives it with the actual `KtorSyncTransport`. That is the only way to find out whether client and
server speak one language rather than two similar ones — a serialization mismatch is invisible to
tests that mock either side.

# Part 4 — Checklist

Server:

1. `SyncMigrations.migrate` before the first call to the module.
2. A `Database` you created; never Exposed's global default.
3. Every entity type registered in its `CollectionSpec`.
4. `401` / `403` / `410` distinguishable in your routes.
5. A commit listener wired to whatever delivers notifications.
6. `maintenance.trim()` on a schedule you own.

Client:

1. The library's seven entities in your `@Database`, and `SyncDatabase` implemented by it.
2. KSP configured per target.
3. An adapter per collection, with no transactions and no I/O inside it.
4. `syncHttpClient` (or a transport that keeps `explicitNulls` and non-throwing status codes).
5. Every write inside `mutate { }`, with an explicit `markUpserted` / `markDeleted`.
6. A `TokenProvider` that can actually refresh.
7. Trigger sources for foreground and connectivity — the timer alone is a floor, not a plan.
8. A resync path for your own schema migrations.
