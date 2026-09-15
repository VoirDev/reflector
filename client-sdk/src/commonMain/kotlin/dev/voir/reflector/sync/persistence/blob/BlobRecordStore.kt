package dev.voir.reflector.sync.persistence.blob

import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Typed access to the synchronisation metadata of files.
 *
 * Named for what it stores rather than for the concept, because the concept's name is taken: the
 * application's [dev.voir.reflector.sync.core.blob.BlobStore] is where the bytes live, and this is
 * where the library's bookkeeping about them lives. Confusing the two is exactly the mistake the
 * whole design is arranged to prevent, so they do not share a name.
 *
 * @property dao Generated access to the table.
 */
internal class BlobRecordStore(
    private val dao: SyncBlobDao,
) {
    /**
     * Reads what the library knows about one file.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @return Stored metadata, or `null` when the library has never seen it.
     */
    suspend fun find(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobRecord? = dao.find(scope.value, collection.value, blobId.value)?.toRecord()

    /**
     * Publishes what the library knows about one file as it changes.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @return Stored metadata, or `null` while the library has never seen it.
     */
    fun observe(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): Flow<BlobRecord?> = dao.observe(scope.value, collection.value, blobId.value).map { it?.toRecord() }

    /**
     * Records a file the library has just learned of.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param state Where its bytes are.
     * @param stat What the bytes are, when anything has declared them.
     * @param wanted Whether this device is trying to hold the bytes.
     */
    suspend fun put(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        state: BlobTransferState,
        stat: BlobStat?,
        wanted: Boolean,
    ) {
        dao.upsert(
            SyncBlobEntity(
                scopeId = scope.value,
                collectionId = collection.value,
                blobId = blobId.value,
                state = state,
                wanted = wanted,
                contentType = stat?.contentType?.value,
                size = stat?.size,
                checksum = stat?.checksum?.value,
            ),
        )
    }

    /**
     * Moves a file to a new state and forgets what the last attempt failed with.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param state State to move to.
     */
    suspend fun setState(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        state: BlobTransferState,
    ) {
        dao.setState(scope.value, collection.value, blobId.value, state)
    }

    /**
     * Records whether this device is trying to hold a file's bytes.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param wanted Whether the bytes are wanted here.
     */
    suspend fun setWanted(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        wanted: Boolean,
    ) {
        dao.setWanted(scope.value, collection.value, blobId.value, wanted)
    }

    /**
     * Marks every file a document of the collection declares eagerly as wanted here.
     *
     * @param scope Scope to promote in.
     * @param collection Collection to promote in.
     */
    suspend fun wantEagerlyReferenced(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.wantEagerlyReferenced(scope.value, collection.value)
    }

    /**
     * Replaces what a file was declared to be.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param stat What the bytes are now known to be.
     */
    suspend fun setDeclaration(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        stat: BlobStat,
    ) {
        dao.setDeclaration(
            scope.value,
            collection.value,
            blobId.value,
            stat.contentType.value,
            stat.size,
            stat.checksum?.value,
        )
    }

    /**
     * Records that a transfer failed and when the next attempt may start.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param error What the attempt failed with.
     * @param nextRetryAt When the next attempt may start.
     */
    suspend fun recordFailure(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        error: String,
        nextRetryAt: Long,
    ) {
        dao.recordFailure(scope.value, collection.value, blobId.value, error, nextRetryAt)
    }

    /**
     * Records how much of a transfer has moved.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     * @param transferred Octets moved so far.
     */
    suspend fun setTransferred(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
        transferred: Long,
    ) {
        dao.setTransferred(scope.value, collection.value, blobId.value, transferred)
    }

    /**
     * Reads files waiting for a transfer in one direction.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @param state State the waiting files are in.
     * @param now Current moment, so that a file serving a backoff is skipped.
     * @param limit Largest number of files to return.
     * @return Files ready to be transferred.
     */
    suspend fun waiting(
        scope: ScopeId,
        collection: CollectionId,
        state: BlobTransferState,
        now: Long,
        limit: Int,
    ): List<BlobRecord> = dao.waiting(scope.value, collection.value, state, now, limit).map { it.toRecord() }

    /**
     * Counts files waiting for a transfer in one direction.
     *
     * @param scope Scope to count in.
     * @param collection Collection to count in.
     * @param state State to count.
     * @return How many files are waiting.
     */
    fun observeCount(
        scope: ScopeId,
        collection: CollectionId,
        state: BlobTransferState,
    ): Flow<Int> = dao.observeCount(scope.value, collection.value, state)

    /**
     * Reads files no document points at any more.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @return Files the application may be offered.
     */
    suspend fun unreferenced(
        scope: ScopeId,
        collection: CollectionId,
    ): List<BlobRecord> = dao.unreferenced(scope.value, collection.value).map { it.toRecord() }

    /**
     * Reads every file of a collection.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @return Every file the library knows of.
     */
    suspend fun all(
        scope: ScopeId,
        collection: CollectionId,
    ): List<BlobRecord> = dao.all(scope.value, collection.value).map { it.toRecord() }

    /**
     * Reads every file of a collection, saying which of them nothing points at any more.
     *
     * For diagnostics, where the difference matters: a file nothing references is one the library is
     * about to offer back, and a list of them on an otherwise idle collection means the application
     * is declining to take them.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @return Every file, paired with whether a document still names it.
     */
    suspend fun allWithReferences(
        scope: ScopeId,
        collection: CollectionId,
    ): List<Pair<BlobRecord, Boolean>> {
        val unreferenced = unreferenced(scope, collection).mapTo(mutableSetOf()) { it.blobId }
        return all(scope, collection).map { it to (it.blobId !in unreferenced) }
    }

    /**
     * Forgets one file.
     *
     * @param scope Scope of the file.
     * @param collection Collection of the file.
     * @param blobId Identifier of the file.
     */
    suspend fun delete(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ) {
        dao.delete(scope.value, collection.value, blobId.value)
    }

    /**
     * Reads every file of a scope, across all of its collections.
     *
     * @param scope Scope to read.
     * @return Every file the library knows of in the scope.
     */
    suspend fun allOfScope(scope: ScopeId): List<BlobRecord> = dao.allOfScope(scope.value).map { it.toRecord() }

    /**
     * Forgets every file of a scope.
     *
     * @param scope Scope to clear.
     */
    suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    /**
     * Forgets every file of a collection.
     *
     * @param scope Scope to clear.
     * @param collection Collection to clear.
     */
    suspend fun deleteAll(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.deleteAll(scope.value, collection.value)
    }

    private fun SyncBlobEntity.toRecord(): BlobRecord =
        BlobRecord(
            blobId = BlobId(blobId),
            state = state,
            wanted = wanted,
            stat =
                size?.let { declared ->
                    BlobStat(
                        size = declared,
                        contentType = BlobContentType(contentType.orEmpty()),
                        checksum = checksum?.let(::BlobChecksum),
                    )
                },
            attempts = attempts,
            nextRetryAt = nextRetryAt,
            lastError = lastError,
            transferred = transferred,
        )
}

/**
 * Synchronisation metadata of one file, in the terms the engine works in.
 *
 * @property blobId Identifier of the file.
 * @property state Where its bytes are.
 * @property wanted Whether this device is trying to hold the bytes. The download queue is the rows
 *   where this and the state agree that bytes are missing and wanted.
 * @property stat What the bytes are, or `null` before anything has declared them.
 * @property attempts Transfers tried since the last success.
 * @property nextRetryAt When the next attempt may start, or `null` when one may start now.
 * @property lastError What the last attempt failed with, or `null` when none has.
 * @property transferred Octets moved in the current direction.
 */
internal data class BlobRecord(
    val blobId: BlobId,
    val state: BlobTransferState,
    val wanted: Boolean,
    val stat: BlobStat?,
    val attempts: Int,
    val nextRetryAt: Long?,
    val lastError: String?,
    val transferred: Long,
)
