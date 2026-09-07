package dev.voir.reflector.sync.core.conflict

/**
 * Where a conflict was detected.
 *
 * Both origins produce the same decision for the application; the distinction is kept because it is
 * the first thing to look at when diagnosing why a conflict appeared at all.
 */
public enum class ConflictOrigin {
    /**
     * Detected while applying incoming changes to an entity with unsent local edits.
     *
     * The incoming change is not applied to the application's tables, and the conflict is recorded
     * in the same transaction: the cursor moves on regardless, so anything not recorded is lost.
     */
    PULL,

    /** Detected when the server refused a push because the entity had already moved on. */
    PUSH,
}
