package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Current server state of one entity that made a push group conflict.
 *
 * The state is sent along with the refusal so that the client can present both sides to the
 * application without an extra round trip. There is no common ancestor: the merge offered to the
 * application is two-sided by design, because storing a base snapshot would mean the library owns
 * a copy of the business data.
 *
 * @property entity Type of the conflicting entity.
 * @property id Identifier of the conflicting entity.
 * @property serverVersion Version the entity currently has on the server.
 * @property data Current server state of the entity, or `null` when the server has deleted it,
 *   which turns the conflict into a delete/update one.
 */
@Serializable
public data class ConflictEntry(
    public val entity: EntityType,
    public val id: EntityId,
    public val serverVersion: EntityVersion,
    public val data: JsonObject?,
)
