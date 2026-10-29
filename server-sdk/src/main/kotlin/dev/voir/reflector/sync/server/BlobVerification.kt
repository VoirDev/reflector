package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.blob.BlobChecksum

/**
 * What the host's storage found when asked about a blob's object.
 *
 * The host observes, the module decides. That split is deliberate: only the host can look inside its
 * bucket, and only the module knows what the client declared, so a host that also judged would be
 * re-implementing the comparison in every integration — and getting the checksum case wrong in most
 * of them, because "the client declared none" is not the same as "they disagree".
 */
public sealed class BlobVerification {
    /**
     * The object is there.
     *
     * @property size Length of the stored object in octets, as the storage reports it.
     * @property checksum Digest the storage reports, or `null` when it computes none. A `null` here
     *   never fails a verification: it means the storage cannot answer, not that the bytes are
     *   wrong, and the size comparison still applies.
     */
    public data class Stored(
        public val size: Long,
        public val checksum: BlobChecksum? = null,
    ) : BlobVerification()

    /**
     * There is no object under that key.
     *
     * The ordinary answer for an upload that was never finished, and the reason a client's word that
     * it uploaded is not enough on its own.
     */
    public data object Absent : BlobVerification()

    /**
     * The object is there and the host will not keep it.
     *
     * The one judgement a host makes here, because it is the one only the host can make: whether the
     * bytes are what it is willing to serve — usually whether they are what the declared media type
     * says they are. The module refuses the blob for good; the client is told not to send it again.
     *
     * The object is left where it is. The blob stays registered and unusable, so nothing will ever
     * serve it, and it is collected like any other blob once no document references it. A client
     * that registers the same identifier with different bytes overwrites it.
     *
     * @property reason What the host found, for its logs. It reaches the client as the refusal's
     *   message, so it must name nothing a client should not see.
     */
    public data class Rejected(
        public val reason: String,
    ) : BlobVerification()
}
