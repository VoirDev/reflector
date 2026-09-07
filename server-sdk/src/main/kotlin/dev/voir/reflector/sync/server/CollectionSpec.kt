package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityType

/**
 * A collection the module is allowed to synchronise.
 *
 * Registration is mandatory rather than convenient. Without it a typo in a client would quietly
 * create a collection nobody recognises, and half a year later nobody would dare delete it either.
 *
 * @property id Identifier clients address the collection by.
 * @property entityTypes Types this collection accepts; anything else is refused.
 * @property maxDocumentBytes Largest serialised document accepted for one entity.
 */
public data class CollectionSpec(
    public val id: CollectionId,
    public val entityTypes: Set<EntityType>,
    public val maxDocumentBytes: Int = DEFAULT_MAX_DOCUMENT_BYTES,
) {
    init {
        require(entityTypes.isNotEmpty()) { "collection ${id.value} has to accept at least one entity type" }
        require(maxDocumentBytes > 0) { "document limit of collection ${id.value} has to be positive" }
    }

    private companion object {
        /** Generous for a document, small enough that one row cannot dominate a batch. */
        const val DEFAULT_MAX_DOCUMENT_BYTES = 256 * 1024
    }
}
