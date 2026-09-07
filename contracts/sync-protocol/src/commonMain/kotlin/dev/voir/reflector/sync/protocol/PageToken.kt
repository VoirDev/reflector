package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque continuation token of a paged snapshot read.
 *
 * Snapshot pages use keyset pagination, so the token encodes the last returned key rather than an
 * offset. It is valid only for the snapshot it was produced by and is not comparable or ordered.
 *
 * @property value Opaque server-defined continuation token.
 */
@Serializable
@JvmInline
public value class PageToken(
    public val value: String,
)
