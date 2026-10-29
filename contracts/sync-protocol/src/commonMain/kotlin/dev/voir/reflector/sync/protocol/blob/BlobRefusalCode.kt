package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Machine-readable reason the server refused a file.
 *
 * Carried in the body of the `409` a blob path answers with, so that a client can tell a refusal it
 * may outlast from one it never will. Before the body existed both looked the same, and a file the
 * server would never take was retried until the client ran out of attempts and then reported as a
 * transfer that kept failing — which tells a user to try again later about a file that will be
 * refused again later.
 *
 * Open rather than an enum, for the same reason as the push refusal codes: a server may learn a
 * reason before its clients do, and an unknown code must arrive as data instead of failing to parse.
 * A client that does not recognise a code treats the file as refused for good, which is the safe
 * default — it stops sending bytes the server has already said it will not keep.
 *
 * @property value Wire representation of the code.
 */
@Serializable
@JvmInline
public value class BlobRefusalCode(
    public val value: String,
) {
    /** Codes both sides of the protocol agree on. */
    public companion object {
        /**
         * The identifier already names a usable file with different bytes.
         *
         * A client bug rather than a refusal of the file itself, and answered the way it always was:
         * the transfer is tried again and given up on after the client's bounded attempts.
         */
        public val CONFLICT: BlobRefusalCode = BlobRefusalCode("CONFLICT")

        /**
         * The upload was claimed finished and the storage does not bear that out.
         *
         * The one refusal a retry can fix: nothing was accepted under the identifier, so the client
         * sends the bytes again.
         */
        public val NOT_STORED: BlobRefusalCode = BlobRefusalCode("NOT_STORED")

        /** The declared size exceeds the limit the server publishes. Final. */
        public val TOO_LARGE: BlobRefusalCode = BlobRefusalCode("TOO_LARGE")

        /** The declared media type is not one the host accepts. Final. */
        public val UNSUPPORTED_TYPE: BlobRefusalCode = BlobRefusalCode("UNSUPPORTED_TYPE")

        /**
         * The bytes arrived and the host will not keep them, usually because they are not what the
         * declared media type says they are. Final.
         */
        public val CONTENT_REJECTED: BlobRefusalCode = BlobRefusalCode("CONTENT_REJECTED")
    }
}
