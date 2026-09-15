package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.BlobNotStoredException
import dev.voir.reflector.sync.server.BlobStorage
import dev.voir.reflector.sync.server.BlobSweepReport
import dev.voir.reflector.sync.server.PurgeReport
import dev.voir.reflector.sync.server.StoredBlob
import dev.voir.reflector.sync.server.SyncBlobService
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
import org.jetbrains.exposed.v1.core.inList
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
 * @property blobStorage The host's object storage, when the deployment serves files.
 * @property blobService The module's own file operations, used to confirm uploads nobody confirmed.
 * @property blobConfig Limits on files, when the deployment serves them.
 */
public class SyncMaintenance internal constructor(
    private val database: Database,
    private val retentionSeconds: Long,
    private val clock: Clock,
    private val metrics: SyncMetrics,
    private val log: SyncLog,
    private val blobStorage: BlobStorage? = null,
    private val blobService: SyncBlobService? = null,
    private val blobConfig: BlobConfig? = null,
) {
    private val logger = SyncLogger(log)
    private val blobs = BlobRows()

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
     * Removes files nothing has referenced for longer than the retention window.
     *
     * The window is the same number as for the log, and for the same reason: a client whose cursor
     * is still inside it may yet apply a batch in which the blob was referenced, and one that has
     * fallen outside it rebuilds from a snapshot and sees only what is current. Anything shorter is
     * a missing photograph on a device that did nothing wrong.
     *
     * **The rows go first, and the objects are handed over afterwards.** It cannot be the other way
     * round: between choosing a candidate and disposing of its object, a push could arrive that
     * references it, and an object deleted under a live document is a broken attachment on every
     * device, for good. Dropping the row first closes that — from the commit onwards a push naming
     * the blob is refused with `BLOB_MISSING`, which the client answers by uploading again.
     *
     * What becomes of the object after that is the host's, through [BlobStorage.onReleased]. The
     * module does not ask and is not told: disposal is a policy — delete now, tag for a lifecycle
     * rule, keep it for a retention period somebody legislated — and a module that judged the
     * outcome would be reporting three correct policies as a fault.
     *
     * Each collection is swept under its own counter lock, so the check a push makes that a
     * referenced blob exists cannot be overtaken by this sweep. The host's storage is never called
     * while that lock is held.
     *
     * @param dryRun Whether to report what would be collected without removing anything. Worth
     *   running first on a collection whose clients are not all known to declare their references:
     *   a client that predates files says nothing and is respected, but an application that has
     *   files and forgets to answer for one entity type says "references nothing", and the two are
     *   indistinguishable from here.
     * @return What was found, and what became of it.
     */
    public fun collectBlobs(dryRun: Boolean = false): BlobSweepReport {
        val storage = blobStorage ?: return BlobSweepReport.Empty
        val cutoff = clock.now().minus(kotlin.time.Duration.parse("${retentionSeconds}s"))
        return collections().fold(BlobSweepReport.Empty) { total, target ->
            total + collectFrom(storage, target, cutoff, dryRun)
        }
    }

    /**
     * Confirms uploads that finished without anybody saying so.
     *
     * The third of the three ways a blob becomes usable, and the one that exists because a device
     * can die between writing the object and reporting it. Without this, bytes that are sitting in
     * the bucket stay unfetchable for as long as the document naming them lives.
     *
     * A blob whose object never arrived throws from here and that is the expected, common case: an
     * abandoned upload is an ordinary thing for a user to produce, and it is left alone to be
     * collected by [collectBlobs] once nothing references it.
     *
     * @return Number of blobs this pass made usable.
     */
    public fun confirmPendingUploads(): Int {
        val service = blobService ?: return 0
        val life = blobConfig?.uploadTicketLife ?: return 0
        val cutoff = clock.now().minus(life)
        return collections().sumOf { target ->
            val stale =
                transaction(database) {
                    BlobsTable
                        .selectAll()
                        .where {
                            (BlobsTable.collection eq target.rowId) and
                                (BlobsTable.state eq BlobState.PENDING) and
                                (BlobsTable.createdAt less cutoff)
                        }.map { BlobId(it[BlobsTable.blobId]) }
                }
            // Outside the transaction: confirming reaches the host's storage, and each one opens
            // transactions of its own.
            stale.count { blobId ->
                runCatching { service.markUploaded(target.scope, target.collection, blobId) }
                    .fold(
                        onSuccess = { true },
                        onFailure = { failure ->
                            // Anything else is a fault worth surfacing rather than counting.
                            if (failure !is BlobNotStoredException) throw failure
                            false
                        },
                    )
            }
        }
    }

    /**
     * Sweeps one collection, holding its counter lock only for as long as the rows take.
     *
     * @param storage The host's object storage.
     * @param target Collection to sweep.
     * @param cutoff Moment before which an unreferenced blob is garbage.
     * @param dryRun Whether to remove anything.
     * @return What was found, and what became of it.
     */
    private fun collectFrom(
        storage: BlobStorage,
        target: CollectionTarget,
        cutoff: kotlin.time.Instant,
        dryRun: Boolean,
    ): BlobSweepReport {
        val candidates =
            transaction(database) {
                // The same lock the push path takes. Without it a blob could be verified as present
                // by a push and deleted here before that push inserted its reference.
                CollectionsTable
                    .selectAll()
                    .where { CollectionsTable.id eq target.rowId }
                    .forUpdate()
                    .toList()
                val found =
                    BlobsTable
                        .selectAll()
                        .where {
                            (BlobsTable.collection eq target.rowId) and
                                (BlobsTable.unreferencedSince less cutoff)
                        }.map { it.toStoredBlob() }
                if (!dryRun && found.isNotEmpty()) {
                    BlobsTable.deleteWhere {
                        (BlobsTable.collection eq target.rowId) and
                            (BlobsTable.blobId inList found.map { blob -> blob.blobId.value })
                    }
                }
                found
            }
        if (candidates.isEmpty()) {
            return BlobSweepReport(0, 0, dryRun)
        }
        if (dryRun) {
            logger.forCollection(target.scope, target.collection).info(
                SyncLogEvent.BLOBS_COLLECTED,
                context = { mapOf("candidates" to candidates.size.toString(), "dryRun" to "true") },
            ) { "a dry run found files nothing has referenced for longer than the retention window" }
            return BlobSweepReport(candidates.size, released = 0, dryRun = true)
        }

        storage.onReleased(target.scope, target.collection, candidates)
        report(target, candidates)
        return BlobSweepReport(candidates.size, released = candidates.size, dryRun = false)
    }

    /**
     * Says what the sweep let go of.
     *
     * Reports the module's own decision and stops there. What the host then does with the objects is
     * recorded where it happens, in the host's storage log, which is also the only account of it an
     * auditor would accept.
     *
     * @param target Collection that was swept.
     * @param candidates Blobs whose rows were dropped and whose keys were handed over.
     */
    private fun report(
        target: CollectionTarget,
        candidates: List<StoredBlob>,
    ) {
        val logger = logger.forCollection(target.scope, target.collection)
        logger.info(
            SyncLogEvent.BLOBS_COLLECTED,
            context = {
                mapOf(
                    "released" to candidates.size.toString(),
                    "bytes" to candidates.sumOf { it.size }.toString(),
                )
            },
        ) { "released files nothing has referenced for longer than the retention window" }
        metrics.emit(
            SyncMetricEvent.BlobsCollected(
                scope = target.scope,
                collection = target.collection,
                released = candidates.size,
                bytes = candidates.sumOf { it.size },
            ),
            logger,
        )
    }

    /**
     * Lists every collection the module holds rows for.
     *
     * Read in its own transaction and materialised, so that the work each one needs can be done
     * without holding a cursor open across all of them.
     *
     * @return Every collection, registered or not.
     */
    private fun collections(): List<CollectionTarget> =
        transaction(database) {
            CollectionsTable.selectAll().map { row ->
                CollectionTarget(
                    rowId = row[CollectionsTable.id].value,
                    scope = ScopeId(row[CollectionsTable.scopeId]),
                    collection = CollectionId(row[CollectionsTable.collectionId]),
                )
            }
        }

    /**
     * One collection, as maintenance addresses it.
     *
     * @property rowId Row identifier, which is what the module's own tables key on.
     * @property scope Scope it belongs to.
     * @property collection Identifier clients address it by.
     */
    private data class CollectionTarget(
        val rowId: Uuid,
        val scope: ScopeId,
        val collection: CollectionId,
    )

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
                targets.map { (collection, rowId) ->
                    // Read before the erasure, because the rows are about to be gone and the host
                    // still has to be told which objects to remove.
                    val files = filesOf(rowId)
                    Erased(collection, erase(rowId), files)
                }
            }

        // Outside the transaction, like every other call into the host's storage: an erasure may be
        // thousands of objects, and until the commit the purge holds every collection row it touched.
        val erased =
            purged.map { target ->
                handOver(scope, target)
                target.report.copy(blobs = target.files.size)
            }

        // Reported once the erasure is durable, and never from inside the transaction: these ports
        // are the host's code, and until the commit the purge holds every collection row it is
        // deleting — a slow sink would make writers to the scope wait behind it.
        purged.zip(erased).forEach { (target, report) ->
            val collection = target.collection
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
                            "blobs" to report.blobs.toString(),
                        )
                    },
                ) { "purged the collection; every row of it has been deleted" }
        }
        return erased.fold(PurgeReport.Empty) { total, report -> total + report }
    }

    /**
     * Reads the files of a collection about to be erased.
     *
     * @param collectionRowId Collection row being erased.
     * @return Every blob it carries.
     */
    private fun filesOf(collectionRowId: Uuid): List<StoredBlob> =
        BlobsTable
            .selectAll()
            .where { BlobsTable.collection eq collectionRowId }
            .map { it.toStoredBlob() }

    /**
     * Hands the files of a purged collection to the host.
     *
     * A purge does not wait for the retention window, which is the same licence that lets it break
     * every cursor in the collection: it answers an erasure request, and an erasure that waits
     * thirty days is not one. What the host then does about the objects is its own — and for an
     * erasure that matters more than usual, because the evidence an auditor wants is the host's
     * storage log rather than the module's account of having asked.
     *
     * @param scope Scope the collection belonged to.
     * @param target What was erased.
     */
    private fun handOver(
        scope: ScopeId,
        target: Erased,
    ) {
        val storage = blobStorage
        if (storage == null || target.files.isEmpty()) {
            return
        }
        storage.onReleased(scope, target.collection, target.files)
    }

    /**
     * One erased collection, before its files have been removed from storage.
     *
     * @property collection Collection that was erased.
     * @property report Rows removed from each table.
     * @property files Blobs it carried, read before the rows went.
     */
    private data class Erased(
        val collection: CollectionId,
        val report: PurgeReport,
        val files: List<StoredBlob>,
    )

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
        // Before the collection row, whose cascade would take these without reporting a number.
        val blobs = BlobsTable.deleteWhere { BlobsTable.collection eq collectionRowId }
        val entities = EntitiesTable.deleteWhere { EntitiesTable.collection eq collectionRowId }
        val batches = BatchesTable.deleteWhere { BatchesTable.collection eq collectionRowId }
        val collections = CollectionsTable.deleteWhere { CollectionsTable.id eq collectionRowId }
        return PurgeReport(
            collections = collections,
            batches = batches,
            changes = changes,
            entities = entities,
            pushResults = pushResults,
            blobs = blobs,
        )
    }
}
