package dev.voir.reflector.sync.core.blob

import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobContentType

/**
 * What the application knows about a file's bytes without the library reading them.
 *
 * Declared to the server before a transfer so that an oversized file is refused before any of it
 * moves, and compared against what arrives so that a truncated download is discarded rather than
 * written into the application's store.
 *
 * @property size Exact length of the bytes in octets.
 * @property contentType Media type of the bytes, stored and served back but never interpreted.
 * @property checksum Digest of the bytes, or `null` when the application computes none — which is
 *   supported deliberately, because requiring one would mean requiring a hash function the Kotlin
 *   standard library does not offer on every target. Without it, verification is by size alone.
 */
public data class BlobStat(
    public val size: Long,
    public val contentType: BlobContentType,
    public val checksum: BlobChecksum? = null,
)
