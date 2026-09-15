package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Told after a blob's bytes have been accepted and the fact is durable.
 *
 * This is the acknowledgement a host waits for, and the place everything it wants to do with an
 * uploaded file hangs from: thumbnails, transcoding, scanning, text extraction. It fires once per
 * blob, after the commit that made it usable, for the same reason [SyncCommitListener] does — a
 * thumbnail that could not be made must not undo an upload the storage has already taken.
 *
 * A listener must not overwrite the object it is told about. A blob is immutable, and every client
 * that already fetched it holds bytes that would no longer match; a derivative belongs in a **new**
 * blob, which the host registers and then names in a document through an ordinary push of its own.
 *
 * Failures are logged and ignored, as for the commit listener. What is lost is whatever the host
 * meant to do, not the upload.
 */
public fun interface BlobListener {
    /**
     * Reports that a blob became usable.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob Blob whose bytes were accepted.
     */
    public fun onBlobReady(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    )
}
