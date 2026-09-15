package dev.voir.reflector.sync.server

/**
 * Part of the module a record came from.
 *
 * The reason this exists rather than severity alone is the shape of a server's traffic. A module
 * being investigated for slow writes wants every decision the push path takes and nothing from the
 * read paths, which on any real deployment outnumber it by orders of magnitude. Turning everything
 * to `DEBUG` to see one of them is not a practical option on a running server.
 *
 * [id] is what an adapter builds a hierarchical logger name from — `dev.voir.reflector.sync.push`
 * and the rest — so it is a contract with the host's logging configuration and does not change when
 * an entry is renamed.
 *
 * @property id Stable lowercase identifier, safe to use as the last segment of a logger name.
 */
public enum class SyncLogSource(
    public val id: String,
) {
    /** Assembly of the module, its migrations, and the host callbacks it invokes. */
    MODULE("module"),

    /** Applying pushed groups: conflicts, refusals, and the counter lock. */
    PUSH("push"),

    /** Serving pages of the change log, and refusing cursors that fell out of the window. */
    CHANGES("changes"),

    /** Serving pages of a snapshot to a client that is rebuilding a collection. */
    SNAPSHOT("snapshot"),

    /** Trimming history to the retention window. */
    MAINTENANCE("maintenance"),
}
