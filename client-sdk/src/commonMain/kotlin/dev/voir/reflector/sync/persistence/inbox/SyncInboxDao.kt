package dev.voir.reflector.sync.persistence.inbox

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.voir.reflector.sync.persistence.SyncColumnConverters

/**
 * Access to downloaded batches waiting to be applied.
 *
 * The inbox exists so that downloading and applying are separate transactions: a page is written
 * once, and each of its batches is then applied together with advancing the cursor. A crash while
 * applying costs a repeated apply of one batch, never a repeated download of the page.
 */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncInboxDao {
    /**
     * Stores downloaded batches.
     *
     * @param batches Batches of one page, in the order the server returned them.
     */
    @Insert
    public suspend fun insertBatches(batches: List<SyncInboxBatchEntity>)

    /**
     * Stores the operations of downloaded batches.
     *
     * @param ops Operations to store.
     */
    @Insert
    public suspend fun insertOps(ops: List<SyncInboxOpEntity>)

    /**
     * Returns the ordinal the next downloaded batch has to take.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return One past the largest arrival ordinal in use.
     */
    @Query(
        "SELECT COALESCE(MAX(received_ord), 0) + 1 FROM sync_inbox_batch " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun nextReceivedOrdinal(
        scopeId: String,
        collectionId: String,
    ): Long

    /**
     * Returns the oldest batch that has not been applied.
     *
     * Ordered by arrival, not by sequence: the sequence is an opaque string, and sorting it as text
     * would let the cursor jump over a batch.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param state State an unapplied batch has, always [InboxBatchState.PENDING].
     * @return Batch to apply, or `null` when the inbox is empty.
     */
    @Query(
        "SELECT * FROM sync_inbox_batch WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND state = :state ORDER BY received_ord LIMIT 1",
    )
    public suspend fun oldestPendingBatch(
        scopeId: String,
        collectionId: String,
        state: InboxBatchState,
    ): SyncInboxBatchEntity?

    /**
     * Returns the operations of one batch, in the order the server committed them.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param seq Sequence of the batch.
     * @return Operations of the batch, ordered by their position inside it.
     */
    @Query(
        "SELECT * FROM sync_inbox_op WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seq = :seq ORDER BY ordinal",
    )
    public suspend fun opsOf(
        scopeId: String,
        collectionId: String,
        seq: String,
    ): List<SyncInboxOpEntity>

    /**
     * Removes an applied batch together with its operations.
     *
     * Called in the transaction that applied it, so that a crash cannot leave a batch that would be
     * applied twice — which would be harmless for the data, since operations carry full states, but
     * would move the cursor twice.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param seq Sequence of the batch.
     */
    @Query(
        "DELETE FROM sync_inbox_batch WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seq = :seq",
    )
    public suspend fun deleteBatch(
        scopeId: String,
        collectionId: String,
        seq: String,
    )

    /**
     * Removes the operations of a batch.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param seq Sequence of the batch.
     */
    @Query(
        "DELETE FROM sync_inbox_op WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seq = :seq",
    )
    public suspend fun deleteOps(
        scopeId: String,
        collectionId: String,
        seq: String,
    )

    /**
     * Empties the inbox of a collection, used before a bootstrap.
     *
     * Anything downloaded before a resynchronisation is meaningless: the snapshot replaces it.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     */
    @Query("DELETE FROM sync_inbox_batch WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun deleteBatches(
        scopeId: String,
        collectionId: String,
    )

    /**
     * Empties the stored operations of a collection, used before a bootstrap.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     */
    @Query("DELETE FROM sync_inbox_op WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun deleteAllOps(
        scopeId: String,
        collectionId: String,
    )
}
