package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the blob transport puts on the wire.
 *
 * The first test is the one this file exists for. Everything else here is ordinary request
 * plumbing; sending the scope's credentials to a presigned URL would hand a bearer token for the
 * user's entire scope to a storage provider, on every photograph, and nothing downstream would ever
 * notice.
 */
class KtorBlobTransportTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")
    private val blobId = BlobId(Uuid.parse("0199fd1a-0000-7000-8000-0000000000b1"))

    private val requests = mutableListOf<HttpRequestData>()

    private val tokenProvider =
        object : TokenProvider {
            override suspend fun token(): String = "scope-credentials"

            override suspend fun refresh(): Boolean = false
        }

    private fun transport(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorBlobTransport(
            client =
                syncHttpClient(
                    MockEngine { request ->
                        requests += request
                        handler(request)
                    },
                ),
            baseUrl = "https://sync.example.com",
            tokens = tokenProvider,
        )

    private fun ticket(method: BlobTicketMethod) =
        BlobTicket(
            method = method,
            url = "https://bucket.example/obj?sig=abc",
            headers = mapOf("Content-Type" to "image/jpeg"),
            expiresAt = Instant.fromEpochMilliseconds(4_000_000_000_000),
        )

    @Test
    fun `a presigned request carries the ticket's headers and none of this library's credentials`() =
        runTest {
            val transport = transport { respond("", HttpStatusCode.OK) }

            transport.upload(ticket(BlobTicketMethod.PUT), Buffer().apply { write(BYTES) }, stat())

            val request = requests.single()
            assertEquals("https://bucket.example/obj?sig=abc", request.url.toString())
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("image/jpeg", request.headers["Content-Type"])
            // The destination is the host's object storage, not the synchronisation server. A token
            // for the user's whole scope has no business arriving there.
            assertNull(request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a request to the synchronisation server does carry them`() =
        runTest {
            val transport =
                transport {
                    respond(
                        """{"blob":{"blobId":"${blobId.value}","state":"pending","contentType":"image/jpeg",""" +
                            """"size":4,"checksum":null},"upload":null}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            transport.register(
                scope,
                collection,
                BlobDescriptor(blobId, BlobContentType("image/jpeg"), size = 4),
            )

            assertEquals("Bearer scope-credentials", requests.single().headers[HttpHeaders.Authorization])
            assertTrue(
                requests
                    .single()
                    .url
                    .toString()
                    .endsWith("/v1/sync/user-1/ledger/blobs"),
            )
        }

    @Test
    fun `a download writes what arrived and says how much it was`() =
        runTest {
            val transport =
                transport { respond(ByteReadChannel(BYTES), HttpStatusCode.OK) }
            val sink = Buffer()

            val written = transport.download(ticket(BlobTicketMethod.GET), sink)

            assertEquals(BYTES.size.toLong(), written)
            assertContentEquals(BYTES, sink.readByteArray())
        }

    @Test
    fun `a transfer reports progress as it moves`() =
        runTest {
            val transport = transport { respond(ByteReadChannel(BYTES), HttpStatusCode.OK) }
            val seen = mutableListOf<Long>()

            transport.download(ticket(BlobTicketMethod.GET), Buffer()) { seen += it }

            assertEquals(listOf(BYTES.size.toLong()), seen)
        }

    @Test
    fun `a storage that refuses the ticket is a transport failure the engine can retry`() =
        runTest {
            val transport = transport { respondError(HttpStatusCode.InternalServerError) }

            // The ticket expired, or the bucket is unwell. Either way it is the engine's to retry
            // with backoff rather than a defect to crash the worker with.
            assertFailsWith<SyncTransportFailure> {
                transport.upload(ticket(BlobTicketMethod.PUT), Buffer().apply { write(BYTES) }, stat())
            }
        }

    private fun stat() = BlobStat(size = BYTES.size.toLong(), contentType = BlobContentType("image/jpeg"))

    private companion object {
        val BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
