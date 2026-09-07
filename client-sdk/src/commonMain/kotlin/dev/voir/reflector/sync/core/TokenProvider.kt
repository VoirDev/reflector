package dev.voir.reflector.sync.core

/**
 * Source of the credentials the transport authenticates with.
 *
 * The library never stores, parses or refreshes tokens on its own: authentication belongs to the
 * application, and duplicating its rules here would put the same policy in two places.
 */
public interface TokenProvider {
    /**
     * Returns the token to authenticate the next request with.
     *
     * @return Current token, or `null` when the user is not authenticated, which stops the workers
     *   instead of sending an unauthenticated request.
     */
    public suspend fun token(): String?

    /**
     * Refreshes the token after the server rejected it.
     *
     * Called at most once per rejection: a refresh loop against a server that keeps refusing is
     * indistinguishable from an outage but far more expensive.
     *
     * @return `true` when a new token is available and the request may be retried, `false` when the
     *   user has to authenticate again.
     */
    public suspend fun refresh(): Boolean
}
