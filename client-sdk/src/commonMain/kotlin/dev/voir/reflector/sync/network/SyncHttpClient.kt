package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json

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
        install(WebSockets)
        if (tracing != SyncHttpTracing.NONE) {
            installTracing(log, tracing)
        }
    }

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
