package dev.voir.reflector.sync.core

/**
 * Connection and access state of a scope.
 *
 * The state says whether synchronisation can make progress at all; per-collection progress is
 * reported separately by [CollectionSyncState].
 */
public sealed class ScopeState {
    /** The transport is connected and workers are free to push and pull. */
    public data object Online : ScopeState()

    /**
     * The transport cannot reach the server.
     *
     * Local mutations keep working and keep queueing: this is the normal state of an offline-first
     * client, not an error to show as one.
     */
    public data object Offline : ScopeState()

    /**
     * The server rejected the client's credentials and refreshing them did not help.
     *
     * Workers stop instead of backing off, because a retry cannot produce a token. Local data and
     * the push queue are preserved, so signing in again lets the queued changes leave.
     */
    public data object AuthRequired : ScopeState()

    /**
     * Access to the scope has been revoked.
     *
     * Terminal for this scope: there is nobody left to accept its queued changes, and the local
     * data of the scope is wiped.
     */
    public data object Revoked : ScopeState()
}
