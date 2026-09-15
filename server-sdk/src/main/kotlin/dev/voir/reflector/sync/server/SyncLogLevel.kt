package dev.voir.reflector.sync.server

/**
 * Severity of one thing the module reports about itself.
 *
 * The entries are declared from most to least severe, and that order is part of the contract: an
 * adapter mapping these onto the host's logging framework relies on the two scales lining up, and
 * [includes] compares against a threshold for a host that has no framework to defer to.
 *
 * The levels are chosen so that a host can leave [ERROR] and [WARN] on in production without
 * drowning: everything reported at them is either broken or a capacity problem in the making.
 * [DEBUG] and below are proportional to request volume and belong to a server being investigated.
 */
public enum class SyncLogLevel {
    /**
     * Something failed that the host has to know about.
     *
     * The module's own work is not reported here — a refused group is the protocol working — but a
     * host listener that threw is, because that failure is invisible everywhere else and its
     * consequence is silent: clients stop being told and fall back on polling.
     */
    ERROR,

    /**
     * Something is a capacity problem, now or soon.
     *
     * The counter lock held far longer than a write should take, a cursor refused because it fell
     * out of the retention window, a page token the module did not produce.
     */
    WARN,

    /** A significant, infrequent step: the module assembled, migrations applied, history trimmed. */
    INFO,

    /** One request answered. Proportional to traffic, so it is expected to be off in production. */
    DEBUG,

    /** Per-group and per-batch detail underneath a request. */
    TRACE,
    ;

    /**
     * Tells whether a record of [level] passes this threshold.
     *
     * @param level Severity of the record being considered.
     * @return `true` when [level] is at least as severe as this one.
     */
    public fun includes(level: SyncLogLevel): Boolean = level.ordinal <= ordinal
}
