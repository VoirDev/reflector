package dev.voir.reflector.sync.core.blob

import dev.voir.reflector.sync.protocol.BlobId

/**
 * A pointer from one document to one file, as the application declares it.
 *
 * The whole coupling between business data and file synchronisation. The library never reads a
 * document — which field of one names a blob is knowledge only the application's schema has — so
 * everything it does about files follows from the set of these the adapter returns.
 *
 * @property id Blob the document points at.
 * @property binding Whether the record may be published before this file is usable. Defaults to
 *   [BlobBinding.DEFERRED], which is both the short spelling and the safe one.
 * @property fetch Whether this device downloads the file on its own. `null` — the default — means
 *   the application has nothing to say about this particular reference and the policy the engine
 *   was configured with applies, which is how an application chooses once for all of its files and
 *   overrides that only where it matters.
 */
public data class BlobRef(
    public val id: BlobId,
    public val binding: BlobBinding = BlobBinding.DEFERRED,
    public val fetch: BlobFetch? = null,
)
