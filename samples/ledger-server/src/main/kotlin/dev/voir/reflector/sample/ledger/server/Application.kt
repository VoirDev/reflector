package dev.voir.reflector.sample.ledger.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.BlobListener
import dev.voir.reflector.sync.server.BlobStorage
import dev.voir.reflector.sync.server.CollectionSpec
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogLevel
import dev.voir.reflector.sync.server.SyncLogRecord
import dev.voir.reflector.sync.server.postgres.SyncMigrations
import dev.voir.reflector.sync.server.postgres.SyncModule
import dev.voir.reflector.sync.server.syncConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Collection this host serves. */
val LEDGER: CollectionId = CollectionId("ledger")

/**
 * Starts the reference host.
 *
 * The order here is the contract the module documents: migrations first, then a `Database` the host
 * owns, then the module, and only then the transport. Nothing about synchronisation is configured
 * anywhere else.
 */
fun main() {
    // Built once and handed to everything the module offers, so that one file — logback.xml —
    // decides what the synchronisation module says and at which level, exactly as it does for the
    // rest of this application.
    val log = Slf4jSyncLog()

    val dataSource = dataSource()
    SyncMigrations.migrate(dataSource, log)

    val events = ScopeEvents()
    val storage =
        DirectoryBlobStorage(
            directory = File(System.getenv("SYNC_FILES_DIR") ?: "build/ledger-files"),
            baseUrl = System.getenv("SYNC_PUBLIC_URL") ?: "http://localhost:${port()}",
            secret = (System.getenv("SYNC_FILES_SECRET") ?: "development-secret").encodeToByteArray(),
            ticketLife = TICKET_LIFE,
        )
    val module = ledgerSyncModule(Database.connect(dataSource), events, log, storage)

    // Retention is the host's schedule, not the module's: only the host knows what else runs on
    // this machine and when it is cheap to sweep.
    val maintenance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    maintenance.launch {
        while (true) {
            delay(RETENTION_INTERVAL_MILLIS)
            // The trim reports what it removed through the module's own log; this only has to keep
            // one failed sweep from ending the schedule.
            runCatching { module.maintenance.trim() }
            // Files nothing has referenced for longer than the window, and uploads that finished
            // without anybody saying so. Both are the host's schedule for the same reason the trim
            // is, and both report what they did through the module's log.
            runCatching { module.maintenance.confirmPendingUploads() }
            runCatching { module.maintenance.collectBlobs() }
        }
    }

    // Access is the host's, and so is taking it away: `revocations.revoke(scope)` is what a real
    // host calls when somebody is removed from a shared workspace. The module is not told and must
    // not be — it holds no access state at all.
    val revocations = ScopeRevocations(events)

    embeddedServer(Netty, port = port()) {
        syncEndpoints(module, TokenIsScopeAuthorizer(), events, revocations, storage)
    }.start(wait = true)
}

/**
 * Assembles the module for this host.
 *
 * @param database Database the host owns and the module writes its schema into.
 * @param events Delivery of commit notifications to connected sockets.
 * @param log Where the module's own account of what it did goes; the default discards it, which is
 *   what the tests want and what a host that has not wired its logging yet gets.
 * @param storage Object storage the files live in, or `null` to serve none. Declaring a blobs
 *   section without it — or the other way round — is refused at assembly, because either half alone
 *   would surface as a failure on a user's first attachment rather than on the line that was wrong.
 * @return Module ready to serve.
 */
fun ledgerSyncModule(
    database: Database,
    events: ScopeEvents,
    log: SyncLog = SyncLog.None,
    storage: BlobStorage? = null,
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
                blobs = storage?.let { BlobConfig() },
            ),
        commitListeners =
            listOf(
                SyncCommitListener {
                    scope,
                    collection,
                    seq,
                    ->
                    events.publish(scope, collection, seq)
                },
            ),
        log = log,
        blobStorage = storage,
        blobListeners =
            listOf(
                BlobListener { _, _, blob ->
                    // Where a real host would make a thumbnail, scan the bytes, or extract text. It
                    // must not overwrite the object it is told about: a blob is immutable, and every
                    // device that already fetched it holds bytes that would no longer match. A
                    // derivative belongs in a new file, which the host registers and then names in a
                    // document through an ordinary push of its own.
                    log.log(
                        SyncLogRecord(
                            level = SyncLogLevel.INFO,
                            event = SyncLogEvent.BLOB_ACCEPTED,
                            message = "a file of ${blob.size} octets is ready to be processed",
                        ),
                    )
                },
            ),
    )

private fun dataSource(): DataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = System.getenv("SYNC_DB_URL") ?: "jdbc:postgresql://localhost:5432/reflector"
            username = System.getenv("SYNC_DB_USER") ?: "reflector"
            password = System.getenv("SYNC_DB_PASSWORD") ?: "reflector"
        },
    )

private fun port(): Int = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT

private const val DEFAULT_PORT = 8080

private val RETENTION_INTERVAL_MILLIS = 6.hours.inWholeMilliseconds

/** How long a signed URL is honoured; long enough for a large file on a poor connection. */
private val TICKET_LIFE = 15.minutes
