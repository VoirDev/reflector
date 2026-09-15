package dev.voir.reflector.sync.persistence.blob

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Access to the synchronisation metadata of files.
 *
 * Reads here answer two questions and nothing else: what has to be sent, and what has to be
 * fetched. Both are driven by the reference table rather than by the blob rows alone — a file
 * nothing points at is not work, whatever state it is in.
 */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncBlobDao {
    /**
     * Reads the metadata of one file.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @return Stored metadata, or `null` when the library has never seen the file.
     */
    @Query(
        "SELECT * FROM sync_blob WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND blob_id = :blobId",
    )
    public suspend fun find(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
    ): SyncBlobEntity?

    /**
     * Publishes the metadata of one file as it changes.
     *
     * What a photograph in a user interface binds to. A flow per file rather than one over the whole
     * collection, because the screen that draws it already knows which identifier it is drawing.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @return Stored metadata, or `null` while the library has never seen the file.
     */
    @Query(
        "SELECT * FROM sync_blob WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND blob_id = :blobId",
    )
    public fun observe(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
    ): Flow<SyncBlobEntity?>

    /**
     * Inserts or replaces the metadata of one file.
     *
     * @param blob Metadata to store.
     */
    @Upsert
    public suspend fun upsert(blob: SyncBlobEntity)

    /**
     * Moves a file to a new state, clearing what the last attempt failed with.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @param state State to move to.
     */
    @Query(
        "UPDATE sync_blob SET state = :state, last_error = NULL, attempts = 0, next_retry_at = NULL, " +
            "transferred = 0 WHERE scope_id = :scopeId AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun setState(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
        state: BlobTransferState,
    )

    /**
     * Records that an attempt failed and when the next one may start.
     *
     * The attempt count is incremented rather than set, because the backoff grows with it and a
     * count that restarted would keep a hopeless transfer running forever.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @param error What the attempt failed with.
     * @param nextRetryAt When the next attempt may start.
     */
    @Query(
        "UPDATE sync_blob SET attempts = attempts + 1, last_error = :error, next_retry_at = :nextRetryAt, " +
            "transferred = 0 WHERE scope_id = :scopeId AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun recordFailure(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
        error: String,
        nextRetryAt: Long,
    )

    /**
     * Records whether this device is trying to hold a file's bytes.
     *
     * Deliberately touches nothing else. A request has to be able to reach a row that is mid-backoff
     * without resetting the attempts that produced it, and eviction has to be able to clear the wish
     * without pretending the transfer never happened.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @param wanted Whether the bytes are wanted here.
     */
    @Query(
        "UPDATE sync_blob SET wanted = :wanted WHERE scope_id = :scopeId " +
            "AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun setWanted(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
        wanted: Boolean,
    )

    /**
     * Marks every file some document declares eagerly as wanted here.
     *
     * The one place the declared policy becomes an intent, and it runs on every reconciliation
     * rather than once when a file is adopted: a document edited into naming a file eagerly, a
     * second document that names one another declared on demand, and a file evicted although the
     * declaration says this device keeps it all arrive later than the adoption did.
     *
     * Eager wins where two documents disagree, because that is the only reading under which a file
     * some record cannot be drawn without is reliably here.
     *
     * @param scopeId Scope to promote in.
     * @param collectionId Collection to promote in.
     */
    @Query(
        "UPDATE sync_blob SET wanted = 1 WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND wanted = 0 AND EXISTS (SELECT 1 FROM sync_blob_ref r WHERE r.scope_id = sync_blob.scope_id " +
            "AND r.collection_id = sync_blob.collection_id AND r.blob_id = sync_blob.blob_id " +
            "AND r.fetch = 'EAGER')",
    )
    public suspend fun wantEagerlyReferenced(
        scopeId: String,
        collectionId: String,
    )

    /**
     * Records how much of a transfer has moved.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @param transferred Octets moved so far.
     */
    @Query(
        "UPDATE sync_blob SET transferred = :transferred WHERE scope_id = :scopeId " +
            "AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun setTransferred(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
        transferred: Long,
    )

    /**
     * Replaces what a file was declared to be.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     * @param contentType Media type as the store now reports it.
     * @param size Length as the store now reports it.
     * @param checksum Digest as the store now reports it, or `null`.
     */
    @Query(
        "UPDATE sync_blob SET content_type = :contentType, size = :size, checksum = :checksum " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun setDeclaration(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
        contentType: String?,
        size: Long?,
        checksum: String?,
    )

    /**
     * Reads files in a given state that this device wants and some document still points at, oldest
     * work first.
     *
     * Two conditions beyond the state, and each excludes a different kind of non-work. A file
     * nothing references is not work whatever state its row is in, and a file nothing wants is a
     * download nobody has asked for — which is the resting state of most files under
     * [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] and the reason that policy costs the
     * worker nothing. Uploads are unaffected: bytes on this device are wanted by construction.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @param state State to look for.
     * @param now Current moment, so that a file serving a backoff is skipped.
     * @param limit Largest number of files to return.
     * @return Files waiting for a transfer.
     */
    @Query(
        "SELECT b.* FROM sync_blob b WHERE b.scope_id = :scopeId AND b.collection_id = :collectionId " +
            "AND b.state = :state AND b.wanted = 1 AND (b.next_retry_at IS NULL OR b.next_retry_at <= :now) " +
            "AND EXISTS (SELECT 1 FROM sync_blob_ref r WHERE r.scope_id = b.scope_id " +
            "AND r.collection_id = b.collection_id AND r.blob_id = b.blob_id) " +
            "ORDER BY b.attempts, b.rowid LIMIT :limit",
    )
    public suspend fun waiting(
        scopeId: String,
        collectionId: String,
        state: BlobTransferState,
        now: Long,
        limit: Int,
    ): List<SyncBlobEntity>

    /**
     * Counts files in a given state that this device wants and some document still points at.
     *
     * The same two conditions as [waiting], for the same reason: what is counted here is published
     * to the application, and a file nobody asked for is not an arrival anything is waiting on.
     *
     * @param scopeId Scope to count in.
     * @param collectionId Collection to count in.
     * @param state State to count.
     * @return How many files are waiting.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_blob b WHERE b.scope_id = :scopeId AND b.collection_id = :collectionId " +
            "AND b.state = :state AND b.wanted = 1 AND EXISTS (SELECT 1 FROM sync_blob_ref r " +
            "WHERE r.scope_id = b.scope_id AND r.collection_id = b.collection_id AND r.blob_id = b.blob_id)",
    )
    public fun observeCount(
        scopeId: String,
        collectionId: String,
        state: BlobTransferState,
    ): Flow<Int>

    /**
     * Reads every file of a collection that no document points at any more.
     *
     * What the library offers the application to delete. Offered rather than deleted, because
     * removing a user's bytes on a judgement of the library's own is not something it will do.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @return Files nothing references.
     */
    @Query(
        "SELECT b.* FROM sync_blob b WHERE b.scope_id = :scopeId AND b.collection_id = :collectionId " +
            "AND NOT EXISTS (SELECT 1 FROM sync_blob_ref r WHERE r.scope_id = b.scope_id " +
            "AND r.collection_id = b.collection_id AND r.blob_id = b.blob_id)",
    )
    public suspend fun unreferenced(
        scopeId: String,
        collectionId: String,
    ): List<SyncBlobEntity>

    /**
     * Reads every file of a collection.
     *
     * For the erasures — signing out, a revoked scope, a purged collection — where the application
     * is offered all of them rather than only what nothing references.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @return Every file the library knows of in the collection.
     */
    @Query("SELECT * FROM sync_blob WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun all(
        scopeId: String,
        collectionId: String,
    ): List<SyncBlobEntity>

    /**
     * Removes the metadata of one file.
     *
     * @param scopeId Scope of the file.
     * @param collectionId Collection of the file.
     * @param blobId Identifier of the file.
     */
    @Query(
        "DELETE FROM sync_blob WHERE scope_id = :scopeId AND collection_id = :collectionId AND blob_id = :blobId",
    )
    public suspend fun delete(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
    )

    /**
     * Reads every file of a scope, across all of its collections.
     *
     * For signing out and for a revoked scope, where what goes is everything the scope brought in
     * rather than what some collection stopped pointing at.
     *
     * @param scopeId Scope to read.
     * @return Every file the library knows of in the scope.
     */
    @Query("SELECT * FROM sync_blob WHERE scope_id = :scopeId")
    public suspend fun allOfScope(scopeId: String): List<SyncBlobEntity>

    /**
     * Removes the metadata of every file of a scope.
     *
     * @param scopeId Scope to clear.
     */
    @Query("DELETE FROM sync_blob WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)

    /**
     * Removes the metadata of every file of a collection.
     *
     * @param scopeId Scope to clear.
     * @param collectionId Collection to clear.
     */
    @Query("DELETE FROM sync_blob WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun deleteAll(
        scopeId: String,
        collectionId: String,
    )
}
