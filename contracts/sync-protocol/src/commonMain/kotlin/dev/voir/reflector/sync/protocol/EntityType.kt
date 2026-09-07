package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Type of a synchronised entity, such as `wallet` or `transaction`.
 *
 * The type only partitions the identifier space and routes an operation to the right adapter;
 * neither side of the protocol models the type's structure. Types are registered on the server
 * together with their collection, so a typo cannot silently create a new kind of data.
 *
 * @property value Stable type name, unique inside a collection.
 */
@Serializable
@JvmInline
public value class EntityType(
    public val value: String,
)
