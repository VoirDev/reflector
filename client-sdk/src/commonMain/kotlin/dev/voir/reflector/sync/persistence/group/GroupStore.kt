package dev.voir.reflector.sync.persistence.group

import dev.voir.reflector.sync.core.RefusedGroup
import dev.voir.reflector.sync.engine.push.rejectionOf
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.push.RejectCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Typed access to the push queue.
 *
 * @property dao Generated data access object of the group table.
 */
internal class GroupStore(
    private val dao: SyncGroupDao,
) {
    /**
     * Creates a new group at the end of the queue.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param groupId Identifier to give the group.
     * @return Ordinal the group took.
     */
    public suspend fun create(
        scope: ScopeId,
        collection: CollectionId,
        groupId: GroupId,
    ): Long {
        val ord = dao.nextOrdinal(scope.value, collection.value)
        dao.insert(
            SyncGroupEntity(
                groupId = groupId.value,
                scopeId = scope.value,
                collectionId = collection.value,
                ord = ord,
                state = PushGroupState.PENDING,
                attempts = 0,
                nextRetryAt = null,
                dependencyMerges = 0,
                lastError = null,
            ),
        )
        return ord
    }

    /**
     * Reads one group.
     *
     * @param groupId Group to read.
     * @return Stored group, or `null` when it has already left the queue.
     */
    public suspend fun find(groupId: GroupId): PendingGroup? = dao.find(groupId.value)?.toPendingGroup()

    /**
     * Returns the group at the head of the queue, whatever state it is in.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Group with the smallest ordinal, or `null` when the queue is empty.
     */
    public suspend fun head(
        scope: ScopeId,
        collection: CollectionId,
    ): PendingGroup? = dao.head(scope.value, collection.value)?.toPendingGroup()

    /**
     * Returns the whole queue of a collection, oldest first.
     *
     * For diagnostics: the engine itself only ever looks at the head.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Every queued group in the order they would leave in.
     */
    public suspend fun queue(
        scope: ScopeId,
        collection: CollectionId,
    ): List<PendingGroup> = dao.ofCollection(scope.value, collection.value).map { it.toPendingGroup() }

    /**
     * Returns the next pending group after a given position in the queue.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param ord Position to search after.
     * @return Next pending group, or `null` when there is none.
     */
    public suspend fun nextPendingAfter(
        scope: ScopeId,
        collection: CollectionId,
        ord: Long,
    ): PendingGroup? =
        dao.nextPendingAfter(scope.value, collection.value, PushGroupState.PENDING, ord)?.toPendingGroup()

    /**
     * Changes the state of a group.
     *
     * @param groupId Group to change.
     * @param state State to move to.
     */
    public suspend fun setState(
        groupId: GroupId,
        state: PushGroupState,
    ) {
        dao.updateState(groupId.value, state)
    }

    /**
     * Records a failed attempt and when the group may be retried.
     *
     * @param groupId Group that failed.
     * @param state State to move to.
     * @param nextRetryAt Local time before which the group must not be sent, in epoch milliseconds,
     *   or `null` when it must not be retried automatically.
     * @param error Description of the failure.
     */
    public suspend fun recordAttempt(
        groupId: GroupId,
        state: PushGroupState,
        nextRetryAt: Long?,
        error: String?,
    ) {
        dao.recordAttempt(groupId.value, state, nextRetryAt, error)
    }

    /**
     * Fails a group for good and records the refusal that failed it.
     *
     * @param groupId Group that was refused.
     * @param error Description of the refusal.
     * @param code Wire code of the refusal, or the one the library chose for a refusal of its own.
     * @param entityType Entity type the refusal named, or `null` when it named the group.
     * @param entityId Entity the refusal named, or `null` when it named the group.
     */
    public suspend fun recordRefusal(
        groupId: GroupId,
        error: String,
        code: RejectCode,
        entityType: EntityType?,
        entityId: EntityId?,
    ) {
        dao.recordRefusal(
            groupId = groupId.value,
            state = PushGroupState.FAILED,
            error = error,
            code = code.value,
            entityType = entityType?.value,
            entityId = entityId?.value,
        )
    }

    /**
     * Follows the groups of a collection that were refused for good.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return The refused groups in queue order, each with the refusal that failed it.
     */
    public fun observeRefused(
        scope: ScopeId,
        collection: CollectionId,
    ): Flow<List<RefusedGroup>> =
        dao.observeInState(scope.value, collection.value, PushGroupState.FAILED).map { groups ->
            groups.map { group ->
                RefusedGroup(
                    groupId = GroupId(group.groupId),
                    entityType = group.rejectEntityType?.let(::EntityType),
                    entityId = group.rejectEntityId?.let(::EntityId),
                    // A group failed before refusals were recorded carries no code. It is still
                    // refused, and "a code this build does not know" is the honest name for that.
                    rejection = rejectionOf(
                        code = RejectCode(group.rejectCode.orEmpty()),
                        message = group.lastError.orEmpty(),
                    ),
                )
            }
        }

    /**
     * Moves a group to another position in the queue.
     *
     * Used when merging: the surviving group takes the smaller ordinal of the two, so that merging
     * never moves changes behind a group that was queued before them.
     *
     * @param groupId Group to move.
     * @param ord Position to take.
     */
    public suspend fun setOrdinal(
        groupId: GroupId,
        ord: Long,
    ) {
        dao.updateOrdinal(groupId.value, ord)
    }

    /**
     * Sets how many dependency merges a group has behind it.
     *
     * @param groupId Group to change.
     * @param count Number of merges to record.
     */
    public suspend fun setDependencyMerges(
        groupId: GroupId,
        count: Int,
    ) {
        dao.setDependencyMerges(groupId.value, count)
    }

    /**
     * Removes a group that has left the queue.
     *
     * @param groupId Group to remove.
     */
    public suspend fun delete(groupId: GroupId) {
        dao.delete(groupId.value)
    }

    /**
     * Removes every group of one collection.
     *
     * @param scope Scope of the collection.
     * @param collection Collection to clear.
     */
    public suspend fun deleteCollection(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.deleteCollection(scope.value, collection.value)
    }

    /**
     * Removes every group of a scope.
     *
     * @param scope Scope to wipe.
     */
    public suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    private fun SyncGroupEntity.toPendingGroup(): PendingGroup =
        PendingGroup(
            groupId = GroupId(groupId),
            ord = ord,
            state = state,
            attempts = attempts,
            nextRetryAt = nextRetryAt,
            dependencyMerges = dependencyMerges,
            lastError = lastError,
        )
}
