package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.events.SyncEventSerializer
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.BlobStorageKey
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
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.collect
import kotlin.uuid.Uuid

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
 * @param storage Object storage behind the file endpoints, or `null` when this host serves no
 *   files. It is this host's, not the module's: the module hands out permission to move bytes and
 *   is told afterwards what arrived, and is never in the data path.
 */
fun Application.syncEndpoints(
    module: SyncModule,
    authorizer: ScopeAuthorizer,
    events: ScopeEvents,
    revocations: ScopeRevocations = ScopeRevocations(events),
    storage: DirectoryBlobStorage? = null,
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

            post("/{scope}/{collection}/blobs") {
                withScope(authorizer, revocations) { scope, collection ->
                    val blobs = requireNotNull(module.blobs) { "this host serves no files" }
                    call.respond(blobs.register(scope, collection, call.receive<BlobDescriptor>()))
                }
            }

            post("/{scope}/{collection}/blobs/{blob}/complete") {
                withScope(authorizer, revocations) { scope, collection ->
                    val blobs = requireNotNull(module.blobs) { "this host serves no files" }
                    val blobId = BlobId(Uuid.parse(requireNotNull(call.parameters["blob"])))
                    // The device saying it finished. The module does not take its word for it: it
                    // asks the storage what is actually there before the file becomes usable, and
                    // tells this host once it is — which is where a real deployment would start a
                    // thumbnail.
                    val info = blobs.markUploaded(scope, collection, blobId)
                    events.publishBlobReady(scope, collection, blobId)
                    call.respond(info)
                }
            }

            get("/{scope}/{collection}/blobs/{blob}") {
                withScope(authorizer, revocations) { scope, collection ->
                    val blobs = requireNotNull(module.blobs) { "this host serves no files" }
                    call.respond(
                        blobs.download(scope, collection, BlobId(Uuid.parse(requireNotNull(call.parameters["blob"])))),
                    )
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
        storage?.let { fileEndpoints(it) }
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

/**
 * Publishes the endpoints the signed tickets point at.
 *
 * Deliberately outside `/v1/sync`: this is object storage, and it stands where a bucket would. It
 * carries no bearer token of the synchronisation protocol and checks none — what authorises a
 * request here is the signature in the URL, which is exactly the property a presigned S3 URL has.
 *
 * @param storage Directory the objects live in, and the signatures they are addressed with.
 */
private fun Route.fileEndpoints(storage: DirectoryBlobStorage) {
    route("/files") {
        put {
            withSignature(storage, "PUT") { file ->
                file.parentFile.mkdirs()
                call.receiveChannel().copyAndClose(file.writeChannel())
                call.respond(HttpStatusCode.OK)
            }
        }

        get {
            withSignature(storage, "GET") { file ->
                if (file.isFile) call.respondFile(file) else call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

/**
 * Refuses anything whose signature does not check out, before a byte is read or written.
 *
 * A ticket is handed to a device and is the only thing between it and every other user's files.
 */
private suspend fun RoutingContext.withSignature(
    storage: DirectoryBlobStorage,
    method: String,
    block: suspend (java.io.File) -> Unit,
) {
    val key = call.request.queryParameters["key"]
    val expires = call.request.queryParameters["expires"]?.toLongOrNull()
    val signature = call.request.queryParameters["sig"]
    if (key == null || expires == null || signature == null) {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    val storageKey = BlobStorageKey(key)
    if (!storage.isValid(storageKey, method, expires, signature)) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }
    block(storage.fileFor(storageKey))
}

private fun bearerToken(header: String?): String? = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/** Page size used when the client does not ask for one; the module clamps it to its own maximum. */
private const val DEFAULT_LIMIT = 100
