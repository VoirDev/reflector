package dev.voir.reflector.sync.core.diagnostics

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import kotlin.time.Instant

/**
 * Everything the library knows about one collection, read at one moment.
 *
 * A snapshot and not a flow, and a separate thing from
 * [dev.voir.reflector.sync.core.CollectionSyncState]. The published state is what a user interface
 * binds to: four values, chosen so that a screen can be drawn from them and so that they can change
 * many times a second without costing anything. This is what a developer asks for when that screen
 * says "3 changes waiting" and has been saying it for an hour — the queue itself, the errors the
 * last attempts were refused with, and where the collection stands in its lifecycle.
 *
 * Most of it is durable, which is the point. A failure kept only in memory is gone after a restart,
 * and a queue that has been blocked since last Tuesday is exactly the case where nobody was watching
 * when it happened. What is stored here has been stored all along; until now there was no way to
 * read it back short of opening the database file.
 *
 * Nothing here is a supported way to drive the engine: the values describe a moment that has already
 * passed by the time they are read, and acting on them races with the worker. They are for a debug
 * screen, a bug report, and a log line.
 *
 * @property scope Scope the collection belongs to.
 * @property collection Identifier of the collection.
 * @property phase Lifecycle phase as stored, which — unlike the published state — is never replaced
 *   by [SyncPhase.NEEDS_ATTENTION]: that one is derived when the state is published, and here the
 *   conflict count is given separately so both are visible.
 * @property cursor Position in the change log, or `null` when nothing has been read yet. Opaque:
 *   the distance between two of them is arithmetic only the server can do.
 * @property generation Bootstrap counter. Records carrying an older one are swept at the end of the
 *   next bootstrap.
 * @property bootstrapPage Continuation token of a snapshot transfer that was interrupted, or `null`
 *   when none is in progress. A value here on a collection that is not bootstrapping is the library
 *   being inconsistent with itself and worth reporting.
 * @property schemaFingerprint Shape the application's tables had when this collection was last
 *   synchronised, or `null` when the adapter declares none.
 * @property pendingCount Local changes that have not reached the server.
 * @property conflictCount Conflicts open and waiting for a decision.
 * @property lastError Description of the most recent failure against the collection, or `null`
 *   after a success. Survives a restart, which the published `lastFailure` deliberately does not.
 * @property failureCount Consecutive failures recorded against the collection.
 * @property lastPullAt When a pull last caught up, or `null` when none has. Local wall-clock time,
 *   and diagnostic only: no decision in this library reads the device's clock.
 * @property lastPushAt When a push was last applied, or `null` when none has been.
 * @property queue Groups waiting to be sent, oldest first. The head is the one that matters: its
 *   state is what decides whether the collection is draining, waiting or stopped.
 */
public data class CollectionDiagnostics(
    public val scope: ScopeId,
    public val collection: CollectionId,
    public val phase: SyncPhase,
    public val cursor: Cursor?,
    public val generation: Long,
    public val bootstrapPage: PageToken?,
    public val schemaFingerprint: SchemaFingerprint?,
    public val pendingCount: Int,
    public val conflictCount: Int,
    public val lastError: String?,
    public val failureCount: Int,
    public val lastPullAt: Instant?,
    public val lastPushAt: Instant?,
    public val queue: List<QueuedGroupDiagnostics>,
) {
    /**
     * Whether the queue has stopped in a way only the application can clear.
     *
     * True when the oldest group is waiting for conflicts to be decided or has been refused
     * permanently. Everything behind such a group waits with it, and no amount of retrying,
     * reconnecting or waiting changes that — which is what separates it from a collection that is
     * merely offline or serving a backoff, and why it is worth a question of its own rather than
     * leaving every caller to work it out from [queue].
     */
    public val isQueueBlocked: Boolean
        get() =
            queue.firstOrNull()?.state.let { head ->
                head == PushGroupState.CONFLICTED || head == PushGroupState.FAILED
            }
}
