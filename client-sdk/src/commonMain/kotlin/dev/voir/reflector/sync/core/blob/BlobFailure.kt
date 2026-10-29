package dev.voir.reflector.sync.core.blob

import dev.voir.reflector.sync.protocol.blob.BlobRefusalCode

/**
 * Why a file will not arrive.
 *
 * The cases are separated by what the application can do about them, which is the same rule the
 * rest of this library's failures follow. Three of them are the application's own data to correct,
 * one is the user's to redo, and one says the file is simply gone.
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
     * The server refused the file, and would refuse it again.
     *
     * Its declared type is not one the server accepts, its size is over a limit the server enforces
     * although this device had not yet heard of it, or its content was rejected once the server had
     * it. Sending the same bytes again cannot change any of those answers, so nothing is retried;
     * the application can attach a different file, drop the reference, or explain to its user why
     * the file is not on their other devices.
     *
     * @property code Why, as the server said; see
     *   [BlobRefusalCode][dev.voir.reflector.sync.protocol.blob.BlobRefusalCode] for the codes both
     *   sides agree on. A code this library does not know is still a refusal.
     * @property message Description the server gave, for logs. It is not a user-facing string.
     */
    public data class Refused(
        public val code: BlobRefusalCode,
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
