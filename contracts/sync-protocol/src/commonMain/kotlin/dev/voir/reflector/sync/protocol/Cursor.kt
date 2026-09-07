package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque position in a collection's change log.
 *
 * The client stores the cursor and sends it back unchanged; it never parses or orders cursors. A
 * cursor always points at a batch boundary, so resuming from it can neither skip nor split a
 * server transaction. It is advanced only together with applying the corresponding batch, in one
 * local transaction, otherwise a crash would leave the cursor ahead of the data.
 *
 * @property value Opaque server-defined position token.
 */
@Serializable
@JvmInline
public value class Cursor(
    public val value: String,
)
