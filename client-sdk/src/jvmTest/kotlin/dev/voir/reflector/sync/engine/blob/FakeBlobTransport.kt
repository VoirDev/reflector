package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobDownload
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobRegistration
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlin.time.Instant

/**
 * Server and storage the file worker's tests drive by hand.
 *
 * Both halves are here on purpose. The worker's whole job is the order of three calls and what it
 * does when one of them fails, and none of that is reproducible against a real server or a real
 * bucket.
 *
 * @property registered Files the client registered, in order.
 * @property uploaded Files whose bytes were sent.
 * @property completed Files the client claimed to have finished sending.
 * @property downloaded Files whose bytes were fetched.
 * @property ticketed Files a download ticket was asked for, in order. A transfer itself carries a
 *   ticket rather than an identifier, so this is where a test learns which files this device tried
 *   to fetch — and an empty list is what "nobody asked for it, so nothing went looking" looks like.
 */
class FakeBlobTransport : BlobTransport {
    val registered: MutableList<BlobId> = mutableListOf()
    val uploaded: MutableList<BlobId> = mutableListOf()
    val completed: MutableList<BlobId> = mutableListOf()
    val downloaded: MutableList<BlobId> = mutableListOf()
    val ticketed: MutableList<BlobId> = mutableListOf()

    /** Files the server already holds, with what it says about them. */
    val onServer: MutableMap<BlobId, BlobInfo> = mutableMapOf()

    /** Failure to raise from the next call, or `null` to let it succeed. */
    var failWith: SyncTransportFailure? = null

    /**
     * Whether the bytes never get through while registration still does.
     *
     * The two are worth separating because the whole default binding lives between them: a record
     * may be published once the server has *heard of* a file, long before it has the bytes.
     */
    var holdUploads: Boolean = false

    /**
     * Runs after an upload has reported its progress and before it returns.
     *
     * Lets a test hold the transfer open until something the report caused has happened, which is
     * how it fixes an order the worker's coroutines would otherwise leave to chance.
     */
    var afterUploadProgress: suspend () -> Unit = {}

    /** Octets the next download will actually produce, or `null` to produce the declared size. */
    var shortenDownloadTo: Long? = null

    override suspend fun register(
        scope: ScopeId,
        collection: CollectionId,
        descriptor: BlobDescriptor,
    ): BlobRegistration {
        registered += descriptor.blobId
        failWith?.let { throw it }
        val known = onServer[descriptor.blobId]
        if (known != null && known.state == BlobState.READY) {
            return BlobRegistration(known, upload = null)
        }
        val info =
            BlobInfo(
                blobId = descriptor.blobId,
                state = BlobState.PENDING,
                contentType = descriptor.contentType,
                size = descriptor.size,
                checksum = descriptor.checksum,
            )
        onServer[descriptor.blobId] = info
        return BlobRegistration(info, upload = ticket(BlobTicketMethod.PUT))
    }

    override suspend fun complete(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobInfo {
        completed += blobId
        failWith?.let { throw it }
        val info = checkNotNull(onServer[blobId]) { "completing a file nobody registered" }
        return info.copy(state = BlobState.READY).also { onServer[blobId] = it }
    }

    override suspend fun ticket(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobDownload {
        ticketed += blobId
        failWith?.let { throw it }
        val info = onServer[blobId] ?: throw SyncTransportFailure.BlobGone("the server has no such file")
        val permission = if (info.state == BlobState.READY) ticket(BlobTicketMethod.GET) else null
        return BlobDownload(info, permission)
    }

    override suspend fun upload(
        ticket: BlobTicket,
        content: RawSource,
        stat: BlobStat,
        onProgress: (Long) -> Unit,
    ) {
        failWith?.let { throw it }
        content.close()
        if (holdUploads) {
            throw SyncTransportFailure.Unreachable("the bytes are being held back")
        }
        onProgress(stat.size)
        afterUploadProgress()
    }

    override suspend fun download(
        ticket: BlobTicket,
        into: RawSink,
        onProgress: (Long) -> Unit,
    ): Long {
        failWith?.let { throw it }
        into.close()
        val written = shortenDownloadTo ?: DEFAULT_SIZE
        onProgress(written)
        return written
    }

    /** Records that a file's bytes were sent, for a test that scripts the upload itself. */
    fun markUploaded(blobId: BlobId) {
        uploaded += blobId
    }

    /** Records that a file's bytes were fetched, for a test that scripts the download itself. */
    fun markDownloaded(blobId: BlobId) {
        downloaded += blobId
    }

    private fun ticket(method: BlobTicketMethod) =
        BlobTicket(
            method = method,
            url = "https://bucket.test/object?sig=abc",
            headers = emptyMap(),
            expiresAt = Instant.fromEpochMilliseconds(TICKET_EXPIRY),
        )

    private companion object {
        const val TICKET_EXPIRY = 4_000_000_000_000L

        /** What a test's file is, unless it says otherwise. */
        const val DEFAULT_SIZE = 2_418_123L
    }
}
