package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identifier of a synchronisation scope: the boundary of data ownership and access.
 *
 * A scope is usually one user, but may be shared between several of them. The protocol never
 * interprets the value: it travels in the request path and is resolved by the host, which is also
 * the only party that decides whether a caller may access it.
 *
 * @property value Opaque host-defined identifier, stable for the lifetime of the scope.
 */
@Serializable
@JvmInline
public value class ScopeId(
    public val value: String,
)
