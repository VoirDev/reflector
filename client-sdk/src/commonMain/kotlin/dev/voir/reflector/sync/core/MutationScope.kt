package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType

/**
 * Marking API available inside [CollectionHandle.mutate].
 *
 * Local changes are announced explicitly rather than discovered: the library does not own the
 * application's tables and cannot observe writes to them. Marking an entity records the intent and
 * the fact that it is dirty; the body of the change is materialised later, at push time, by asking
 * the adapter for the entity's current state.
 *
 * Marking without actually writing the row, or writing without marking, are both bugs the library
 * cannot detect: the first pushes a state that is already current, the second never leaves.
 */
public interface MutationScope {
    /**
     * Records that an entity was created or changed in this transaction.
     *
     * @param entityType Type of the changed entity.
     * @param id Identifier of the changed entity.
     */
    public fun markUpserted(
        entityType: EntityType,
        id: EntityId,
    )

    /**
     * Records that an entity was deleted in this transaction.
     *
     * @param entityType Type of the deleted entity.
     * @param id Identifier of the deleted entity.
     */
    public fun markDeleted(
        entityType: EntityType,
        id: EntityId,
    )
}
