package dev.voir.reflector.sync.core.transport

import kotlin.time.Duration

/**
 * Failure of a call to the server, in the terms the engine reacts to.
 *
 * The cases exist because each one leads to a different decision, and collapsing them would mean
 * guessing: a transient failure is retried with backoff, a stale cursor forces a bootstrap, a
 * refused token stops the workers instead of hammering the server, and a revoked scope wipes it.
 *
 * @param message Description for logs and diagnostics.
 * @param cause Underlying failure, when there is one.
 */
public sealed class SyncTransportFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The server could not be reached or the request did not complete.
     *
     * @property message Description of the transport failure.
     * @property cause Underlying failure, when the implementation has one.
     */
    public class Unreachable(
        message: String,
        cause: Throwable? = null,
    ) : SyncTransportFailure(message, cause)

    /**
     * The server answered with a failure that may pass on its own.
     *
     * @property statusCode Status the server answered with.
     * @property message Description reported by the server.
     */
    public class ServerError(
        public val statusCode: Int,
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The server asked the client to slow down.
     *
     * @property retryAfter How long to wait before the next attempt, when the server said so.
     * @property message Description reported by the server.
     */
    public class RateLimited(
        public val retryAfter: Duration?,
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The credentials were refused and refreshing them did not help.
     *
     * Not retried: another attempt cannot produce a token, and the queue is kept so that signing in
     * again lets it drain.
     *
     * @property message Description reported by the server.
     */
    public class Unauthorized(
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The collection on the server is not the one this client has been following.
     *
     * It was purged and has begun again. Everything stored locally under the old incarnation — the
     * cursor, the versions, the queue of changes that never left — describes a log that no longer
     * exists, and the client discards all of it and rebuilds from a snapshot.
     *
     * Deliberately not a [CursorTooOld]. Both send the collection to a snapshot, but that one keeps
     * the local edits, which is right when the history merely aged out from under a client. Here it
     * would be wrong: those edits would go back up as new entities and put back, one at a time, the
     * data the purge was run to remove.
     *
     * @property message Description reported by the server.
     */
    public class CollectionReset(
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * Access to the scope has been revoked.
     *
     * @property message Description reported by the server.
     */
    public class Revoked(
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The server has no such file.
     *
     * A blob path only. It means the file was collected as garbage while this device was away, or
     * went with a collection that was erased — and either way a device that never fetched the bytes
     * cannot produce them, so it is the application's to decide about rather than a transfer to
     * retry.
     *
     * It exists as a case of its own because the status it arrives as, `404`, means something quite
     * different on the other paths, and the shared mapping cannot tell them apart.
     *
     * @property message Description reported by the server.
     */
    public class BlobGone(
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The server refused something about a file rather than about the request.
     *
     * A blob path only: an identifier already naming different bytes, or a claim that an upload
     * finished which the storage did not bear out. Both arrive as `409`, which on every other path
     * of this protocol means a purged collection — so they must not go through the same mapping, or
     * a failed upload would wipe the collection it belongs to.
     *
     * @property message Description reported by the server.
     */
    public class BlobRefused(
        message: String,
    ) : SyncTransportFailure(message)

    /**
     * The cursor has fallen out of the server's retention window.
     *
     * Recoverable, but only by bootstrapping: the changes between the cursor and the window are
     * gone, and pretending otherwise would leave the client silently missing them.
     *
     * @property message Description reported by the server.
     */
    public class CursorTooOld(
        message: String,
    ) : SyncTransportFailure(message)
}
