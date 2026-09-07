package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identifier of a collection: the unit of consistency, ordering and cursor progress.
 *
 * Changes are totally ordered inside one collection and unordered between collections, so a
 * collection is also the unit of bootstrap, push queue and background worker. Collections are
 * registered on the server; an unregistered identifier is rejected rather than created on the fly.
 *
 * @property value Stable collection name, unique inside a scope.
 */
@Serializable
@JvmInline
public value class CollectionId(
    public val value: String,
)
