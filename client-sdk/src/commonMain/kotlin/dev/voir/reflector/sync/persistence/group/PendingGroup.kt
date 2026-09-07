package dev.voir.reflector.sync.persistence.group

import dev.voir.reflector.sync.protocol.GroupId

/**
 * A push group as the engine works with it.
 *
 * @property groupId Identifier of the group and idempotency key of its push.
 * @property ord Position in the collection's queue; groups leave strictly in this order.
 * @property state Where the group is in the queue.
 * @property attempts Consecutive failed attempts.
 * @property nextRetryAt Local time before which the group must not be retried, in epoch
 *   milliseconds, or `null` when it may be sent immediately.
 * @property dependencyMerges Merges already caused by dependency refusals, bounded to keep a server
 *   that keeps refusing from making the client merge forever.
 */
internal data class PendingGroup(
    public val groupId: GroupId,
    public val ord: Long,
    public val state: PushGroupState,
    public val attempts: Int,
    public val nextRetryAt: Long?,
    public val dependencyMerges: Int,
)
