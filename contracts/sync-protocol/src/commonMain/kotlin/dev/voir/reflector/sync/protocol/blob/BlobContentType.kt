package dev.voir.reflector.sync.protocol.blob

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Media type the application declares for a blob's bytes.
 *
 * Declared by the application and stored by the server without being read: the module never parses
 * a blob, never validates that the bytes match the type, and never decides anything from it. What
 * it is for is the host, which puts it on the stored object so that a later download serves the
 * right `Content-Type`, and the application, which decides how to render what it downloaded.
 *
 * A host that wants the declaration checked against the bytes does that in its own storage port,
 * where it is holding the object anyway.
 *
 * @property value Media type as the application declared it, for example `image/jpeg`.
 */
@Serializable
@JvmInline
public value class BlobContentType(
    public val value: String,
)
