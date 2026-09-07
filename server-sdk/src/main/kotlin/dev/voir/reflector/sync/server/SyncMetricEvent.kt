package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlin.time.Duration

/**
 * Something the module measured while serving one scope's collection.
 *
 * The set is closed and small, and every entry is a number the host cannot obtain from outside the
 * module: how long writers waited on each other, how far behind its clients are running, how much
 * history a collection is carrying, and how often a client has to be sent back to a snapshot.
 *
 * Durations come from the module's clock. They are diagnostics: nothing in the module reads them
 * back, and no decision depends on them.
 */
public sealed class SyncMetricEvent {
    /** Scope the measured work belongs to. */
    public abstract val scope: ScopeId

    /** Collection the measured work belongs to. */
    public abstract val collection: CollectionId

    /**
     * One push group was answered.
     *
     * [lockHeld] is the measurement this whole port exists for. The collection's counter row is
     * locked for the rest of the transaction that writes a batch, which serialises every writer into
     * that collection — a deliberate trade, and the only thing that keeps the change log free of
     * gaps. It is invisible until it is a queue, and this is the number that shows it growing.
     *
     * @property scope Scope the group was written to.
     * @property collection Collection the group was written to.
     * @property operations Operations the group carried.
     * @property outcome How the module answered.
     * @property lockHeld Time from taking the counter lock to the end of the transaction that held
     *   it, or `null` when no lock was taken — which is the case for a group answered from a stored
     *   result, since a repeat does no work.
     */
    public data class PushGroupServed(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val operations: Int,
        public val outcome: PushMetricOutcome,
        public val lockHeld: Duration?,
    ) : SyncMetricEvent()

    /**
     * A page of the change log was served to a client.
     *
     * [cursorLag] is the client's distance from the head at the moment it asked, in sequences. It is
     * measurable here and nowhere else: to a client a cursor is an opaque token, so the arithmetic
     * between two of them belongs to the side that issues them. A population whose lag keeps growing
     * is one whose clients cannot keep up with the writes — long before any of them falls out of the
     * retention window and has to bootstrap.
     *
     * @property scope Scope that was read.
     * @property collection Collection that was read.
     * @property batches Batches on the page served.
     * @property cursorLag Sequences between the client's cursor and the head of the log when the
     *   request arrived; zero for a client that was already up to date, and the full log length for
     *   one arriving without a cursor.
     * @property duration Time spent serving the page.
     */
    public data class ChangesServed(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val batches: Int,
        public val cursorLag: Long,
        public val duration: Duration,
    ) : SyncMetricEvent()

    /**
     * A page of a snapshot was served to a client that is rebuilding a collection.
     *
     * Counting these against [ChangesServed] is how the cost of bootstrapping becomes visible: a
     * healthy population reads the log and bootstraps rarely, and a shift towards snapshots means
     * clients are falling out of the retention window.
     *
     * @property scope Scope that was read.
     * @property collection Collection that was read.
     * @property items Entities on the page served.
     * @property duration Time spent serving the page.
     */
    public data class SnapshotServed(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val items: Int,
        public val duration: Duration,
    ) : SyncMetricEvent()

    /**
     * A client's cursor was refused because it had fallen out of the retention window.
     *
     * The client answers this by bootstrapping the whole collection, so each of these is a snapshot
     * transfer that is about to happen. A few are a fact of life — a device that was off for longer
     * than the window; many mean the window is too short for the population, which is a
     * configuration decision the host can only take with this number in hand.
     *
     * @property scope Scope that was read.
     * @property collection Collection that was read.
     * @property behindFloor Sequences between the refused cursor and the oldest one still served.
     */
    public data class CursorRefused(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val behindFloor: Long,
    ) : SyncMetricEvent()

    /**
     * Retention trimming finished for one collection.
     *
     * [retainedSpan] is the depth of the log after the trim, which is what says whether retention is
     * keeping up with the write volume: a span that grows from one run to the next means history is
     * accumulating faster than the window discards it, and the collection's reads and its
     * maintenance both get slower with it.
     *
     * @property scope Scope that was trimmed.
     * @property collection Collection that was trimmed.
     * @property removedBatches Batches deleted by this run.
     * @property retainedSpan Sequences between the new floor and the head, that is, the history the
     *   collection still carries.
     */
    public data class HistoryTrimmed(
        override val scope: ScopeId,
        override val collection: CollectionId,
        public val removedBatches: Int,
        public val retainedSpan: Long,
    ) : SyncMetricEvent()
}
