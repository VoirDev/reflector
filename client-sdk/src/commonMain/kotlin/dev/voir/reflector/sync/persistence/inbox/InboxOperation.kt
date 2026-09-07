package dev.voir.reflector.sync.persistence.inbox

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.json.JsonObject

/**
 * One stored operation of a downloaded batch.
 *
 * The operation code stays a string here, exactly as it arrived. Parsing it is the applying step's
 * job, and a code this client does not know has to reach that step intact so that it can refuse the
 * batch deliberately instead of failing while reading the response.
 *
 * @property entityType Type of the affected entity.
 * @property entityId Identifier of the affected entity.
 * @property op Operation code as it arrived on the wire.
 * @property version Version the entity has after this operation.
 * @property payload State of the entity as of this batch, or `null` for a removal.
 */
internal data class InboxOperation(
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val op: String,
    public val version: EntityVersion,
    public val payload: JsonObject?,
)
