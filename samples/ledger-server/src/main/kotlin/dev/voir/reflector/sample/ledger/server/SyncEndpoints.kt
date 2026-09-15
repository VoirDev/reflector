package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.events.SyncEventSerializer
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.CollectionResetException
import dev.voir.reflector.sync.server.CursorTooOldException
import dev.voir.reflector.sync.server.SyncServerException
import dev.voir.reflector.sync.server.UnknownCollectionException
import dev.voir.reflector.sync.server.postgres.SyncModule
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.collect

/**
 * Publishes the synchronisation module over HTTP and a WebSocket.
 *
 * The host owns everything about transport and access: it resolves the caller into a scope, decides
 * the status codes, and delivers notifications. The module contributes the five operations and
 * nothing else — which is why swapping this file for Spring or gRPC changes no line of the library.
 *
 * @param module Assembled synchronisation module.
 * @param authorizer Host's own access model.
 * @param events Delivery of commit notifications to connected sockets.
 * @param revocations Scopes this host has taken away; they are refused with `403` rather than
 *   `401`, which is what makes a client wipe them instead of waiting to sign in again.
 */
fun Application.syncEndpoints(
    module: SyncModule,
    authorizer: ScopeAuthorizer,
    events: ScopeEvents,
    revocations: ScopeRevocations = ScopeRevocations(events),
) {
    install(ContentNegotiation) {
        json(SyncProtocolJson.format)
    }
    install(WebSockets)

    routing {
        route("/v1/sync") {
            get("/config") {
                call.respond(module.service.limits())
            }

            post("/{scope}/{collection}/push") {
                withScope(authorizer, revocations) { scope, collection ->
                    call.respond(module.service.push(scope, collection, call.receive<PushRequest>()))
                }
            }

            get("/{scope}/{collection}/changes") {
                withScope(authorizer, revocations) { scope, collection ->
                    val cursor = call.request.queryParameters["cursor"]?.let(::Cursor)
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                    call.respond(module.service.changes(scope, collection, cursor, limit))
                }
            }

            get("/{scope}/{collection}/snapshot") {
                withScope(authorizer, revocations) { scope, collection ->
                    val page = call.request.queryParameters["page"]?.let(::PageToken)
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
                    call.respond(module.service.snapshot(scope, collection, page, limit))
                }
            }

            webSocket("/{scope}/events") {
                val requested = call.parameters["scope"]?.let(::ScopeId)
                val authorized = authorizer.authorize(bearerToken(call.request.headers[HttpHeaders.Authorization]))
                if (requested == null || authorized == null || authorized != requested ||
                    revocations.isRevoked(requested)
                ) {
                    // A socket is not a place to negotiate access: it is closed, and the client
                    // reconnects once it has credentials again.
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "scope is not accessible"))
                    return@webSocket
                }
                events.events(requested).collect { event ->
                    send(Frame.Text(SyncProtocolJson.format.encodeToString(SyncEventSerializer, event)))
                }
            }
        }
    }
}

/**
 * Resolves the caller and the addressed collection, then runs [block].
 *
 * The status codes are chosen for what the client can do about them: `401` means "get credentials",
 * `403` means "this scope is not yours any more" and makes a client wipe it, `410` means "your
 * cursor is gone, bootstrap", and `409` means "the collection you are following was purged" and
 * makes a client discard it, unsent changes included. Collapsing them would cost the client its
 * recoveries — and collapsing the last two would either lose a user's offline edits after an
 * ordinary retention gap, or put an erased collection back after a purge.
 */
private suspend fun RoutingContext.withScope(
    authorizer: ScopeAuthorizer,
    revocations: ScopeRevocations,
    block: suspend (ScopeId, CollectionId) -> Unit,
) {
    val requested = call.parameters["scope"]?.let(::ScopeId)
    val collection = call.parameters["collection"]?.let(::CollectionId)
    if (requested == null || collection == null) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }

    val authorized = authorizer.authorize(bearerToken(call.request.headers[HttpHeaders.Authorization]))
    when {
        authorized == null -> {
            call.respond(HttpStatusCode.Unauthorized)
            return
        }

        // Never theirs, or no longer theirs: the client cannot act differently on the two, and
        // saying which it was would tell an unauthorised caller something about the scope.
        authorized != requested || revocations.isRevoked(requested) -> {
            call.respond(HttpStatusCode.Forbidden)
            return
        }
    }

    try {
        block(requested, collection)
    } catch (failure: CursorTooOldException) {
        call.respond(HttpStatusCode.Gone, failure.message.orEmpty())
    } catch (failure: CollectionResetException) {
        call.respond(HttpStatusCode.Conflict, failure.message.orEmpty())
    } catch (failure: UnknownCollectionException) {
        call.respond(HttpStatusCode.NotFound, failure.message.orEmpty())
    } catch (failure: SyncServerException) {
        call.respond(HttpStatusCode.BadRequest, failure.message.orEmpty())
    } catch (failure: IllegalArgumentException) {
        // A malformed cursor or page token is the client's problem to fix, not a server fault.
        call.respond(HttpStatusCode.BadRequest, failure.message.orEmpty())
    }
}

private fun bearerToken(header: String?): String? = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/** Page size used when the client does not ask for one; the module clamps it to its own maximum. */
private const val DEFAULT_LIMIT = 100
