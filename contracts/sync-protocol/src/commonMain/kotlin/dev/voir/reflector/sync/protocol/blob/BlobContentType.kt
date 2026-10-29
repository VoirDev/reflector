package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Media type the application declares for a blob's bytes.
 *
 * Declared by the application and stored by the server without being read: the module never parses
 * a blob and never validates that the bytes match the type. What it is for is the host, which puts
 * it on the stored object so that a later download serves the right `Content-Type`, and the
 * application, which decides how to render what it downloaded.
 *
 * A host that accepts only some types says so through its configuration, and the module refuses the
 * rest at registration with [BlobRefusalCode.UNSUPPORTED_TYPE]. A host that wants the declaration
 * checked against the bytes does that in its own storage port, where it is holding the object anyway,
 * and the module refuses what it rejects with [BlobRefusalCode.CONTENT_REJECTED].
 *
 * @property value Media type as the application declared it, for example `image/jpeg`.
 */
@Serializable
@JvmInline
public value class BlobContentType(
    public val value: String,
)
