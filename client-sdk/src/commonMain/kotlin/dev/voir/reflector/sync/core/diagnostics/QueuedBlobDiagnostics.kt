package dev.voir.reflector.sync.core.diagnostics

import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.protocol.BlobId
import kotlin.time.Instant

/**
 * One file waiting to be moved.
 *
 * Unlike the push queue, this one is not strictly ordered and its first entry explains nothing:
 * files move independently and several at once, so what is worth reading here is the whole list —
 * and above all the entries whose attempts are climbing, which are the transfers that have stopped
 * succeeding without anything yet having given up.
 *
 * @property blobId Identifier of the file, which is also what the library's log records name it by.
 * @property state Where its bytes are. A list full of [BlobTransferState.REMOTE] is a device that is
 *   behind on downloads, which is slow rather than broken; one with entries in
 *   [BlobTransferState.UNAVAILABLE] is files the application has already been told about and has
 *   not acted on.
 * @property size Length in octets as declared, or `null` before anything declared it. A single
 *   large entry with climbing attempts is the shape of a file that will never finish on the
 *   connection it is being tried over.
 * @property transferred Octets moved in the current direction.
 * @property attempts Consecutive failed transfers.
 * @property nextRetryAt When the next attempt may start, or `null` when one may start now. Local
 *   wall-clock time, compared against the device's own clock and nothing else.
 * @property referenced Whether any document still points at the file. `false` here is a file the
 *   library is about to offer back to the application, and a list of them on a collection that is
 *   otherwise idle means the application is declining to take them.
 * @property wanted Whether this device is trying to hold the bytes. An entry that is
 *   [BlobTransferState.REMOTE], referenced and not wanted is not stalled — it is a file left behind
 *   under [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] that nobody has asked for, and it
 *   is the one entry here that is doing nothing on purpose.
 * @property lastError What the last attempt failed with, or `null` when none has.
 */
public data class QueuedBlobDiagnostics(
    public val blobId: BlobId,
    public val state: BlobTransferState,
    public val wanted: Boolean,
    public val size: Long?,
    public val transferred: Long,
    public val attempts: Int,
    public val nextRetryAt: Instant?,
    public val referenced: Boolean,
    public val lastError: String?,
)
