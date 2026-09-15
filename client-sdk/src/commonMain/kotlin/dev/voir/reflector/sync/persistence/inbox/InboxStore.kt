package dev.voir.reflector.sync.persistence.inbox

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Typed access to downloaded batches waiting to be applied.
 *
 * @property dao Generated data access object of the inbox tables.
 */
internal class InboxStore(
    private val dao: SyncInboxDao,
) {
    /**
     * Stores a downloaded page.
     *
     * The arrival order is assigned here, because it is the only order the batches may be applied
     * in: their sequences are opaque strings and comparing them as text would reorder the log.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param batches Batches of the page, in the order the server returned them.
     */
    public suspend fun store(
        scope: ScopeId,
        collection: CollectionId,
        batches: List<StoredBatch>,
    ) {
        if (batches.isEmpty()) {
            return
        }
        var ord = dao.nextReceivedOrdinal(scope.value, collection.value)
        val batchRows =
            batches.map { batch ->
                SyncInboxBatchEntity(
                    scopeId = scope.value,
                    collectionId = collection.value,
                    seq = batch.seq.value,
                    cursor = batch.cursor.value,
                    receivedOrd = ord++,
                    originClientId = batch.originClientId?.value,
                    state = InboxBatchState.PENDING,
                )
            }
        val opRows =
            batches.flatMap { batch ->
                batch.ops.mapIndexed { index, op ->
                    SyncInboxOpEntity(
                        scopeId = scope.value,
                        collectionId = collection.value,
                        seq = batch.seq.value,
                        ordinal = index,
                        entityType = op.entityType.value,
                        entityId = op.entityId.value,
                        op = op.op,
                        version = op.version.value,
                        payload = op.payload?.toString(),
                    )
                }
            }
        dao.insertBatches(batchRows)
        dao.insertOps(opRows)
    }

    /**
     * Returns the oldest batch that has not been applied, with its operations.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Batch to apply, or `null` when the inbox is empty.
     */
    public suspend fun oldestPending(
        scope: ScopeId,
        collection: CollectionId,
    ): StoredBatch? {
        val batch = dao.oldestPendingBatch(scope.value, collection.value, InboxBatchState.PENDING) ?: return null
        val ops = dao.opsOf(scope.value, collection.value, batch.seq)
        return StoredBatch(
            seq = BatchSeq(batch.seq),
            cursor = Cursor(batch.cursor),
            originClientId = batch.originClientId?.let(::ClientId),
            ops =
                ops.map { op ->
                    InboxOperation(
                        entityType = EntityType(op.entityType),
                        entityId = EntityId(op.entityId),
                        op = op.op,
                        version = EntityVersion(op.version),
                        payload = op.payload?.toJsonObject(),
                    )
                },
        )
    }

    /**
     * Removes an applied batch together with its operations.
     *
     * Called in the transaction that applied it, so that the batch and the cursor it produced can
     * never disagree.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param seq Sequence of the batch.
     */
    public suspend fun deleteApplied(
        scope: ScopeId,
        collection: CollectionId,
        seq: BatchSeq,
    ) {
        dao.deleteOps(scope.value, collection.value, seq.value)
        dao.deleteBatch(scope.value, collection.value, seq.value)
    }

    /**
     * Empties the inbox of a collection, used before a bootstrap replaces everything anyway.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     */
    public suspend fun clear(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.deleteAllOps(scope.value, collection.value)
        dao.deleteBatches(scope.value, collection.value)
    }

    private fun String.toJsonObject(): JsonObject = SyncProtocolJson.format.parseToJsonElement(this).jsonObject
}
