package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogger
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Migrations of the module's own schema.
 *
 * The module keeps its schema and its migration history apart from the host's, so a release of the
 * library can never collide with a release of the application: two independent histories, two
 * independent version numbers, one database.
 *
 * The host runs this at start-up, before the first call to the module. Automatic schema alignment
 * — Exposed's `SchemaUtils` and friends — is never used, in production or in tests: a schema that
 * differs from the migrations is a defect the tests are supposed to catch, not paper over.
 */
public object SyncMigrations {
    /** Schema the module owns. */
    public const val SCHEMA: String = "sync"

    /**
     * Brings the module's schema up to date.
     *
     * @param dataSource Connection source for the database that holds the schema.
     * @param log Sink told how many migrations ran. Worth reporting because this happens once, at
     *   start-up, before anything can go wrong later: a host debugging a schema that does not match
     *   the code wants to know whether this ran at all and what it did.
     * @return Number of migrations applied.
     */
    public fun migrate(
        dataSource: DataSource,
        log: SyncLog = SyncLog.None,
    ): Int {
        val applied =
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table("flyway_schema_history")
                .locations("classpath:db/migration/sync")
                .createSchemas(true)
                .load()
                .migrate()
                .migrationsExecuted
        SyncLogger(log).info(
            SyncLogEvent.MIGRATIONS_APPLIED,
            context = { mapOf("applied" to applied.toString(), "schema" to SCHEMA) },
        ) {
            if (applied == 0) {
                "the module's schema was already up to date"
            } else {
                "brought the module's schema up to date"
            }
        }
        return applied
    }
}
