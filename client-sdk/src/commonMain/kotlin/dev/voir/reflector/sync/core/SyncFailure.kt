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
 *
 * Whatever the library knows is kept rather than flattened into a sentence. A case that came from a
 * throwable carries it, because the description of a fault in code this library does not own is
 * worth very little without the stack that produced it; and a case the protocol names is reported
 * as itself rather than as a status code standing in for it.
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
     * @property cause Throwable behind it, when the transport had one. A refused connection, an
     *   expired certificate and a name that does not resolve all arrive here as the same case, and
     *   this is what tells them apart.
     */
    public data class Network(
        override val message: String,
        public val cause: Throwable? = null,
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
     * The credentials were refused and the application could not renew them.
     *
     * The workers stop rather than back off: another attempt cannot produce a token. The queue and
     * the local data are kept, so signing in again lets the queued changes leave.
     *
     * Reported here as well as through [ScopeState.AuthRequired] because the two are read in
     * different places: the scope's state is what a shell observes, and this is what a screen bound
     * to one collection has in its hand when it has to say why nothing is moving.
     *
     * @property message Description reported by the server.
     */
    public data class AuthRequired(
        override val message: String,
    ) : SyncFailure()

    /**
     * Access to the scope has been taken away.
     *
     * Terminal: there is nobody left to accept the queued changes, and the scope's local data is
     * wiped. Distinguished from [AuthRequired] because signing in again does not help.
     *
     * @property message Description reported by the server.
     */
    public data class Revoked(
        override val message: String,
    ) : SyncFailure()

    /**
     * The application's adapter or database failed while the library was applying a change.
     *
     * The transaction was rolled back, so the local state is consistent, but the failure is not
     * transient: the same data will fail again until the application is fixed.
     *
     * @property message Description of the local failure.
     * @property cause Throwable the adapter or the database threw. The library cannot say anything
     *   useful about a fault in code it does not own, so it carries the one thing that can: a
     *   message alone turns a `NullPointerException` with a stack into the word "null".
     */
    public data class Local(
        override val message: String,
        public val cause: Throwable? = null,
    ) : SyncFailure()
}
