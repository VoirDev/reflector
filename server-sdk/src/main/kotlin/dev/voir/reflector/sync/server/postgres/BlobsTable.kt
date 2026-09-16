package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.blob.BlobState
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Metadata of every blob the module knows about, which is everything about one except its bytes.
 *
 * The module orchestrates files without ever holding one: the bytes move between the device and the
 * host's storage directly, and this row is what lets the module say that a blob exists, what was
 * declared about it, whether it may be served yet, and whether anything still points at it.
 *
 * Two check constraints live in the migration and are deliberately not mirrored here:
 * `ck_blobs__ready_at_matches_state` and `ck_blobs__size_is_not_negative`. Exposed's `check` takes
 * part only in DDL generation, and this module never generates DDL — the migration is the schema.
 * Declaring them in both places would give the impression that the code enforces them, when what
 * enforces them is PostgreSQL.
 */
internal object BlobsTable : UuidTable("sync.blobs") {
    /** Collection the blob belongs to; a blob is addressed within one, like an entity. */
    val collection = reference("collection_id", CollectionsTable, onDelete = ReferenceOption.CASCADE)

    /** Identifier the application generated, unique inside the collection. */
    val blobId = uuid("blob_id")

    /**
     * Whether the bytes are usable.
     *
     * Stored by name rather than by ordinal, so that reordering the enum cannot silently reinterpret
     * every row already written.
     */
    val state = enumerationByName("state", STATE_LENGTH, BlobState::class)

    /** Name the host's storage knows the object by; opaque to the module. */
    val storageKey = varchar("storage_key", STORAGE_KEY_LENGTH)

    /** Media type as the application declared it; stored and served back, never interpreted. */
    val contentType = varchar("content_type", CONTENT_TYPE_LENGTH)

    /** Length in octets as the application declared it, and what the host verifies against. */
    val size = long("size")

    /** Digest as the application declared it, or `null` when it declared none. */
    val checksum = varchar("checksum", CHECKSUM_LENGTH).nullable()

    /** When the blob was registered. */
    val createdAt = timestamp("created_at")

    /** When the bytes were accepted, or `null` while they have not been. */
    val readyAt = timestamp("ready_at").nullable()

    /**
     * Since when nothing in the collection has referenced the blob, or `null` while something does.
     *
     * What the collector reads. A blob starts unreferenced at registration — an upload whose
     * document was never pushed has to be collectable rather than immortal — and stops being a
     * candidate the moment a document names it.
     */
    val unreferencedSince = timestamp("unreferenced_since").nullable()

    init {
        uniqueIndex("uq_blobs__collection_id__blob_id", collection, blobId)
        index("ix_blobs__collection_id__unreferenced_since", false, collection, unreferencedSince)
    }

    /** Long enough for the state names, short enough to stay a fixed-width comparison. */
    private const val STATE_LENGTH = 20

    /** Generous for a bucket key, which some hosts build from a scope, a date and an identifier. */
    private const val STORAGE_KEY_LENGTH = 1024

    /** Longest media type accepted; real ones are far shorter, parameters included. */
    private const val CONTENT_TYPE_LENGTH = 255

    /** Long enough for a prefixed digest such as `sha256:…`, in any algorithm a host may pick. */
    private const val CHECKSUM_LENGTH = 200
}
