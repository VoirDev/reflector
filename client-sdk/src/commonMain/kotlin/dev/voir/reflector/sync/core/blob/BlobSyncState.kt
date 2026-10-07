package dev.voir.reflector.sync.core.blob

/**
 * Progress of one file, as a user interface reads it.
 *
 * Published per file for a screen drawing one, and as a whole collection's worth through
 * [dev.voir.reflector.sync.core.CollectionHandle.blobs] for a screen that reports on all of them.
 *
 * Between them [state] and [wanted] cover every case a screen has to draw, which is why both are
 * here: the state says where the bytes are, and the flag says whether this device is trying to get
 * them. A file that is [BlobTransferState.REMOTE] and not wanted is an offer to download; the same
 * state while wanted is a download that has not started yet.
 *
 * @property state Where the bytes are. [BlobTransferState.REMOTE] and
 *   [BlobTransferState.DOWNLOADING] are ordinary rather than wrong — they are what an attachment
 *   looks like on a device whose record arrived ahead of its bytes.
 * @property wanted Whether this device is trying to hold the bytes: declared by
 *   [BlobFetch.EAGER], asked for through
 *   [dev.voir.reflector.sync.core.CollectionHandle.fetch], or implied by a file whose bytes are
 *   already here. It is `false` only for a file the server has, this device does not, and nobody
 *   has asked for — which under [BlobFetch.ON_DEMAND] is the ordinary resting state of most files.
 * @property size Length of the file in octets, or `null` before anything has declared it. A file
 *   that has never been fetched has never been described either, so a screen that wants to show
 *   what a download would cost before starting it takes that from its own document rather than from
 *   here.
 * @property transferred Octets moved so far in the current direction, for a progress indicator. It
 *   is reset when a transfer starts again, so a retry counts from zero rather than continuing a
 *   figure that no longer describes anything.
 * @property lastError What the most recent attempt failed with, or `null` when none has failed.
 *
 *   A description rather than a [BlobFailure], and the difference is the point. The typed failure is
 *   a decision the application has to make and is delivered where decisions belong, to
 *   [BlobStore.onFailed], once and at the moment nothing more will be tried. This is for drawing:
 *   a value here while the state is still moving means an attempt failed and another is coming, and
 *   a screen that treated it as final would tell the user a photograph was lost every time a train
 *   went into a tunnel.
 * @property attempts Attempts that have failed since the transfer last started from zero. An upload
 *   is never given up on while its bytes are here, so this — not the state — is what says one keeps
 *   failing. Waiting for a file that another device has not finished sending is not counted.
 */
public data class BlobSyncState(
    public val state: BlobTransferState,
    public val wanted: Boolean,
    public val size: Long?,
    public val transferred: Long,
    public val lastError: String?,
    public val attempts: Int,
)
