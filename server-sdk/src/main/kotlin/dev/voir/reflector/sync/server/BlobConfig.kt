package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.config.BlobLimits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration of files, present only when the deployment serves them.
 *
 * Its absence from [SyncConfig] is what makes a deployment blob-free, and the module publishes that
 * absence so that an application configured for files learns at start-up that this server has no
 * storage behind it, rather than on the first photograph a user attaches.
 *
 * @property maxBlobBytes Largest blob accepted, in octets. Checked against the declared size before
 *   a ticket is issued, so an oversized file is refused before any of it is transferred — after the
 *   fact the bytes have already been paid for on somebody's mobile data.
 * @property maxBlobsPerEntity Largest number of blobs one entity's document may reference. It bounds
 *   a list the client otherwise controls.
 * @property uploadTicketLife How long the host honours an upload ticket. The module does not enforce
 *   it — the host's own signature does — and publishes it so that a client can tell a transfer that
 *   is merely slow from one whose permission has run out.
 */
public data class BlobConfig(
    public val maxBlobBytes: Long = DEFAULT_MAX_BLOB_BYTES,
    public val maxBlobsPerEntity: Int = DEFAULT_MAX_BLOBS_PER_ENTITY,
    public val uploadTicketLife: Duration = DEFAULT_UPLOAD_TICKET_LIFE,
) {
    init {
        require(maxBlobBytes > 0) { "the blob size limit has to be positive" }
        require(maxBlobsPerEntity > 0) { "the reference limit has to be positive" }
        require(uploadTicketLife.isPositive()) { "an upload ticket has to be honoured for some time" }
    }

    /**
     * Returns these limits in the shape clients read them.
     *
     * @return Limits as published through the configuration endpoint.
     */
    internal fun toLimits(): BlobLimits =
        BlobLimits(
            maxBlobBytes = maxBlobBytes,
            maxBlobsPerEntity = maxBlobsPerEntity,
            uploadTicketSeconds = uploadTicketLife.inWholeSeconds.toInt(),
        )

    public companion object {
        /** Generous for a photograph, small enough that one file cannot dominate a bucket. */
        public const val DEFAULT_MAX_BLOB_BYTES: Long = 25L * 1024 * 1024

        /** Beyond this a document has stopped being a record with attachments. */
        public const val DEFAULT_MAX_BLOBS_PER_ENTITY: Int = 16

        /** Long enough for a large file on a poor connection, short enough for a bearer token. */
        public val DEFAULT_UPLOAD_TICKET_LIFE: Duration = 15.minutes
    }
}
