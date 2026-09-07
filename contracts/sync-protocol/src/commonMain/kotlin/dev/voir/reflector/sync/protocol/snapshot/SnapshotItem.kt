package dev.voir.reflector.sync.protocol.snapshot

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One entity of a bootstrap snapshot.
 *
 * Deleted entities never appear in a snapshot: a client that bootstraps learns about a removal from
 * the entity's absence, and removes what it still has locally by its own sweep.
 *
 * @property entity Type of the entity.
 * @property id Identifier of the entity.
 * @property version Version the entity currently has. It is mandatory: without it the client would
 *   finish the bootstrap with no base version and its first push would be answered with a conflict.
 * @property data Current state of the entity.
 */
@Serializable
public data class SnapshotItem(
    public val entity: EntityType,
    public val id: EntityId,
    public val version: EntityVersion,
    public val data: JsonObject,
)
