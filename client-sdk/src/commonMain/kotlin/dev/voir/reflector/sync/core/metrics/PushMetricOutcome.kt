package dev.voir.reflector.sync.core.metrics

/**
 * How one push attempt ended, in the terms worth counting.
 *
 * The distinctions are the ones that mean different things for a dashboard: work that left the
 * device, work the user has to answer for, work that will never leave unchanged, and work that
 * simply has to be tried again.
 */
public enum class PushMetricOutcome {
    /** The server applied the group; the local changes are now confirmed. */
    APPLIED,

    /** The server refused the group because an entity in it had moved on; the application decides. */
    CONFLICT,

    /**
     * The server refused the group for a reason a retry cannot fix, or it never left because it
     * exceeded the published limits. The queue is blocked until the application corrects the data.
     */
    REJECTED,

    /**
     * The server called the group dependent on another one, so it was merged with the next group and
     * will go out again. Worth counting on its own: a rising number is a client and a server pushing
     * the same envelope back and forth, which the merge ceiling stops only by failing the group.
     */
    DEPENDENCY,

    /** The attempt did not complete — the server could not be reached, or it failed transiently. */
    FAILED,
}
