# Client guide

Integrating the client SDK into a Kotlin Multiplatform application. The SDK keeps the device's
tables in step with the server; it stores only the metadata of synchronisation and reaches your rows
through an adapter you write.

Everything here is taken from the consumer sample, [`samples/ledger/shared`](../samples/ledger/shared),
which compiles and is covered by tests; when this page and the sample disagree, the sample is right.
Why the mechanism is shaped this way is in [How it works](concepts.md) and in the
[client specification](sync-client-design.md).

**Contents:** [Dependencies](#dependencies) · [Your rows](#your-rows) · [Your documents](#your-documents) ·
[One database for both](#one-database-for-both) · [The adapter](#the-adapter--the-whole-integration-contract) ·
[Transport](#transport) · [Wiring the engine](#wiring-the-engine) · [Writing data](#writing-data) ·
[Reading state](#reading-state) · [Conflicts](#conflicts-in-the-user-interface) · [Triggers](#triggers) ·
[Metrics](#numbers-if-you-want-to-know-how-it-is-going) · [Sign-out and resync](#sign-out-and-resync) ·
[Files](#files) · [Checklist](#checklist)

## Dependencies

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The whole client library is one artifact: the engine, the Ktor transport and the
            // Room tables your @Database declares all arrive with it.
            implementation("dev.voir.reflector:client-sdk:<version>")

            // Room itself arrives with the SDK — your @Database is written against it, so it is
            // part of the contract rather than a detail. A SQLite driver does not: bundled,
            // Android and native are all valid choices and the SDK does not make yours.
            implementation("androidx.sqlite:sqlite-bundled:2.7.1")
        }
        // A Ktor engine per platform, for `syncHttpClient(engine)`.
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.6.0") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.0") }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-cio:3.6.0") }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Room runs KSP per target: there is no shared `ksp` configuration in KMP, and without
    // listing the targets the DAOs are simply not generated.
    add("kspAndroid", "androidx.room3:room3-compiler:3.0.3")
    add("kspJvm", "androidx.room3:room3-compiler:3.0.3")
    add("kspIosArm64", "androidx.room3:room3-compiler:3.0.3")
    add("kspIosSimulatorArm64", "androidx.room3:room3-compiler:3.0.3")
}
```

`<version>` is the latest release on the
[releases page](https://github.com/VoirDev/reflector/releases); how to reach the GitHub Packages
repository is in the [README](../README.md#installation).

## Your rows

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

## Your documents

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

## One database for both

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
        SyncBlobEntity::class,
        SyncBlobRefEntity::class,
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

The nine `Sync*Entity` classes are the library's tables, and implementing `SyncDatabase` is what
gives it their DAOs. They live in *your* database because applying an incoming batch has to write
business rows and advance the cursor in one transaction. Declare all nine even if you synchronise no
files: `SyncDatabase` asks for the blob DAOs either way, and an application that registers no
`BlobStore` simply leaves those two tables empty.

That also means **the library's schema changes are your migrations**: your `@Database` owns the
version number, so a release of the library that adds a column to one of its tables needs a version
bump and a migration from you, exactly as one of your own tables would. No release of the library
has made such a change yet, so there is no upgrade path to reproduce here — what follows is the
shape one takes when it comes.

An additive change is Room's to derive: raise the version and declare the step.

```kotlin
@Database(
    entities = [ /* … */ ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
```

A change that alters an existing column is yours to write, and the interesting part is rarely the
`ALTER`. Anything the library holds on behalf of the server — downloaded-but-unapplied batches, and
the cursors that point at them — is re-derivable, so a migration that cannot translate it drops it
and lets the collection bootstrap once on the next cycle:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM sync_inbox_op")
        connection.execSQL("DELETE FROM sync_inbox_batch")
        connection.execSQL("UPDATE sync_collection SET cursor = NULL, phase = 'RESYNC_REQUIRED'")
    }
}
```

**Unsent local changes survive that** — a bootstrap's sweep does not touch dirty records, and the
queue is left alone; only the `409` of an actual purge discards them.

The cost is visible and worth stating plainly: every version of the library's schema is a version of
your database, and migrating it is your job.

## The adapter — the whole integration contract

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

## Transport

```kotlin
val http = syncHttpClient(engine)                    // JSON = SyncProtocolJson, expectSuccess = false, WebSockets
val transport = KtorSyncTransport(http, baseUrl, tokens)
val channel = KtorSyncEventChannel(http, baseUrl, tokens)
```

`syncHttpClient` is not a convenience: a client that drops nulls turns "clear this field" into
"leave it alone" once the server merges the patch, and `expectSuccess = false` is required because
`409`, `410` and `429` are part of the protocol rather than failures to throw — `409` in particular
is how a client is told the collection it follows was purged.

It also pings the event channel's socket every 20 seconds and gives requests a 15-second connect
timeout and a 30-second idle timeout, so a connection a mobile network dropped without closing is
noticed instead of waited on. There is deliberately no overall request deadline: file transfers
share the client and stream. The OkHttp engine runs its own WebSocket and ignores the plugin's ping
setting, so on Android configure it on the engine:

```kotlin
val engine = OkHttp.create { config { pingInterval(20, TimeUnit.SECONDS) } }
```

Tokens come from your session layer:

```kotlin
interface TokenProvider {
    suspend fun token(): String?     // null — not authenticated
    suspend fun refresh(): Boolean   // true — a new token was obtained
}
```

If you do not use Ktor, implement `SyncTransport` (four methods) and, optionally,
`SyncEventChannel`. Nothing else in the library assumes HTTP.

## Wiring the engine

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

## Writing data

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

### Rows you had before there was anybody to sync them for

A device used without an account, whose owner then signs in, holds rows the library was never told
about — and never will be by `mutate`, which you would have to call once per row or once for the
lot. Once per row is thousands of round trips. Once for the lot is one group, refused as too large
before it leaves the device, and blocking everything queued behind it.

```kotlin
val scope = engine.scope(ScopeId(ownerId))
val ledger = scope.collection(LEDGER)

ledger.adopt(LedgerAdapter.WALLET, walletIds)            // referred to, so first
ledger.adopt(LedgerAdapter.TRANSACTION, transactionIds)  // queued behind the wallets
```

Each call queues the rows as groups within the server's limit, in the order you gave, and sends
them as created. It takes only rows the library has no record of, so calling it again after a crash
queues nothing twice. It waits until the server's limit is known — the first cycle after opening the
collection reads it — so call it after `collection(...)`, online, and bound it with a timeout if a
screen is waiting on it.

## Reading state

```kotlin
data class CollectionSyncState(
    val phase: SyncPhase,          // NEW | BOOTSTRAPPING | LIVE | NEEDS_ATTENTION | RESYNC_REQUIRED
    val pendingCount: Int,
    val conflictCount: Int,
    val lastFailure: SyncFailure?, // Network | Server | AuthRequired | Revoked | Rejected | Local
)
```

`collection.state` is a `StateFlow`; `scope.state` carries `Online | Offline | ServerUnreachable |
AuthRequired | Revoked`. `Offline` and `ServerUnreachable` end on their own: the first push, log
read or snapshot the server answers puts the scope back to `Online`, and so does the event channel
reconnecting. `AuthRequired` ends the same way once the user has signed in again and the server
accepts the new token; `Revoked` is final. Between the two, a status line ("3 changes waiting", "sign in to sync", "1 conflict") is a
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
and nothing will leave the device until you rewrite that data or [discard it](#refused-changes).

`NEEDS_ATTENTION` is the phase to notice. It replaces `LIVE` once open conflicts reach the engine's
`conflictThreshold` (`ConflictThreshold.Default`, 20), and it means what it says: a conflict blocks
its push group, the queue is FIFO, and nobody has answered enough of them for the collection to be
making progress. Resolving them clears it. Pass `conflictThreshold = ConflictThreshold(3)` to
`SyncEngine(...)` if your application wants to be told earlier.

Your rows are read the way they always were — a Room `Flow` from your own DAO. The library writes
into your tables inside its transaction, and Room's invalidation does the rest.

## Conflicts in the user interface

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

## Refused changes

A conflict is a disagreement the user can settle. A refusal is not: the server looked at a change
and said no for good — it fails validation, it is too large, its type is not one the server knows. The
group that carried it stays at the head of the queue, and since the queue is strictly in order,
nothing behind it leaves the device either.

The adapter's `onRejected` is told once, at the moment it happens. `refusals` is the same answer
read back from storage, so it is still there after a restart:

```kotlin
collection.refusals.collect { refused ->
    // RefusedGroup(groupId, entityType, entityId, rejection) — usually one: the head of the queue
}
```

There are two ways out. **Change the data**: an edit to the refused entity moves it into a new group,
and the queue moves on. **Or give up on it**:

```kotlin
collection.discardLocalChanges()
```

This throws away *everything* this device has not sent — not just the refused group — and rebuilds
the collection from the server. Ask the user first; the library never does this on its own.

What is done by the time the call returns, in one transaction and without needing a connection:

- the queue, the open conflicts and every pending edit are gone, and `pendingCount` is `0`;
- entities created on this device that the server never confirmed are deleted, through your
  adapter's `applyRemote`, as the server's own deletions are;
- the phase is `RESYNC_REQUIRED`, written in the same transaction, so a process that dies straight
  afterwards still rebuilds;
- a cycle that was running is cancelled rather than waited for.

What is left for the snapshot that follows: an entity the server does have — one edited or deleted
here — can only be put back from the server's copy. Until the snapshot arrives those rows still show
the discarded change, which on a device without a connection can be a while. **The discard is
finished when the phase is `LIVE` again**, not when the call returns:

```kotlin
collection.discardLocalChanges()
collection.state.first { it.phase == SyncPhase.LIVE }   // rows now match the server
```

Changes made after the call returns are new changes and are kept. One made to a row that is still
showing a discarded change is built on top of it, so it is not pushed as an edit of the server's
copy: the snapshot opens a conflict for it, and the user picks a side as for any other. If you would
rather that never happened, keep those screens read-only until the phase is `LIVE`.

Files only this device held go with the documents that pointed at them.

Call it from your user interface, not from inside the adapter: it waits for the running cycle to
stop, and `onRejected` runs inside that cycle, so the call throws `IllegalStateException` there.

## Triggers

```kotlin
val triggers = ManualTriggerSource()
val engine = ledgerSync(database, transport, scope, channel, listOf(triggers, PeriodicTriggerSource()))
```

Then, from wherever your platform already observes these events — the library ships no platform
code, and [`samples/ledger/android`](../samples/ledger/android) is this wired up in a real
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

## Numbers, if you want to know how it is going

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
to the head is the server's arithmetic — `ChangesServed.cursorLag` in the [server guide](server-guide.md#numbers-if-you-want-to-know-how-it-is-going). Implementations run on
the collection's worker and must not throw; a throw is swallowed and you lose the metric.

## Sign-out and resync

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
    override val schema = SchemaFingerprint("ledger-v1")   // bump it in the same commit as the migration
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

## Files

Files are opt-in: pass a `BlobStore` and a `BlobTransport` to the engine and answer `blobs()` in the
adapter. The whole of it — bindings, fetch policies and what your screens have to render — is in the
[files guide](files-guide.md#client).

## Checklist

1. The library's nine `Sync*Entity` tables in your `@Database`, and `SyncDatabase` implemented by it.
2. KSP configured per target.
3. An adapter per collection, with no transactions and no I/O inside it.
4. `syncHttpClient` (or a transport that keeps `explicitNulls` and non-throwing status codes).
5. Every write inside `mutate { }`, with an explicit `markUpserted` / `markDeleted` — and rows that
   existed before the scope did taken in with `adopt`.
6. A `TokenProvider` that can actually refresh.
7. Trigger sources for foreground and connectivity — the timer alone is a floor, not a plan.
8. A resync path for your own schema migrations.
9. A screen for `refusals`, with `discardLocalChanges()` behind a confirmation.
10. If you synchronise files: the [files checklist](files-guide.md#checklist).
