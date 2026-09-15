package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Read access to stored documents, for the host's own purposes.
 *
 * The module stores entity bodies but does not model them, so the host has no relational access to
 * this data. That is the honest cost of letting the module own the documents — and this API, plus a
 * [ProjectionListener] for hosts that need real relational reads, is what pays it back.
 */
public interface SyncQueries {
    /**
     * Reads one stored document.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the entity belongs to.
     * @param type Type of the entity.
     * @param id Identifier of the entity.
     * @return Stored document, or `null` when the entity is unknown or deleted.
     */
    public fun document(
        scope: ScopeId,
        collection: CollectionId,
        type: EntityType,
        id: EntityId,
    ): StoredDocument?

    /**
     * Reads a page of stored documents of one type.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to read.
     * @param type Type of the entities to return.
     * @param page Continuation token, or `null` for the first page.
     * @param limit Largest number of documents to return.
     * @return Page of documents, excluding deleted entities.
     */
    public fun documents(
        scope: ScopeId,
        collection: CollectionId,
        type: EntityType,
        page: PageToken?,
        limit: Int,
    ): DocumentPage

    /**
     * Reads what the module knows about one file.
     *
     * Everything except the bytes, which the module has never seen: what the client declared, the
     * key the host's storage chose, whether the bytes were accepted, and since when nothing has
     * pointed at it.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the file belongs to.
     * @param blobId Identifier of the file.
     * @return What the module knows, or `null` when it knows nothing about the file.
     */
    public fun blob(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): StoredBlob?

    /**
     * Reads a page of the files a collection holds.
     *
     * The read a host builds an administrative screen on, and the one that answers questions the
     * protocol cannot: how much storage a scope is using, which of its files never finished
     * uploading, and which are waiting out the retention window before the collector offers them
     * back. None of that is visible from the client side, where a file is either there or not.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to read.
     * @param page Continuation token, or `null` for the first page.
     * @param limit Largest number of files to return.
     * @return Page of files, including those whose bytes have not been accepted.
     */
    public fun blobs(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): BlobPage
}
