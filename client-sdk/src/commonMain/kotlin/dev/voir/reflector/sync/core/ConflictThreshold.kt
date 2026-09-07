package dev.voir.reflector.sync.core

import kotlin.jvm.JvmInline

/**
 * Number of open conflicts at which a collection stops calling itself healthy.
 *
 * A conflict blocks the group it belongs to, and the push queue is FIFO, so conflicts nobody
 * answers do not merely accumulate: they stop the collection while it keeps reporting
 * [SyncPhase.LIVE]. The threshold is where that becomes a state of its own,
 * [SyncPhase.NEEDS_ATTENTION], which the application can show, count or ignore as it sees fit.
 *
 * The library does nothing else with it. It does not expire conflicts, resolve them on a policy or
 * refuse further work: discarding a user's edit because it has waited long enough is exactly what
 * an offline-first library must not do.
 *
 * @property value Number of open conflicts the collection tolerates before it asks for attention;
 *   always positive, and reached rather than exceeded — a collection with [value] open conflicts is
 *   already asking.
 */
@JvmInline
public value class ConflictThreshold(
    public val value: Int,
) {
    init {
        require(value > 0) { "a conflict threshold of $value would ask for attention before a conflict exists" }
    }

    /** The value used when the application does not choose one. */
    public companion object {
        /**
         * Threshold a healthy application never reaches.
         *
         * Twenty unanswered conflicts in one collection is not a busy user; it is an adapter that
         * declines every conflict and a user interface that never asks. The default is deliberately
         * far from the numbers ordinary use produces, so that reaching it means something.
         */
        public val Default: ConflictThreshold = ConflictThreshold(DEFAULT_VALUE)

        private const val DEFAULT_VALUE = 20
    }
}
