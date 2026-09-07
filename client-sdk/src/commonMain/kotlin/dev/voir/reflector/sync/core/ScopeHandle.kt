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
     * Returns the handle of a collection, starting its worker on first access.
     *
     * @param id Collection to synchronise; it must be registered on the server, otherwise every
     *   push to it is rejected.
     * @return Handle of the collection; repeated calls with the same identifier return the same
     *   handle.
     */
    public fun collection(id: CollectionId): CollectionHandle
}
