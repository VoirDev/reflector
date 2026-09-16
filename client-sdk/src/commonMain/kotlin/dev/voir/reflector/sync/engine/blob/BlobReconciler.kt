package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Works out what each referenced file means for this device, and what to do with what is left.
 *
 * The whole of it is four lines of arithmetic over two tables:
 *
 * ```
 * referenced, bytes here, server does not know it        → upload
 * referenced, bytes absent, wanted here                  → download
 * referenced, bytes absent, declared eager by a document  → want it, then download
 * referenced, bytes absent, wanted, server knows nothing  → the application made a mistake
 * unreferenced, bytes here                               → offer the application its file back
 * ```
 *
 * The middle lines are where [dev.voir.reflector.sync.core.blob.BlobFetch] lives. A file is wanted
 * because a document declares it eagerly or because the application asked for it, and a file that
 * is neither is not work: the reference is known, the row says the server has bytes this device
 * does not, and nothing moves until somebody opens the record that needs it.
 *
 * It runs **between** transactions, never inside one, because every line of it asks the application
 * about its own file store and that is not a question to ask with a write lock held. The price is
 * that a reference exists for a moment before anything is known about the file it names, which is
 * why [BlobReferences] writes the reference and stops there.
 *
 * @property scope Scope being synchronised.
 * @property collection Collection being synchronised.
 * @property stores Storage of the library.
 * @property transactions Transaction boundary of the application's database.
 * @property blobStore Application's own file store.
 * @property log Sink for what was decided, already bound to this collection.
 */
internal class BlobReconciler(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val blobStore: BlobStore,
    private val log: SyncLogger,
) {
    /**
     * Brings what is known about files into line with what documents point at.
     *
     * Safe to run at any time and as often as the worker likes: everything it does is derived from
     * the two tables rather than from anything it remembers, so an interrupted run leaves work for
     * the next one and repeats nothing.
     */
    suspend fun reconcile() {
        adopt()
        promote()
        release()
    }

    /**
     * Looks into the files that references have named and nothing has described yet.
     *
     * A file whose bytes are on this device was created by the application and is waiting to be
     * sent; one whose bytes are not is on the server and is waiting to be fetched. That single
     * question is the only thing separating an upload from a download, and only the application can
     * answer it.
     */
    private suspend fun adopt() {
        val unknown = transactions.transaction { stores.blobRefs.unknown(scope, collection) }
        for (blobId in unknown) {
            // Outside the transaction: this reaches the application's own storage, which may be a
            // file system, a content provider or something slower still.
            val stat = runCatching { blobStore.stat(blobId) }.getOrNull()
            val state = if (stat == null) BlobTransferState.REMOTE else BlobTransferState.LOCAL
            transactions.transaction {
                // Checked again inside the transaction: another cycle may have adopted it while the
                // application was being asked, and the answer would then be overwritten with a
                // state that has since moved on.
                if (stores.blobs.find(scope, collection, blobId) == null) {
                    // A file whose bytes are here is wanted by construction — they are already
                    // holding the space, and the only thing left to do with them is send them. One
                    // whose bytes are absent is adopted unwanted whatever the declaration says, and
                    // `promote` reads the declaration a moment later: that keeps the two questions
                    // in the two places that own them and makes the promotion one statement rather
                    // than one query per file.
                    stores.blobs.put(scope, collection, blobId, state, stat, wanted = stat != null)
                }
            }
            log.debug(
                SyncLogEvent.BLOB_ADOPTED,
                context = { mapOf("blob" to blobId.value.toString(), "state" to state.name) },
            ) {
                when (state) {
                    BlobTransferState.LOCAL -> "a document names a file this device holds; it is waiting to be sent"
                    else -> "a document names a file this device does not hold; it is waiting to be fetched"
                }
            }
        }
    }

    /**
     * Turns what documents declare eagerly into an intention to hold it.
     *
     * Separate from [adopt] because it is not about newly discovered files: a document edited into
     * naming a file eagerly, a second document naming one the first declared on demand, and a file
     * evicted although a declaration says this device keeps it all arrive after the file was
     * adopted. Running it on every pass is one statement and settles all three.
     */
    private suspend fun promote() {
        transactions.transaction { stores.blobs.wantEagerlyReferenced(scope, collection) }
    }

    /**
     * Offers back the files no document points at any more.
     *
     * An offer rather than a deletion. The library will not remove a user's bytes on a judgement of
     * its own — an application keeping the file for an undo stack is correct and need do nothing —
     * so what happens here is that the application is told, and the library then stops tracking it.
     *
     * The row goes only once the application has been told without throwing. Dropping it first would
     * mean an application that failed to hear is never told again, and its file is then untracked by
     * both sides at once.
     */
    private suspend fun release() {
        val unreferenced = transactions.transaction { stores.blobs.unreferenced(scope, collection) }
        for (record in unreferenced) {
            val told =
                runCatching { blobStore.remove(record.blobId) }
                    .onFailure { failure ->
                        log.warn(
                            SyncLogEvent.BLOB_RELEASE_FAILED,
                            failure,
                            context = { mapOf("blob" to record.blobId.value.toString()) },
                        ) { "the application could not be told about a file nothing references; it stays tracked" }
                    }.isSuccess
            if (told) {
                transactions.transaction { stores.blobs.delete(scope, collection, record.blobId) }
                log.debug(
                    SyncLogEvent.BLOB_RELEASED,
                    context = { mapOf("blob" to record.blobId.value.toString()) },
                ) { "no document names this file any more, so the application was offered it back" }
            }
        }
    }

    /**
     * Records that the application wants a file's bytes on this device.
     *
     * What [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] is waiting for. It writes the
     * wish and nothing more: the transfer belongs to [BlobWorker], which is woken by the caller,
     * and the wish is durable so a process that dies mid-download resumes instead of waiting to be
     * asked again.
     *
     * Three things it deliberately does not do. It does not fetch a file no document references —
     * there would be nothing to keep it for, and [release] would offer it straight back. It does
     * not shorten a backoff that is still running, because a screen redrawing itself is not new
     * information about a failing transfer. And it does not touch a file whose bytes are already
     * here beyond marking them wanted, since there is nothing to fetch.
     *
     * What it does do is restart a file the library has given up on, which is the call behind a
     * retry button: the state goes back to [BlobTransferState.REMOTE] with no attempts and no
     * error, and the worker treats it as new work.
     *
     * @param blobId File the application wants.
     */
    suspend fun request(blobId: BlobId) {
        if (!transactions.transaction { stores.blobRefs.isReferenced(scope, collection, blobId) }) {
            log.warn(
                SyncLogEvent.BLOB_REQUESTED,
                context = { mapOf("blob" to blobId.value.toString()) },
            ) { "a file was asked for that no document of this collection names; nothing will be fetched" }
            return
        }
        // Outside the transaction, as everywhere else this asks the application about its own store.
        val stat = runCatching { blobStore.stat(blobId) }.getOrNull()
        transactions.transaction {
            val record = stores.blobs.find(scope, collection, blobId)
            when {
                record == null -> {
                    stores.blobs.put(
                        scope,
                        collection,
                        blobId,
                        if (stat == null) BlobTransferState.REMOTE else BlobTransferState.LOCAL,
                        stat,
                        wanted = true,
                    )
                }

                // Given up on, and the bytes are not here: the user asking again is the only thing
                // that can restart it, so the attempts and the error go with the state.
                record.state == BlobTransferState.UNAVAILABLE && stat == null -> {
                    stores.blobs.setState(scope, collection, blobId, BlobTransferState.REMOTE)
                    stores.blobs.setWanted(scope, collection, blobId, wanted = true)
                }

                else -> {
                    stores.blobs.setWanted(scope, collection, blobId, wanted = true)
                }
            }
        }
        log.debug(
            SyncLogEvent.BLOB_REQUESTED,
            context = { mapOf("blob" to blobId.value.toString()) },
        ) { "the application asked for a file; this device wants its bytes now" }
    }

    /**
     * Gives up a file's bytes on this device, leaving the file on the server.
     *
     * The other half of fetching on demand: a device that declines to hold everything eventually
     * wants back what it did hold. The application's store is told first and the row moves only if
     * it did not throw — the same order as [release], and for the same reason: a row that says the
     * bytes are gone while they are still there is how a device stops accounting for a file nobody
     * else is accounting for either.
     *
     * Refused for anything but a file the server has and this device has as well. Bytes that exist
     * only here are not a cache, they are the user's file waiting to be uploaded, and discarding
     * them would be losing data rather than freeing space.
     *
     * @param blobId File to give up.
     */
    suspend fun evict(blobId: BlobId) {
        val record = transactions.transaction { stores.blobs.find(scope, collection, blobId) } ?: return
        if (record.state != BlobTransferState.READY && record.state != BlobTransferState.UPLOADED) {
            log.warn(
                SyncLogEvent.BLOB_EVICTED,
                context = { mapOf("blob" to blobId.value.toString(), "state" to record.state.name) },
            ) { "a file was asked to be given up that the server does not have yet; its bytes stay on this device" }
            return
        }
        val told =
            runCatching { blobStore.remove(blobId) }
                .onFailure { failure ->
                    log.warn(
                        SyncLogEvent.BLOB_RELEASE_FAILED,
                        failure,
                        context = { mapOf("blob" to blobId.value.toString()) },
                    ) { "the application could not give up a file's bytes; the library keeps saying it has them" }
                }.isSuccess
        if (!told) {
            return
        }
        transactions.transaction {
            stores.blobs.setState(scope, collection, blobId, BlobTransferState.REMOTE)
            stores.blobs.setWanted(scope, collection, blobId, wanted = false)
        }
        log.debug(
            SyncLogEvent.BLOB_EVICTED,
            context = { mapOf("blob" to blobId.value.toString()) },
        ) { "the file's bytes were given up here; the server still has it and it can be fetched again" }
    }

    /**
     * Forgets every file of the collection, offering each back to the application first.
     *
     * For the erasures — signing out, a revoked scope, a collection that was purged — where this is
     * not eviction but the same act as wiping the rows. Leaving a signed-out user's photographs on a
     * shared device is not a policy question, so here the offer is made for everything rather than
     * only for what nothing references.
     */
    suspend fun erase() {
        val all = transactions.transaction { stores.blobs.all(scope, collection) }
        for (record in all) {
            runCatching { blobStore.remove(record.blobId) }
                .onFailure { failure ->
                    log.warn(
                        SyncLogEvent.BLOB_RELEASE_FAILED,
                        failure,
                        context = { mapOf("blob" to record.blobId.value.toString()) },
                    ) { "the application could not be told to remove a file of an erased collection" }
                }
        }
        transactions.transaction {
            stores.blobs.deleteAll(scope, collection)
            stores.blobRefs.deleteAll(scope, collection)
        }
    }
}
