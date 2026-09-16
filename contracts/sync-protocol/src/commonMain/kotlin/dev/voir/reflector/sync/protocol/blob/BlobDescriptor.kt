package dev.voir.reflector.sync.protocol.blob

import dev.voir.reflector.sync.protocol.BlobId
import kotlinx.serialization.Serializable

/**
 * What a client declares about a blob before it uploads it.
 *
 * Sent to register the blob, and handed on to the host so that it can presign an upload scoped to
 * exactly this object. The declaration is a promise the host checks after the transfer rather than a
 * fact the module trusts: a blob whose stored object disagrees with its declared size — or its
 * checksum, where there is one — never becomes usable.
 *
 * Declaring the size in advance is what lets an oversized blob be refused before a single byte
 * moves, which on a metered connection is the difference between a refusal and a bill.
 *
 * @property blobId Identifier the application generated; the blob is addressed by it from here on.
 * @property contentType Media type of the bytes, stored and served back but never interpreted.
 * @property size Exact length of the bytes in octets, as the device measured it.
 * @property checksum Digest of the bytes, or `null` when the application computes none. Its absence
 *   weakens verification to a size comparison and is supported deliberately — see [BlobChecksum].
 */
@Serializable
public data class BlobDescriptor(
    public val blobId: BlobId,
    public val contentType: BlobContentType,
    public val size: Long,
    public val checksum: BlobChecksum? = null,
)
