package dev.voir.reflector.sync.server

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
}
