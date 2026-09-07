package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque sequence token of one committed batch inside a collection.
 *
 * A batch is one server transaction, so the sequence is the unit of both ordering and cursor
 * progress: the client stores the sequence of the last applied batch as its cursor. Like a cursor,
 * the value is never parsed or ordered by the client.
 *
 * @property value Opaque server-defined sequence token.
 */
@Serializable
@JvmInline
public value class BatchSeq(
    public val value: String,
) {
    /**
     * Returns the cursor position reached after the batch with this sequence has been applied.
     *
     * Cursors and batch sequences share one token space by protocol contract; the conversion is
     * explicit so that a cursor is never built from anything but an applied batch.
     *
     * @return Cursor pointing at this batch boundary.
     */
    public fun asCursor(): Cursor = Cursor(value)
}
