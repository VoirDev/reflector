package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * [SyncTransport] over HTTP.
 *
 * The class does two things beyond issuing requests, and both are protocol decisions rather than
 * plumbing.
 *
 * It attaches credentials and reacts to their refusal: a `401` is retried exactly once, after asking
 * the application to refresh the token. Retrying further would be indistinguishable from an outage
 * while being far more expensive, and backing off would keep a signed-out user waiting for a state
 * that cannot arrive without them.
 *
 * It maps status codes into the closed set the engine reacts to. Codes like `410` and `429` are part
 * of the protocol — a stale cursor and a slow-down request — and turning them into generic failures
 * would cost the engine the only two recoveries it has for them.
 *
 * @param client HTTP client, configured by [syncHttpClient].
 * @param baseUrl Root the endpoints are resolved against, for example `https://api.example.com`.
 * @param tokens Application's source of credentials.
 * @param log Sink told when a request fails and when credentials are renewed. The exchanges
 *   themselves are described by the client's own tracing, which
 *   [syncHttpClient] routes into the same sink.
 */
public class KtorSyncTransport(
    private val client: HttpClient,
    private val baseUrl: String,
    private val tokens: TokenProvider,
    private val log: SyncLog = SyncLog.None,
) : SyncTransport {
    private val logger = SyncLogger(log)
    private val requests = AuthorizedRequests(tokens, logger)

    override suspend fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse =
        requests
            .send { token ->
                client.post(baseUrl) {
                    url { appendPathSegments(API, "sync", scope.value, collection.value, "push") }
                    with(requests) { authorize(token) }
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }.body()

    override suspend fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage =
        requests
            .send { token ->
                client.get(baseUrl) {
                    url {
                        appendPathSegments(API, "sync", scope.value, collection.value, "changes")
                        cursor?.let { parameters.append("cursor", it.value) }
                        parameters.append("limit", limit.toString())
                    }
                    with(requests) { authorize(token) }
                }
            }.body()

    override suspend fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage =
        requests
            .send { token ->
                client.get(baseUrl) {
                    url {
                        appendPathSegments(API, "sync", scope.value, collection.value, "snapshot")
                        page?.let { parameters.append("page", it.value) }
                        parameters.append("limit", limit.toString())
                    }
                    with(requests) { authorize(token) }
                }
            }.body()

    override suspend fun limits(): SyncLimits =
        requests
            .send { token ->
                client.get(baseUrl) {
                    url { appendPathSegments(API, "sync", "config") }
                    with(requests) { authorize(token) }
                }
            }.body()

    private companion object {
        /** Version segment of the protocol's endpoints. */
        const val API = "v1"
    }
}
