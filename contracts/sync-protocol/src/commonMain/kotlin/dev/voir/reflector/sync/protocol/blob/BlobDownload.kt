package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable

/**
 * Answer to asking for a blob's bytes: what it is, and permission to fetch it when there is any.
 *
 * A blob that is registered and still uploading is **not** a failure and is not reported as one. It
 * is the ordinary state of an attachment whose record reached this device ahead of its bytes, which
 * is what the default binding of a reference produces on purpose; the answer says
 * [BlobState.PENDING] and carries no ticket, and the client retries on a capped backoff or as soon
 * as the scope's channel says the blob became ready.
 *
 * Saying it in the state rather than in a status code is deliberate. The refusal codes on the
 * neighbouring sync paths already carry three different recoveries — refused credentials, a revoked
 * scope, a purged collection, a stale cursor — and a fourth meaning for one of them is how a client
 * ends up mapping one of the others wrong. A blob that exists but is not servable yet is
 * information, not a failure.
 *
 * @property blob What the server knows about the blob.
 * @property download Permission to fetch the bytes, or `null` while the blob is not usable yet.
 */
@Serializable
public data class BlobDownload(
    public val blob: BlobInfo,
    public val download: BlobTicket? = null,
)
