package dev.voir.reflector.sync.server.postgres

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * What each entity's document points at.
 *
 * The set is the client's declaration rather than anything the module derives: it does not read
 * documents, and which field of one names a blob is knowledge only the application's own schema
 * has. Every write of an entity replaces its rows here whole, which is what makes references
 * state-based like the rest of the protocol — there is no "reference added" or "reference removed"
 * event that could be lost.
 *
 * The row carries no identifier of its own. It is a pure association with no lifecycle, no audit
 * identity and nothing that could address it: its natural key is all four columns, and a surrogate
 * would be a column nothing would ever read.
 */
internal object BlobRefsTable : Table("sync.blob_refs") {
    /** Collection both the entity and the blob belong to. */
    val collection = uuid("collection_id")

    /** Type of the entity whose document holds the reference. */
    val entityType = varchar("entity_type", CollectionsTable.COLLECTION_LENGTH)

    /** Identifier of the entity whose document holds the reference. */
    val entityId = uuid("entity_id")

    /** Blob the document points at. */
    val blobId = uuid("blob_id")

    override val primaryKey: PrimaryKey = PrimaryKey(collection, entityType, entityId, blobId, name = "pk_blob_refs")

    init {
        // A reference cannot exist without its blob, which is the one case where cascading a
        // deletion is right. It is not what protects a blob in use from being collected — that is
        // `unreferenced_since` — and it is also what lets a purge stay simple, since the cascade
        // from `sync.collections` then reaches this table through `sync.blobs`.
        foreignKey(
            collection to BlobsTable.collection,
            blobId to BlobsTable.blobId,
            onDelete = ReferenceOption.CASCADE,
            name = "fk_blob_refs__collection_id__blob_id__blobs",
        )
        index("ix_blob_refs__collection_id__blob_id", false, collection, blobId)
    }
}
