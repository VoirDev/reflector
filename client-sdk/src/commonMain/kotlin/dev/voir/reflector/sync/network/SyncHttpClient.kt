package dev.voir.reflector.sync.network

import dev.voir.reflector.sync.protocol.SyncProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
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
 * @param engine Platform engine to use.
 * @return Configured client, owned by the caller.
 */
public fun syncHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(SyncProtocolJson.format)
        }
        install(WebSockets)
    }
