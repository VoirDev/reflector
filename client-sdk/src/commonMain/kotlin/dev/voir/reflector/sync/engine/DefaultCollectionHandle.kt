package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.CollectionSyncState
import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.MutationScope
import dev.voir.reflector.sync.core.RefusedGroup
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.blob.BlobSyncState
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.diagnostics.CollectionDiagnostics
import dev.voir.reflector.sync.core.diagnostics.QueuedBlobDiagnostics
import dev.voir.reflector.sync.core.diagnostics.QueuedGroupDiagnostics
import dev.voir.reflector.sync.engine.conflict.ConflictCoordinator
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.blob.BlobRecord
import dev.voir.reflector.sync.persistence.conflict.StoredConflict
import dev.voir.reflector.sync.persistence.group.PendingGroup
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Instant

/**
 * The application's handle on one collection.
 *
 * Everything observable here is derived from the database rather than kept in memory, so a value
 * the user interface shows cannot disagree with what the next push will actually send. The one
 * exception is the last failure, which is deliberately not persisted: a failure from a previous
 * process is history, not a state anybody can act on.
 *
 * @param scope Scope of the collection.
 * @param collection Identifier of the collection.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param mutations Coordinator that turns local transactions into queued work.
 * @param conflictCoordinator Coordinator that applies decisions about conflicts.
 * @param worker Worker driving this collection.
 * @param conflictThreshold Number of open conflicts at which the published phase stops saying the
 *   collection is live.
 * @param coroutineScope Scope the state flows are shared in.
 */
internal class DefaultCollectionHandle(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val mutations: MutationCoordinator,
    private val conflictCoordinator: ConflictCoordinator,
    private val worker: CollectionWorker,
    private val conflictThreshold: ConflictThreshold,
    coroutineScope: CoroutineScope,
) : CollectionHandle {
    override val state: StateFlow<CollectionSyncState> =
        combine(
            stores.collections.observe(scope, collection),
            stores.records.observePendingCount(scope, collection),
            stores.conflicts.observeCount(scope, collection),
            worker.lastFailure,
            // The two file counts are combined into one pair first: `combine` takes five flows at
            // most, and a sixth would mean reaching for the list-of-flows overload and losing every
            // type in the lambda.
            combine(
                stores.blobs.observeCount(scope, collection, BlobTransferState.LOCAL),
                stores.blobs.observeCount(scope, collection, BlobTransferState.UPLOADING),
                stores.blobs.observeCount(scope, collection, BlobTransferState.REMOTE),
                stores.blobs.observeCount(scope, collection, BlobTransferState.DOWNLOADING),
            ) { local, uploading, remote, downloading ->
                (local + uploading) to (remote + downloading)
            },
        ) { collectionState, pending, conflictCount, failure, files ->
            CollectionSyncState(
                phase = publishedPhase(collectionState?.phase ?: SyncPhase.NEW, conflictCount),
                pendingCount = pending,
                conflictCount = conflictCount,
                lastFailure = failure,
                pendingBlobs = files.first,
                incomingBlobs = files.second,
            )
        }.stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = CollectionSyncState(SyncPhase.NEW, pendingCount = 0, conflictCount = 0, lastFailure = null),
        )

    override fun blob(id: BlobId): Flow<BlobSyncState?> =
        stores.blobs.observe(scope, collection, id).map { record -> record?.toSyncState() }

    override val blobs: Flow<Map<BlobId, BlobSyncState>> =
        stores.blobs.observeReferenced(scope, collection).map { records ->
            records.associate { record -> record.blobId to record.toSyncState() }
        }

    override val conflicts: Flow<List<Conflict>> =
        stores.conflicts.observe(scope, collection).map { stored -> stored.map { it.toConflict() } }

    override val refusals: Flow<List<RefusedGroup>> = stores.groups.observeRefused(scope, collection)

    override suspend fun fetch(id: BlobId) {
        worker.fetchBlob(id)
    }

    override suspend fun evict(id: BlobId) {
        worker.evictBlob(id)
    }

    override suspend fun retry(id: BlobId) {
        worker.retryBlob(id)
    }

    override suspend fun <R> mutate(block: suspend MutationScope.() -> R): R {
        val result = mutations.mutate(scope, collection) { block() }
        // A local change is a reason to synchronise, and the user expects it to leave as soon as the
        // network allows; waiting for the next timer would look like the change was lost.
        worker.requestSync()
        return result
    }

    override suspend fun requestSync() {
        worker.requestSync()
    }

    override suspend fun requestResync() {
        // The phase is written down and the worker woken, rather than a bootstrap started here: the
        // worker owns the order of things, and starting one from under it would race with whatever
        // it is doing.
        transactions.transaction {
            stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
        }
        worker.requestSync()
    }

    override suspend fun discardLocalChanges() {
        worker.discardLocalChanges()
    }

    override suspend fun resolve(
        conflictId: ConflictId,
        resolution: Resolution,
    ) {
        conflictCoordinator.resolve(conflictId, resolution)
        worker.requestSync()
    }

    override suspend fun diagnostics(): CollectionDiagnostics =
        // One transaction for all of it. Read separately, the queue could be a group shorter than
        // the pending count beside it describes, and a snapshot whose parts disagree is worse to
        // reason about than one that is a moment old.
        transactions.transaction {
            val stored = stores.collections.ensure(scope, collection)
            val queue = stores.groups.queue(scope, collection)
            CollectionDiagnostics(
                scope = scope,
                collection = collection,
                // The stored phase, not the published one: NEEDS_ATTENTION is derived from the
                // conflict count, which is reported here in its own right.
                phase = stored.phase,
                cursor = stored.cursor,
                generation = stored.generation,
                bootstrapPage = stored.bootstrapPage,
                schemaFingerprint = stored.schemaFingerprint,
                pendingCount = stores.records.pendingCount(scope, collection),
                conflictCount = stores.conflicts.openIds(scope, collection).size,
                lastError = stored.lastError,
                failureCount = stored.failureCount,
                lastPullAt = stored.lastPullAt,
                lastPushAt = stored.lastPushAt,
                queue = queue.map { group -> group.describe() },
                files =
                    stores.blobs.allWithReferences(scope, collection).map { (record, referenced) ->
                        QueuedBlobDiagnostics(
                            blobId = record.blobId,
                            state = record.state,
                            wanted = record.wanted,
                            size = record.stat?.size,
                            transferred = record.transferred,
                            attempts = record.attempts,
                            nextRetryAt = record.nextRetryAt?.let(Instant::fromEpochMilliseconds),
                            referenced = referenced,
                            lastError = record.lastError,
                        )
                    },
            )
        }

    /**
     * Describes one queued group, counting what it would actually send.
     *
     * The operation count comes from the records rather than from the group row, because a group
     * holds records that have since been acknowledged along another path: a group of five whose
     * four clean records were never released would be described as sending five operations and
     * would send one.
     *
     * @return The group as the application is shown it.
     */
    private suspend fun PendingGroup.describe(): QueuedGroupDiagnostics =
        QueuedGroupDiagnostics(
            groupId = groupId,
            ordinal = ord,
            state = state,
            operations = stores.records.ofGroup(groupId).count { it.isDirty },
            attempts = attempts,
            nextRetryAt = nextRetryAt?.let(Instant::fromEpochMilliseconds),
            dependencyMerges = dependencyMerges,
            lastError = lastError,
        )

    /**
     * Replaces a live phase with [SyncPhase.NEEDS_ATTENTION] once conflicts have piled up.
     *
     * Derived rather than stored, and derived here rather than in the worker, for two reasons. The
     * phase in the database is what decides which worker may run, and the answer to a pile of
     * conflicts is that they all keep running — nothing about pulling or pushing changes. And the
     * count moves on its own as the application resolves conflicts, so a stored phase would have to
     * be recomputed by whoever happened to write next, and would be wrong in between.
     *
     * Only [SyncPhase.LIVE] is replaced. A collection that is bootstrapping or has lost its cursor
     * is already saying something more urgent, and conflicts are not what its application should be
     * told about first.
     *
     * @param stored Phase the collection's row carries.
     * @param conflictCount Conflicts currently open in the collection.
     * @return Phase to publish to the application.
     */
    private fun publishedPhase(
        stored: SyncPhase,
        conflictCount: Int,
    ): SyncPhase =
        if (stored == SyncPhase.LIVE && conflictCount >= conflictThreshold.value) {
            SyncPhase.NEEDS_ATTENTION
        } else {
            stored
        }

    private fun BlobRecord.toSyncState(): BlobSyncState =
        BlobSyncState(
            state = state,
            wanted = wanted,
            size = stat?.size,
            transferred = transferred,
            lastError = lastError,
            attempts = attempts,
        )

    private fun StoredConflict.toConflict(): Conflict =
        Conflict(
            id = conflictId,
            entityType = entityType,
            entityId = entityId,
            origin = origin,
            local = local,
            server = server,
        )
}
