package dev.voir.reflector.sync.core.adapter

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import kotlinx.serialization.json.JsonObject

/**
 * Incoming change handed to the application's adapter.
 *
 * Versions and cursors are deliberately absent: they are the library's bookkeeping, and an adapter
 * that stored them would end up with two sources of truth about what the server knows.
 */
public sealed class RemoteOp {
    /** Type of the affected entity. */
    public abstract val entityType: EntityType

    /** Identifier of the affected entity. */
    public abstract val id: EntityId

    /**
     * State the entity has to be written with.
     *
     * The payload is a full state rather than a diff, so applying it twice produces the same result
     * as applying it once. That idempotence is what allows a bootstrap to overlap with the log and
     * a crashed pull to be replayed.
     *
     * @property entityType Type of the affected entity.
     * @property id Identifier of the affected entity.
     * @property data State to write.
     */
    public data class Upsert(
        override val entityType: EntityType,
        override val id: EntityId,
        public val data: JsonObject,
    ) : RemoteOp()

    /**
     * Entity that has to be removed locally.
     *
     * Also used to sweep entities that a bootstrap did not return, which is how an application
     * learns about deletions it was offline for.
     *
     * @property entityType Type of the affected entity.
     * @property id Identifier of the affected entity.
     */
    public data class Delete(
        override val entityType: EntityType,
        override val id: EntityId,
    ) : RemoteOp()
}
