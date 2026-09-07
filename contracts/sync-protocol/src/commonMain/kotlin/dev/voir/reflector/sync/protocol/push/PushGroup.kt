package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.GroupId
import kotlinx.serialization.Serializable

/**
 * One atomically applied group of operations.
 *
 * A group is the protocol's unit of atomicity: it exists because changes made in a single local
 * transaction must reach the server together. The server applies all of its operations or none of
 * them, and stores the outcome under [groupId], so re-sending the same group after a lost response
 * returns the stored result instead of applying it a second time.
 *
 * @property groupId Identifier of the group, which is also the idempotency key of the push.
 * @property ops Operations of the group, in a deterministic client-defined order.
 */
@Serializable
public data class PushGroup(
    public val groupId: GroupId,
    public val ops: List<PushOperation>,
)
