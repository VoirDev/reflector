package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque digest of a blob's bytes, as whoever produced it spells it.
 *
 * The protocol defines no algorithm and never computes one. Both sides compare checksums **only for
 * equality**: the server hands what it was told to the host's storage, which decides whether the
 * object it holds agrees, and a client compares what it downloaded against what it was promised.
 *
 * It is optional throughout, because requiring one would mean requiring a hash function the Kotlin
 * standard library does not offer on every target, and dragging a cryptography dependency into a
 * multiplatform library to enforce a check the host can already do. Where it is absent, verification
 * falls back on the declared size, which catches a truncated transfer and nothing subtler.
 *
 * @property value Digest as its producer spells it, for example `sha256:9f86d0…`.
 */
@Serializable
@JvmInline
public value class BlobChecksum(
    public val value: String,
)
