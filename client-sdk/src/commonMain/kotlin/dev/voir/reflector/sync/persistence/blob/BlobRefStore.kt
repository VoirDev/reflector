package dev.voir.reflector.sync.persistence.blob

import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Typed access to what the application's documents point at.
 *
 * @property dao Generated access to the table.
 */
internal class BlobRefStore(
    private val dao: SyncBlobRefDao,
) {
    /**
     * Replaces everything one document points at.
     *
     * Whole rather than incremental, which is what keeps references state-based: the adapter answers
     * with the set a document names now, and there is no delta to lose.
     *
     * @param scope Scope of the entity.
     * @param collection Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @param refs Files the document names now.
     * @param defaultFetch Policy to store for a reference that states none of its own. Resolved
     *   here so that the three states the application may answer with become the two the rest of
     *   the library reads, and nothing downstream has to know what the engine was configured with.
     * @param generation Bootstrap generation to stamp, so that the sweep can tell a reference the
     *   current snapshot confirmed from one it did not.
     */
    suspend fun replace(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
        refs: Set<BlobRef>,
        defaultFetch: BlobFetch,
        generation: Long,
    ) {
        dao.clear(scope.value, collection.value, entityType.value, entityId.value)
        for (ref in refs) {
            dao.upsert(
                SyncBlobRefEntity(
                    scopeId = scope.value,
                    collectionId = collection.value,
                    entityType = entityType.value,
                    entityId = entityId.value,
                    blobId = ref.id.value,
                    binding = ref.binding,
                    fetch = ref.fetch ?: defaultFetch,
                    seenGen = generation,
                ),
            )
        }
    }

    /**
     * Reads what one document points at.
     *
     * @param scope Scope of the entity.
     * @param collection Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Files the document names.
     */
    suspend fun of(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ): Set<BlobRef> =
        dao
            .of(scope.value, collection.value, entityType.value, entityId.value)
            .mapTo(mutableSetOf()) { BlobRef(BlobId(it.blobId), it.binding, it.fetch) }

    /**
     * Reads the files one document cannot be published without.
     *
     * @param scope Scope of the entity.
     * @param collection Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     * @return Files bound so that the record waits for them.
     */
    suspend fun required(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ): Set<BlobId> =
        dao
            .required(scope.value, collection.value, entityType.value, entityId.value)
            .mapTo(mutableSetOf()) { BlobId(it.blobId) }

    /**
     * Reads every file some document points at that the library knows nothing else about.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @return Files whose metadata has still to be worked out.
     */
    suspend fun unknown(
        scope: ScopeId,
        collection: CollectionId,
    ): List<BlobId> = dao.unknown(scope.value, collection.value).map(::BlobId)

    /**
     * Whether any document of the collection still points at one file.
     *
     * @param scope Scope to read.
     * @param collection Collection to read.
     * @param blobId File to look for.
     * @return `true` when at least one document names it.
     */
    suspend fun isReferenced(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): Boolean = dao.referenceCount(scope.value, collection.value, blobId.value) > 0

    /**
     * Removes everything one document pointed at.
     *
     * @param scope Scope of the entity.
     * @param collection Collection of the entity.
     * @param entityType Type of the entity.
     * @param entityId Identifier of the entity.
     */
    suspend fun clear(
        scope: ScopeId,
        collection: CollectionId,
        entityType: EntityType,
        entityId: EntityId,
    ) {
        dao.clear(scope.value, collection.value, entityType.value, entityId.value)
    }

    /**
     * Removes references a bootstrap did not confirm.
     *
     * @param scope Scope to sweep.
     * @param collection Collection to sweep.
     * @param generation Generation the bootstrap wrote.
     */
    suspend fun sweep(
        scope: ScopeId,
        collection: CollectionId,
        generation: Long,
    ) {
        dao.sweep(scope.value, collection.value, generation)
    }

    /**
     * Removes every reference of a scope.
     *
     * @param scope Scope to clear.
     */
    suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    /**
     * Removes every reference of a collection.
     *
     * @param scope Scope to clear.
     * @param collection Collection to clear.
     */
    suspend fun deleteAll(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.deleteAll(scope.value, collection.value)
    }
}
