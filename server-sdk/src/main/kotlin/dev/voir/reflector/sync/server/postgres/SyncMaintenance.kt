package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.server.PurgeReport
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogger
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.emit
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Removals the host asks for: the retention window, and erasure.
 *
 * The two are not the same operation wearing different numbers. [trim] is the window doing its
 * work — bounded, repeated on a schedule, and it takes away only what no client may still ask for.
 * [purgeScope] and [purgeCollection] take away everything, on the host's instruction, and nothing
 * in the module will bring any of it back.
 *
 * How often either runs is the host's decision; the module only knows how much history to keep and
 * what it was told to erase.
 *
 * @property database Database the module's schema lives in.
 * @property retentionSeconds How long history is kept, in seconds.
 * @property clock Source of the current moment.
 * @property metrics Sink told, per collection, how much was removed and how much history is left.
 * @property log Sink told the same, in the words of somebody reading the host's log.
 */
public class SyncMaintenance internal constructor(
    private val database: Database,
    private val retentionSeconds: Long,
    private val clock: Clock,
    private val metrics: SyncMetrics,
    private val log: SyncLog,
) {
    private val logger = SyncLogger(log)

    /**
     * Removes history older than the retention window from every collection.
     *
     * The order of the two steps is the whole point and must not be swapped: the floor is raised
     * **before** anything is deleted. The other way round, a reader could pass the retention check
     * and then be served a range whose oldest batches have just disappeared — it would receive a
     * partial answer and believe it was complete, which is worse than the honest refusal it should
     * have got.
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
        trimmed.forEach { event ->
            metrics.emit(event, logger)
            // A span that grows from one run to the next means history is accumulating faster than
            // the window discards it, and every read of that collection gets slower with it.
            logger
                .forCollection(event.scope, event.collection)
                .info(
                    SyncLogEvent.HISTORY_TRIMMED,
                    context = {
                        mapOf(
                            "removed" to event.removedBatches.toString(),
                            "retainedSpan" to event.retainedSpan.toString(),
                        )
                    },
                ) { "trimmed history to the retention window" }
        }
        return removed
    }

    /**
     * Removes every row of every collection of one scope from the database.
     *
     * This is erasure, not deletion as the protocol means it. A pushed removal leaves a tombstone
     * that the log carries for the retention window, because clients have to learn about it; a
     * purge leaves nothing at all, which is the only answer to a host that has to be able to say
     * the data is gone. Nothing in the module can undo it and no listener is told: there is no
     * sequence to notify at, and the host asked for this itself.
     *
     * **The host has to stop serving the scope before calling this.** A push that arrives during
     * the purge waits on the collection's counter row and then re-creates the collection from the
     * client's own copy — which is not the module failing, but it is not an erasure either.
     *
     * Unregistered collections are purged too. A collection taken out of the configuration keeps
     * its rows and can be reached by no other operation, so refusing to purge it would leave the
     * host no way at all to the data it most likely wants gone.
     *
     * @param scope Authorised scope to erase; every collection it has is removed.
     * @return What was removed, summed over the scope's collections; [PurgeReport.Empty] when the
     *   scope holds nothing.
     */
    public fun purgeScope(scope: ScopeId): PurgeReport = purge(scope, CollectionsTable.scopeId eq scope.value)

    /**
     * Removes every row of one collection of one scope from the database.
     *
     * The same erasure as [purgeScope], narrowed to one collection; everything said there about
     * irreversibility, concurrent pushes and unregistered collections holds here too.
     *
     * The collection's sequence counter goes with the rest, so the next write to it starts at one
     * again. A client that survived the purge still holding a cursor from before it asks for a
     * position the collection no longer has, is refused with
     * [dev.voir.reflector.sync.server.CursorTooOldException] and rebuilds — which is what makes a
     * counter that restarts safe rather than silent.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to erase; it need not be registered.
     * @return What was removed; [PurgeReport.Empty] when the collection was never written to.
     */
    public fun purgeCollection(
        scope: ScopeId,
        collection: CollectionId,
    ): PurgeReport =
        purge(
            scope,
            (CollectionsTable.scopeId eq scope.value) and (CollectionsTable.collectionId eq collection.value),
        )

    /**
     * Erases every collection matching a predicate, in one transaction.
     *
     * One transaction for the whole purge, unlike [trim], and for a different reason than
     * performance: a scope that is half erased is a state no host can report and none asked for.
     * The set is bounded by the scope's collections rather than by the size of the database, so the
     * transaction stays short in the number of statements even when it is long in rows.
     *
     * @param scope Scope the purge was addressed to; used for reporting.
     * @param selector Which collection rows to erase.
     * @return What was removed, summed over the collections that matched.
     */
    private fun purge(
        scope: ScopeId,
        selector: Op<Boolean>,
    ): PurgeReport {
        val purged =
            transaction(database) {
                // Locked before anything is deleted, for the same reason the push path locks it: a
                // writer that is midway through a batch holds this row, so the purge waits for it
                // instead of deleting a collection whose sequence has just been handed out.
                val targets =
                    CollectionsTable
                        .selectAll()
                        .where(selector)
                        .forUpdate()
                        .map { row ->
                            CollectionId(row[CollectionsTable.collectionId]) to row[CollectionsTable.id].value
                        }
                // Materialised above, deleted here: the deletes must not run while the query that
                // found the rows is still being read.
                targets.map { (collection, rowId) -> collection to erase(rowId) }
            }

        // Reported once the erasure is durable, and never from inside the transaction: these ports
        // are the host's code, and until the commit the purge holds every collection row it is
        // deleting — a slow sink would make writers to the scope wait behind it.
        purged.forEach { (collection, report) ->
            metrics.emit(
                SyncMetricEvent.CollectionPurged(
                    scope = scope,
                    collection = collection,
                    removedEntities = report.entities,
                    removedBatches = report.batches,
                ),
                logger,
            )
            // At INFO, like a trim, but this is the line somebody comes looking for: it is the one
            // removal no window and no schedule accounts for.
            logger
                .forCollection(scope, collection)
                .info(
                    SyncLogEvent.COLLECTION_PURGED,
                    context = {
                        mapOf(
                            "entities" to report.entities.toString(),
                            "batches" to report.batches.toString(),
                            "changes" to report.changes.toString(),
                            "pushResults" to report.pushResults.toString(),
                        )
                    },
                ) { "purged the collection; every row of it has been deleted" }
        }
        return purged.fold(PurgeReport.Empty) { total, (_, report) -> total + report }
    }

    /**
     * Deletes the rows of one collection, children first.
     *
     * The order is the one the foreign keys require, and the deletes are spelled out rather than
     * left to `ON DELETE CASCADE`: the cascade would remove the same rows and report nothing, and a
     * purge whose extent cannot be stated is one the host cannot put in its own audit trail.
     *
     * @param collectionRowId Collection row to erase, already locked.
     * @return Count of rows removed from each table.
     */
    private fun erase(collectionRowId: Uuid): PurgeReport {
        val pushResults = PushResultsTable.deleteWhere { PushResultsTable.collection eq collectionRowId }
        val changes =
            ChangesTable.deleteWhere {
                ChangesTable.batch inSubQuery
                    BatchesTable
                        .select(BatchesTable.id)
                        .where { BatchesTable.collection eq collectionRowId }
            }
        val entities = EntitiesTable.deleteWhere { EntitiesTable.collection eq collectionRowId }
        val batches = BatchesTable.deleteWhere { BatchesTable.collection eq collectionRowId }
        val collections = CollectionsTable.deleteWhere { CollectionsTable.id eq collectionRowId }
        return PurgeReport(
            collections = collections,
            batches = batches,
            changes = changes,
            entities = entities,
            pushResults = pushResults,
        )
    }
}
