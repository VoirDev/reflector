package dev.voir.reflector.sync.protocol

import kotlinx.serialization.json.Json

/**
 * JSON format shared by both sides of the synchronisation protocol.
 *
 * The configuration is part of the wire contract rather than a matter of taste:
 * - `explicitNulls` stays enabled, because the server merges an upsert by the keys present in the
 *   payload. A field omitted from the document keeps its stored value, while an explicit `null`
 *   clears it, so dropping nulls silently turns "clear this field" into "leave it alone".
 * - `encodeDefaults` stays enabled for the same reason: a default value that is not written is
 *   indistinguishable from a field the client does not know about.
 * - `ignoreUnknownKeys` is enabled so that an older client survives a server that added a field.
 *   Unknown *operation codes* are handled separately and are never ignored, because skipping an
 *   operation while the cursor moves on loses the change forever.
 */
public object SyncProtocolJson {
    /** Configured format instance; safe to share, as [Json] is immutable and thread-safe. */
    public val format: Json =
        Json {
            explicitNulls = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
}
