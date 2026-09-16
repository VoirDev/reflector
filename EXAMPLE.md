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

## 1.10 Files, if your documents point at any

Files are opt-in on both sides. A deployment that does not configure them serves none, says so in
its published limits, and nothing below applies.

The module never touches a byte. It hands out permission to move them, is told afterwards what
arrived, and keeps the metadata that says which files exist and whether anything still points at
them. The bytes travel between the device and **your** storage, directly:

```kotlin
class S3BlobStorage(private val s3: S3Presigner, private val bucket: String) : BlobStorage {
    // Called once per file, before it is registered. Must be cheap and local: the module asks
    // before it knows whether it will need the answer. Put deduplication here if you want it.
    override fun keyFor(scope: ScopeId, collection: CollectionId, blob: BlobDescriptor) =
        BlobStorageKey("${'$'}{scope.value}/${'$'}{collection.value}/${'$'}{blob.blobId.value}")

    override fun createUpload(scope, collection, blob) = s3.presignPut(bucket, blob.storageKey) // …
    override fun createDownload(scope, collection, blob) = s3.presignGet(bucket, blob.storageKey)

    // A HEAD. You observe; the module compares the answer with what the client declared and
    // decides. Reporting no checksum is fine and never fails a verification on its own.
    override fun verify(scope, collection, blob) = s3.head(bucket, blob.storageKey) // …

    // Not an instruction. The module has already dropped its rows: nothing points at these any
    // more, here are their keys, they are yours. Delete now, tag them for a lifecycle rule, or keep
    // them for a retention period somebody legislated — the module has no opinion and asks nothing
    // back. Whatever you do, record it: that record is the erasure's evidence, because the module
    // keeps none.
    override fun onReleased(scope, collection, blobs) = blobs.forEach { s3.delete(bucket, it.storageKey) }
}
```

Wire it in, and publish the three endpoints over `module.blobs`:

```kotlin
SyncModule.create(
    database = database,
    config = syncConfig(collections = setOf(ledger), blobs = BlobConfig(maxBlobBytes = 25.mb)),
    blobStorage = S3BlobStorage(presigner, bucket),
    blobListeners = listOf(BlobListener { scope, collection, blob -> thumbnails.submit(blob) }),
)
```

`config.blobs` and `blobStorage` go together or not at all; either half alone is refused at
assembly, because either half alone surfaces as a failure on a user's first attachment rather than
on the line that was wrong.

**`BlobListener` is the acknowledgement you asked for.** It fires once per file, after the commit
that made it usable, and it is where thumbnailing, scanning and extraction belong. It is reached
however the file was confirmed — the device said so, your storage's own event notification said so,
or the maintenance sweep found an upload nobody ever confirmed. Two rules: never overwrite the
object you are given, because a file is immutable and every device that already fetched it holds
bytes that would no longer match; and if the result has to reach the clients, register the
derivative as a **new** file and name it in a document through an ordinary `SyncService.push` of
your own.

Three endpoints, and they carry your credentials like every other route:

```
POST /v1/sync/{scope}/{collection}/blobs                   → module.blobs.register(…)
POST /v1/sync/{scope}/{collection}/blobs/{id}/complete     → module.blobs.markUploaded(…)
GET  /v1/sync/{scope}/{collection}/blobs/{id}              → module.blobs.download(…)
```

`markUploaded` is also what your storage's event notification should call — a device can die between
writing the object and reporting it, and then the bytes sit there unfetchable forever. Publish
`blobReady` on the scope's socket after it, or the other devices discover the file whenever their
own backoff next fires.

Two more things on your schedule, beside `trim()`:

```kotlin
module.maintenance.confirmPendingUploads()          // uploads nobody confirmed
module.maintenance.collectBlobs(dryRun = true)      // read this before switching the next line on
module.maintenance.collectBlobs()                   // files nothing has pointed at for the window
```

Run the dry run first on any collection whose clients are not all known to declare their references:
a client older than files says nothing and is respected, but an application that *has* files and
forgets to answer for one entity type says "references nothing", and from the server the two are
identical.

## 1.11 Erasing a scope

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
reach the scope: revoke its access first, which is the paragraph above.

What this does not do is make a purge stick against a host that keeps serving the scope. A refused
client rebuilds and syncs normally, and a user who writes afterwards has made new data.

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
bump and a migration from you, exactly as one of your own tables would. The library is at 0.1.0 and
has not yet made such a release, so there is no upgrade path to reproduce here — what follows is the
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
`409`, `410` and `429` are part of the protocol rather than failures to throw — `409` in particular
is how a client is told the collection it follows was purged.

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

---

## 2.14 Files, if your documents point at any

Three things: say where the bytes live, say which files a document points at, and hand the engine a
transport for them.

```kotlin
class LedgerFiles(private val directory: Path) : BlobStore {
    override suspend fun stat(blobId: BlobId): BlobStat? = // size, media type, checksum if you have one
    override suspend fun read(blobId: BlobId): RawSource = // for an upload
    override suspend fun write(blobId: BlobId, stat: BlobStat): RawSink = // somewhere temporary
    override suspend fun finish(blobId: BlobId, complete: Boolean) { /* move it into place, or discard */ }
    override suspend fun remove(blobId: BlobId) { /* nothing points at it any more — your call */ }
    override suspend fun onFailed(blobId: BlobId, failure: BlobFailure) { /* it will not arrive */ }
}
```

`write` hands back a sink and `finish` says whether to keep it. Write somewhere temporary and move
the file into place only when `finish` says the bytes arrived in full — otherwise an interrupted
download leaves a truncated photograph exactly where your own code will find it and believe it.

`remove` is an offer, not an instruction: the library will not delete a user's bytes on a judgement
of its own, and keeping the file for an undo stack is a correct implementation that does nothing
here. Sign-out, a revoked scope and a purged collection are the exceptions — there it is called for
everything, and it is not eviction.

Then say what a document points at:

```kotlin
override fun blobs(entityType: EntityType, id: EntityId, document: JsonObject): Set<BlobRef> =
    when (entityType) {
        WALLET -> decode(WalletDocument.serializer(), document).photoBlobId
            ?.let { setOf(BlobRef(BlobId(it))) }.orEmpty()
        else -> emptySet()
    }
```

The whole set, every time: the answer **replaces** what was stored, so a document that still names a
file must still name it here, or the file is offered up for deletion while it is in use.

```kotlin
ledgerSync(
    database = database,
    transport = KtorSyncTransport(client, host, tokens),
    blobStore = LedgerFiles(filesDirectory),
    blobTransport = KtorBlobTransport(client, host, tokens),
    // EAGER is the default: every file a document names is brought to this device. See below.
    blobFetch = BlobFetch.EAGER,
    coroutineScope = scope,
)
```

Both or neither — the engine refuses to be built with one and not the other, because a store with
no transport leaves every record naming a file waiting for a registration nothing can perform.

**Write the bytes before the `mutate` that names them.** A document may not point at a file whose
bytes are not there yet:

```kotlin
files.put(photoId, bytes)
collection.mutate {
    dao.upsertWallet(Wallet(id, "Holiday", "EUR", photoId.value))
    markUpserted(WALLET, EntityId(id))
}
```

### The binding, which is the one editorial decision

`BlobRef(id)` defaults to `BlobBinding.DEFERRED`: the record goes out as soon as the file has been
**registered** — a request and an answer, not a transfer — and the bytes follow on their own. Use it
for a receipt, an avatar, a photograph of a wallet: anything the record survives without. A
transaction must not wait on a photograph stuck behind a hotel's captive portal.

`BlobBinding.REQUIRED` holds the record until the bytes are on the server. Use it only when the
record without its file would mislead — a photo post, a voice message — and know what it costs: the
group waits, and the queue is FIFO, so everything behind it waits too.

### The fetch policy, which is the other one

The binding is about the device that has the file. The policy is about every device that receives
the record, and the question is the same one turned around: is this file worth holding before
anybody asks for it?

`BlobFetch.EAGER` is the default and is right for anything a list draws — a thumbnail, an avatar, a
photograph of a wallet. `BlobFetch.ON_DEMAND` records the reference, fetches nothing, and waits:

```kotlin
// One switch for the whole application…
ledgerSync(…, blobFetch = BlobFetch.ON_DEMAND)

// …and an override per reference, where the choice actually differs.
override fun blobs(entityType: EntityType, id: EntityId, document: JsonObject): Set<BlobRef> =
    when (entityType) {
        SCAN -> {
            val scan = decode(ScanDocument.serializer(), document)
            setOfNotNull(
                // small, drawn in every list: hold it
                scan.thumbnailBlobId?.let { BlobRef(BlobId(it), fetch = BlobFetch.EAGER) },
                // forty megabytes, opened by one screen: fetch it there
                scan.originalBlobId?.let { BlobRef(BlobId(it), fetch = BlobFetch.ON_DEMAND) },
            )
        }
        else -> emptySet()
    }
```

Leave `fetch` unset and the engine's policy applies, which is how you answer once for almost every
file. Where two documents name the same file and disagree, eager wins.

Then the screen that opens the record asks for it, and gives it up when it wants the space back:

```kotlin
suspend fun onScanOpened(original: BlobId) = collection.fetch(original)   // idempotent
suspend fun onCacheCleared(original: BlobId) = collection.evict(original) // bytes go, file stays
```

`fetch` records the wish and returns — the transfer is the worker's, and you watch it through
`collection.blob(id)`. The wish is durable, so a download interrupted by the process being killed
resumes rather than waiting to be asked again, and what arrives stays until you `evict` it: the
second time that record is opened, the file is simply there. Calling `fetch` on a file the library
gave up on starts it again from zero attempts, which is what you put behind a retry button.

Two things to know before you choose it:

- **A file you have not fetched has never been described.** Its size and media type come from the
  download ticket, and there has not been one — so `BlobSyncState.size` is `null`. If a screen wants
  to say *2.4 MB* before offering the download, keep that in your own document, where you put it
  when you attached the file.
- **`evict` refuses a file whose bytes are the only copy.** A file this device created and has not
  finished uploading is not a cache, and the library will not discard it.

### What your screens have to render

```kotlin
collection.blob(photoId).collect { state ->
    when {
        state == null -> showNothing()                        // no document names it yet
        state.state == READY -> showPhoto()
        state.state == UNAVAILABLE -> showBroken()            // onFailed has already told you why
        state.wanted -> showProgress(state.transferred, state.size)   // REMOTE or DOWNLOADING
        else -> showDownloadOffer()                           // REMOTE, and nobody asked
    }
}
```

`REMOTE` and `DOWNLOADING` are what an attachment looks like on a device whose record arrived ahead
of its bytes — which is what the default binding produces on purpose. A screen that treats either as
a failure will report photographs lost every time somebody goes into a tunnel.

`wanted` is what tells a wait apart from an offer: `REMOTE` with `wanted = false` is a file this
device has decided not to hold, and the only thing that will change that is your call to `fetch`. If
you never use `ON_DEMAND`, it is always `true` and you can ignore it.

`CollectionSyncState` counts files apart from records (`pendingBlobs`, `incomingBlobs`), because a
record that has not reached the server is work that could be lost and a photograph that has not is a
slow upload. `incomingBlobs` counts what this device wants and has not got yet, so a file left
behind under `ON_DEMAND` is not reported as an arrival nothing is waiting for.

`BlobStore.onFailed` is the only notice you get that a file will never arrive, and
under the default binding the record naming it is already on every device — so show a broken
attachment and offer to retry or detach it. The library will not drop the reference for you: that is
your document, and a receipt worth asking a user about should not vanish silently.

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
4. `401` / `403` / `409` / `410` distinguishable in your routes.
5. A commit listener wired to whatever delivers notifications.
6. `maintenance.trim()` on a schedule you own.
7. `maintenance.purgeScope` wired to whatever closes an account, after access is revoked.
8. If you serve files: `BlobStorage` implemented, `config.blobs` set, the three endpoints published,
   `markUploaded` reachable from your storage's event notifications, `blobReady` on the socket, and
   `confirmPendingUploads` / `collectBlobs` on your schedule — the dry run read first.

Client:

1. The library's seven entities in your `@Database`, and `SyncDatabase` implemented by it.
2. KSP configured per target.
3. An adapter per collection, with no transactions and no I/O inside it.
4. `syncHttpClient` (or a transport that keeps `explicitNulls` and non-throwing status codes).
5. Every write inside `mutate { }`, with an explicit `markUpserted` / `markDeleted`.
6. A `TokenProvider` that can actually refresh.
7. Trigger sources for foreground and connectivity — the timer alone is a floor, not a plan.
8. A resync path for your own schema migrations.
9. If you synchronise files: a `BlobStore`, a `BlobTransport`, `blobs()` answered for every entity
   type that can name one, bytes written before the `mutate` that names them, and `REMOTE` /
   `DOWNLOADING` rendered as ordinary states rather than as errors.
10. If your files are large enough that holding them all is a cost: `blobFetch = ON_DEMAND`, a
    `fetch(blobId)` from the screen that opens the record, `REMOTE` with `wanted = false` drawn as an
    offer rather than a wait, and each file's size kept in your own document — a file nobody has
    fetched has never been described.
