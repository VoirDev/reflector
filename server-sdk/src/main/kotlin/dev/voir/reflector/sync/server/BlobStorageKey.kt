package dev.voir.reflector.sync.server

import kotlin.jvm.JvmInline

/**
 * Name the host's storage knows a blob's object by.
 *
 * Produced by the host when it presigns the upload, kept by the module, and handed back on every
 * later operation about that blob. The module never parses it, never builds one and never assumes a
 * shape: a bucket key, a path on a disk and an opaque identifier in a content store are all the same
 * thing from here.
 *
 * It is a type of its own rather than a `String` because everything else the storage port passes
 * around is also a string — a URL, a content type, a checksum — and handing the wrong one to a
 * bucket fails somewhere far away from the mistake.
 *
 * @property value Key as the host's storage spells it.
 */
@JvmInline
public value class BlobStorageKey(
    public val value: String,
)
