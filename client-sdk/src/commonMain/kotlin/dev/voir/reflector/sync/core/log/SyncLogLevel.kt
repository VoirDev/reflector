package dev.voir.reflector.sync.core.log

/**
 * Severity of one thing the library reports about itself.
 *
 * The entries are declared from most to least severe, and that order is part of the contract: a
 * sink filtering by a threshold compares against it through [includes], and an adapter mapping
 * these onto a host's logging framework relies on the two scales lining up.
 *
 * What each level means here is a statement about the application, not about the library's mood.
 * [ERROR] and [WARN] are the levels a developer is meant to be able to leave on in a running
 * application without drowning: everything reported at them is either broken or stuck, and both are
 * things only the application can clear. Everything below is the story of a healthy client and
 * exists to answer "what is it doing right now".
 */
public enum class SyncLogLevel {
    /**
     * Something failed and will not recover on its own.
     *
     * The application's code threw inside the library's transaction, or the server refused a change
     * permanently. Synchronisation of the collection has stopped making progress and will not
     * restart until the application acts.
     */
    ERROR,

    /**
     * Something is stuck, unusual, or about to become a problem.
     *
     * A queue blocked on its head, a cursor refused, conflicts nobody has answered, a socket that
     * keeps failing to connect. None of it is broken in the sense [ERROR] means, and all of it ends
     * badly if it persists.
     */
    WARN,

    /**
     * A significant, infrequent step completed.
     *
     * A bootstrap finished, a scope changed state, a collection was sent back to a snapshot. Rare
     * enough to leave on in production, informative enough to reconstruct a device's history from.
     */
    INFO,

    /**
     * What the engine decided, once per cycle and once per request.
     *
     * The level a developer turns on to answer "why is nothing leaving this device". Ordinary
     * traffic, so it is expected to be off unless somebody is looking.
     */
    DEBUG,

    /**
     * Per-entity and per-batch detail underneath a [DEBUG] decision.
     *
     * Proportional to the data being synchronised rather than to the number of cycles, so it is
     * loud on any real collection.
     */
    TRACE,
    ;

    /**
     * Tells whether a record of [level] passes this threshold.
     *
     * @param level Severity of the record being considered.
     * @return `true` when [level] is at least as severe as this one, which is what a sink
     *   configured with a minimum severity wants to know.
     */
    public fun includes(level: SyncLogLevel): Boolean = level.ordinal <= ordinal
}
