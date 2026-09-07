package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Notified after a batch has been committed.
 *
 * This is the hook a host builds its event channel on, and it fires **after** the transaction on
 * purpose: a delivery failure must not roll back data the server has already accepted. The worst
 * case of a lost notification is a client that learns about the change on its next poll, which is
 * exactly why the channel is allowed to be unreliable.
 */
public fun interface SyncCommitListener {
    /**
     * Reports a committed batch.
     *
     * Errors thrown here are logged and ignored; the data is already durable.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection that changed.
     * @param seq Sequence of the committed batch.
     */
    public fun onCommitted(
        scope: ScopeId,
        collection: CollectionId,
        seq: BatchSeq,
    )
}
