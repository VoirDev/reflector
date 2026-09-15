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
 * @property pendingBlobs Files this device holds that the server does not have yet. Counted
 *   separately from [pendingCount] because they mean different things to a user: a record that has
 *   not reached the server is work that could be lost, and a photograph that has not is a slow
 *   upload. Only files some document still points at are counted — one nothing references is not
 *   work.
 * @property incomingBlobs Files the server has that this device wants and does not have yet. Not a
 *   failure and not a count of anything wrong: it is the ordinary state of attachments whose
 *   records arrived first, and what a screen showing placeholders is drawn from.
 *
 *   Wants, rather than merely references. A file left behind under
 *   [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] is not counted until something asks for
 *   it, because it is not work in progress — it is a file this device has decided not to hold, and
 *   an application that showed it as incoming would be promising an arrival that nothing is
 *   waiting for.
 */
public data class CollectionSyncState(
    public val phase: SyncPhase,
    public val pendingCount: Int,
    public val conflictCount: Int,
    public val lastFailure: SyncFailure?,
    public val pendingBlobs: Int = 0,
    public val incomingBlobs: Int = 0,
)
