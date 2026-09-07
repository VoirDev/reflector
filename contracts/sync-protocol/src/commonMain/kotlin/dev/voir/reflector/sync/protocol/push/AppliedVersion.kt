package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.Serializable

/**
 * Version assigned by the server to one entity of an applied group.
 *
 * The client stores it as the base version of the next change to that entity; without it the next
 * push would look like a blind write and be answered with a conflict.
 *
 * @property entity Type of the changed entity.
 * @property id Identifier of the changed entity.
 * @property version Version the entity has after the group was applied.
 */
@Serializable
public data class AppliedVersion(
    public val entity: EntityType,
    public val id: EntityId,
    public val version: EntityVersion,
)
