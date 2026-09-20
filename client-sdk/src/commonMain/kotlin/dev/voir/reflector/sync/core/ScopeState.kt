package dev.voir.reflector.sync.core

/**
 * Connection and access state of a scope.
 *
 * The state says whether synchronisation can make progress at all; per-collection progress is
 * reported separately by [CollectionSyncState].
 *
 * [Offline] and [ServerUnreachable] are one situation for the library and two for a person, which
 * is the whole reason they are separate values.
 */
public sealed class ScopeState {
    /** The transport is connected and workers are free to push and pull. */
    public data object Online : ScopeState()

    /**
     * The device has no network at all.
     *
     * Local mutations keep working and keep queueing: this is the normal state of an offline-first
     * client, not an error to show as one, and it ends on its own when the connection returns.
     *
     * Reported only when the application supplies a
     * [dev.voir.reflector.sync.core.transport.NetworkAvailability] and it says the device has no
     * network. Without one the library says [ServerUnreachable] instead, because that is all it
     * witnessed.
     */
    public data object Offline : ScopeState()

    /**
     * The device has a network, and the server did not answer.
     *
     * Told apart from [Offline] because the two mean opposite things to whoever is reading about
     * them: one is this device's own situation and ends when they walk back into coverage, and the
     * other is not their doing and will not. Both queue local changes and both retry, so nothing in
     * the library behaves differently — the distinction exists so that an application can say the
     * true one.
     */
    public data object ServerUnreachable : ScopeState()

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
