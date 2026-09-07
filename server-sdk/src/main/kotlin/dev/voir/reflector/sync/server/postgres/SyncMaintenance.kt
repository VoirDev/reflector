package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.emit
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * Trims the change log to the retention window.
 *
 * The order of the two steps is the whole point and must not be swapped: the floor is raised
 * **before** anything is deleted. The other way round, a reader could pass the retention check and
 * then be served a range whose oldest batches have just disappeared — it would receive a partial
 * answer and believe it was complete, which is worse than the honest refusal it should have got.
 *
 * How often this runs is the host's decision; the module only knows how much history to keep.
 *
 * @property database Database the module's schema lives in.
 * @property retentionSeconds How long history is kept, in seconds.
 * @property clock Source of the current moment.
 * @property metrics Sink told, per collection, how much was removed and how much history is left.
 */
public class SyncMaintenance internal constructor(
    private val database: Database,
    private val retentionSeconds: Long,
    private val clock: Clock,
    private val metrics: SyncMetrics,
) {
    /**
     * Removes history older than the retention window from every collection.
     *
     * @return Number of batches removed, for logging and metrics.
     */
    public fun trim(): Int {
        val cutoff = clock.now().minus(kotlin.time.Duration.parse("${retentionSeconds}s"))
        val trimmed = mutableListOf<SyncMetricEvent.HistoryTrimmed>()
        val removed =
            transaction(database) {
                CollectionsTable
                    .selectAll()
                    .toList()
                    .sumOf { collectionRow ->
                        val collectionId = collectionRow[CollectionsTable.id].value
                        val floor =
                            BatchesTable
                                .select(BatchesTable.seq.min())
                                .where {
                                    (BatchesTable.collection eq collectionId) and
                                        (BatchesTable.committedAt greaterEq cutoff)
                                }.single()[BatchesTable.seq.min()]
                                ?: collectionRow[CollectionsTable.nextSeq]

                        CollectionsTable.update({ CollectionsTable.id eq collectionId }) { row ->
                            row[retentionFloorSeq] = floor
                        }

                        val removed =
                            BatchesTable.deleteWhere {
                                (BatchesTable.collection eq collectionId) and (BatchesTable.seq less floor)
                            }

                        // Tombstones live exactly as long as the log: a client inside the window learns
                        // about a deletion from the log, a later one from the entity's absence.
                        EntitiesTable.deleteWhere {
                            (EntitiesTable.collection eq collectionId) and
                                (EntitiesTable.isDeleted eq true) and
                                (EntitiesTable.lastSeq less floor)
                        }

                        trimmed +=
                            SyncMetricEvent.HistoryTrimmed(
                                scope = ScopeId(collectionRow[CollectionsTable.scopeId]),
                                collection = CollectionId(collectionRow[CollectionsTable.collectionId]),
                                removedBatches = removed,
                                // What the collection still carries after the sweep: the distance from
                                // the new floor to the head is the log a reader may have to walk.
                                retainedSpan = collectionRow[CollectionsTable.nextSeq] - floor,
                            )
                        removed
                    }
            }

        // Reported once the deletions are durable, and never from inside the transaction: this
        // sweep walks every collection, and holding it open across the host's code would be paying
        // for a metric with a long-running write transaction.
        trimmed.forEach(metrics::emit)
        return removed
    }
}
