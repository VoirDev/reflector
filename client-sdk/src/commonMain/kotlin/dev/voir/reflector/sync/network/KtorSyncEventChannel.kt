package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncChannelSignal
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.events.SyncEventSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [SyncEventChannel] over a WebSocket.
 *
 * The socket carries no data, only the fact that there is data — so a dropped connection costs
 * nothing but latency, and reconnecting forever is the right behaviour rather than a failure to
 * report. Every successful connection emits [SyncChannelSignal.Connected], because notifications
 * sent while the socket was down are simply gone and only an unconditional pull can cover them.
 *
 * A frame this client cannot parse is skipped rather than fatal: the alternative is dropping the
 * connection over a message that carried no data anyway, and the periodic pull covers whatever it
 * might have announced.
 *
 * None of that is a reason to be silent about it. Reconnecting forever is correct behaviour and
 * looks exactly like a socket that has never once connected — a wrong address, a handshake the host
 * refuses, a proxy in the way — which is a real misconfiguration that costs every client its
 * latency and nothing else visible. So each attempt is described, and a run of them is escalated.
 *
 * @param client HTTP client with the WebSocket plugin installed, as built by [syncHttpClient].
 * @param baseUrl Root the endpoint is resolved against; `http` and `https` are upgraded by Ktor.
 * @param tokens Application's source of credentials.
 * @param log Sink told about connections, drops and frames that could not be parsed.
 * @param minRetry Delay before the first reconnect attempt.
 * @param maxRetry Ceiling the reconnect delay grows to.
 */
public class KtorSyncEventChannel(
    private val client: HttpClient,
    private val baseUrl: String,
    private val tokens: TokenProvider,
    private val log: SyncLog = SyncLog.None,
    private val minRetry: Duration = 1.seconds,
    private val maxRetry: Duration = 1.minutes,
) : SyncEventChannel {
    override fun signals(scope: ScopeId): Flow<SyncChannelSignal> =
        channelFlow {
            val logger = SyncLogger(log, scope)
            var attempt = 0
            while (true) {
                try {
                    // The token is read before the handshake: the request builder is not a coroutine,
                    // and a socket that outlives its token is re-authenticated by reconnecting.
                    val token = tokens.token()
                    client.webSocket(
                        request = {
                            url {
                                takeFrom(baseUrl)
                                appendPathSegments(API, "sync", scope.value, "events")
                            }
                            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        },
                    ) {
                        logger.info(SyncLogEvent.CHANNEL_CONNECTED) {
                            "the notification socket is connected; anything announced while it was down is " +
                                "covered by the pull that follows"
                        }
                        attempt = 0
                        send(SyncChannelSignal.Connected)
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val event = decode(text)
                            if (event == null) {
                                logger.warn(SyncLogEvent.FRAME_UNDECODABLE) {
                                    "a frame could not be parsed and is skipped; the periodic pull covers " +
                                        "whatever it announced"
                                }
                                continue
                            }
                            send(SyncChannelSignal.Received(event))
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // The channel is an optimisation; a client without it still synchronises on its
                    // own triggers, so there is nothing to do but come back. Saying so is another
                    // matter: a socket that never connects is indistinguishable from one that works
                    // until somebody is told which it is.
                    reportDrop(logger, attempt + 1, failure)
                }
                delay(retryDelay(++attempt))
            }
        }

    /**
     * Reports a socket that closed or failed to open.
     *
     * Escalated by repetition rather than by the failure itself, because one drop means nothing and
     * a hundred mean the socket is never going to work. The threshold is deliberately low: by the
     * time it is reached the delay has already grown to seconds, so the warning cannot repeat often.
     *
     * @param logger Sink bound to the scope whose socket this is.
     * @param attempt Number of consecutive failed attempts, including this one.
     * @param failure What went wrong.
     */
    private fun reportDrop(
        logger: SyncLogger,
        attempt: Int,
        failure: Exception,
    ) {
        if (attempt >= PERSISTENT_FAILURE_ATTEMPTS) {
            logger.warn(SyncLogEvent.CHANNEL_DROPPED, failure, { mapOf("attempt" to attempt.toString()) }) {
                "the notification socket has failed $attempt times in a row; the client is falling back on " +
                    "its own triggers, which is slower but correct"
            }
        } else {
            logger.debug(SyncLogEvent.CHANNEL_DROPPED, { mapOf("attempt" to attempt.toString()) }) {
                "the notification socket dropped and will be retried: ${failure.message.orEmpty()}"
            }
        }
    }

    private fun decode(text: String) =
        runCatching { SyncProtocolJson.format.decodeFromString(SyncEventSerializer, text) }.getOrNull()

    private fun retryDelay(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        return minOf(minRetry * (1 shl exponent), maxRetry)
    }

    private companion object {
        /** Version segment of the protocol's endpoints. */
        const val API = "v1"

        /** Beyond this the ceiling is reached anyway, and shifting further would overflow. */
        const val MAX_EXPONENT = 20

        /** Consecutive failures after which the socket is reported as broken rather than unlucky. */
        const val PERSISTENT_FAILURE_ATTEMPTS = 5
    }
}
