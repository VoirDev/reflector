package dev.voir.reflector.sample.ledger.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.server.CollectionSpec
import dev.voir.reflector.sync.server.SyncCommitListener
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
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours

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
    val dataSource = dataSource()
    SyncMigrations.migrate(dataSource)

    val events = ScopeEvents()
    val module = ledgerSyncModule(Database.connect(dataSource), events)

    // Retention is the host's schedule, not the module's: only the host knows what else runs on
    // this machine and when it is cheap to sweep.
    val maintenance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    maintenance.launch {
        while (true) {
            delay(RETENTION_INTERVAL_MILLIS)
            runCatching { module.maintenance.trim() }
        }
    }

    // Access is the host's, and so is taking it away: `revocations.revoke(scope)` is what a real
    // host calls when somebody is removed from a shared workspace. The module is not told and must
    // not be — it holds no access state at all.
    val revocations = ScopeRevocations(events)

    embeddedServer(Netty, port = port()) {
        syncEndpoints(module, TokenIsScopeAuthorizer(), events, revocations)
    }.start(wait = true)
}

/**
 * Assembles the module for this host.
 *
 * @param database Database the host owns and the module writes its schema into.
 * @param events Delivery of commit notifications to connected sockets.
 * @return Module ready to serve.
 */
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
                SyncCommitListener {
                    scope,
                    collection,
                    seq,
                    ->
                    events.publish(scope, collection, seq)
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
