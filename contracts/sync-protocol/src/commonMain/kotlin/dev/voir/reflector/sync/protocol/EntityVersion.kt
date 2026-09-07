package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Opaque version of one entity as known to the server.
 *
 * The value is produced by the server and compared **only for equality**: it is not ordered, not
 * parsed and not incremented by the client. A push carries the version the change was based on,
 * and the server rejects the operation when it no longer matches the stored one, which is how
 * optimistic concurrency is enforced.
 *
 * @property value Opaque server-defined version token.
 */
@Serializable
@JvmInline
public value class EntityVersion(
    public val value: String,
)
