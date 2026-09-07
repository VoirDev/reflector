package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import kotlinx.serialization.Serializable

/**
 * Reason a push group was refused.
 *
 * @property code Machine-readable reason; see [RejectCode] for the codes both sides agree on.
 * @property entity Type of the entity the refusal is about, or `null` when it concerns the group
 *   as a whole.
 * @property id Identifier of the entity the refusal is about, or `null` when it concerns the group
 *   as a whole.
 * @property message Human-readable explanation for logs and diagnostics. It is not a user-facing
 *   string and must not be parsed.
 */
@Serializable
public data class RejectError(
    public val code: RejectCode,
    val entity: EntityType? = null,
    val id: EntityId? = null,
    public val message: String,
)
