package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.blob.BlobFailure
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.retry.BackoffPolicy
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.blob.BlobRecord
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.protocol.config.BlobLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Moves the bytes, beside the cycle rather than inside it.
 *
 * A transfer is long and a cycle has to stay answerable, so files get a coroutine of their own. It
 * keeps nothing in memory that the database does not have: attempts, backoff and how far a transfer
 * got are all columns, so a process killed mid-upload resumes from the row on its next run instead
 * of starting the queue again.
 *
 * Uploads are driven by the reference rather than by the push queue. A deferred attachment uploads
 * while its record is already on the server and its entity long since clean — which is the whole
 * point of the default binding, and the reason this cannot be work the push does on its way past.
 *
 * @property scope Scope being synchronised.
 * @property collection Collection being synchronised.
 * @property stores Storage of the library.
 * @property transactions Transaction boundary of the application's database.
 * @property transport Connection to the server and to the storage its tickets point at.
 * @property blobStore Application's own file store.
 * @property limits Limits on files published by the server.
 * @property metrics Sink for what each transfer cost and what was given up on.
 * @property log Sink for what was transferred and what failed, already bound to this collection.
 * @property coroutineScope Scope the loop runs in; cancelling it stops the worker.
 * @property clock Source of local time, for backoff only.
 * @property onProgressed Told when a file has got far enough for a record to follow it — registered,
 *   which is what a deferred reference waits for, and accepted, which is what a required one waits
 *   for. Nothing else would tell the push queue: it has no reason of its own to look again, so
 *   without this a record would sit until the next timer, which on a device nobody touches is
 *   however long that is.
 * @property backoff Delay policy for transfers that failed.
 * @property maxAttempts Transfers tried before a file is given up on.
 */
internal class BlobWorker(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: BlobTransport,
    private val blobStore: BlobStore,
    private val limits: BlobLimits,
    private val metrics: SyncMetrics,
    private val log: SyncLogger,
    private val coroutineScope: CoroutineScope,
    private val clock: Clock,
    private val onProgressed: () -> Unit = {},
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** Starts the loop. Cancelling the coroutine scope stops it. */
    fun start() {
        coroutineScope.launch {
            for (request in requests) {
                runCatching { drain() }
                    .onFailure { failure ->
                        log.error(SyncLogEvent.BLOB_TRANSFER_FAILED, failure) {
                            "the file worker threw; what it had already moved stands and the rest is retried"
                        }
                    }
            }
        }
    }

    /** Asks the worker to move what it can as soon as it can. */
    fun requestTransfers() {
        requests.trySend(Unit)
    }

    /**
     * Sends and fetches until nothing is ready to move.
     *
     * Uploads go first and are drained before downloads are looked at. A record waiting on a file it
     * cannot be published without is the only thing in this library that blocks a queue on bytes, so
     * whatever might be holding one is moved before anything that is merely somebody's photograph
     * arriving.
     */
    private suspend fun drain() {
        while (true) {
            val moved = uploadBatch() || downloadBatch()
            if (!moved) {
                return
            }
        }
    }

    private suspend fun uploadBatch(): Boolean {
        val waiting = waiting(BlobTransferState.LOCAL) + waiting(BlobTransferState.UPLOADING)
        if (waiting.isEmpty()) {
            return false
        }
        waiting.take(CONCURRENCY).forEach { record -> upload(record) }
        return true
    }

    private suspend fun downloadBatch(): Boolean {
        val waiting = waiting(BlobTransferState.REMOTE) + waiting(BlobTransferState.DOWNLOADING)
        if (waiting.isEmpty()) {
            return false
        }
        waiting.take(CONCURRENCY).forEach { record -> download(record) }
        return true
    }

    private suspend fun waiting(state: BlobTransferState): List<BlobRecord> =
        transactions.transaction {
            stores.blobs.waiting(scope, collection, state, clock.now().toEpochMilliseconds(), CONCURRENCY)
        }

    /**
     * Sends one file, from wherever the last attempt left it.
     *
     * A row left `UPLOADING` by a process that died is not a special case: registering is idempotent
     * by identifier, so asking again simply produces a fresh ticket for the same object, and the
     * bytes go out a second time. That is the whole of the crash recovery, and it is why no state
     * beyond the row is kept.
     *
     * @param record File to send.
     */
    private suspend fun upload(record: BlobRecord) {
        val stat = blobStore.statOrNull(record.blobId)
        if (stat == null) {
            // The application's own file has gone. Nothing can recover it, and only the application
            // can decide whether to attach another or drop the reference.
            giveUp(record.blobId, BlobFailure.SourceMissing("the application no longer holds the bytes"))
            return
        }
        if (stat.size > limits.maxBlobBytes) {
            giveUp(
                record.blobId,
                BlobFailure.TooLarge(
                    size = stat.size,
                    limit = limits.maxBlobBytes,
                    message = "the file is larger than this server accepts",
                ),
            )
            return
        }

        val startedAt = clock.now()
        try {
            // Registered before the row says it is being uploaded, and that order is load-bearing:
            // the push reads the state to decide whether the server has heard of this file at all,
            // and a record naming one it has not is refused outright. A crash in between leaves the
            // row LOCAL and the registration is simply made again, which is idempotent by identifier.
            val registration =
                transport.register(
                    scope,
                    collection,
                    BlobDescriptor(record.blobId, stat.contentType, stat.size, stat.checksum),
                )
            transactions.transaction {
                stores.blobs.setDeclaration(scope, collection, record.blobId, stat)
                stores.blobs.setState(scope, collection, record.blobId, BlobTransferState.UPLOADING)
            }
            // The server has heard of the file now, which is all a deferred record was waiting for.
            // Its bytes may still take minutes, and the record should not wait them out.
            onProgressed()
            val ticket = registration.upload
            if (ticket != null) {
                transport.upload(ticket, blobStore.read(record.blobId), stat) { moved ->
                    // Best effort and deliberately not awaited: progress is for a spinner, and a
                    // transfer must not be slowed by the reporting of it.
                    coroutineScope.launch {
                        transactions.transaction {
                            stores.blobs.setTransferred(scope, collection, record.blobId, moved)
                        }
                    }
                }
                transport.complete(scope, collection, record.blobId)
            }
            transactions.transaction {
                stores.blobs.setState(scope, collection, record.blobId, BlobTransferState.UPLOADED)
            }
            log.debug(
                SyncLogEvent.BLOB_UPLOADED,
                context = { mapOf("blob" to record.blobId.value.toString(), "bytes" to stat.size.toString()) },
            ) { "the file is on the server; a record waiting for it can go out now" }
            metrics.emit(
                SyncMetricEvent.BlobTransferred(scope, collection, stat.size, clock.now() - startedAt, outgoing = true),
                log,
            )
            onProgressed()
        } catch (failure: SyncTransportFailure) {
            retryOrGiveUp(record, failure)
        }
    }

    /**
     * Fetches one file, if the server has its bytes yet.
     *
     * A file the server holds as still uploading is **not** a failure and is the ordinary state of
     * an attachment whose record arrived ahead of it. It is answered by waiting, either until the
     * backoff expires or until the scope's channel says the file became usable.
     *
     * @param record File to fetch.
     */
    private suspend fun download(record: BlobRecord) {
        val startedAt = clock.now()
        try {
            val answer = transport.ticket(scope, collection, record.blobId)
            val ticket = answer.download
            if (answer.blob.state != BlobState.READY || ticket == null) {
                log.debug(
                    SyncLogEvent.BLOB_NOT_READY,
                    context = { mapOf("blob" to record.blobId.value.toString()) },
                ) { "the file is registered and still arriving at the server; this device waits for it" }
                schedule(record, "the server does not have the bytes yet")
                return
            }

            val stat = BlobStat(answer.blob.size, answer.blob.contentType, answer.blob.checksum)
            transactions.transaction {
                stores.blobs.setDeclaration(scope, collection, record.blobId, stat)
                stores.blobs.setState(scope, collection, record.blobId, BlobTransferState.DOWNLOADING)
            }
            val written =
                transport.download(ticket, blobStore.write(record.blobId, stat)) { moved ->
                    coroutineScope.launch {
                        transactions.transaction {
                            stores.blobs.setTransferred(scope, collection, record.blobId, moved)
                        }
                    }
                }
            val complete = written == stat.size
            // Told before anything else, so that a partial file is discarded rather than left where
            // the application's own code will find it and believe it.
            blobStore.finish(record.blobId, complete)
            if (!complete) {
                schedule(record, "the transfer ended after $written of ${stat.size} octets")
                return
            }
            transactions.transaction {
                stores.blobs.setState(scope, collection, record.blobId, BlobTransferState.READY)
            }
            log.debug(
                SyncLogEvent.BLOB_DOWNLOADED,
                context = { mapOf("blob" to record.blobId.value.toString(), "bytes" to written.toString()) },
            ) { "the file arrived and the application has it" }
            metrics.emit(
                SyncMetricEvent.BlobTransferred(scope, collection, written, clock.now() - startedAt, outgoing = false),
                log,
            )
        } catch (failure: SyncTransportFailure.BlobGone) {
            // Collected while this device was away, or erased with its collection. A device that
            // never had the bytes cannot produce them; only the application can decide what to show.
            giveUp(record.blobId, BlobFailure.Gone(failure.message.orEmpty()))
        } catch (failure: SyncTransportFailure) {
            retryOrGiveUp(record, failure)
        }
    }

    /**
     * Schedules another attempt, or gives up once there have been enough of them.
     *
     * @param record File that failed to move.
     * @param failure What stopped it.
     */
    private suspend fun retryOrGiveUp(
        record: BlobRecord,
        failure: SyncTransportFailure,
    ) {
        if (record.attempts + 1 >= maxAttempts) {
            giveUp(
                record.blobId,
                BlobFailure.Exhausted(record.attempts + 1, failure.message ?: "the transfer kept failing"),
            )
            return
        }
        schedule(record, failure.message ?: "the transfer did not complete")
    }

    private suspend fun schedule(
        record: BlobRecord,
        reason: String,
    ) {
        val delay = backoff.nextDelay(record.attempts + 1)
        transactions.transaction {
            stores.blobs.recordFailure(
                scope = scope,
                collection = collection,
                blobId = record.blobId,
                error = reason,
                nextRetryAt = clock.now().toEpochMilliseconds() + delay.inWholeMilliseconds,
            )
        }
        log.debug(
            SyncLogEvent.BLOB_TRANSFER_FAILED,
            context = {
                mapOf(
                    "blob" to record.blobId.value.toString(),
                    "attempts" to (record.attempts + 1).toString(),
                    "retryInMs" to delay.inWholeMilliseconds.toString(),
                )
            },
        ) { "the transfer did not complete and will be tried again: $reason" }
    }

    /**
     * Stops trying and tells the application, which is the only party that can do anything about it.
     *
     * The reference is left exactly as it is. Dropping it would be the library editing the
     * application's document on its own judgement, and a receipt that failed to upload may well be
     * worth asking a user about rather than silently forgetting.
     *
     * @param blobId File being given up on.
     * @param failure Why.
     */
    private suspend fun giveUp(
        blobId: BlobId,
        failure: BlobFailure,
    ) {
        transactions.transaction {
            stores.blobs.setState(scope, collection, blobId, BlobTransferState.UNAVAILABLE)
        }
        log.warn(
            SyncLogEvent.BLOB_UNAVAILABLE,
            context = { mapOf("blob" to blobId.value.toString(), "reason" to failure::class.simpleName.orEmpty()) },
        ) { "this file will not arrive: ${failure.message}" }
        metrics.emit(
            SyncMetricEvent.BlobUnavailable(scope, collection, failure::class.simpleName.orEmpty()),
            log,
        )
        runCatching { blobStore.onFailed(blobId, failure) }
            .onFailure { thrown ->
                log.error(SyncLogEvent.BLOB_TRANSFER_FAILED, thrown) {
                    "the application threw when told a file will not arrive"
                }
            }
    }

    /**
     * Describes a file, treating a throwing store as a file that is not there.
     *
     * An application whose store cannot answer for a file is saying the same thing as one that
     * answers `null`, and the recovery is identical.
     *
     * @param blobId File to describe.
     * @return What the bytes are, or `null` when this device does not have them.
     */
    private suspend fun BlobStore.statOrNull(blobId: BlobId): BlobStat? = runCatching { stat(blobId) }.getOrNull()

    private companion object {
        /** Transfers running at once. Two keeps a connection busy without monopolising a phone's. */
        const val CONCURRENCY = 2

        /** Attempts before a file is given up on and the application is told. */
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
