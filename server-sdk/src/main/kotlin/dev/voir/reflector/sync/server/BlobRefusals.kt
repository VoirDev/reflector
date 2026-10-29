package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.blob.BlobRefusal
import dev.voir.reflector.sync.protocol.blob.BlobRefusalCode

/**
 * Returns the body a host answers a refused file with, or `null` for a failure that is not one.
 *
 * Every case returned here is answered `409` on a blob path, and the code in the body is what lets
 * the client tell a refusal it may outlast from one it never will. Kept in the module rather than
 * left to each host, because a host that wrote its own mapping and missed a case would send a file
 * the server will never keep round the client's retries for as long as it lives.
 *
 * Only for the blob routes: on every other path `409` means a purged collection.
 *
 * @return The refusal to serialise with `SyncProtocolJson`, or `null` when this failure is answered
 *   with another status.
 */
public fun SyncServerException.toBlobRefusal(): BlobRefusal? {
    val code =
        when (this) {
            is SyncServerException.BlobConflictException -> BlobRefusalCode.CONFLICT
            is SyncServerException.BlobNotStoredException -> BlobRefusalCode.NOT_STORED
            is SyncServerException.BlobTooLargeException -> BlobRefusalCode.TOO_LARGE
            is SyncServerException.BlobUnsupportedTypeException -> BlobRefusalCode.UNSUPPORTED_TYPE
            is SyncServerException.BlobRejectedException -> BlobRefusalCode.CONTENT_REJECTED
            is SyncServerException.CursorTooOldException,
            is SyncServerException.UnknownCollectionException,
            is SyncServerException.CollectionResetException,
            is SyncServerException.UnknownBlobException,
            -> return null
        }
    return BlobRefusal(code, message.orEmpty())
}
