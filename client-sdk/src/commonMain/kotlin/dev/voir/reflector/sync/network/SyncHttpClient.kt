package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builds the HTTP client the synchronisation transport expects.
 *
 * Two settings are not a matter of taste. The JSON format is the protocol's own — a client that
 * drops nulls turns "clear this field" into "leave it alone" once the server merges the patch. And
 * `expectSuccess` stays off, because the transport maps status codes itself: `409`, `410` and `429`
 * are part of the protocol, not failures to be thrown as generic client exceptions.
 *
 * The WebSocket plugin is installed here as well, so that the event channel and the request
 * endpoints share one client, one engine and one set of settings.
 *
 * Neither the socket nor a request is allowed to hang on a connection that has silently died. A
 * mobile network drops a route without closing anything — a handover, a device waking from sleep,
 * a NAT that forgot the mapping — and without a heartbeat the event channel sat on such a socket
 * indefinitely, never reconnecting and so never announcing the reconnect the scope's state waits
 * for. The socket is therefore pinged, and closed when the pongs stop, which the channel answers by
 * reconnecting. The OkHttp engine is the exception: it runs its own WebSocket and ignores this
 * setting, so an application using it sets `pingInterval` on the OkHttp client it configures.
 *
 * Requests get a connect timeout and an idle timeout, but no overall one: file transfers share this
 * client and stream, and a large photograph on a slow link would be cut off by a deadline that
 * fits a page of the change log. A transfer that is still moving is not hung; one that has received
 * nothing for [SOCKET_TIMEOUT] is. The idle timeout also covers the socket, and never fires on a
 * healthy one because it is longer than the ping interval.
 *
 * Tracing is off unless asked for. When it is asked for, the client's own tracing is routed into
 * [log] rather than to a second destination, so that one sink holds the whole story of a device in
 * order — the engine's decision to push, the request it produced, and what came back.
 *
 * @param engine Platform engine to use.
 * @param log Sink the HTTP tracing is written to. Ignored when [tracing] is
 *   [SyncHttpTracing.NONE], which is the default.
 * @param tracing How much of each exchange to describe. Never includes bodies — see
 *   [SyncHttpTracing].
 * @return Configured client, owned by the caller.
 */
public fun syncHttpClient(
    engine: HttpClientEngine,
    log: SyncLog = SyncLog.None,
    tracing: SyncHttpTracing = SyncHttpTracing.NONE,
): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(SyncProtocolJson.format)
        }
        install(WebSockets) {
            pingInterval = PING_INTERVAL
        }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT.inWholeMilliseconds
            socketTimeoutMillis = SOCKET_TIMEOUT.inWholeMilliseconds
        }
        if (tracing != SyncHttpTracing.NONE) {
            installTracing(log, tracing)
        }
    }

/**
 * How often the event channel's socket is pinged.
 *
 * Short enough to notice a dead socket within a minute — a socket is given up after two missed
 * pongs — and to keep NAT mappings and proxies from expiring an idle one, long enough to cost a
 * battery nothing measurable.
 */
private val PING_INTERVAL: Duration = 20.seconds

/** How long a connection may take to establish before the request fails as unreachable. */
private val CONNECT_TIMEOUT: Duration = 15.seconds

/**
 * How long a request may go without receiving anything before it is given up.
 *
 * Longer than [PING_INTERVAL], so that a healthy socket always hears a pong before it expires.
 */
private val SOCKET_TIMEOUT: Duration = 30.seconds

/**
 * Routes the HTTP client's own tracing into the library's log.
 *
 * Installed only when tracing was asked for: the plugin formats its lines before handing them over,
 * so leaving it installed and filtering afterwards would pay for every exchange in a client that is
 * not being watched.
 *
 * @param log Sink the lines are written to.
 * @param tracing How much to describe, which has already been checked not to be
 *   [SyncHttpTracing.NONE].
 */
private fun HttpClientConfig<*>.installTracing(
    log: SyncLog,
    tracing: SyncHttpTracing,
) {
    val sink = SyncLogger(log)
    install(Logging) {
        logger =
            object : Logger {
                override fun log(message: String) {
                    sink.debug(SyncLogEvent.HTTP_TRACE) { message }
                }
            }
        level =
            when (tracing) {
                SyncHttpTracing.BASIC -> LogLevel.INFO

                SyncHttpTracing.HEADERS -> LogLevel.HEADERS

                // Refused by the type, and repeated here so that adding an entry cannot quietly
                // start logging documents.
                SyncHttpTracing.NONE -> LogLevel.NONE
            }
        // A bearer token in a log is a credential that has left the device's keystore. Debug logs
        // are copied into bug reports and pasted into chats, which is exactly how they travel.
        sanitizeHeader { header -> header.equals(HttpHeaders.Authorization, ignoreCase = true) }
    }
}
