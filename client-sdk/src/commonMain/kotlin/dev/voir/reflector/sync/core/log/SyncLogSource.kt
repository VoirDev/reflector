package dev.voir.reflector.sync.core.log

/**
 * Part of the library a record came from.
 *
 * The source exists so that severity does not have to be the only dial. A client that is being
 * debugged for a queue that will not drain wants every decision the push side takes and nothing at
 * all from the pull side, which on a busy collection is the louder of the two by far.
 *
 * [id] is what an adapter builds a hierarchical logger name from — `dev.voir.reflector.sync.push`
 * and the rest — so it is a contract with somebody's logging configuration and does not change with
 * the name of the entry.
 *
 * @property id Stable lowercase identifier, safe to use as the last segment of a logger name.
 */
public enum class SyncLogSource(
    public val id: String,
) {
    /** The engine, the scope and the per-collection worker that decides what runs next. */
    ENGINE("engine"),

    /** Turning the application's local transactions into queued groups, and merging them. */
    MUTATION("mutation"),

    /** Sending queued groups and applying what the server answered. */
    PUSH("push"),

    /** Reading the change log and applying it to the application's tables. */
    PULL("pull"),

    /** Rebuilding a collection from a snapshot. */
    BOOTSTRAP("bootstrap"),

    /** The lifecycle of conflicts, wherever they were detected. */
    CONFLICT("conflict"),

    /** HTTP requests to the server and the credentials attached to them. */
    TRANSPORT("transport"),

    /** The WebSocket that carries the server's notifications. */
    CHANNEL("channel"),
}
