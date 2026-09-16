package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the server knows about a blob's bytes.
 *
 * The set is closed and deliberately small: a blob is either being uploaded or usable, and nothing
 * else about it is the protocol's business. Processing the host performs after an upload —
 * thumbnails, transcoding, scanning — is invisible here, because a client cannot act on it; a host
 * that needs clients to see a processing stage puts it in a document, which is the thing built to
 * carry facts about the application's data.
 *
 * Unlike a refusal code, this is not an open set. A third state would change what a client must do
 * with a blob and therefore cannot be introduced without a protocol version, which is the same rule
 * the change log's operation codes are under.
 */
@Serializable
public enum class BlobState {
    /** Registered, and the bytes have not been confirmed to have arrived. No download is served. */
    @SerialName("pending")
    PENDING,

    /** The bytes are in the host's storage and were verified against what the client declared. */
    @SerialName("ready")
    READY,
}
