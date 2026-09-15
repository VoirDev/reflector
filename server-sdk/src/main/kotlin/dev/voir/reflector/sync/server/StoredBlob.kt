package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobState
import kotlin.time.Instant

/**
 * One blob as the module stores it — which is to say, everything about it except the bytes.
 *
 * The module has never seen the object this describes. It holds what the client declared, the key
 * the host's storage chose, and the one fact it owns itself: whether the bytes are usable. That is
 * the whole of its part in files, and it is deliberately less than it holds for documents, where it
 * at least stores the opaque body.
 *
 * Handed to [BlobStorage] on every call, so that the host has what it needs to address the object
 * without the module having to understand how.
 *
 * @property blobId Identifier the application generated.
 * @property state Whether the bytes are usable. It moves from [BlobState.PENDING] to
 *   [BlobState.READY] once and never back, because a blob is immutable.
 * @property storageKey Name the host's storage knows the object by.
 * @property contentType Media type as the application declared it; stored, never interpreted.
 * @property size Length in octets as the application declared it, and what the host verifies the
 *   stored object against.
 * @property checksum Digest as the application declared it, or `null` when it declared none, in
 *   which case verification is by size alone.
 * @property createdAt When the blob was registered.
 * @property readyAt When the bytes were accepted, or `null` while they have not been.
 * @property unreferencedSince Since when no document in the collection has pointed at this blob, or
 *   `null` while at least one does. It is what the collector reads: a blob unreferenced for longer
 *   than the retention window is garbage, and one referenced again before then stops being a
 *   candidate. A freshly registered blob starts unreferenced, so an upload whose document was never
 *   pushed is collectable rather than immortal.
 */
public data class StoredBlob(
    public val blobId: BlobId,
    public val state: BlobState,
    public val storageKey: BlobStorageKey,
    public val contentType: BlobContentType,
    public val size: Long,
    public val checksum: BlobChecksum?,
    public val createdAt: Instant,
    public val readyAt: Instant?,
    public val unreferencedSince: Instant?,
)
