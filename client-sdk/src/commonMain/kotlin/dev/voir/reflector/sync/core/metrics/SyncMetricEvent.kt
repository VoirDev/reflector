package dev.voir.reflector.sync.core.metrics

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlin.time.Duration

/**
 * Something the library measured while synchronising one collection.
 *
 * The set is closed and deliberately small. Each entry answers a question that cannot be answered
 * from the outside: how much of the queue leaves and in what condition, how far behind the log the
 * client is running, how long a bootstrap costs, how much time is spent waiting out a backoff, and
 * how deep the queue and the conflict list have grown.
 *
 * Durations are measured with the engine's clock, which is the device's wall clock. They are
 * diagnostics and nothing in the library reads them back: a device whose clock jumps produces a
 * nonsensical duration and a perfectly correct synchronisation.
 */
public sealed class SyncMetricEvent {
    /** Scope the measured work belongs to. */
    public abstract val scope: ScopeId

    /** Collection the measured work belongs to. */
    public abstract val collection: CollectionId

    /**
     * One push group reached a conclusion.
     *
     * Emitted once per attempt, including the attempt that never left the device because the group
     * exceeded the server's limits. Counting [outcome] over time is what makes the conflict share
     * and the refusal rate visible; a client whose conflicts are a large share of its pushes has an
     * application that overwrites the same entities from several devices, not a transport problem.
     *
     * @property scope Scope the group belongs to.
     * @property collection Collection the group belongs to.
     * @property operations Number of operations the group carried.
     * @property outcome How the attempt ended.
     * @property duration Time from picking the group up to writing down what the server answered,
     *   the network round trip included.
     */
    public data class PushCompleted(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val operations: Int,
        public val outcome: PushMetricOutcome,
        public val duration: Duration,
    ) : SyncMetricEvent()

    /**
     * A pull read the log to its end and applied everything it found.
     *
     * Only a pull that caught up is reported: one that stopped on a failure or on a required
     * bootstrap has no meaningful volume to report, and counting it as a pull would flatter the
     * numbers. The batch count is the closest thing to cursor lag the client can measure — the
     * cursor is an opaque token and the distance between two of them is the server's arithmetic,
     * not the client's, which is why the server module measures the lag itself.
     *
     * @property scope Scope that was read.
     * @property collection Collection that was read.
     * @property batches Batches applied to the application's tables during this pull.
     * @property duration Time from the first request to the last batch applied.
     */
    public data class PullCompleted(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val batches: Int,
        public val duration: Duration,
    ) : SyncMetricEvent()

    /**
     * A bootstrap transferred a whole snapshot and the collection follows the log again.
     *
     * The duration is the number to watch: a bootstrap is what every unusable cursor ends in, and
     * on a large collection it is the longest thing the library ever does. An interrupted bootstrap
     * reports nothing and the resumed one reports only its own share, so a collection that never
     * finishes shows up as silence rather than as a large number.
     *
     * @property scope Scope that was rebuilt.
     * @property collection Collection that was rebuilt.
     * @property items Snapshot entries applied by the run that finished, which excludes the pages a
     *   previous, interrupted run had already applied.
     * @property duration Time this run took, from its first page to the sweep.
     */
    public data class BootstrapCompleted(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val items: Int,
        public val duration: Duration,
    ) : SyncMetricEvent()

    /**
     * A push failed on the transport and the group was given a delay before the next attempt.
     *
     * This is where time spent in backoff becomes visible: the delays are the wait, and their sum
     * over a period is how long the collection was deliberately doing nothing. A group waiting for a
     * dependency it cannot merge away is not reported here — it is waiting for somebody else's
     * change rather than serving a penalty for a failure of its own.
     *
     * @property scope Scope of the queue that is waiting.
     * @property collection Collection of the queue that is waiting.
     * @property attempts Consecutive failed attempts of this group, one after the first failure.
     * @property delay Wait before the group is picked up again; a server's `Retry-After` when it
     *   sent one, otherwise the client's own exponential backoff.
     */
    public data class RetryScheduled(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val attempts: Int,
        public val delay: Duration,
    ) : SyncMetricEvent()

    /**
     * Depth of the queue and of the conflict list at the end of one synchronisation cycle.
     *
     * A gauge rather than a count of events, and the one that says whether the rest of them add up:
     * a client whose pushes all succeed while its queue keeps growing is producing changes faster
     * than it can send them, which no per-attempt number shows.
     *
     * @property scope Scope that was worked on.
     * @property collection Collection that was worked on.
     * @property pendingCount Local changes that have not reached the server.
     * @property conflictCount Conflicts open and waiting for a decision.
     */
    public data class QueueObserved(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val pendingCount: Int,
        public val conflictCount: Int,
    ) : SyncMetricEvent()
}
