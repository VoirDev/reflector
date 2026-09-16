package dev.voir.reflector.sync.protocol.config

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Limits a server enforces on blobs, published only by a server that serves them.
 *
 * Their absence from [SyncLimits] is the statement that this deployment has no blob storage behind
 * it, which a client configured for files has to learn at start-up rather than as a refusal on the
 * first photograph a user attaches.
 *
 * As with the limits on documents, knowing them in advance is a requirement rather than a
 * convenience, and for a sharper reason: a blob that exceeds [maxBlobBytes] has to be refused before
 * the transfer starts, because discovering it afterwards means the bytes were already paid for on
 * somebody's mobile data.
 *
 * @property maxBlobBytes Largest blob the server accepts, in octets.
 * @property maxBlobsPerEntity Largest number of blobs one entity's document may reference. It bounds
 *   the reference set the server stores per entity, which is otherwise a list a client controls.
 * @property uploadTicketSeconds How long an upload ticket is honoured, in whole seconds. Reported so
 *   that a client can tell a transfer that is merely slow from one whose permission has run out.
 */
@Serializable
public data class BlobLimits(
    public val maxBlobBytes: Long,
    public val maxBlobsPerEntity: Int,
    public val uploadTicketSeconds: Int,
) {
    /** Life of an upload ticket as a duration, for logs and diagnostics. */
    public val uploadTicketLife: Duration
        get() = uploadTicketSeconds.seconds
}
