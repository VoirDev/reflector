package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable

/**
 * Answer to registering a blob: what the server now knows, and how to send the bytes.
 *
 * Registration is idempotent by identifier, which is what makes the upload recoverable from any
 * interruption: a client that crashed after registering and before transferring registers again and
 * is handed a fresh ticket for the same blob. A client that registers one that is already
 * [BlobState.READY] is told so and given no ticket — the bytes are there, and a blob cannot be
 * rewritten.
 *
 * @property blob What the server knows about the blob after the registration.
 * @property upload Permission to send the bytes, or `null` when there is nothing left to send
 *   because the blob is already usable.
 */
@Serializable
public data class BlobRegistration(
    public val blob: BlobInfo,
    public val upload: BlobTicket? = null,
)
