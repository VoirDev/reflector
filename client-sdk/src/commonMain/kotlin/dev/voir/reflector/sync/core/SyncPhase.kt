package dev.voir.reflector.sync.core

/**
 * Lifecycle phase of a collection.
 *
 * The phase decides which worker may run: incremental pulls are meaningless before the first
 * snapshot has been applied, and are unsafe once the cursor is known to be unusable.
 *
 * All of them except [NEEDS_ATTENTION] are stored in the collection's row and survive a restart.
 * [NEEDS_ATTENTION] is derived when the state is published, which is why it is here at all: it is
 * something the application has to see, not a state the engine works in.
 */
public enum class SyncPhase {
    /** Nothing has been synchronised yet; the collection needs its first snapshot. */
    NEW,

    /** A snapshot is being transferred and applied; the change log is not followed yet. */
    BOOTSTRAPPING,

    /** The collection follows the change log incrementally. */
    LIVE,

    /**
     * The collection is live but has accumulated conflicts nobody has answered.
     *
     * Entered when the number of open conflicts reaches the configured [ConflictThreshold], and
     * left again as soon as enough of them are resolved — it is computed from the stored conflicts
     * every time the state is published, never written to the database. The collection is otherwise
     * exactly as [LIVE] describes it, and its workers keep running; what has stopped is the push
     * queue of every group holding a conflicting entity, since the queue is FIFO and a conflict
     * blocks its group until the application decides.
     *
     * The library takes no action on it. Answering the conflicts is the only thing that clears the
     * phase, and only the application can do that — through the adapter, or through the user, which
     * is what this phase exists to let it ask.
     */
    NEEDS_ATTENTION,

    /**
     * The cursor can no longer be used and a new snapshot is required.
     *
     * Entered when the server refuses the cursor as too old, when it asks for a resynchronisation,
     * or when the client meets an operation code it does not understand — skipping the operation
     * while the cursor moves on would lose the change forever.
     */
    RESYNC_REQUIRED,
}
