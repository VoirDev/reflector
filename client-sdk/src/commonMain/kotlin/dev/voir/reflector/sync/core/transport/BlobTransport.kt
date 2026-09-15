package dev.voir.reflector.sync.core.transport

import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobDownload
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobRegistration
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * The engine's view of files on the server, and of the storage behind it.
 *
 * Five calls, and they are of two kinds that must not be confused. [register], [complete] and
 * [ticket] go to the synchronisation server and carry the scope's credentials like every other
 * request. [upload] and [download] go to **somebody else entirely** — the host's object storage,
 * addressed by a presigned URL — and must carry no credential of this library's at all.
 *
 * That is the reason this is a port with a shipped implementation rather than something left to the
 * application. A presigned request has to go out with exactly the headers the ticket names and
 * nothing else, must not pass through content negotiation, and must stream rather than buffer a
 * file into memory. Three things to get subtly wrong in every integration, against an SDK that
 * already owns an HTTP client.
 */
public interface BlobTransport {
    /**
     * Registers a file and asks for permission to send it.
     *
     * @param scope Scope of the collection.
     * @param collection Collection the file belongs to.
     * @param descriptor What this device declares about the file.
     * @return What the server knows, and permission to send when there is anything to send.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun register(
        scope: ScopeId,
        collection: CollectionId,
        descriptor: BlobDescriptor,
    ): BlobRegistration

    /**
     * Tells the server the bytes are in its storage, so that it verifies and accepts them.
     *
     * @param scope Scope of the collection.
     * @param collection Collection the file belongs to.
     * @param blobId File whose bytes were sent.
     * @return What the server knows about the file afterwards.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun complete(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobInfo

    /**
     * Asks what a file is and for permission to fetch it.
     *
     * A file whose bytes the server has not accepted yet comes back with no permission and is not a
     * failure: it is the ordinary state of an attachment whose record arrived first.
     *
     * @param scope Scope of the collection.
     * @param collection Collection the file belongs to.
     * @param blobId File to fetch.
     * @return What the server knows, and permission to fetch when there is anything to fetch.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun ticket(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobDownload

    /**
     * Sends a file's bytes to the host's storage.
     *
     * Performs exactly the request the ticket describes, with exactly its headers, and **without**
     * the scope's credentials: the destination is not the synchronisation server and has no business
     * seeing them.
     *
     * @param ticket Permission to write, as the server issued it.
     * @param content Bytes to send; closed by the implementation.
     * @param stat What the bytes are, for the length the request has to declare.
     * @param onProgress Told the running total of octets sent, so that a user interface can show a
     *   large file moving. Called from the transfer, often, and must not block.
     * @throws SyncTransportFailure When the transfer could not be completed.
     */
    public suspend fun upload(
        ticket: BlobTicket,
        content: RawSource,
        stat: BlobStat,
        onProgress: (Long) -> Unit = {},
    )

    /**
     * Fetches a file's bytes from the host's storage into the application's own store.
     *
     * @param ticket Permission to read, as the server issued it.
     * @param into Where to write the bytes; closed by the implementation.
     * @param onProgress Told the running total of octets written, so that a user interface can show
     *   a large file arriving. Called from the transfer, often, and must not block.
     * @return Number of octets written, so that the caller can compare it with what was promised.
     * @throws SyncTransportFailure When the transfer could not be completed.
     */
    public suspend fun download(
        ticket: BlobTicket,
        into: RawSink,
        onProgress: (Long) -> Unit = {},
    ): Long
}
