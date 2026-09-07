package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType

/**
 * Failure of a synchronisation attempt, as reported to the application.
 *
 * The cases differ in what the application can do about them, which is the only reason to
 * distinguish them: a transient failure needs no reaction, an authentication failure needs the
 * user, and a rejection needs the data to change before anything can move again.
 */
public sealed class SyncFailure {
    /** Human-readable description for logs and diagnostics; never a user-facing string. */
    public abstract val message: String

    /**
     * The server could not be reached, or the request did not complete.
     *
     * Retried automatically with backoff; nothing is required from the application.
     *
     * @property message Description of the transport failure.
     */
    public data class Network(
        override val message: String,
    ) : SyncFailure()

    /**
     * The server answered with a failure the client cannot interpret further.
     *
     * @property statusCode HTTP status the server answered with.
     * @property message Description reported by the server, for logs.
     */
    public data class Server(
        public val statusCode: Int,
        override val message: String,
    ) : SyncFailure()

    /**
     * The server refused a change permanently.
     *
     * The queue of the collection is blocked on this group until the application reacts: retrying
     * unchanged data would produce a poison message that never leaves.
     *
     * @property entityType Type of the entity the refusal is about, or `null` when it concerns the
     *   whole group.
     * @property entityId Identifier of the entity the refusal is about, or `null` when it concerns
     *   the whole group.
     * @property rejection Reason the server gave.
     * @property message Description reported by the server, for logs.
     */
    public data class Rejected(
        public val entityType: EntityType?,
        public val entityId: EntityId?,
        public val rejection: SyncRejection,
        override val message: String,
    ) : SyncFailure()

    /**
     * The application's adapter or database failed while the library was applying a change.
     *
     * The transaction was rolled back, so the local state is consistent, but the failure is not
     * transient: the same data will fail again until the application is fixed.
     *
     * @property message Description of the local failure.
     */
    public data class Local(
        override val message: String,
    ) : SyncFailure()
}
