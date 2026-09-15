package dev.voir.reflector.sync.server

/**
 * Why a claim that a blob's bytes were stored was refused.
 *
 * Split because the two mean different things about a deployment. One says transfers are being cut
 * off, which is the network; the other says bytes arrived and are not the bytes that were promised,
 * which is a client bug or a storage that is rewriting what it is given.
 */
public enum class BlobRefusalReason {
    /** The storage holds no object under the blob's key. */
    ABSENT,

    /** The stored object is there and its size or checksum disagrees with the declaration. */
    MISMATCHED,
}
