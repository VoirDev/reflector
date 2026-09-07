package dev.voir.reflector.sync.persistence.inbox

/**
 * State of a downloaded batch waiting to be applied.
 *
 * The inbox makes the pull two-phase: a page is written first, then applied batch by batch. The
 * split buys two things — a crash while applying does not require downloading the page again, and
 * one server transaction becomes exactly one local transaction, which is what keeps atomicity at
 * the level of a batch rather than of a whole synchronisation cycle.
 */
public enum class InboxBatchState {
    /** Downloaded and not applied yet. */
    PENDING,

    /**
     * Applied to the application's tables, together with advancing the cursor.
     *
     * The row is deleted right after; the state exists so that a crash between applying and
     * deleting cannot make the batch look pending again.
     */
    APPLIED,
}
