package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.protocol.ScopeId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KtorSyncEventChannelTest {
    private val tokens =
        object : TokenProvider {
            override suspend fun token(): String = "scope-credentials"

            override suspend fun refresh(): Boolean = false
        }

    @Test
    fun `the handshake carries the credentials and the application's headers`() =
        runTest {
            val handshake = CompletableDeferred<HttpRequestData>()
            val client =
                syncHttpClient(
                    MockEngine { request ->
                        handshake.complete(request)
                        respondError(HttpStatusCode.UpgradeRequired)
                    },
                    defaultHeaders = mapOf("X-Client-Version" to "3.1.0"),
                )
            val channel = KtorSyncEventChannel(client, "https://sync.example.com", tokens)

            backgroundScope.launch { channel.signals(ScopeId("user-1")).collect {} }
            val request = handshake.await()

            assertEquals("/v1/sync/user-1/events", request.url.encodedPath)
            assertEquals("Bearer scope-credentials", request.headers[HttpHeaders.Authorization])
            assertEquals("3.1.0", request.headers["X-Client-Version"])
        }
}
