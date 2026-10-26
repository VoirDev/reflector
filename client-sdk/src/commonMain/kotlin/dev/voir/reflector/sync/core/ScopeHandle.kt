package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.protocol.CollectionId
import kotlinx.coroutines.flow.StateFlow

/**
 * Handle of one synchronised scope.
 *
 * A scope groups collections that share an access decision and an event channel. Collections inside
 * it are independent: they have their own cursor, their own queue and their own worker, and no
 * ordering is guaranteed between them.
 */
public interface ScopeHandle {
    /**
     * Connection and access state of the scope.
     *
     * The state is shared by every collection of the scope, because it is decided by the transport
     * and by the server's answer about access, not by any single collection.
     */
    public val state: StateFlow<ScopeState>

    /**
     * What the application has paused in this scope, or `null` while nothing is.
     *
     * Kept apart from [state] on purpose. That one is the library's report of whether the server
     * can be reached and still accepts this user, and it goes on being true while a scope is paused:
     * a scope whose uploads are paused still reads from the server, and can still lose its network,
     * its credentials or its access — all of which the application has to keep seeing.
     */
    public val paused: StateFlow<SyncPause?>

    /**
     * Pauses some or all of the scope's synchronisation until [resume] is called.
     *
     * Takes effect between requests rather than during one: a push or a transfer already on its
     * way is let finish and its answer applied as usual, and nothing after it starts. Calling it
     * again replaces the earlier pause, in either direction.
     *
     * A pause lives in memory only and a new process starts unpaused. An application that pauses a
     * scope for a reason that outlives the process applies it again on start — between
     * [SyncEngine.scope] opening the scope and [collection] opening the first collection, since opening a collection
     * is what starts its worker and the worker's first cycle.
     *
     * [SyncEngine.signOut], [CollectionHandle.discardLocalChanges], resolving conflicts and
     * recording local changes are not affected.
     *
     * @param what What to stop.
     */
    public fun pause(what: SyncPause = SyncPause.ALL)

    /**
     * Lifts the pause and starts what it held back at once.
     *
     * Work that was serving a backoff when the pause began is made due now rather than left to
     * wait the rest of it out — the attempts it has used are kept. A pause is commonly the
     * application's answer to the very refusals that produced the backoff, so lifting it means
     * those conditions have changed. The same holds for a wait the server asked for with
     * `Retry-After`. Does nothing when the scope is not paused.
     */
    public suspend fun resume()

    /**
     * Returns the handle of a collection, starting its worker on first access.
     *
     * @param id Collection to synchronise; it must be registered on the server, otherwise every
     *   push to it is rejected.
     * @return Handle of the collection; repeated calls with the same identifier return the same
     *   handle.
     */
    public fun collection(id: CollectionId): CollectionHandle
}
