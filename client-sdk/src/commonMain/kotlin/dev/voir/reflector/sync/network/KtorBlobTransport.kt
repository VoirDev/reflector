package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobDownload
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobRegistration
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.coroutines.cancellation.CancellationException

/**
 * [BlobTransport] over HTTP.
 *
 * Two kinds of request live here and the difference between them is a security boundary rather than
 * a detail. [register], [complete] and [ticket] go to the synchronisation server and carry the
 * scope's credentials. [upload] and [download] go wherever a presigned ticket points — the host's
 * object storage, a different server entirely — and carry **only** the headers the ticket names.
 *
 * Sending this library's `Authorization` header to a presigned URL would hand a bearer token for the
 * user's whole scope to a storage provider that has no business holding one, and would keep doing it
 * on every photograph. The ticket path therefore never goes through [AuthorizedRequests], which is
 * the only thing that attaches credentials, and builds its request from nothing.
 *
 * Both transfers stream. A file is exactly the thing that does not fit in memory, and buffering one
 * to hand it to a request is how a device with a large video runs out of it.
 *
 * @param client HTTP client, configured by [syncHttpClient].
 * @param baseUrl Root the endpoints are resolved against, for example `https://api.example.com`.
 * @param tokens Application's source of credentials, for the server calls only.
 * @param log Sink told when a request fails and when credentials are renewed.
 */
public class KtorBlobTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val tokens: TokenProvider,
    private val log: SyncLog = SyncLog.None,
) : BlobTransport {
    private val logger = SyncLogger(log)
    private val requests = AuthorizedRequests(tokens, logger, ::interpret)

    /**
     * Reads the two statuses that mean something different here than anywhere else.
     *
     * `404` is a file the server does not have, and `409` is a file it refuses — an identifier that
     * already names different bytes, or a claim that an upload finished which the storage did not
     * bear out. On every other path of this protocol `409` means a purged collection, and letting a
     * failed upload through that mapping would wipe the collection it belongs to.
     *
     * @param response Answer from the server.
     * @return Failure to raise, or `null` to let the shared mapping decide.
     */
    private fun interpret(response: HttpResponse): SyncTransportFailure? =
        when (response.status) {
            HttpStatusCode.NotFound -> SyncTransportFailure.BlobGone("the server has no such file")
            HttpStatusCode.Conflict -> SyncTransportFailure.BlobRefused("the server refused the file")
            else -> null
        }

    override suspend fun register(
        scope: ScopeId,
        collection: CollectionId,
        descriptor: BlobDescriptor,
    ): BlobRegistration =
        requests
            .send { token ->
                client.post(baseUrl) {
                    url { appendPathSegments(API, "sync", scope.value, collection.value, "blobs") }
                    with(requests) { authorize(token) }
                    contentType(ContentType.Application.Json)
                    setBody(descriptor)
                }
            }.body()

    override suspend fun complete(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobInfo =
        requests
            .send { token ->
                client.post(baseUrl) {
                    url {
                        appendPathSegments(
                            API,
                            "sync",
                            scope.value,
                            collection.value,
                            "blobs",
                            blobId.value.toString(),
                            "complete",
                        )
                    }
                    with(requests) { authorize(token) }
                }
            }.body()

    override suspend fun ticket(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobDownload =
        requests
            .send { token ->
                client.get(baseUrl) {
                    url {
                        appendPathSegments(
                            API,
                            "sync",
                            scope.value,
                            collection.value,
                            "blobs",
                            blobId.value.toString(),
                        )
                    }
                    with(requests) { authorize(token) }
                }
            }.body()

    override suspend fun upload(
        ticket: BlobTicket,
        content: RawSource,
        stat: BlobStat,
        onProgress: (Long) -> Unit,
    ) {
        transfer {
            content.use { source ->
                client
                    .prepareRequest(ticket.url) {
                        method = ticket.method.toHttpMethod()
                        // Exactly what the ticket names and nothing else. In particular no credential of
                        // this library's: the destination is not the synchronisation server.
                        ticketHeaders(ticket)
                        setBody(
                            object : OutgoingContent.WriteChannelContent() {
                                override val contentLength: Long = stat.size

                                override suspend fun writeTo(channel: ByteWriteChannel) {
                                    copy(source, channel, onProgress)
                                }
                            },
                        )
                    }.execute { response ->
                        ensureStored(response, "upload")
                    }
            }
        }
    }

    override suspend fun download(
        ticket: BlobTicket,
        into: RawSink,
        onProgress: (Long) -> Unit,
    ): Long =
        transfer {
            into.use { sink ->
                client
                    .prepareRequest(ticket.url) {
                        method = ticket.method.toHttpMethod()
                        ticketHeaders(ticket)
                    }.execute { response ->
                        ensureStored(response, "download")
                        copy(response.bodyAsChannel(), sink, onProgress)
                    }
            }
        }

    /**
     * Checks the storage's answer to a presigned request, in the storage's own terms.
     *
     * The synchronisation server's status mapping does not apply here, and used to be applied: a
     * bucket's `403` is a refused signature or a missing permission on the host's side, not a
     * revoked scope, and reporting it as one sent every diagnosis of a misconfigured bucket in the
     * wrong direction. Only `404` keeps a meaning of its own — the object is not there — and only
     * for a download.
     *
     * What is reported is the status and the S3 error code (`SignatureDoesNotMatch`,
     * `AccessDenied`, `RequestTimeTooSkewed`…), because that code is the whole of the diagnosis.
     * Nothing else from the body is: an S3 error document quotes the canonical request and the
     * string that was signed, which carry the object's key and therefore the scope.
     *
     * @param response Answer from the storage.
     * @param operation What was attempted, for the message.
     * @throws SyncTransportFailure When the storage did not accept the request.
     */
    private suspend fun ensureStored(
        response: HttpResponse,
        operation: String,
    ) {
        if (response.status.isSuccess()) {
            return
        }
        val status = response.status.value
        val code = storageErrorCode(response)
        val message = "the storage refused the $operation: $status ${code ?: response.status.description}"
        if (operation == "download" && response.status == HttpStatusCode.NotFound) {
            throw SyncTransportFailure.BlobGone(message)
        }
        throw SyncTransportFailure.ServerError(status, message)
    }

    /**
     * Reads the error code out of an S3-style error document, if the storage sent one.
     *
     * Bounded, because the body belongs to whoever answered — a proxy's HTML page is as likely as an
     * S3 document — and restricted to the characters an S3 code is made of, so that nothing else from
     * the body can reach a log through it.
     *
     * @param response Answer that was not a success.
     * @return The code, or `null` when the body carries none.
     */
    private suspend fun storageErrorCode(response: HttpResponse): String? {
        val body =
            runCatching { response.bodyAsChannel().readRemaining(ERROR_BODY_LIMIT).readString() }
                .getOrNull() ?: return null
        return STORAGE_ERROR_CODE.find(body)?.groupValues?.get(1)
    }

    /**
     * Runs a transfer, reporting anything that stopped it as a transport failure.
     *
     * A transfer fails in more ways than a request does — a closed connection halfway, a disk that
     * filled, a file that vanished under the reader — and every one of them is something the engine
     * retries with backoff rather than a defect. A failure already in the engine's terms is left
     * alone, so a refused ticket stays a refused ticket instead of becoming a generic outage.
     *
     * @param block Transfer to run.
     * @return Whatever the transfer produced.
     */
    private suspend fun <R> transfer(block: suspend () -> R): R =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SyncTransportFailure) {
            throw failure
        } catch (failure: Exception) {
            throw SyncTransportFailure.Unreachable(failure.message ?: "the transfer did not complete", failure)
        }

    /**
     * Streams a source into a channel, in pieces, reporting how much has moved.
     *
     * @param source Bytes to send.
     * @param channel Where to send them.
     * @param onProgress Told the running total.
     */
    private suspend fun copy(
        source: RawSource,
        channel: ByteWriteChannel,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.readAtMostTo(buffer, CHUNK)
            if (read <= 0L) {
                break
            }
            channel.writeFully(buffer.readByteArray())
            total += read
            onProgress(total)
        }
    }

    /**
     * Streams a channel into a sink, in pieces, reporting how much has moved.
     *
     * @param channel Bytes arriving.
     * @param sink Where to put them.
     * @param onProgress Told the running total.
     * @return Octets written.
     */
    private suspend fun copy(
        channel: io.ktor.utils.io.ByteReadChannel,
        sink: RawSink,
        onProgress: (Long) -> Unit,
    ): Long {
        val chunk = ByteArray(CHUNK.toInt())
        var total = 0L
        while (true) {
            val read = channel.readAvailable(chunk)
            if (read <= 0) {
                break
            }
            val buffer = Buffer()
            buffer.write(chunk, 0, read)
            sink.write(buffer, read.toLong())
            total += read
            onProgress(total)
        }
        sink.flush()
        return total
    }

    /**
     * Sets the headers a ticket names, each exactly once.
     *
     * The body headers are respelled the way Ktor spells them. Every engine merges a request's
     * headers with its body's and writes `Content-Type` and `Content-Length` itself, skipping the
     * request's own copies only when the name matches its constant exactly. A signer that names the
     * header in lower case — the AWS SDK does — therefore had it sent twice, and S3 joins duplicates
     * into `image/jpeg,image/jpeg` before checking a signature computed over one value: every
     * upload was refused with `SignatureDoesNotMatch`. The value is left untouched; it is signed.
     *
     * @param ticket Ticket whose headers to send.
     */
    private fun HttpRequestBuilder.ticketHeaders(ticket: BlobTicket) {
        ticket.headers.forEach { (name, value) ->
            val spelling = ENGINE_HEADERS.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
            header(spelling, value)
        }
    }

    private fun BlobTicketMethod.toHttpMethod(): HttpMethod =
        when (this) {
            BlobTicketMethod.GET -> HttpMethod.Get
            BlobTicketMethod.PUT -> HttpMethod.Put
            BlobTicketMethod.POST -> HttpMethod.Post
        }

    private companion object {
        /** Version segment of the protocol's endpoints. */
        const val API = "v1"

        /** Large enough to keep the syscalls cheap, small enough that a phone does not feel it. */
        const val CHUNK = 64L * 1024

        /** More than any S3 error document needs; the rest of a refusal's body is never read. */
        const val ERROR_BODY_LIMIT = 4L * 1024

        /** Headers the engine writes from the body, recognised only in exactly this spelling. */
        val ENGINE_HEADERS = listOf(HttpHeaders.ContentType, HttpHeaders.ContentLength)

        /** An S3 error code, and only the characters such a code is made of. */
        val STORAGE_ERROR_CODE = Regex("<Code>([A-Za-z0-9.]{1,64})</Code>")
    }
}
