package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.json.JsonObject

/**
 * One change as it was committed, handed to a projection.
 *
 * @property entityType Type of the changed entity.
 * @property entityId Identifier of the changed entity.
 * @property version Version the entity has after the change.
 * @property data State after the change, or `null` when the entity was deleted.
 */
public data class AppliedChange(
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val version: EntityVersion,
    public val data: JsonObject?,
)
