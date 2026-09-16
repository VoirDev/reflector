package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the record of what each document points at, inside the transaction that wrote the document.
 *
 * There are exactly two moments a document passes through this library, and both go through here:
 * just after the adapter materialised one for a push, and just after one that arrived was applied.
 * In both the answer **replaces** what was stored, which is what makes references state-based like
 * the rest of the protocol — there is no "reference added" or "reference removed" to be lost.
 *
 * Being inside the transaction is the point. A reference written beside the document it came from
 * cannot disagree with it after a crash, and disagreement here has one consequence: a file offered
 * for deletion while a document still names it.
 *
 * Only the reference is written here. Whether the bytes are on this device is a question for the
 * application's own store, which must not be asked with a write lock held — [BlobReconciler] picks
 * that up afterwards.
 *
 * @property scope Scope being synchronised.
 * @property collection Collection being synchronised.
 * @property stores Storage of the library.
 * @property adapter Application's bridge to its own rows.
 * @property defaultFetch Policy applied to a reference whose own is unstated, which is how an
 *   application chooses once for every file it synchronises and overrides that per reference only
 *   where the choice actually differs.
 */
internal class BlobReferences(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val adapter: CollectionAdapter,
    private val defaultFetch: BlobFetch,
) {
    /**
     * Records what a document points at, and says so for the envelope.
     *
     * @param entityType Type of the entity whose document this is.
     * @param entityId Identifier of that entity.
     * @param document Document as it stands.
     * @param generation Bootstrap generation to stamp on the references.
     * @return Every file the document names, for the push that is about to carry it. The order is
     *   the adapter's; nothing downstream depends on it.
     */
    suspend fun declare(
        entityType: EntityType,
        entityId: EntityId,
        document: JsonObject,
        generation: Long,
    ): List<BlobId> {
        val refs = adapter.blobs(entityType, entityId, document)
        stores.blobRefs.replace(scope, collection, entityType, entityId, refs, defaultFetch, generation)
        return refs.map { it.id }
    }

    /**
     * Forgets what a document pointed at, because the document is gone.
     *
     * @param entityType Type of the removed entity.
     * @param entityId Identifier of the removed entity.
     */
    suspend fun clear(
        entityType: EntityType,
        entityId: EntityId,
    ) {
        stores.blobRefs.clear(scope, collection, entityType, entityId)
    }

    /**
     * Removes references a bootstrap did not confirm.
     *
     * The sweep, for files. A document the snapshot no longer mentions takes its references with it,
     * exactly as it takes its record — and the files it named then become something to offer the
     * application, which is how a device learns about an attachment deleted while it was away.
     *
     * @param generation Generation the bootstrap wrote.
     */
    suspend fun sweep(generation: Long) {
        stores.blobRefs.sweep(scope, collection, generation)
    }

    /**
     * Reads what a document points at, with the binding each reference was declared under.
     *
     * Read by the push before it sends a group, which reads the bindings to decide how long to wait:
     * a required reference has to be usable on the server, and a deferred one only has to have been
     * registered.
     *
     * @param entityType Type of the entity about to be pushed.
     * @param entityId Identifier of that entity.
     * @return Files the document names.
     */
    suspend fun of(
        entityType: EntityType,
        entityId: EntityId,
    ): Set<BlobRef> = stores.blobRefs.of(scope, collection, entityType, entityId)
}
