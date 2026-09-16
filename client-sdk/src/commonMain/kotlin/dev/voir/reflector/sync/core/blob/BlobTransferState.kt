package dev.voir.reflector.sync.core.blob

/**
 * Where a file's bytes are, as far as this device knows.
 *
 * Two of these are states an application's user interface has to render as ordinary rather than as
 * errors: [REMOTE] and [DOWNLOADING] are what a file looks like on a device whose record arrived
 * ahead of its bytes, which is the ordinary result of [BlobBinding.DEFERRED] and not a fault
 * anybody can act on.
 */
public enum class BlobTransferState {
    /** The bytes are on this device and the server does not know the file yet. */
    LOCAL,

    /** The bytes are on this device and are being sent. */
    UPLOADING,

    /** The bytes are on this device and the server has accepted them. */
    UPLOADED,

    /** The server has the file and this device does not. */
    REMOTE,

    /** The server has the file and it is arriving. */
    DOWNLOADING,

    /** The bytes are on this device and the server has them too. */
    READY,

    /**
     * The bytes cannot be obtained and nothing further will be tried.
     *
     * Reached from either direction: a local file that was gone before it could be uploaded, or a
     * server that no longer has one this device never fetched. Only the application can decide what
     * to do about it, which is usually to drop the reference in a new mutation or to offer the user
     * a way to attach the file again.
     */
    UNAVAILABLE,
}
