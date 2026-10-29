package dev.voir.reflector.sync.server

/**
 * Why a claim that a blob's bytes were stored was refused.
 *
 * Split because each means something different about a deployment. One says transfers are being cut
 * off, which is the network; one says bytes arrived and are not the bytes that were promised, which
 * is a client bug or a storage that is rewriting what it is given; and one says the bytes are the
 * promised ones and the host will not keep them, which is a client whose own checks disagree with
 * the host's.
 */
public enum class BlobRefusalReason {
    /** The storage holds no object under the blob's key. */
    ABSENT,

    /** The stored object is there and its size or checksum disagrees with the declaration. */
    MISMATCHED,

    /** The stored object is there and the host's storage port rejected its content. */
    REJECTED,
}
