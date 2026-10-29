package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.blob.BlobContentType

/**
 * Which declared media types the host is willing to store.
 *
 * Asked at registration, before a ticket is issued, for the same reason the size limit is: a file the
 * host would never keep should be refused before anybody pays to transfer it. It judges the
 * declaration alone — the bytes do not exist yet — so a host that also wants the bytes checked does
 * that when it is asked to verify them, and answers [BlobVerification.Rejected].
 *
 * Called outside every transaction and on every registration, so it has to be a cheap, local
 * decision: a set lookup, not a request to anything.
 */
public fun interface BlobTypePolicy {
    /**
     * Whether a file declaring this media type may be registered.
     *
     * @param contentType Media type exactly as the client declared it, parameters and case included;
     *   normalising it is the host's decision.
     * @return `true` to issue a ticket, `false` to refuse the file as an unsupported type.
     */
    public fun accepts(contentType: BlobContentType): Boolean

    /** Policies every deployment can name. */
    public companion object {
        /** Accepts every declared type, which is what a deployment without an opinion gets. */
        public val ANY: BlobTypePolicy = BlobTypePolicy { true }
    }
}
