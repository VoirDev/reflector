package dev.voir.reflector.sync.network

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey

/**
 * Marks a request as bound for the synchronisation server.
 *
 * Only requests carrying it receive the headers given to [syncHttpClient]. The mark is opt-in rather
 * than an exclusion because the same client also sends presigned file transfers to the host's
 * storage, and a header the application meant for its own server — a client version, an
 * installation id — has no business arriving there. Forgetting to mark a request costs a header;
 * forgetting to exclude one would leak it.
 */
private val SyncServerRequest: AttributeKey<Unit> = AttributeKey("SyncServerRequest")

/**
 * Marks this request as one the synchronisation server receives, so the default headers reach it.
 */
internal fun HttpRequestBuilder.markForSyncServer() {
    attributes.put(SyncServerRequest, Unit)
}

/**
 * Builds the plugin that adds the application's headers to every request bound for the server.
 *
 * A header the request already sets is left alone: the library's own `Authorization` and content
 * headers are what the protocol depends on, and a default must not be able to replace them.
 *
 * @param headers Header names and values to add.
 * @return Plugin to install on the client.
 */
internal fun syncDefaultHeaders(headers: Map<String, String>): ClientPlugin<Unit> =
    createClientPlugin("SyncDefaultHeaders") {
        onRequest { request, _ ->
            if (!request.attributes.contains(SyncServerRequest)) {
                return@onRequest
            }
            headers.forEach { (name, value) ->
                if (!request.headers.contains(name)) {
                    request.headers.append(name, value)
                }
            }
        }
    }
