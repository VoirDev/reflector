package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    override suspend fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse =
        send { token ->
            client.post(baseUrl) {
                url { appendPathSegments(API, "sync", scope.value, collection.value, "push") }
                authorize(token)
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
        send { token ->
            client.get(baseUrl) {
                url {
                    appendPathSegments(API, "sync", scope.value, collection.value, "changes")
                    cursor?.let { parameters.append("cursor", it.value) }
                    parameters.append("limit", limit.toString())
                }
                authorize(token)
            }
        }.body()

    override suspend fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage =
        send { token ->
            client.get(baseUrl) {
                url {
                    appendPathSegments(API, "sync", scope.value, collection.value, "snapshot")
                    page?.let { parameters.append("page", it.value) }
                    parameters.append("limit", limit.toString())
                }
                authorize(token)
            }
        }.body()

    override suspend fun limits(): SyncLimits =
        send { token ->
            client.get(baseUrl) {
                url { appendPathSegments(API, "sync", "config") }
                authorize(token)
            }
        }.body()

    /**
     * Issues a request, refreshing the credentials once if the server refuses them.
     *
     * @param block Request to issue, given the token to present.
     * @return Successful response.
     * @throws SyncTransportFailure When the request failed or the server answered with an error.
     */
    private suspend fun send(block: suspend (String?) -> HttpResponse): HttpResponse {
        val response = attempt(block)
        if (response.status != HttpStatusCode.Unauthorized) {
            return ensureSuccess(response)
        }
        val renewed = tokens.refresh()
        logger.info(SyncLogEvent.TOKEN_REFRESHED, context = { mapOf("renewed" to renewed.toString()) }) {
            if (renewed) {
                "the server refused the credentials; the application renewed them and the request is retried once"
            } else {
                "the server refused the credentials and the application could not renew them"
            }
        }
        if (!renewed) {
            throw SyncTransportFailure.Unauthorized("the server refused the credentials and they could not be renewed")
        }
        return ensureSuccess(attempt(block))
    }

    private suspend fun attempt(block: suspend (String?) -> HttpResponse): HttpResponse =
        try {
            block(tokens.token())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Anything that prevents an answer from arriving is transient by default: the engine
            // retries it with backoff, which is the right behaviour for a client that works offline.
            // The throwable is carried on the failure and reported here as well, because this is
            // where the original — a DNS failure, a certificate, a refused connection — still exists;
            // by the time the engine has it, it is a description.
            logger.debug(
                SyncLogEvent.REQUEST_FAILED,
                context = { mapOf("failure" to (failure::class.simpleName ?: "unknown")) },
            ) { "the request did not complete: ${failure.message.orEmpty()}" }
            throw SyncTransportFailure.Unreachable(failure.message ?: "the server could not be reached", failure)
        }

    private fun ensureSuccess(response: HttpResponse): HttpResponse =
        when {
            response.status.isSuccess() -> {
                response
            }

            response.status == HttpStatusCode.Unauthorized -> {
                throw SyncTransportFailure.Unauthorized("the server refused the credentials")
            }

            response.status == HttpStatusCode.Forbidden -> {
                throw SyncTransportFailure.Revoked("access to the scope has been revoked")
            }

            response.status == HttpStatusCode.Gone -> {
                throw SyncTransportFailure.CursorTooOld("the cursor has fallen out of the retention window")
            }

            response.status == HttpStatusCode.TooManyRequests -> {
                throw SyncTransportFailure.RateLimited(response.retryAfter(), "the server asked to slow down")
            }

            else -> {
                logger.warn(
                    SyncLogEvent.REQUEST_FAILED,
                    context = { mapOf("status" to response.status.value.toString()) },
                ) { "the server answered with a status this client cannot interpret further" }
                throw SyncTransportFailure.ServerError(response.status.value, response.status.description)
            }
        }

    private fun HttpResponse.retryAfter(): Duration? = headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds

    private fun HttpRequestBuilder.authorize(token: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private companion object {
        /** Version segment of the protocol's endpoints. */
        const val API = "v1"
    }
}
