package dev.voir.reflector.sync.persistence.record

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Access to per-entity synchronisation metadata.
 *
 * Every statement here is meant to run inside the library's transaction together with whatever it
 * describes: marking a record dirty belongs with the application's own write, acknowledging a push
 * belongs with clearing its group, and applying a batch belongs with advancing the cursor.
 */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncRecordDao {
    /**
     * Reads the metadata of one entity.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Stored metadata, or `null` when the library has never seen the entity.
     */
    @Query(
        "SELECT * FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun find(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    ): SyncRecordEntity?

    /**
     * Inserts or replaces the metadata of one entity.
     *
     * @param record Metadata to store.
     */
    @Upsert
    public suspend fun upsert(record: SyncRecordEntity)

    /**
     * Records a local mutation of an entity that is already known.
     *
     * The revision is incremented rather than set, which is what keeps an edit made while a push is
     * in flight from being acknowledged by that push.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param intent Operation the mutation implies.
     * @param groupId Group the record now belongs to.
     * @return Number of updated rows: zero means the entity is new and has to be inserted.
     */
    @Query(
        "UPDATE sync_record SET local_rev = local_rev + 1, intent = :intent, group_id = :groupId " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun markMutated(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        intent: MutationIntent,
        groupId: Uuid,
    ): Int

    /**
     * Returns the pending group an entity already belongs to.
     *
     * Used when a new mutation has to be merged with the groups of the entities it touches.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Group of the record, or `null` when it is clean or unknown.
     */
    @Query(
        "SELECT group_id FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun groupOf(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    ): Uuid?

    /**
     * Moves every record of one group into another, which is how two groups are merged.
     *
     * @param source Group being absorbed.
     * @param target Group that survives.
     */
    @Query("UPDATE sync_record SET group_id = :target WHERE group_id = :source")
    public suspend fun reassignGroup(
        source: Uuid,
        target: Uuid,
    )

    /**
     * Returns the dirty records of a group, in a deterministic order.
     *
     * @param groupId Group to read.
     * @return Records of the group, ordered by type and identifier so that two envelopes built from
     *   the same data are byte-identical, which matters for idempotent retries.
     */
    @Query("SELECT * FROM sync_record WHERE group_id = :groupId ORDER BY entity_type, entity_id")
    public suspend fun ofGroup(groupId: Uuid): List<SyncRecordEntity>

    /**
     * Captures the revision every record of a group is being pushed at.
     *
     * @param groupId Group whose envelope is being assembled.
     */
    @Query("UPDATE sync_record SET pushing_rev = local_rev WHERE group_id = :groupId")
    public suspend fun capturePushingRevisions(groupId: Uuid)

    /**
     * Acknowledges one entity of an applied group.
     *
     * Only the revision that was actually sent is acknowledged: anything edited while the push was
     * in flight stays dirty and joins the next group.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version the server assigned.
     */
    @Query(
        "UPDATE sync_record SET server_version = :serverVersion, acked_rev = pushing_rev " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun acknowledge(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        serverVersion: String?,
    )

    /**
     * Releases the records of a group that came out of it clean.
     *
     * Records edited during the push stay dirty and keep a group, so they are left alone here.
     *
     * @param groupId Group being retired.
     */
    @Query(
        "UPDATE sync_record SET group_id = NULL, intent = NULL " +
            "WHERE group_id = :groupId AND local_rev <= acked_rev",
    )
    public suspend fun releaseCleanRecords(groupId: Uuid)

    /**
     * Stores the server version of a record without touching the application's tables.
     *
     * Used to suppress the echo of the client's own push: the change is already in the local rows,
     * only the version has to catch up.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version seen in the change log.
     * @param seenGen Current bootstrap generation of the collection.
     * @return Number of updated rows: zero means the entity is unknown and has to be inserted.
     */
    @Query(
        "UPDATE sync_record SET server_version = :serverVersion, seen_gen = :seenGen " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun updateServerVersion(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        serverVersion: String?,
        seenGen: Long,
    ): Int

    /**
     * Attaches or detaches an open conflict.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param conflictId Conflict to attach, or `null` to detach the current one.
     */
    @Query(
        "UPDATE sync_record SET conflict_id = :conflictId " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun setConflict(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        conflictId: Uuid?,
    )

    /**
     * Applies a decision that keeps the local state: the record stays dirty on top of the server's
     * version.
     *
     * The revision is incremented so that the record is pushed again even though nothing in the
     * application's rows may have changed — for the server it is a new write, based on the version
     * the conflict was detected against.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version the conflict was detected against.
     * @param intent Operation the kept local state implies.
     */
    @Query(
        "UPDATE sync_record SET server_version = :serverVersion, local_rev = local_rev + 1, " +
            "intent = :intent, conflict_id = NULL " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun keepLocal(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        serverVersion: String?,
        intent: MutationIntent,
    )

    /**
     * Applies a decision that drops the local change in favour of the server's state.
     *
     * The record becomes clean and leaves its group. The user's unsent change is gone by
     * construction, which is why this decision belongs to the application and not to the library.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version that was taken.
     */
    @Query(
        "UPDATE sync_record SET server_version = :serverVersion, acked_rev = local_rev, " +
            "intent = NULL, group_id = NULL, conflict_id = NULL " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun takeServer(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
        serverVersion: String?,
    )

    /**
     * Counts the records of a group that still hold an unresolved conflict.
     *
     * A group is atomic, so it may leave only when none of its records waits for a decision.
     *
     * @param groupId Group to inspect.
     * @return Number of records with an open conflict.
     */
    @Query("SELECT COUNT(*) FROM sync_record WHERE group_id = :groupId AND conflict_id IS NOT NULL")
    public suspend fun openConflictsOfGroup(groupId: Uuid): Int

    /**
     * Counts the local changes of a collection that have not reached the server.
     *
     * @param scopeId Scope to count in.
     * @param collectionId Collection to count in.
     * @return Number of dirty records.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND local_rev > acked_rev",
    )
    public suspend fun pendingCount(
        scopeId: String,
        collectionId: String,
    ): Int

    /**
     * Counts the local changes that have not reached the server yet.
     *
     * @param scopeId Scope to count in.
     * @param collectionId Collection to count in.
     * @return Flow emitting the current number of dirty records and every change to it.
     */
    @Query(
        "SELECT COUNT(*) FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND local_rev > acked_rev",
    )
    public fun observePendingCount(
        scopeId: String,
        collectionId: String,
    ): Flow<Int>

    /**
     * Returns the records a finished bootstrap did not confirm and that hold no local change.
     *
     * These are entities that were deleted on the server while the client could not follow the log.
     * Dirty records are excluded on purpose: they survive a resynchronisation and leave by push.
     *
     * @param scopeId Scope to sweep.
     * @param collectionId Collection to sweep.
     * @param generation Generation of the bootstrap that has just finished.
     * @return Records to remove, so that the application can be told which rows to delete.
     */
    @Query(
        "SELECT * FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seen_gen < :generation AND local_rev <= acked_rev",
    )
    public suspend fun staleRecords(
        scopeId: String,
        collectionId: String,
        generation: Long,
    ): List<SyncRecordEntity>

    /**
     * Removes the records a finished bootstrap did not confirm.
     *
     * Must run in the same transaction as the deletion of the corresponding application rows.
     *
     * @param scopeId Scope to sweep.
     * @param collectionId Collection to sweep.
     * @param generation Generation of the bootstrap that has just finished.
     */
    @Query(
        "DELETE FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND seen_gen < :generation AND local_rev <= acked_rev",
    )
    public suspend fun deleteStale(
        scopeId: String,
        collectionId: String,
        generation: Long,
    )

    /**
     * Removes one record, used when a deletion has been confirmed by the server.
     *
     * @param scopeId Scope of the record.
     * @param collectionId Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     */
    @Query(
        "DELETE FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    public suspend fun delete(
        scopeId: String,
        collectionId: String,
        entityType: String,
        entityId: Uuid,
    )

    /**
     * Drops every local change of a collection, leaving the records clean.
     *
     * Used when the server's collection turns out to be a different one from the one these records
     * describe. The rows are not deleted here: they still stand for the application's own rows, and
     * deleting them would orphan those. Marking them clean hands them to the bootstrap's sweep,
     * which removes both together — or, for the ones the new snapshot does mention, overwrites them.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     */
    @Query(
        "UPDATE sync_record SET local_rev = acked_rev, pushing_rev = acked_rev, server_version = NULL, " +
            "intent = NULL, group_id = NULL, conflict_id = NULL " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun discardLocalChanges(
        scopeId: String,
        collectionId: String,
    )

    /**
     * Returns the dirty records the server has never confirmed.
     *
     * What a discard can undo without asking the server: an entity with no confirmed version was
     * created on this device and never acknowledged, so the server's state of it is "absent", and
     * that is known without a snapshot. A creation whose acknowledgement was lost is among them too;
     * the snapshot that follows brings it back, because then the server does have it.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Records whose entities exist only on this device.
     */
    @Query(
        "SELECT * FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND local_rev > acked_rev AND server_version IS NULL",
    )
    public suspend fun unconfirmed(
        scopeId: String,
        collectionId: String,
    ): List<SyncRecordEntity>

    /**
     * Removes the dirty records the server has never confirmed.
     *
     * Must run in the same transaction as the deletion of the corresponding application rows.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     */
    @Query(
        "DELETE FROM sync_record WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND local_rev > acked_rev AND server_version IS NULL",
    )
    public suspend fun deleteUnconfirmed(
        scopeId: String,
        collectionId: String,
    )

    /**
     * Drops every local change of a collection the server still holds, because the application
     * asked to.
     *
     * Unlike [discardLocalChanges], the collection is the same one, so a clean record keeps the
     * version it was confirmed at. Only a record that held a change loses it: its row still shows
     * the discarded change until the snapshot overwrites it, and an edit made on top of that row in
     * the meantime has to come back as a conflict rather than be pushed as if it were based on the
     * server's state.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     */
    @Query(
        "UPDATE sync_record SET server_version = CASE WHEN local_rev > acked_rev THEN NULL " +
            "ELSE server_version END, local_rev = acked_rev, pushing_rev = acked_rev, intent = NULL, " +
            "group_id = NULL, conflict_id = NULL " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun abandonLocalChanges(
        scopeId: String,
        collectionId: String,
    )

    /**
     * Removes every record of a scope, used when the scope's data is wiped.
     *
     * @param scopeId Scope to wipe.
     */
    @Query("DELETE FROM sync_record WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)
}
