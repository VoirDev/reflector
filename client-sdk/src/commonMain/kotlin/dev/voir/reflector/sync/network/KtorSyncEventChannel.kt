package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.TokenProvider
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
import kotlinx.coroutines.channels.awaitClose
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
 * @param client HTTP client with the WebSocket plugin installed, as built by [syncHttpClient].
 * @param baseUrl Root the endpoint is resolved against; `http` and `https` are upgraded by Ktor.
 * @param tokens Application's source of credentials.
 * @param minRetry Delay before the first reconnect attempt.
 * @param maxRetry Ceiling the reconnect delay grows to.
 */
public class KtorSyncEventChannel(
    private val client: HttpClient,
    private val baseUrl: String,
    private val tokens: TokenProvider,
    private val minRetry: Duration = 1.seconds,
    private val maxRetry: Duration = 1.minutes,
) : SyncEventChannel {
    override fun signals(scope: ScopeId): Flow<SyncChannelSignal> =
        channelFlow {
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
                        attempt = 0
                        send(SyncChannelSignal.Connected)
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            decode(text)?.let { send(SyncChannelSignal.Received(it)) }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Exception) {
                    // The channel is an optimisation; a client without it still synchronises on its
                    // own triggers. There is nothing to report and nothing to do but come back.
                }
                delay(retryDelay(++attempt))
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
    }
}
