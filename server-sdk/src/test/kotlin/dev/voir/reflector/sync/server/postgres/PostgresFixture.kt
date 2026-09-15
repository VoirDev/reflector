package dev.voir.reflector.sync.server.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.server.CollectionSpec
import dev.voir.reflector.sync.server.ProjectionListener
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.syncConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * One PostgreSQL container shared by every test of the module.
 *
 * A real database is not optional here. Everything this module claims — no gaps in the log, an
 * idempotent push, a merge that keeps explicit nulls, a retention sweep that cannot serve a partial
 * range — rests on `SELECT … FOR UPDATE`, `jsonb`, MVCC and unique constraints. Against an in-memory
 * database those tests would pass while proving nothing.
 */
object PostgresFixture {
    private val container =
        PostgreSQLContainer("postgres:17-alpine").apply {
            withReuse(false)
            start()
        }

    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = POOL_SIZE
            },
        )

    /** Database the module writes to; migrations have already been applied. */
    val database: Database by lazy {
        SyncMigrations.migrate(dataSource)
        Database.connect(dataSource)
    }

    /** Scope every test writes to. */
    val scope: ScopeId = ScopeId("user-1")

    /** Collection every test writes to. */
    val ledger: CollectionId = CollectionId("ledger")

    /** Entity type the test collection accepts. */
    val wallet: EntityType = EntityType("wallet")

    /**
     * Builds a module over the shared database.
     *
     * @param clock Clock the module reads timestamps from.
     * @param retention How long history is kept.
     * @param maxOperationsPerGroup Largest group accepted.
     * @param commitListeners Listeners notified after a commit.
     * @param projections Listeners notified inside the transaction.
     * @param metrics Sink the module reports its measurements to.
     * @param log Sink the module's own account of what it did goes to.
     * @return Freshly assembled module.
     */
    fun module(
        clock: Clock = MutableClock(),
        retention: Duration = 30.days,
        maxOperationsPerGroup: Int = 500,
        commitListeners: List<SyncCommitListener> = emptyList(),
        projections: List<ProjectionListener> = emptyList(),
        metrics: SyncMetrics = SyncMetrics.None,
        log: SyncLog = SyncLog.None,
    ): SyncModule =
        SyncModule.create(
            database = database,
            config =
                syncConfig(
                    collections = setOf(CollectionSpec(ledger, setOf(wallet), maxDocumentBytes = MAX_DOCUMENT_BYTES)),
                    retention = retention,
                    maxOperationsPerGroup = maxOperationsPerGroup,
                ),
            clock = clock,
            commitListeners = commitListeners,
            projections = projections,
            metrics = metrics,
            log = log,
        )

    /** Empties every table so that one test cannot see another's rows. */
    fun reset() {
        transaction(database) {
            exec("TRUNCATE sync.collections, sync.batches, sync.changes, sync.entities, sync.push_results CASCADE")
        }
    }

    private const val POOL_SIZE = 8

    /** Small enough that a test can exceed it on purpose. */
    const val MAX_DOCUMENT_BYTES: Int = 4096
}

/**
 * Metrics sink that keeps what it was told.
 *
 * The module reports through a port whose other side it cannot see, so being that side is the only
 * way to check that a measurement is taken, and taken with the right numbers.
 */
class RecordingMetrics : SyncMetrics {
    /** Everything reported, in order. */
    val events: MutableList<SyncMetricEvent> = mutableListOf()

    override fun record(event: SyncMetricEvent) {
        events += event
    }
}

/**
 * Clock the tests move by hand.
 *
 * @property instant Current moment; tests advance it to make retention observable without waiting.
 */
class MutableClock(
    var instant: Instant = Instant.fromEpochMilliseconds(0),
) : Clock {
    override fun now(): Instant = instant
}
