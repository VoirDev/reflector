package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque sequence token of one committed batch inside a collection.
 *
 * A batch is one server transaction, so the sequence is the unit of ordering. The value is never
 * parsed or ordered by the client, and it is **not** a cursor: a cursor also says which incarnation
 * of the collection the position belongs to, and the server serves one per batch rather than
 * letting the client build one from this.
 *
 * @property value Opaque server-defined sequence token.
 */
@Serializable
@JvmInline
public value class BatchSeq(
    public val value: String,
)
