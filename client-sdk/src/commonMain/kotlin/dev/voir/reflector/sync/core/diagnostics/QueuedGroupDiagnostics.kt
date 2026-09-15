package dev.voir.reflector.sync.core.diagnostics

import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.protocol.GroupId
import kotlin.time.Instant

/**
 * One group waiting in a collection's push queue.
 *
 * The queue is strictly ordered and only its head is ever sent, so the first of these explains the
 * whole collection: everything behind a group that cannot leave is waiting on it, however healthy
 * it is itself.
 *
 * @property groupId Identifier of the group, and the idempotency key its push carries. It appears
 *   in the library's log records under the same name, so a group followed through a log can be
 *   found here and the other way round.
 * @property ordinal Position in the queue; groups leave strictly in this order.
 * @property state Where the group stands. [PushGroupState.CONFLICTED] and [PushGroupState.FAILED]
 *   at the head are the two that stop the collection until the application acts.
 * @property operations Changes the group would send, counting only the records still dirty.
 * @property attempts Consecutive failed attempts made on it.
 * @property nextRetryAt When it may be tried again, or `null` when nothing is holding it back.
 *   Local wall-clock time, compared against the device's own clock and nothing else.
 * @property dependencyMerges Times the server has called this group dependent on another and the
 *   two were merged. A number climbing towards the library's ceiling is a client and a server
 *   pushing the same envelope back and forth.
 * @property lastError What the last attempt was refused or failed with, or `null` when there has
 *   been none. For a failed head this is the sentence that says what has to change.
 */
public data class QueuedGroupDiagnostics(
    public val groupId: GroupId,
    public val ordinal: Long,
    public val state: PushGroupState,
    public val operations: Int,
    public val attempts: Int,
    public val nextRetryAt: Instant?,
    public val dependencyMerges: Int,
    public val lastError: String?,
)
