package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.events.SyncEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Delivery of commit notifications to connected sockets.
 *
 * The module notifies after a batch commits; turning that into a message on a socket is the host's
 * job, and so is spreading it between instances — this one is deliberately in-process, because a
 * sample that pulled in a message bus would teach the wrong lesson. A missed notification costs a
 * client latency and nothing else: data always travels over the pull.
 */
class ScopeEvents {
    private val committed = MutableSharedFlow<Committed>(extraBufferCapacity = BUFFER)
    private val revoked = MutableSharedFlow<ScopeId>(extraBufferCapacity = BUFFER)
    private val filesReady = MutableSharedFlow<FileReady>(extraBufferCapacity = BUFFER)

    /**
     * Publishes a committed batch.
     *
     * Called from the module's commit listener, after the transaction. It never suspends and never
     * fails the caller: the data is already durable, and a full buffer only means a client learns
     * about the change on its next poll.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection that changed.
     * @param seq Sequence of the committed batch.
     */
    fun publish(
        scope: ScopeId,
        collection: CollectionId,
        seq: BatchSeq,
    ) {
        committed.tryEmit(Committed(scope, collection, seq))
    }

    /**
     * Announces that a file's bytes can now be fetched.
     *
     * The other half of what makes publishing a record ahead of its file bearable. The record
     * reached the other devices immediately and nothing about the log has changed since, so a pull
     * would not discover that the bytes have landed — without this they would find out whenever
     * their own backoff next happened to fire, which is minutes of a photograph sitting ready.
     *
     * Losing one costs latency and nothing else, exactly like an invalidation.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection the file belongs to.
     * @param blobId File that became usable.
     */
    fun publishBlobReady(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ) {
        filesReady.tryEmit(FileReady(scope, collection, blobId))
    }

    /**
     * Announces that a scope is no longer accessible to whoever is connected to it.
     *
     * The module knows nothing about this and must not: access is the host's model, and the host is
     * the only party that learns the moment somebody is removed from a shared workspace. What the
     * module's protocol offers is the other half — a client that receives this wipes the scope's
     * data instead of keeping it until its next request happens to be refused, which on a device
     * that is not being used may be days.
     *
     * Revoking access itself is a separate act, and it is [ScopeRevocations] that performs both.
     *
     * @param scope Scope that is no longer accessible.
     */
    fun revoke(scope: ScopeId) {
        revoked.tryEmit(scope)
    }

    /**
     * Events a socket for one scope should send.
     *
     * @param scope Scope the socket belongs to.
     * @return Flow of events for that scope only.
     */
    fun events(scope: ScopeId): Flow<SyncEvent> =
        merge(
            committed
                .filter { it.scope == scope }
                .map { SyncEvent.Invalidate(it.collection, it.seq) },
            revoked
                .filter { it == scope }
                .map { SyncEvent.Revoked },
            filesReady
                .filter { it.scope == scope }
                .map { SyncEvent.BlobReady(it.collection, it.blobId) },
        )

    private data class FileReady(
        val scope: ScopeId,
        val collection: CollectionId,
        val blobId: BlobId,
    )

    private data class Committed(
        val scope: ScopeId,
        val collection: CollectionId,
        val seq: BatchSeq,
    )

    private companion object {
        /** Enough to absorb a burst; beyond it a client falls back to polling, which is fine. */
        const val BUFFER = 256
    }
}
