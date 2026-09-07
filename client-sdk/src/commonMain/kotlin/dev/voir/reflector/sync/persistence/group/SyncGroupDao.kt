package dev.voir.reflector.sync.persistence.group

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/** Access to the push queue of collections. */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncGroupDao {
    /**
     * Adds a new group to the queue.
     *
     * @param group Group to add.
     */
    @Insert
    public suspend fun insert(group: SyncGroupEntity)

    /**
     * Reads one group.
     *
     * @param groupId Group to read.
     * @return Stored group, or `null` when it has already left the queue.
     */
    @Query("SELECT * FROM sync_group WHERE group_id = :groupId")
    public suspend fun find(groupId: Uuid): SyncGroupEntity?

    /**
     * Returns the ordinal a new group of a collection has to take.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return One past the largest ordinal in use, starting at one for an empty queue.
     */
    @Query(
        "SELECT COALESCE(MAX(ord), 0) + 1 FROM sync_group " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun nextOrdinal(
        scopeId: String,
        collectionId: String,
    ): Long

    /**
     * Returns the group at the head of the collection's queue, whatever state it is in.
     *
     * The state is deliberately not part of the predicate. Groups do not overtake each other: two
     * of them never share an entity, but they can depend on each other in ways the library cannot
     * see — a wallet created in one and a transaction referring to it in the next — and ordering is
     * what covers that, at the price of a stuck group blocking the ones behind it. Selecting the
     * oldest *pending* group would collect that price and hand back the group behind the stuck one
     * anyway, which is the one case ordering exists to prevent.
     *
     * Whether the head can be sent right now — its state, its backoff — is the caller's decision,
     * because the answers differ: one is waited for, one is resent, one needs the application.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Group at the head of the queue, or `null` when the queue is empty.
     */
    @Query(
        "SELECT * FROM sync_group WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "ORDER BY ord LIMIT 1",
    )
    public suspend fun head(
        scopeId: String,
        collectionId: String,
    ): SyncGroupEntity?

    /**
     * Returns the next pending group after a given ordinal.
     *
     * Used when the server refuses a group as depending on another one: the two are merged and sent
     * again as one.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param state State to look for, always [PushGroupState.PENDING].
     * @param ord Ordinal to search after.
     * @return Next pending group, or `null` when the refused group is the last one.
     */
    @Query(
        "SELECT * FROM sync_group WHERE scope_id = :scopeId AND collection_id = :collectionId " +
            "AND state = :state AND ord > :ord ORDER BY ord LIMIT 1",
    )
    public suspend fun nextPendingAfter(
        scopeId: String,
        collectionId: String,
        state: PushGroupState,
        ord: Long,
    ): SyncGroupEntity?

    /**
     * Changes the state of a group.
     *
     * @param groupId Group to change.
     * @param state State to move to.
     */
    @Query("UPDATE sync_group SET state = :state WHERE group_id = :groupId")
    public suspend fun updateState(
        groupId: Uuid,
        state: PushGroupState,
    )

    /**
     * Records a failed attempt and the moment the group may be retried.
     *
     * @param groupId Group that failed.
     * @param state State to move to.
     * @param nextRetryAt Local time before which the group must not be sent, in epoch milliseconds,
     *   or `null` when it must not be retried automatically at all.
     * @param error Description of the failure.
     */
    @Query(
        "UPDATE sync_group SET state = :state, attempts = attempts + 1, " +
            "next_retry_at = :nextRetryAt, last_error = :error WHERE group_id = :groupId",
    )
    public suspend fun recordAttempt(
        groupId: Uuid,
        state: PushGroupState,
        nextRetryAt: Long?,
        error: String?,
    )

    /**
     * Takes the ordinal and the merge counter of an absorbed group into the surviving one.
     *
     * The merged group keeps the smaller ordinal, so merging never moves changes backwards past a
     * group that was queued earlier.
     *
     * @param groupId Group that survives.
     * @param ord Ordinal to take.
     */
    @Query("UPDATE sync_group SET ord = :ord WHERE group_id = :groupId")
    public suspend fun updateOrdinal(
        groupId: Uuid,
        ord: Long,
    )

    /**
     * Counts one more merge caused by a dependency refusal.
     *
     * @param groupId Group that was merged.
     */
    @Query("UPDATE sync_group SET dependency_merges = dependency_merges + 1 WHERE group_id = :groupId")
    public suspend fun incrementDependencyMerges(groupId: Uuid)

    /**
     * Sets the merge counter of a group.
     *
     * Needed because a merged group is a **new** group: the counter has to be carried over to it,
     * otherwise the ceiling on dependency merges would reset on every merge and never be reached.
     *
     * @param groupId Group to change.
     * @param count Number of dependency merges the group has behind it.
     */
    @Query("UPDATE sync_group SET dependency_merges = :count WHERE group_id = :groupId")
    public suspend fun setDependencyMerges(
        groupId: Uuid,
        count: Int,
    )

    /**
     * Removes a group that has left the queue.
     *
     * @param groupId Group to remove.
     */
    @Query("DELETE FROM sync_group WHERE group_id = :groupId")
    public suspend fun delete(groupId: Uuid)

    /**
     * Removes every group of a scope, used when the scope's data is wiped.
     *
     * @param scopeId Scope to wipe.
     */
    @Query("DELETE FROM sync_group WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)
}
