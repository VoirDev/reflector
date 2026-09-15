package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.push.PushRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class KtorSyncTransportTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    private val requests = mutableListOf<HttpRequestData>()
    private var tokens = mutableListOf<String?>("first")
    private var refreshes = 0

    private val tokenProvider =
        object : TokenProvider {
            override suspend fun token(): String? = tokens.firstOrNull()

            override suspend fun refresh(): Boolean {
                refreshes++
                tokens.removeFirstOrNull()
                return tokens.isNotEmpty()
            }
        }

    private fun transport(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorSyncTransport(
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

    private fun emptyChanges() = """{"batches":[],"nextCursor":null,"hasMore":false,"epoch":"e1"}"""

    private fun json(body: String) = headersOf(HttpHeaders.ContentType, "application/json") to body

    @Test
    fun `a change request carries the cursor and the limit and the credentials`() =
        runTest {
            val (headers, body) = json(emptyChanges())
            val transport = transport { respond(body, HttpStatusCode.OK, headers) }

            transport.changes(scope, collection, Cursor("1184"), limit = 500)

            val request = requests.single()
            assertEquals("/v1/sync/user-1/ledger/changes", request.url.encodedPath)
            assertEquals("1184", request.url.parameters["cursor"])
            assertEquals("500", request.url.parameters["limit"])
            assertEquals("Bearer first", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a push goes to the collection's endpoint`() =
        runTest {
            val (headers, body) = json("""{"results":[],"latestSeq":"1","epoch":"e1"}""")
            val transport = transport { respond(body, HttpStatusCode.OK, headers) }

            transport.push(
                scope,
                collection,
                PushRequest(
                    clientId = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1")),
                    groups = emptyList(),
                ),
            )

            assertEquals("/v1/sync/user-1/ledger/push", requests.single().url.encodedPath)
        }

    @Test
    fun `refused credentials are renewed once and the request repeated`() =
        runTest {
            tokens = mutableListOf("stale", "fresh")
            val (headers, body) = json(emptyChanges())
            val transport =
                transport { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer stale") {
                        respondError(HttpStatusCode.Unauthorized)
                    } else {
                        respond(body, HttpStatusCode.OK, headers)
                    }
                }

            transport.changes(scope, collection, cursor = null, limit = 10)

            assertEquals(2, requests.size, "the refused request has to be repeated, not abandoned")
            assertEquals(1, refreshes, "renewing more than once is indistinguishable from an outage")
            assertEquals("Bearer fresh", requests.last().headers[HttpHeaders.Authorization])
        }

    @Test
    fun `credentials that cannot be renewed stop the transport`() =
        runTest {
            tokens = mutableListOf("stale")
            val transport = transport { respondError(HttpStatusCode.Unauthorized) }

            assertFailsWith<SyncTransportFailure.Unauthorized> {
                transport.changes(scope, collection, cursor = null, limit = 10)
            }
            assertEquals(1, refreshes)
            assertEquals(1, requests.size, "there is nothing to repeat the request with")
        }

    @Test
    fun `a revoked scope is reported as such`() =
        runTest {
            val transport = transport { respondError(HttpStatusCode.Forbidden) }

            assertFailsWith<SyncTransportFailure.Revoked> {
                transport.changes(scope, collection, cursor = null, limit = 10)
            }
        }

    @Test
    fun `a stale cursor is reported as such`() =
        runTest {
            val transport = transport { respondError(HttpStatusCode.Gone) }

            assertFailsWith<SyncTransportFailure.CursorTooOld> {
                transport.changes(scope, collection, cursor = Cursor("1"), limit = 10)
            }
        }

    @Test
    fun `a slow-down answer carries the delay the server asked for`() =
        runTest {
            val transport =
                transport {
                    respond(
                        "",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.RetryAfter, "30"),
                    )
                }

            val failure =
                assertFailsWith<SyncTransportFailure.RateLimited> {
                    transport.changes(scope, collection, cursor = null, limit = 10)
                }

            assertEquals(30.seconds, failure.retryAfter)
        }

    @Test
    fun `an unreachable server is a transient failure`() =
        runTest {
            val transport = transport { throw kotlinx.io.IOException("connection reset") }

            val failure =
                assertFailsWith<SyncTransportFailure.Unreachable> {
                    transport.changes(scope, collection, cursor = null, limit = 10)
                }

            assertTrue(failure.message.orEmpty().isNotEmpty())
        }
}
