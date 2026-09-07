package dev.voir.reflector.sync.core.adapter

import kotlin.jvm.JvmInline

/**
 * The application's own description of the shape of the tables behind one collection.
 *
 * The library cannot notice that the application's rows stopped agreeing with what it synchronised.
 * A migration rewrites them, a repair after a bug rewrites them, an import puts rows there that no
 * server ever sent — and the cursor keeps pointing where it pointed, so nothing is ever re-read.
 * Declaring a fingerprint is how an application says "the tables are not what they were", and the
 * library answers by rebuilding the collection from a snapshot.
 *
 * Any stable string will do: a schema version number, a hash of the DDL, the application's own
 * migration identifier. The library only compares it for equality with the one stored when the
 * collection was last synchronised, and never parses or orders it — a fingerprint that changed
 * back to an earlier value is still a change.
 *
 * @property value Description of the current shape, compared for equality and nothing else.
 */
@JvmInline
public value class SchemaFingerprint(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "a blank fingerprint cannot be distinguished from having declared none" }
    }
}
