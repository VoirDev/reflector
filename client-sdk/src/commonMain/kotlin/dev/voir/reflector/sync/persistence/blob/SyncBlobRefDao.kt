package dev.voir.reflector.sync.persistence.blob

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * Access to what the application's documents point at.
 *
 * Every write here replaces one entity's references whole, which is what keeps them state-based
 * like the rest of the protocol: the application answers with the set a document names now, and
 * that answer is the truth rather than a delta against some earlier one.
 */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncBlobRefDao {
    /**
     * Inserts or replaces one reference.
     *
     * @param reference Reference to store.
     */
    @Upsert
    public suspend fun upsert(reference: SyncBlobRefEntity)

    /**
     * Reads every file one entity's document points at.
     *
     * @param scopeId Scope of the entity.
     * @param collectionId Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return References the document holds.
     */
    @Query(
        "SELECT * FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun of(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    ): List<SyncBlobRefEntity>

    /**
     * Whether any document of the collection still points at a file with a binding that holds a
     * push back.
     *
     * Read by the push before it builds an envelope. A deferred reference never holds a group, so
     * asking about the binding here rather than about the reference is the whole of what the
     * default means in the queue.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @param entityType Type of the entity being pushed.
     * @param entityId Identifier of the entity being pushed.
     * @return Files the entity cannot be published without.
     */
    @Query(
        "SELECT * FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId AND binding = 'REQUIRED'",
    )
    public suspend fun required(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    ): List<SyncBlobRefEntity>

    /**
     * Counts the documents of a collection that point at one file.
     *
     * Asked before a file is fetched on the application's request. The library will not bring down
     * bytes nothing references: it would have nothing to keep them for, and the next reconciliation
     * would offer them straight back.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @param blobId File to count references to.
     * @return How many of the collection's documents name the file.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND blob_id = :blobId",
    )
    public suspend fun referenceCount(
        scopeId: String,
        collectionId: String,
        blobId: Uuid,
    ): Int

    /**
     * Reads every file a document points at that the library knows nothing else about.
     *
     * The work list of reconciliation. A reference is written inside the transaction that applied
     * the document, where the application cannot be asked whether the bytes are on this device —
     * that question reaches the application's own store and must not be asked with a write lock
     * held. So the reference lands first and the file it names is looked into afterwards, and this
     * is how the second step finds the first one's leftovers.
     *
     * @param scopeId Scope to read.
     * @param collectionId Collection to read.
     * @return Files referenced by some document and unknown to the blob table.
     */
    @Query(
        "SELECT DISTINCT r.blob_id FROM sync_blob_ref r WHERE r.scope_id = :scopeId " +
            "AND r.collection_id = :collectionId AND NOT EXISTS (SELECT 1 FROM sync_blob b " +
            "WHERE b.scope_id = r.scope_id AND b.collection_id = r.collection_id AND b.blob_id = r.blob_id)",
    )
    public suspend fun unknown(
        scopeId: String,
        collectionId: String,
    ): List<Uuid>

    /**
     * Removes every reference one entity's document held.
     *
     * @param scopeId Scope of the entity.
     * @param collectionId Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     */
    @Query(
        "DELETE FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun clear(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    )

    /**
     * Removes references a bootstrap did not confirm.
     *
     * The sweep, for files: a document the snapshot no longer mentions takes its references with
     * it, exactly as it takes its record.
     *
     * @param scopeId Scope to sweep.
     * @param collectionId Collection to sweep.
     * @param generation Generation the bootstrap wrote.
     */
    @Query(
        "DELETE FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seen_gen < :generation",
    )
    public suspend fun sweep(
        scopeId: String,
        collectionId: String,
        generation: Long,
    )

    /**
     * Removes every reference of a scope.
     *
     * @param scopeId Scope to clear.
     */
    @Query("DELETE FROM sync_blob_ref WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)

    /**
     * Removes every reference of a collection.
     *
     * @param scopeId Scope to clear.
     * @param collectionId Collection to clear.
     */
    @Query("DELETE FROM sync_blob_ref WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun deleteAll(
        scopeId: String,
        collectionId: String,
    )
}
