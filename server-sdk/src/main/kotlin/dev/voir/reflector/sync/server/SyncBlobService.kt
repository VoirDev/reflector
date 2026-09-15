package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobDownload
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobRegistration

/**
 * Files, as the host calls them.
 *
 * Three operations, and the module is in none of the data paths: it hands out permission to write,
 * records that bytes were accepted, and hands out permission to read. The transfers themselves
 * happen between the device and the host's storage, which is why nothing here takes or returns a
 * stream.
 *
 * Every operation is idempotent by blob identifier, because every one of them is reached from a
 * device that may have crashed halfway through the last attempt.
 *
 * As everywhere else in this module, a [ScopeId] arrives **already authorised**. What is checked
 * here is only that the blob belongs to the collection being addressed.
 */
public interface SyncBlobService {
    /**
     * Registers a blob and, unless its bytes are already accepted, says how to send them.
     *
     * Repeating a registration is normal rather than exceptional: a device that crashed between
     * registering and transferring asks again and is handed a fresh ticket for the same object. A
     * blob whose bytes are already accepted is returned with no ticket — it is immutable, so there is
     * nothing left to send and nothing that may be overwritten.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to; it must be registered in the configuration.
     * @param descriptor What the client declares about the blob.
     * @return What the server now knows, and permission to write when there is anything to write.
     * @throws SyncServerException.UnknownCollectionException When the collection is not registered.
     * @throws SyncServerException.BlobTooLargeException When the declared size exceeds the limit.
     * @throws SyncServerException.BlobConflictException When the identifier already names a blob
     *   whose bytes were accepted and whose declaration disagrees with this one.
     */
    public fun register(
        scope: ScopeId,
        collection: CollectionId,
        descriptor: BlobDescriptor,
    ): BlobRegistration

    /**
     * Verifies that a blob's bytes are in storage and makes it usable.
     *
     * The one way a blob becomes usable, and deliberately reachable from more than one direction:
     * the device says it finished, the host's own storage notifications say so, or the maintenance
     * sweep finds an upload nobody ever confirmed. A device that dies between writing the object and
     * saying so is the reason its word cannot be the only path.
     *
     * The client's claim is never taken on trust. The host is asked what it holds, and the answer is
     * compared with what was declared; a blob whose object is missing or disagrees stays unusable.
     *
     * Calling it for a blob that is already usable answers with what is stored and notifies nobody:
     * a [BlobListener] fires once per blob, whichever path got there first.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blobId Blob whose bytes are claimed to be in storage.
     * @return What the server knows about the blob afterwards.
     * @throws SyncServerException.UnknownCollectionException When the collection is not registered.
     * @throws SyncServerException.UnknownBlobException When no such blob was ever registered here.
     * @throws SyncServerException.BlobNotStoredException When the storage has no such object, or
     *   holds one that disagrees with what was declared.
     */
    public fun markUploaded(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobInfo

    /**
     * Says what a blob is and, when its bytes are usable, how to fetch them.
     *
     * A blob whose bytes have not been accepted yet is **not** a failure and is not reported as one:
     * it is answered with its state and no ticket. That is the ordinary condition of an attachment
     * whose record reached a device ahead of its bytes, which is what the default binding of a
     * reference produces on purpose, and a client answers it by waiting rather than by giving up.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blobId Blob to fetch.
     * @return What the server knows, and permission to read when there is anything to read.
     * @throws SyncServerException.UnknownCollectionException When the collection is not registered.
     * @throws SyncServerException.UnknownBlobException When no such blob was ever registered here,
     *   which is also the answer for one that has since been collected as garbage.
     */
    public fun download(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobDownload
}
