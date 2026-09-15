package dev.voir.reflector.sync.engine.push

import dev.voir.reflector.sync.core.transport.SyncTransportFailure

/**
 * Result of one push attempt, in the terms the collection's worker decides on.
 */
internal sealed class PushOutcome {
    /** Nothing was ready to send. */
    data object Idle : PushOutcome()

    /** A group left the queue; the next one may be attempted right away. */
    data object Applied : PushOutcome()

    /**
     * The queue cannot move on for now.
     *
     * Covers a conflict waiting for a decision, a permanently refused group and a group in backoff:
     * they differ in what unblocks them, but for the worker they mean the same thing — stop pushing
     * this collection until something changes.
     */
    data object Blocked : PushOutcome()

    /**
     * The collection these changes were made against no longer exists.
     *
     * The server refused the whole request rather than applying it, which is the point: the queue
     * is about to be discarded, and a queue that had already been applied could not be.
     */
    data object ResetRequired : PushOutcome()

    /**
     * The scope itself has to react before any push can succeed.
     *
     * @property failure Reason the scope is not usable, such as refused credentials or a revoked
     *   scope.
     */
    data class Interrupted(
        val failure: SyncTransportFailure,
    ) : PushOutcome()
}
