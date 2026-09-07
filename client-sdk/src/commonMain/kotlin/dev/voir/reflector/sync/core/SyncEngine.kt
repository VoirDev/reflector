package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Entry point of the synchronisation library.
 *
 * The engine owns the background work of every scope it hands out: workers, transport and retry
 * schedules live here, not in the handles. One engine instance is expected per application.
 *
 * Only one scope is active at a time. Signing out wipes the local data of the active scope, because
 * the library has no way to tell which rows of the application's tables belong to whom once a
 * different user signs in.
 */
public interface SyncEngine {
    /**
     * Returns the handle of a scope, starting its workers on first access.
     *
     * The scope is expected to be already authorised by the application: the library never decides
     * whether the current user may read it, it only synchronises what the server agrees to serve.
     *
     * @param scopeId Scope to synchronise.
     * @return Handle of the scope; repeated calls with the same identifier return the same handle.
     */
    public fun scope(scopeId: ScopeId): ScopeHandle

    /**
     * Stops synchronisation and wipes the local data of the active scope.
     *
     * Pending local changes are a policy decision of the application rather than of the library:
     * [CollectionHandle.state] exposes how many are queued, so the caller can warn the user or
     * wait for them to drain before signing out.
     *
     * @param discardPending Whether to drop queued local changes instead of refusing to sign out
     *   while any are left. When `false` and the queue is not empty, the call fails and nothing is
     *   wiped.
     * @throws IllegalStateException When changes are still queued and [discardPending] is `false`.
     */
    public suspend fun signOut(discardPending: Boolean)
}
