package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What each entity's document points at, maintained inside the transaction that writes the batch.
 *
 * References are state-based like everything else in this protocol: a client sends the whole set it
 * declares, and that set replaces what was stored. There is no "reference added" or "reference
 * removed" event, so there is none to lose — which matters here more than usual, because the only
 * consequence of losing one is a file deleted while a document still names it.
 *
 * Everything here runs under the collection's counter lock, so it is written to be set-based rather
 * than row-at-a-time: the statements are bounded by the number of distinct entity **types** in a
 * group, not by the number of operations, and the lock is what every other writer of the collection
 * is waiting out.
 */
internal class BlobReferences {
    /**
     * Returns the declared blobs this collection has never registered.
     *
     * A blob that is registered and still uploading is **not** missing: a record is allowed to be
     * published ahead of its file, and refusing here would defeat the whole default. What this finds
     * is a reference to something that was never registered, or to something already collected as
     * garbage — the second being what a client meets when its group sat blocked for longer than the
     * retention window.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param declared Blobs the group references.
     * @return Those of them the collection does not know, in no particular order.
     */
    fun missing(
        collectionRowId: Uuid,
        declared: Set<BlobId>,
    ): Set<BlobId> {
        if (declared.isEmpty()) {
            return emptySet()
        }
        val known =
            BlobsTable
                .select(BlobsTable.blobId)
                .where {
                    (BlobsTable.collection eq collectionRowId) and
                        (BlobsTable.blobId inList declared.map { it.value })
                }.mapTo(mutableSetOf()) { BlobId(it[BlobsTable.blobId]) }
        return declared - known
    }

    /**
     * Replaces the references of the entities a batch wrote, and settles what became garbage.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param cleared Entities whose stored references are to be dropped: everything the batch
     *   deleted, and everything it wrote that declared a set.
     * @param declared References to store afterwards.
     * @param now Moment the batch was committed, stamped on blobs that just lost their last
     *   reference.
     */
    fun apply(
        collectionRowId: Uuid,
        cleared: Collection<EntityKey>,
        declared: Collection<BlobReference>,
        now: Instant,
    ) {
        if (cleared.isEmpty()) {
            return
        }
        // Read before deleting: a blob that loses its last reference here is one nothing will
        // mention again, and after the delete there is nothing left to say it was ever referenced.
        val released = referencedBlobs(collectionRowId, cleared)
        clear(collectionRowId, cleared)
        if (declared.isNotEmpty()) {
            BlobRefsTable.batchInsert(declared, shouldReturnGeneratedValues = false) { reference ->
                this[BlobRefsTable.collection] = collectionRowId
                this[BlobRefsTable.entityType] = reference.entity.entityType.value
                this[BlobRefsTable.entityId] = reference.entity.entityId.value
                this[BlobRefsTable.blobId] = reference.blobId.value
            }
        }
        settle(collectionRowId, released + declared.map { it.blobId }, now)
    }

    /**
     * Reads every blob the given entities currently point at.
     *
     * Grouped by entity type rather than filtered by identifier alone, because the natural key is
     * both: two entity types are free to use the same identifier, and dropping the type from the
     * filter would quietly take another entity's references with it.
     */
    private fun referencedBlobs(
        collectionRowId: Uuid,
        entities: Collection<EntityKey>,
    ): Set<BlobId> =
        entities
            .groupBy { it.entityType }
            .flatMapTo(mutableSetOf()) { (type, keys) ->
                BlobRefsTable
                    .select(BlobRefsTable.blobId)
                    .where {
                        (BlobRefsTable.collection eq collectionRowId) and
                            (BlobRefsTable.entityType eq type.value) and
                            (BlobRefsTable.entityId inList keys.map { it.entityId.value })
                    }.map { BlobId(it[BlobRefsTable.blobId]) }
            }

    private fun clear(
        collectionRowId: Uuid,
        entities: Collection<EntityKey>,
    ) {
        entities.groupBy { it.entityType }.forEach { (type, keys) ->
            BlobRefsTable.deleteWhere {
                (BlobRefsTable.collection eq collectionRowId) and
                    (BlobRefsTable.entityType eq type.value) and
                    (BlobRefsTable.entityId inList keys.map { it.entityId.value })
            }
        }
    }

    /**
     * Stamps or clears the moment each touched blob became garbage.
     *
     * Two set-based statements rather than a decision per blob, and each one conditional on the
     * value it is changing, so that a blob that merely stayed referenced keeps the moment it was
     * first registered instead of having it rewritten by every unrelated write.
     */
    private fun settle(
        collectionRowId: Uuid,
        touched: Collection<BlobId>,
        now: Instant,
    ) {
        if (touched.isEmpty()) {
            return
        }
        val ids = touched.map { it.value }.distinct()
        val stillReferenced =
            exists(
                BlobRefsTable.selectAll().where {
                    (BlobRefsTable.collection eq collectionRowId) and (BlobRefsTable.blobId eq BlobsTable.blobId)
                },
            )
        val unreferenced =
            notExists(
                BlobRefsTable.selectAll().where {
                    (BlobRefsTable.collection eq collectionRowId) and (BlobRefsTable.blobId eq BlobsTable.blobId)
                },
            )
        BlobsTable.update(
            where = {
                (BlobsTable.collection eq collectionRowId) and
                    (BlobsTable.blobId inList ids) and
                    BlobsTable.unreferencedSince.isNotNull() and
                    stillReferenced
            },
        ) { row -> row[unreferencedSince] = null }
        BlobsTable.update(
            where = {
                (BlobsTable.collection eq collectionRowId) and
                    (BlobsTable.blobId inList ids) and
                    BlobsTable.unreferencedSince.isNull() and
                    unreferenced
            },
        ) { row -> row[unreferencedSince] = now }
    }
}

/**
 * One document's pointer at one blob.
 *
 * @property entity Entity whose document holds the reference.
 * @property blobId Blob it points at.
 */
internal data class BlobReference(
    val entity: EntityKey,
    val blobId: BlobId,
)
