package dev.voir.reflector.sync.persistence.record

import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.flow.Flow

/**
 * Typed access to per-entity synchronisation metadata.
 *
 * @property dao Generated data access object of the record table.
 */
internal class RecordStore(
    private val dao: SyncRecordDao,
) {
    /**
     * Reads the metadata of one entity.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Stored metadata, or `null` when the entity is unknown to the library.
     */
    public suspend fun find(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ): RecordState? = dao.find(scope.value, collection.value, entityType.value, entityId.value)?.toState()

    /**
     * Records a local mutation, creating the record when the entity is new.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param intent Operation the mutation implies.
     * @param groupId Group the record now belongs to.
     * @param generation Current bootstrap generation, carried by the new record so that a sweep
     *   running later does not mistake it for something the snapshot failed to confirm.
     */
    public suspend fun markMutation(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        intent: MutationIntent,
        groupId: GroupId,
        generation: Long,
    ) {
        val updated =
            dao.markMutated(
                scopeId = scope.value,
                collectionId = collection.value,
                entityType = entityType.value,
                entityId = entityId.value,
                intent = intent,
                groupId = groupId.value,
            )
        if (updated == 0) {
            dao.upsert(
                SyncRecordEntity(
                    scopeId = scope.value,
                    collectionId = collection.value,
                    entityType = entityType.value,
                    entityId = entityId.value,
                    serverVersion = null,
                    localRev = 1,
                    pushingRev = 0,
                    ackedRev = 0,
                    intent = intent,
                    groupId = groupId.value,
                    conflictId = null,
                    seenGen = generation,
                ),
            )
        }
    }

    /**
     * Returns the group an entity already belongs to.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Group of the record, or `null` when it is clean or unknown.
     */
    public suspend fun groupOf(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ): GroupId? = dao.groupOf(scope.value, collection.value, entityType.value, entityId.value)?.let(::GroupId)

    /**
     * Moves every record of one group into another.
     *
     * @param source Group being absorbed.
     * @param target Group that survives.
     */
    public suspend fun reassignGroup(
        source: GroupId,
        target: GroupId,
    ) {
        dao.reassignGroup(source.value, target.value)
    }

    /**
     * Returns the records of a group in a deterministic order.
     *
     * @param groupId Group to read.
     * @return Records of the group, ordered so that rebuilding the envelope produces the same bytes.
     */
    public suspend fun ofGroup(groupId: GroupId): List<RecordState> = dao.ofGroup(groupId.value).map { it.toState() }

    /**
     * Captures the revision every record of a group is being pushed at.
     *
     * @param groupId Group whose envelope is being assembled.
     */
    public suspend fun capturePushingRevisions(groupId: GroupId) {
        dao.capturePushingRevisions(groupId.value)
    }

    /**
     * Acknowledges one entity of an applied group.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version the server assigned.
     */
    public suspend fun acknowledge(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        serverVersion: EntityVersion?,
    ) {
        dao.acknowledge(scope.value, collection.value, entityType.value, entityId.value, serverVersion?.value)
    }

    /**
     * Releases the records of a group that came out of it clean.
     *
     * @param groupId Group being retired.
     */
    public suspend fun releaseCleanRecords(groupId: GroupId) {
        dao.releaseCleanRecords(groupId.value)
    }

    /**
     * Stores the server version of an entity, creating the record when it is new.
     *
     * Used both for suppressing the echo of this client's own push and for remembering what a
     * remote change left behind.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version seen in the change log.
     * @param generation Current bootstrap generation.
     */
    public suspend fun rememberServerVersion(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        serverVersion: EntityVersion?,
        generation: Long,
    ) {
        val updated =
            dao.updateServerVersion(
                scopeId = scope.value,
                collectionId = collection.value,
                entityType = entityType.value,
                entityId = entityId.value,
                serverVersion = serverVersion?.value,
                seenGen = generation,
            )
        if (updated == 0) {
            dao.upsert(
                SyncRecordEntity(
                    scopeId = scope.value,
                    collectionId = collection.value,
                    entityType = entityType.value,
                    entityId = entityId.value,
                    serverVersion = serverVersion?.value,
                    localRev = 0,
                    pushingRev = 0,
                    ackedRev = 0,
                    intent = null,
                    groupId = null,
                    conflictId = null,
                    seenGen = generation,
                ),
            )
        }
    }

    /**
     * Attaches or detaches an open conflict.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param conflictId Conflict to attach, or `null` to detach the current one.
     */
    public suspend fun setConflict(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        conflictId: ConflictId?,
    ) {
        dao.setConflict(scope.value, collection.value, entityType.value, entityId.value, conflictId?.value)
    }

    /**
     * Keeps the local state of a record on top of the server's version.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version the conflict was detected against.
     * @param intent Operation the kept state implies.
     */
    public suspend fun keepLocal(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        serverVersion: EntityVersion?,
        intent: MutationIntent,
    ) {
        dao.keepLocal(scope.value, collection.value, entityType.value, entityId.value, serverVersion?.value, intent)
    }

    /**
     * Drops the local change of a record in favour of the server's state.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param serverVersion Version that was taken.
     */
    public suspend fun takeServer(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        serverVersion: EntityVersion?,
    ) {
        dao.takeServer(scope.value, collection.value, entityType.value, entityId.value, serverVersion?.value)
    }

    /**
     * Counts the records of a group that still wait for a decision.
     *
     * @param groupId Group to inspect.
     * @return Number of records with an open conflict.
     */
    public suspend fun openConflictsOfGroup(groupId: GroupId): Int = dao.openConflictsOfGroup(groupId.value)

    /**
     * Counts the local changes of a collection that have not reached the server.
     *
     * @param scope Scope to count in.
     * @param collection Collection to count in.
     * @return Number of dirty records.
     */
    public suspend fun pendingCount(
        scope: ScopeId,
        collection: CollectionId,
    ): Int = dao.pendingCount(scope.value, collection.value)

    /**
     * Counts the local changes that have not reached the server.
     *
     * @param scope Scope to count in.
     * @param collection Collection to count in.
     * @return Flow emitting the current number of dirty records and every change to it.
     */
    public fun observePendingCount(
        scope: ScopeId,
        collection: CollectionId,
    ): Flow<Int> = dao.observePendingCount(scope.value, collection.value)

    /**
     * Returns the clean records a finished bootstrap did not confirm.
     *
     * @param scope Scope to sweep.
     * @param collection Collection to sweep.
     * @param generation Generation of the bootstrap that has just finished.
     * @return Records to remove, so that the application can be told which rows to delete.
     */
    public suspend fun staleRecords(
        scope: ScopeId,
        collection: CollectionId,
        generation: Long,
    ): List<RecordState> = dao.staleRecords(scope.value, collection.value, generation).map { it.toState() }

    /**
     * Removes the clean records a finished bootstrap did not confirm.
     *
     * @param scope Scope to sweep.
     * @param collection Collection to sweep.
     * @param generation Generation of the bootstrap that has just finished.
     */
    public suspend fun deleteStale(
        scope: ScopeId,
        collection: CollectionId,
        generation: Long,
    ) {
        dao.deleteStale(scope.value, collection.value, generation)
    }

    /**
     * Drops every local change of a collection, leaving its records clean.
     *
     * @param scope Scope of the collection.
     * @param collection Collection whose local changes are abandoned.
     */
    public suspend fun discardLocalChanges(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.discardLocalChanges(scope.value, collection.value)
    }

    /**
     * Removes the metadata of one entity.
     *
     * @param scope Scope of the record.
     * @param collection Collection of the record.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     */
    public suspend fun delete(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ) {
        dao.delete(scope.value, collection.value, entityType.value, entityId.value)
    }

    /**
     * Removes every record of a scope.
     *
     * @param scope Scope to wipe.
     */
    public suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    private fun SyncRecordEntity.toState(): RecordState =
        RecordState(
            entityType = EntityType(entityType),
            entityId = EntityId(entityId),
            serverVersion = serverVersion?.let(::EntityVersion),
            localRev = localRev,
            pushingRev = pushingRev,
            ackedRev = ackedRev,
            intent = intent,
            groupId = groupId?.let(::GroupId),
            conflictId = conflictId?.let(::ConflictId),
        )
}
