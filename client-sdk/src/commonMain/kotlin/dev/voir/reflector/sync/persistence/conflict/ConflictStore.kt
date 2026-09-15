package dev.voir.reflector.sync.persistence.conflict

import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Typed access to conflicts waiting for a decision.
 *
 * Payloads are stored as JSON text and parsed here. Keeping them as text in the database rather
 * than as structured columns is deliberate: the library must not know the shape of the business
 * data it is holding on behalf of the application.
 *
 * @property dao Generated data access object of the conflict table.
 */
internal class ConflictStore(
    private val dao: SyncConflictDao,
) {
    /**
     * Opens a disagreement about an entity, or brings the one already open up to date.
     *
     * An entity has at most one open conflict, and the schema says so: a record holds a single
     * `conflict_id`. The same disagreement is nevertheless reachable twice — a push refused by the
     * server, and then the change that refused it arriving through the log over the same still-dirty
     * record — and inserting a second row would leave the first with nothing pointing at it,
     * counted forever and impossible to answer.
     *
     * What is refreshed is what has moved: both states and the version the decision will be applied
     * on top of. The origin and the moment of detection stay as they were, because the disagreement
     * is the same one and started when it started — which is also what keeps the order conflicts are
     * offered in stable.
     *
     * Whether the refresh found anything decides the rest. Today it always does: resolving a
     * conflict clears the record's pointer in the same transaction that deletes the row, and wiping
     * a scope removes both. Should the two ever come apart, a conflict is opened rather than
     * silently dropped — the entity does disagree, and it has to be answerable.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param existing Conflict the record already holds, or `null` when it holds none.
     * @param conflictId Identifier to give a conflict that turns out to be new.
     * @param entityType Type of the conflicting entity.
     * @param entityId Identifier of the conflicting entity.
     * @param origin Where the conflict was detected.
     * @param local Local state at detection time, or `null` when the entity was deleted locally.
     * @param server Server state at detection time, or `null` when the server deleted it.
     * @param serverVersion Version the conflict is detected against.
     * @param detectedAt Local timestamp of detection, in epoch milliseconds.
     * @return Conflict now open for the entity, which is [existing] when there already was one.
     */
    @Suppress("LongParameterList")
    public suspend fun open(
        scope: ScopeId,
        collection: CollectionId,
        existing: ConflictId?,
        conflictId: ConflictId,
        entityType: EntityType,
        entityId: EntityId,
        origin: ConflictOrigin,
        local: JsonObject?,
        server: JsonObject?,
        serverVersion: EntityVersion,
        detectedAt: Long,
    ): ConflictId {
        if (existing != null &&
            dao.refresh(existing.value, local?.toString(), server?.toString(), serverVersion.value) > 0
        ) {
            return existing
        }
        dao.insert(
            SyncConflictEntity(
                conflictId = conflictId.value,
                scopeId = scope.value,
                collectionId = collection.value,
                entityType = entityType.value,
                entityId = entityId.value,
                origin = origin,
                localPayload = local?.toString(),
                serverPayload = server?.toString(),
                serverVersion = serverVersion.value,
                detectedAt = detectedAt,
            ),
        )
        return conflictId
    }

    /**
     * Reads one conflict.
     *
     * @param conflictId Conflict to read.
     * @return Stored conflict, or `null` when it has already been resolved.
     */
    public suspend fun find(conflictId: ConflictId): StoredConflict? = dao.find(conflictId.value)?.toStored()

    /**
     * Returns the identifiers of the open conflicts of a collection, oldest first.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Identifiers of the open conflicts.
     */
    public suspend fun openIds(
        scope: ScopeId,
        collection: CollectionId,
    ): List<ConflictId> = dao.openIds(scope.value, collection.value).map(::ConflictId)

    /**
     * Observes the open conflicts of a collection.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Flow emitting the current conflicts and every change to them.
     */
    public fun observe(
        scope: ScopeId,
        collection: CollectionId,
    ): Flow<List<StoredConflict>> =
        dao.observe(scope.value, collection.value).map { conflicts -> conflicts.map { it.toStored() } }

    /**
     * Counts the open conflicts of a collection.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Flow emitting the current number of conflicts and every change to it.
     */
    public fun observeCount(
        scope: ScopeId,
        collection: CollectionId,
    ): Flow<Int> = dao.observeCount(scope.value, collection.value)

    /**
     * Removes a resolved conflict.
     *
     * @param conflictId Conflict to remove.
     */
    public suspend fun delete(conflictId: ConflictId) {
        dao.delete(conflictId.value)
    }

    /**
     * Removes every conflict of one collection.
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
     * Removes every conflict of a scope.
     *
     * @param scope Scope to wipe.
     */
    public suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    private fun SyncConflictEntity.toStored(): StoredConflict =
        StoredConflict(
            conflictId = ConflictId(conflictId),
            entityType = EntityType(entityType),
            entityId = EntityId(entityId),
            origin = origin,
            local = localPayload?.toJsonObject(),
            server = serverPayload?.toJsonObject(),
            serverVersion = EntityVersion(serverVersion),
        )

    private fun String.toJsonObject(): JsonObject = SyncProtocolJson.format.parseToJsonElement(this).jsonObject
}
