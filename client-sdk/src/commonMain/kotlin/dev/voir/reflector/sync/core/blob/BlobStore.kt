package dev.voir.reflector.sync.core.blob

import dev.voir.reflector.sync.protocol.BlobId
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Where a file's bytes live on this device, implemented by the application.
 *
 * The library stores the metadata of file synchronisation and never the files. It does not know
 * where the application keeps them, whether they are encrypted, or whether they are worth keeping
 * once nothing references them — so all three questions stay here.
 *
 * **Implementing this port is how an application opts into files at all.** An engine assembled
 * without one never calls [dev.voir.reflector.sync.core.adapter.CollectionAdapter.blobs] and never
 * enters a blob path.
 *
 * Unlike the adapter, these methods are **not** called inside the library's transaction. A transfer
 * takes as long as it takes, and a database write lock held across one would stop the collection.
 */
public interface BlobStore {
    /**
     * Describes the bytes this device holds for a file.
     *
     * The library's only way to tell a file it can upload from one it has to download: a reference
     * to a blob this answers for is one whose bytes are here.
     *
     * @param blobId File to describe.
     * @return What the bytes are, or `null` when this device does not have them.
     */
    public suspend fun stat(blobId: BlobId): BlobStat?

    /**
     * Opens the bytes of a file for sending.
     *
     * Called once per upload attempt; the library closes the source. A file that has disappeared
     * between [stat] and here is an ordinary outcome rather than a defect — throw, and the failure
     * is reported as [BlobFailure.SourceMissing].
     *
     * @param blobId File to send.
     * @return Bytes of the file.
     */
    public suspend fun read(blobId: BlobId): RawSource

    /**
     * Opens somewhere to put the bytes of a file that is arriving.
     *
     * The library writes the whole file and closes the sink, then verifies what arrived against
     * what was promised. An implementation should write somewhere temporary and move the file into
     * place only when [finish] says so: a download interrupted halfway must not leave a truncated
     * file where the application's own code will find it and believe it.
     *
     * @param blobId File that is arriving.
     * @param stat What the bytes are expected to be.
     * @return Where to write them.
     */
    public suspend fun write(
        blobId: BlobId,
        stat: BlobStat,
    ): RawSink

    /**
     * Reports that a download finished, and whether what arrived was what was promised.
     *
     * @param blobId File that was arriving.
     * @param complete Whether the bytes arrived in full and matched what was declared. When `false`
     *   the partial file must be discarded; the library will try again.
     */
    public suspend fun finish(
        blobId: BlobId,
        complete: Boolean,
    )

    /**
     * Offers a file that nothing synchronised references any more.
     *
     * An offer rather than an instruction: the library will not delete a user's bytes on a judgement
     * of its own, and an implementation that keeps the file — for an undo stack, for a local-only
     * album — is correct and need do nothing here.
     *
     * The exceptions are sign-out, a revoked scope and a purged collection. There this is called for
     * every file of what went away, and it is not eviction but the same act as wiping the rows:
     * leaving a signed-out user's photographs on a shared device is not a policy question.
     *
     * @param blobId File nothing references any more.
     */
    public suspend fun remove(blobId: BlobId)

    /**
     * Reports that a file will not arrive.
     *
     * Under [BlobBinding.DEFERRED] this is the only notice the application gets, and the record the
     * file belongs to is already published — so a user interface showing a broken attachment, and
     * offering to retry or to detach it, is built on this call.
     *
     * @param blobId File that will not arrive.
     * @param failure Why not.
     */
    public suspend fun onFailed(
        blobId: BlobId,
        failure: BlobFailure,
    )
}
