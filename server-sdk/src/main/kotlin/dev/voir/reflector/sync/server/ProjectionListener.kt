package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Notified inside the transaction that applies a batch.
 *
 * The opposite trade-off from [SyncCommitListener], and for the opposite reason: a host projection
 * that disagrees with the module's data is worse than a refused write, so a failure here rolls the
 * batch back. Whatever runs in it must be a local database write — no remote calls, no work that can
 * hang, because it holds the collection's write lock while it runs.
 */
public fun interface ProjectionListener {
    /**
     * Applies a batch to the host's own projection.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection that changed.
     * @param changes Changes of the batch, in the order they were applied.
     */
    public fun onBatch(
        scope: ScopeId,
        collection: CollectionId,
        changes: List<AppliedChange>,
    )
}
