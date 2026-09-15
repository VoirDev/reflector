package dev.voir.reflector.sync.protocol.blob

import dev.voir.reflector.sync.protocol.BlobId
import kotlinx.serialization.Serializable

/**
 * What the server knows about one blob.
 *
 * Every field but [state] is what the client declared at registration, echoed back rather than
 * re-derived: the module does not read the bytes and has no independent opinion about them. [state]
 * is the module's own, and it is the only part that changes over a blob's life — from
 * [BlobState.PENDING] to [BlobState.READY], once and never back, because a blob is immutable.
 *
 * @property blobId Identifier of the blob.
 * @property state Whether the bytes are usable yet.
 * @property contentType Media type as the application declared it.
 * @property size Length of the bytes in octets as the application declared it.
 * @property checksum Digest as the application declared it, or `null` when it declared none.
 */
@Serializable
public data class BlobInfo(
    public val blobId: BlobId,
    public val state: BlobState,
    public val contentType: BlobContentType,
    public val size: Long,
    public val checksum: BlobChecksum? = null,
)
