package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.server.ProjectionListener
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncConfig
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogger
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.SyncQueries
import dev.voir.reflector.sync.server.SyncService
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Clock

/**
 * The synchronisation module, assembled for one database.
 *
 * @property service Protocol operations the host publishes as endpoints.
 * @property queries Read access to stored documents, for the host's own screens.
 * @property maintenance Retention trimming and erasure of a scope or a collection; the host
 *   decides when either runs.
 */
public class SyncModule internal constructor(
    public val service: SyncService,
    public val queries: SyncQueries,
    public val maintenance: SyncMaintenance,
) {
    public companion object {
        /**
         * Assembles the module.
         *
         * The database is passed explicitly rather than taken from Exposed's global default: a host
         * with more than one database would otherwise write synchronisation data into whichever one
         * happened to be registered last.
         *
         * @param database Database whose `sync` schema the module owns; its migrations must have
         *   been applied by [SyncMigrations.migrate] first.
         * @param config Collections to serve and the limits clients must respect.
         * @param clock Source of timestamps; injected so that tests are deterministic.
         * @param commitListeners Notified after each batch commits — this is where a host hangs its
         *   event channel. Their failures cannot undo accepted data.
         * @param projections Notified inside the batch's transaction, for hosts that keep their own
         *   relational projections. A failure here rolls the batch back, which is the point: a
         *   projection that disagrees with the data is worse than a refused write.
         * @param metrics Sink for what the module measures about itself — above all how long the
         *   per-collection counter lock is held, which is the number that says when the module's
         *   deliberate serialisation of writers has become a queue. The default discards everything.
         * @param log Sink for what the module decided, request by request, and for the host
         *   callbacks that threw — a commit listener whose failure costs every client of a scope its
         *   notifications is invisible anywhere else. A port rather than a logging dependency,
         *   because this module has almost none and no host will swap its backend for one; the
         *   adapter over the host's own framework is about ten lines, and it is what puts the levels
         *   in effect under the host's logging configuration, per source and at runtime. The default
         *   discards everything and is asked nothing.
         * @return Module ready to serve.
         */
        public fun create(
            database: Database,
            config: SyncConfig,
            clock: Clock = Clock.System,
            commitListeners: List<SyncCommitListener> = emptyList(),
            projections: List<ProjectionListener> = emptyList(),
            metrics: SyncMetrics = SyncMetrics.None,
            log: SyncLog = SyncLog.None,
        ): SyncModule {
            // The first line in any log of this module, and the one that settles what a reader would
            // otherwise have to ask of every line after it.
            SyncLogger(log).info(
                SyncLogEvent.MODULE_CREATED,
                context = {
                    mapOf(
                        "collections" to config.collections.keys.joinToString { it.value },
                        "retentionDays" to config.retention.inWholeDays.toString(),
                        "maxOperationsPerGroup" to config.maxOperationsPerGroup.toString(),
                        "commitListeners" to commitListeners.size.toString(),
                        "projections" to projections.size.toString(),
                    )
                },
            ) { "the synchronisation module was assembled" }
            return SyncModule(
                service = PostgresSyncService(database, config, clock, commitListeners, projections, metrics, log),
                queries = PostgresSyncQueries(database, config, clock),
                maintenance = SyncMaintenance(database, config.retention.inWholeSeconds, clock, metrics, log),
            )
        }
    }
}
