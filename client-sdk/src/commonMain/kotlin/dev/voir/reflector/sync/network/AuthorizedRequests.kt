package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Requests to the synchronisation server: credentials attached, status codes interpreted.
 *
 * Shared by the two transports rather than written twice, because neither half of it is plumbing.
 * The refusal of credentials is retried exactly once and only after the application has renewed
 * them; the status codes are mapped into the closed set the engine reacts to, and `409`, `410` and
 * `429` are protocol answers whose recoveries would be lost if they became generic failures.
 *
 * It is deliberately **not** used for a ticket. A presigned request goes to the host's storage,
 * which is a different server that must never see this scope's credentials.
 *
 * @property tokens Application's source of credentials.
 * @property logger Sink told when a request fails and when credentials are renewed.
 * @property interpret How a status that is not a success becomes a failure the engine reacts to.
 *   Replaceable because the same code means different things on different paths of this protocol:
 *   `409` is a purged collection when reading the log and a refused file when uploading one, and a
 *   single mapping would have an upload failure wipe the collection it belongs to.
 */
internal class AuthorizedRequests(
    private val tokens: TokenProvider,
    private val logger: SyncLogger,
    private val interpret: (HttpResponse) -> SyncTransportFailure? = { null },
) {
    /**
     * Issues a request, refreshing the credentials once if the server refuses them.
     *
     * Retrying further would be indistinguishable from an outage while being far more expensive, and
     * backing off would keep a signed-out user waiting for a state that cannot arrive without them.
     *
     * @param block Request to issue, given the token to present.
     * @return Successful response.
     * @throws SyncTransportFailure When the request failed or the server answered with an error.
     */
    suspend fun send(block: suspend (String?) -> HttpResponse): HttpResponse {
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

    /**
     * Issues a request without interpreting the answer beyond whether it arrived.
     *
     * For requests whose statuses mean something of their own to the caller.
     *
     * @param block Request to issue, given the token to present.
     * @return Whatever the server answered.
     * @throws SyncTransportFailure When no answer arrived at all.
     */
    suspend fun attempt(block: suspend (String?) -> HttpResponse): HttpResponse =
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

    /**
     * Turns a status into the failure the engine reacts to, or returns the response.
     *
     * @param response Answer from the server.
     * @return The response, when it was a success.
     * @throws SyncTransportFailure When it was not.
     */
    fun ensureSuccess(response: HttpResponse): HttpResponse =
        when {
            response.status.isSuccess() -> {
                response
            }

            // Asked before the shared mapping, never after: what it overrides are codes the shared
            // mapping already has an answer for, and a wrong one.
            interpret(response) != null -> {
                throw checkNotNull(interpret(response))
            }

            response.status == HttpStatusCode.Unauthorized -> {
                throw SyncTransportFailure.Unauthorized("the server refused the credentials")
            }

            response.status == HttpStatusCode.Forbidden -> {
                throw SyncTransportFailure.Revoked("access to the scope has been revoked")
            }

            response.status == HttpStatusCode.Conflict -> {
                throw SyncTransportFailure.CollectionReset("the collection has been purged and started again")
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

    /**
     * Attaches the scope's credentials to a request bound for the synchronisation server.
     *
     * @param token Credential to present, or `null` when the application has none.
     */
    fun HttpRequestBuilder.authorize(token: String?) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}
