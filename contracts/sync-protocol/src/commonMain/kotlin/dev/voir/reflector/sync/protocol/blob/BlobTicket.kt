package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One-shot permission to move a blob's bytes, issued by the host and performed by the device.
 *
 * This is the whole of how bytes travel: neither the server module nor the client library is ever in
 * the data path. The host presigns a request against its own storage — S3, GCS, a signed endpoint of
 * its own, a directory in a test — and the device performs exactly that request. Nothing in either
 * SDK interprets [url] or [headers], which is why swapping the storage behind a host changes no line
 * of this protocol.
 *
 * **A ticket is a bearer capability and is treated as one.** It is never written to a database, never
 * put in a log or a diagnostic record, and never reused after the transfer it was issued for: a
 * client that needs to transfer again asks for a new one. [expiresAt] is reported for diagnostics
 * rather than for a decision — a client that let a ticket expire mid-transfer learns so from the
 * failed request, which is the same recovery as any other interrupted transfer, and one that trusted
 * a device clock instead would refuse a valid ticket whenever the clock was skewed.
 *
 * @property method Method the request has to use.
 * @property url Absolute URL to send the request to, opaque to both SDKs.
 * @property headers Headers the request has to carry for the host's storage to accept it, which is
 *   where a presigned upload puts its content type and its signature. Sent exactly as given and
 *   never merged with the SDK's own headers; in particular the scope's credentials are **not** sent
 *   to this URL, because it is not the sync server and has no business seeing them.
 * @property expiresAt When the host stops honouring the ticket, for logs and diagnostics only.
 */
@Serializable
public data class BlobTicket(
    public val method: BlobTicketMethod,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val expiresAt: Instant,
)
