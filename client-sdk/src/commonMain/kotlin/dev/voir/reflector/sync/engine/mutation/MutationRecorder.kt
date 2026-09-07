package dev.voir.reflector.sync.engine.mutation

import dev.voir.reflector.sync.core.MutationScope
import dev.voir.reflector.sync.persistence.record.MutationIntent
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType

/**
 * Collects what the application marked inside one local transaction.
 *
 * Marks are kept per entity rather than as a list: an entity created and then edited in the same
 * transaction is one change to send, and its last intent is the only one that means anything, since
 * the body is materialised later from the current state anyway.
 */
internal class MutationRecorder : MutationScope {
    private val intents = LinkedHashMap<EntityKey, MutationIntent>()

    /** Entities marked in this transaction, in the order they were first touched. */
    val marks: Map<EntityKey, MutationIntent> get() = intents

    override fun markUpserted(
        entityType: EntityType,
        id: EntityId,
    ) {
        intents[EntityKey(entityType, id)] = MutationIntent.UPSERT
    }

    override fun markDeleted(
        entityType: EntityType,
        id: EntityId,
    ) {
        intents[EntityKey(entityType, id)] = MutationIntent.DELETE
    }
}

/**
 * Identity of an entity inside one collection.
 *
 * @property entityType Type of the entity.
 * @property entityId Identifier of the entity.
 */
internal data class EntityKey(
    val entityType: EntityType,
    val entityId: EntityId,
)
