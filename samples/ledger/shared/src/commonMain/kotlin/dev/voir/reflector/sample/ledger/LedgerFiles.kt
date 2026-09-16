package dev.voir.reflector.sample.ledger

import dev.voir.reflector.sync.core.blob.BlobFailure
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Where the demonstration application keeps the bytes of the files its wallets point at.
 *
 * A directory and nothing cleverer, which is the point: the library never learns where this is,
 * whether the files are encrypted, or whether they are worth keeping once nothing references them.
 * A real application would put this under its private storage and probably keep thumbnails beside
 * the originals; none of that would change a line of the library.
 *
 * A download is written to a neighbouring file and moved into place only once the library says the
 * bytes arrived in full. Writing straight to the final path would leave a truncated photograph
 * exactly where this application's own code looks for one, and nothing downstream could tell it from
 * a complete file.
 *
 * @property directory Where the files live; created on first use.
 */
class LedgerFiles(
    private val directory: Path,
) : BlobStore {
    /** Files this store was told nothing references any more, in order, for the sample's tests. */
    val released: MutableList<BlobId> = mutableListOf()

    /** Files the library said will never arrive, with the reason, for the sample's tests. */
    val failures: MutableList<Pair<BlobId, BlobFailure>> = mutableListOf()

    init {
        SystemFileSystem.createDirectories(directory)
    }

    /**
     * Writes a file the user has just attached.
     *
     * Called by the application before the mutation that references it, which is the order the
     * library requires: a document may not name a file whose bytes are not there yet.
     *
     * @param blobId Identifier the application generated for the file.
     * @param bytes Contents of the file.
     */
    fun put(
        blobId: BlobId,
        bytes: ByteArray,
    ) {
        SystemFileSystem.sink(pathOf(blobId)).use { sink ->
            sink.write(kotlinx.io.Buffer().apply { write(bytes) }, bytes.size.toLong())
        }
    }

    /**
     * Reads a file back, for this application's own screens.
     *
     * @param blobId File to read.
     * @return Contents, or `null` when this device does not hold them.
     */
    fun contentsOf(blobId: BlobId): ByteArray? {
        val path = pathOf(blobId)
        if (!SystemFileSystem.exists(path)) {
            return null
        }
        return SystemFileSystem.source(path).use { source ->
            val buffer = kotlinx.io.Buffer()
            while (source.readAtMostTo(buffer, CHUNK) > 0L) {
                // Drained into the buffer; a real application would stream this into a decoder.
            }
            buffer.readByteArray()
        }
    }

    override suspend fun stat(blobId: BlobId): BlobStat? {
        val metadata = SystemFileSystem.metadataOrNull(pathOf(blobId)) ?: return null
        return BlobStat(size = metadata.size, contentType = BlobContentType(CONTENT_TYPE))
    }

    override suspend fun read(blobId: BlobId): RawSource = SystemFileSystem.source(pathOf(blobId))

    override suspend fun write(
        blobId: BlobId,
        stat: BlobStat,
    ): RawSink = SystemFileSystem.sink(partialPathOf(blobId))

    override suspend fun finish(
        blobId: BlobId,
        complete: Boolean,
    ) {
        val partial = partialPathOf(blobId)
        if (complete) {
            SystemFileSystem.atomicMove(partial, pathOf(blobId))
        } else {
            SystemFileSystem.delete(partial, mustExist = false)
        }
    }

    override suspend fun remove(blobId: BlobId) {
        released += blobId
        // Taken up rather than declined, which is this application's choice and not the library's:
        // there is no undo here to keep a detached photograph for.
        SystemFileSystem.delete(pathOf(blobId), mustExist = false)
    }

    override suspend fun onFailed(
        blobId: BlobId,
        failure: BlobFailure,
    ) {
        // A real application would show a broken attachment and offer to retry or detach it. What it
        // must not do is nothing: under the default binding this is the only notice it gets, and the
        // record naming the file is already on every device.
        failures += blobId to failure
    }

    private fun pathOf(blobId: BlobId): Path = Path(directory, blobId.value.toString())

    private fun partialPathOf(blobId: BlobId): Path = Path(directory, "${blobId.value}.partial")

    private companion object {
        /** What this sample pretends every attachment is. */
        const val CONTENT_TYPE = "image/jpeg"

        const val CHUNK = 64L * 1024
    }
}
