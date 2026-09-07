package dev.voir.reflector.sync.persistence.conflict

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Access to conflicts waiting for a decision. */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncConflictDao {
    /**
     * Records a detected conflict.
     *
     * Written in the same transaction that detected it. On a pull that is not optional: the cursor
     * moves on regardless, so a conflict that is not durable at that moment is a lost change.
     *
     * @param conflict Conflict to record.
     */
    @Insert
    public suspend fun insert(conflict: SyncConflictEntity)

    /**
     * Brings an open conflict up to date with a newer view of the same disagreement.
     *
     * The server version is the part that has to move. A resolution is applied on top of the version
     * stored with the conflict, so one left behind would send the user's decision against a version
     * the server has already passed — and be refused as a conflict all over again.
     *
     * @param conflictId Conflict to update.
     * @param localPayload Local state as it is now, or `null` when the entity is deleted locally.
     * @param serverPayload Newer server state, or `null` when the server deleted the entity.
     * @param serverVersion Version the newer state carries.
     * @return Number of updated rows: zero means the conflict has been resolved in the meantime.
     */
    @Query(
        "UPDATE sync_conflict SET local_payload = :localPayload, server_payload = :serverPayload, " +
            "server_version = :serverVersion WHERE conflict_id = :conflictId",
    )
    public suspend fun refresh(
        conflictId: Uuid,
        localPayload: String?,
        serverPayload: String?,
        serverVersion: String,
    ): Int

    /**
     * Reads one conflict.
     *
     * @param conflictId Conflict to read.
     * @return Stored conflict, or `null` when it has already been resolved.
     */
    @Query("SELECT * FROM sync_conflict WHERE conflict_id = :conflictId")
    public suspend fun find(conflictId: Uuid): SyncConflictEntity?

    /**
     * Returns the identifiers of the open conflicts of a collection, oldest first.
     *
     * Used by the worker to offer conflicts to the application one at a time; the full rows are read
     * only for the ones it actually decides about.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Identifiers of the open conflicts.
     */
    @Query(
        "SELECT conflict_id FROM sync_conflict WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "ORDER BY detected_at",
    )
    public suspend fun openIds(
        scopeId: String,
        collectionId: String,
    ): List<Uuid>

    /**
     * Observes the open conflicts of a collection.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Flow emitting the current conflicts and every change to them, oldest first.
     */
    @Query(
        "SELECT * FROM sync_conflict WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "ORDER BY detected_at",
    )
    public fun observe(
        scopeId: String,
        collectionId: String,
    ): Flow<List<SyncConflictEntity>>

    /**
     * Counts the open conflicts of a collection.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Flow emitting the current number of conflicts and every change to it.
     */
    @Query("SELECT COUNT(*) FROM sync_conflict WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public fun observeCount(
        scopeId: String,
        collectionId: String,
    ): Flow<Int>

    /**
     * Removes a resolved conflict.
     *
     * @param conflictId Conflict to remove.
     */
    @Query("DELETE FROM sync_conflict WHERE conflict_id = :conflictId")
    public suspend fun delete(conflictId: Uuid)

    /**
     * Removes every conflict of a scope, used when the scope's data is wiped.
     *
     * @param scopeId Scope to wipe.
     */
    @Query("DELETE FROM sync_conflict WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)
}
