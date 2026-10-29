package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable

/**
 * Body of a `409` answered on a blob path: why the server refused the file.
 *
 * Optional on the wire in the sense that matters: a host that predates it answers `409` with a body
 * of its own, and a client reads that as a refusal with no code, which it treats exactly as it
 * treated every refusal before codes existed.
 *
 * @property code Machine-readable reason; see [BlobRefusalCode] for the codes both sides agree on.
 * @property message Human-readable explanation for logs and diagnostics. It is not a user-facing
 *   string and must not be parsed.
 */
@Serializable
public data class BlobRefusal(
    public val code: BlobRefusalCode,
    public val message: String,
)
