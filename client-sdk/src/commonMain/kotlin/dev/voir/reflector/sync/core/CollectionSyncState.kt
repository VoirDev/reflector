package dev.voir.reflector.sync.core

/**
 * Progress of one collection, as observed by the application.
 *
 * @property phase Where the collection is in its lifecycle. [SyncPhase.NEEDS_ATTENTION] is
 *   reported here in place of [SyncPhase.LIVE] once [conflictCount] reaches the engine's
 *   [ConflictThreshold]: a collection whose queue is blocked on conflicts nobody answers is not
 *   live in any sense the application can act on.
 * @property pendingCount Number of local changes waiting to reach the server. It is the number the
 *   application needs before signing out or showing an "unsaved changes" indicator.
 * @property conflictCount Number of conflicts waiting for the application to decide.
 * @property lastFailure Failure of the most recent attempt, or `null` when the last attempt
 *   succeeded. A failure here does not mean synchronisation stopped: transient ones are retried.
 */
public data class CollectionSyncState(
    public val phase: SyncPhase,
    public val pendingCount: Int,
    public val conflictCount: Int,
    public val lastFailure: SyncFailure?,
)
