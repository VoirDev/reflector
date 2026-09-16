package dev.voir.reflector.sample.ledger.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.voir.reflector.sync.server.postgres.SyncMigrations
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * The one PostgreSQL the tests of this module share.
 *
 * A container per test class would be honest isolation and thirty seconds of it per class; the
 * tests run sequentially and each one empties the schema before it starts, which buys the same
 * independence for the price of one container.
 */
object LedgerTestHost {
    private val container = PostgreSQLContainer("postgres:17-alpine").apply { start() }

    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
            },
        )

    /** Database the sync module is given, migrated once. */
    val database: Database by lazy {
        SyncMigrations.migrate(dataSource)
        Database.connect(dataSource)
    }

    /** Empties everything the module owns, so that a test starts from a server that knows nothing. */
    fun clean() {
        transaction(database) {
            exec(
                "TRUNCATE sync.collections, sync.batches, sync.changes, sync.entities, " +
                    "sync.push_results, sync.blobs, sync.blob_refs CASCADE",
            )
        }
    }
}
