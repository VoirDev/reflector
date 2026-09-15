package dev.voir.reflector.sync.core.blob

/**
 * Why a file will not arrive.
 *
 * The cases are separated by what the application can do about them, which is the same rule the
 * rest of this library's failures follow. Two of them are the application's own data to correct, one
 * is the user's to redo, and one says the file is simply gone.
 *
 * None of them is ever a reason for the library to change the application's document. Dropping a
 * reference is a decision with a user behind it — a receipt that failed to upload may be worth
 * asking about rather than silently forgetting — so the library reports and the application acts.
 */
public sealed class BlobFailure {
    /** Description for logs and for the application's own error reporting. */
    public abstract val message: String

    /**
     * The bytes are not in the application's own store any more.
     *
     * The file was on storage that has gone, or was cleaned up before it could be sent. Nothing can
     * recover it: the only ways forward are to attach a different file or to drop the reference.
     *
     * @property message Description of what the store reported.
     */
    public data class SourceMissing(
        override val message: String,
    ) : BlobFailure()

    /**
     * The server no longer has the file.
     *
     * Usually collected as garbage while this device was away, or erased with its collection. A
     * device that never fetched the bytes cannot produce them, so this is a reference the
     * application has to decide about rather than a transfer to retry.
     *
     * @property message Description reported by the server.
     */
    public data class Gone(
        override val message: String,
    ) : BlobFailure()

    /**
     * The file is larger than the server accepts.
     *
     * Refused before any of it was transferred, which is the point of the limit being published.
     *
     * @property size Size of the file, in octets.
     * @property limit Largest size this deployment accepts.
     * @property message Description of the refusal.
     */
    public data class TooLarge(
        public val size: Long,
        public val limit: Long,
        override val message: String,
    ) : BlobFailure()

    /**
     * The transfer was attempted enough times and never succeeded.
     *
     * Distinct from the others because nothing is known to be wrong with either side: the bytes
     * exist, the server wants them, and the transfers kept failing. A retry later may well work, and
     * the application is told so that it can offer one rather than leaving a spinner running.
     *
     * @property attempts How many transfers were tried.
     * @property message Description of the last failure.
     */
    public data class Exhausted(
        public val attempts: Int,
        override val message: String,
    ) : BlobFailure()
}
