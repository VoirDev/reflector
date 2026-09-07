package dev.voir.reflector.sync.core.conflict

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import kotlinx.serialization.json.JsonObject

/**
 * Two states of one entity that cannot both be kept.
 *
 * The payloads are materialised at detection time rather than referenced lazily: the local state
 * may keep changing afterwards, and a decision made about a state nobody can reproduce is worse
 * than a slightly stale one.
 *
 * @property id Identifier of the conflict, stable until it is resolved.
 * @property entityType Type of the conflicting entity.
 * @property entityId Identifier of the conflicting entity.
 * @property origin Where the conflict was detected.
 * @property local Local state at the moment of detection, or `null` when the entity was deleted
 *   locally.
 * @property server Server state at the moment of detection, or `null` when the server deleted the
 *   entity, which makes keeping the local side a re-creation under the same identifier.
 */
public data class Conflict(
    public val id: ConflictId,
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val origin: ConflictOrigin,
    public val local: JsonObject?,
    public val server: JsonObject?,
)
