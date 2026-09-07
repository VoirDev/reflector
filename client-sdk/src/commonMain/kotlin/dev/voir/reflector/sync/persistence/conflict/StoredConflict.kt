package dev.voir.reflector.sync.persistence.conflict

import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.json.JsonObject

/**
 * A recorded conflict with both sides parsed.
 *
 * @property conflictId Identifier of the conflict.
 * @property entityType Type of the conflicting entity.
 * @property entityId Identifier of the conflicting entity.
 * @property origin Whether the conflict was found while pulling or while pushing.
 * @property local Local state at detection time, or `null` when the entity was deleted locally.
 * @property server Server state at detection time, or `null` when the server deleted it.
 * @property serverVersion Version the conflict was detected against; a resolution is applied on top
 *   of it, which is why it has to survive the decision.
 */
internal data class StoredConflict(
    public val conflictId: ConflictId,
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val origin: ConflictOrigin,
    public val local: JsonObject?,
    public val server: JsonObject?,
    public val serverVersion: EntityVersion,
)
