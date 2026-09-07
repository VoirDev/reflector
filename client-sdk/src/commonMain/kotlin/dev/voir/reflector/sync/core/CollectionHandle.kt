package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.Resolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Handle of one synchronised collection: the unit of consistency, cursor and push queue.
 *
 * Everything the application does with the library goes through a collection handle: local changes
 * are announced inside [mutate], progress is observed through [state], and conflicts the adapter
 * refused to decide are resolved through [resolve].
 */
public interface CollectionHandle {
    /** Progress of the collection: its phase, queue depth, open conflicts and last failure. */
    public val state: StateFlow<CollectionSyncState>

    /**
     * Conflicts waiting for the application to decide.
     *
     * Only conflicts the adapter declined to resolve appear here; the ones it decided are applied
     * without ever reaching this flow.
     */
    public val conflicts: Flow<List<Conflict>>

    /**
     * Runs a block of local changes as one transaction of the application's database.
     *
     * The block is the boundary of atomicity for synchronisation as well: entities marked inside it
     * are guaranteed to reach the server together. That guarantee is what forces the library to
     * merge push groups that share an entity — once two changes were made in one transaction, they
     * can no longer be sent apart.
     *
     * The application writes its own rows inside the block and announces them through
     * [MutationScope]; the library never discovers changes on its own.
     *
     * @param block Changes to perform, with the marking API in scope.
     * @return Whatever the block returns.
     */
    public suspend fun <R> mutate(block: suspend MutationScope.() -> R): R

    /**
     * Asks the workers to synchronise now instead of waiting for the next trigger.
     *
     * The call returns once the request is accepted, not once synchronisation has finished:
     * progress is observed through [state].
     */
    public suspend fun requestSync()

    /**
     * Throws away what is known about the collection and rebuilds it from the server.
     *
     * The library cannot detect when the application's own tables stopped agreeing with what it
     * synchronised — a migration that rewrites rows, a repair after a bug, an import from elsewhere.
     * In those cases a bootstrap is cheaper than trusting a cursor whose data has changed underneath
     * it. Local changes that never reached the server survive: they are pushed afterwards.
     */
    public suspend fun requestResync()

    /**
     * Applies a decision to a conflict that was waiting for the application.
     *
     * The decision is applied in one transaction together with the resulting change of local
     * metadata, so a crash cannot leave a resolved conflict with unresolved bookkeeping.
     *
     * @param conflictId Conflict to resolve; it must still be open.
     * @param resolution Decision to apply.
     * @throws IllegalArgumentException When the conflict is unknown or has already been resolved.
     */
    public suspend fun resolve(
        conflictId: ConflictId,
        resolution: Resolution,
    )
}
