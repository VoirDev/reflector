package dev.voir.reflector.sync.core.adapter

/**
 * Reason the server refused a change permanently.
 *
 * Only refusals the application can act on reach it. A refusal the library can recover from on its
 * own — a group refused because it depends on another one — is handled by merging and retrying the
 * groups and never becomes a rejection here.
 */
public sealed class SyncRejection {
    /** Description reported by the server, for logs and diagnostics. */
    public abstract val message: String

    /**
     * The payload violates a rule the host enforces on top of the documents.
     *
     * @property message Explanation reported by the server.
     */
    public data class Validation(
        override val message: String,
    ) : SyncRejection()

    /**
     * The entity type is not registered for this collection on the server.
     *
     * Almost always a typo or a client that is newer than the server's configuration: registering
     * types is mandatory precisely so that this fails loudly instead of creating stray data.
     *
     * @property message Explanation reported by the server.
     */
    public data class UnknownEntityType(
        override val message: String,
    ) : SyncRejection()

    /**
     * The group or one of its documents exceeds the server's limits.
     *
     * A group cannot be split without breaking its atomicity, so the application has to make the
     * change smaller — usually by splitting the original local transaction.
     *
     * @property message Explanation reported by the server.
     */
    public data class TooLarge(
        override val message: String,
    ) : SyncRejection()

    /**
     * A refusal code this client does not know.
     *
     * Treated as permanent, which is the safe default: the alternative is retrying forever a change
     * the server has already decided about.
     *
     * @property code Refusal code as it arrived on the wire.
     * @property message Explanation reported by the server.
     */
    public data class Unknown(
        public val code: String,
        override val message: String,
    ) : SyncRejection()
}
