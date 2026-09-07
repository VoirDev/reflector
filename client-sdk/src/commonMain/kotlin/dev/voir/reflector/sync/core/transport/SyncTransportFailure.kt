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
     * Access to the scope has been revoked.
     *
     * @property message Description reported by the server.
     */
    public class Revoked(
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
