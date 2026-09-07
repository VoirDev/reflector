package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.ConflictEntry
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.protocol.push.RejectError
import dev.voir.reflector.sync.server.AppliedChange
import dev.voir.reflector.sync.server.CollectionSpec
import dev.voir.reflector.sync.server.ProjectionListener
import dev.voir.reflector.sync.server.PushMetricOutcome
import dev.voir.reflector.sync.server.SyncConfig
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Applies one push group inside one transaction.
 *
 * A group is the unit of atomicity and of idempotency at once, and both properties are visible here:
 * everything the group changes is written under a single sequence, and the answer it produced is
 * stored so that a repeat returns it instead of doing the work again.
 *
 * @property config Registered collections and limits.
 * @property collections Access to collection rows and the ordering counter.
 * @property projections Host projections applied inside the same transaction.
 * @property clock Source of timestamps.
 */
internal class GroupApplier(
    private val config: SyncConfig,
    private val collections: CollectionRows,
    private val projections: List<ProjectionListener>,
    private val clock: Clock,
) {
    /**
     * Applies one group and returns what to answer, together with the sequence it produced.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection being written to.
     * @param spec Registered specification of that collection.
     * @param clientId Installation that sent the group.
     * @param group Group to apply.
     * @return Answer for the client, and the committed sequence when a batch was written.
     */
    fun apply(
        scope: ScopeId,
        collection: CollectionId,
        spec: CollectionSpec,
        clientId: Uuid,
        group: PushGroup,
    ): AppliedGroup {
        val collectionRow = collections.ensure(scope, collection)
        storedResult(clientId, group.groupId.value)?.let {
            return AppliedGroup(it, committedSeq = null, outcome = PushMetricOutcome.REPEATED, lockedAt = null)
        }

        // The counter is locked before anything is read: it serialises writers to this collection,
        // which is what makes the version checks below safe without locking every entity row.
        val locked = collections.lock(collectionRow.id)
        // From here to the commit is the window every other writer of this collection waits out. The
        // moment is carried out of this method so that the wait can be measured against the commit
        // rather than against the end of this method, which is not where the lock is released.
        val lockedAt = clock.now()

        rejectionOf(spec, group)?.let { rejection ->
            val result = PushGroupResult.Rejected(group.groupId, rejection)
            store(collectionRow.id, clientId, group.groupId.value, STATUS_REJECTED, result)
            return AppliedGroup(result, committedSeq = null, outcome = PushMetricOutcome.REJECTED, lockedAt = lockedAt)
        }

        val current = currentEntities(collectionRow.id, group)
        val conflicts = conflictsOf(group, current)
        if (conflicts.isNotEmpty()) {
            // Nothing of the group is applied and no sequence is consumed: the group is atomic, and a
            // partially applied envelope is exactly what the client cannot recover from.
            val result = PushGroupResult.Conflict(group.groupId, conflicts)
            store(collectionRow.id, clientId, group.groupId.value, STATUS_CONFLICT, result)
            return AppliedGroup(result, committedSeq = null, outcome = PushMetricOutcome.CONFLICT, lockedAt = lockedAt)
        }

        val seq = locked.nextSeq
        val versions = write(scope, collection, collectionRow.id, clientId, group, current, seq)
        val result = PushGroupResult.Applied(group.groupId, versions)
        store(collectionRow.id, clientId, group.groupId.value, STATUS_APPLIED, result)
        return AppliedGroup(result, committedSeq = seq, outcome = PushMetricOutcome.APPLIED, lockedAt = lockedAt)
    }

    private fun rejectionOf(
        spec: CollectionSpec,
        group: PushGroup,
    ): RejectError? {
        if (group.ops.size > config.maxOperationsPerGroup) {
            return RejectError(
                code = RejectCode.TOO_LARGE,
                message = "group holds ${group.ops.size} operations, the limit is ${config.maxOperationsPerGroup}",
            )
        }
        for (op in group.ops) {
            if (op.entity !in spec.entityTypes) {
                return RejectError(
                    code = RejectCode.UNKNOWN_ENTITY_TYPE,
                    entity = op.entity,
                    id = op.id,
                    message = "collection ${spec.id.value} does not accept entity type ${op.entity.value}",
                )
            }
            val size =
                (op as? PushOperation.Upsert)
                    ?.data
                    ?.toString()
                    ?.encodeToByteArray()
                    ?.size ?: 0
            if (size > spec.maxDocumentBytes) {
                return RejectError(
                    code = RejectCode.TOO_LARGE,
                    entity = op.entity,
                    id = op.id,
                    message = "document of $size bytes exceeds the limit of ${spec.maxDocumentBytes}",
                )
            }
        }
        return null
    }

    private fun currentEntities(
        collectionRowId: Uuid,
        group: PushGroup,
    ): Map<EntityKey, EntityRow> {
        val ids = group.ops.map { it.id.value }
        return EntitiesTable
            .selectAll()
            .where { (EntitiesTable.collection eq collectionRowId) and (EntitiesTable.entityId inList ids) }
            .associate { row ->
                EntityKey(EntityType(row[EntitiesTable.entityType]), EntityId(row[EntitiesTable.entityId])) to
                    EntityRow(
                        version = row[EntitiesTable.version],
                        data = row[EntitiesTable.data],
                        isDeleted = row[EntitiesTable.isDeleted],
                    )
            }
    }

    /**
     * Finds the operations whose base version no longer matches what is stored.
     *
     * An operation against an entity the module does not have is **not** a conflict: there is nothing
     * to disagree with, and refusing it would strand a client whose tombstone the retention window
     * has already swept. It is applied as a creation instead.
     */
    private fun conflictsOf(
        group: PushGroup,
        current: Map<EntityKey, EntityRow>,
    ): List<ConflictEntry> =
        group.ops.mapNotNull { op ->
            val stored = current[EntityKey(op.entity, op.id)] ?: return@mapNotNull null
            val base = op.baseVersion?.let(SyncSequences::sequenceOf)
            if (base == stored.version) {
                null
            } else {
                ConflictEntry(
                    entity = op.entity,
                    id = op.id,
                    serverVersion = SyncSequences.version(stored.version),
                    data = if (stored.isDeleted) null else stored.data,
                )
            }
        }

    private fun write(
        scope: ScopeId,
        collection: CollectionId,
        collectionRowId: Uuid,
        clientId: Uuid,
        group: PushGroup,
        current: Map<EntityKey, EntityRow>,
        seq: Long,
    ): List<AppliedVersion> {
        val now = clock.now()
        val batchId = Uuid.random()
        BatchesTable.insert { row ->
            row[id] = batchId
            row[BatchesTable.collection] = collectionRowId
            row[BatchesTable.seq] = seq
            row[originClientId] = clientId
            row[clientGroupId] = group.groupId.value
            row[committedAt] = now
        }

        val applied = mutableListOf<AppliedChange>()
        group.ops.forEachIndexed { ordinal, op ->
            val key = EntityKey(op.entity, op.id)
            val stored = current[key]
            val document =
                when (op) {
                    // Merge by top-level key: an absent key keeps what is stored, an explicit null
                    // clears it. The merge happens here rather than in SQL because the module holds
                    // the document anyway and the write lock makes read-modify-write safe.
                    is PushOperation.Upsert -> JsonObject(stored?.data.orEmpty() + op.data)

                    is PushOperation.Delete -> null
                }
            writeEntity(collectionRowId, key, document, seq, now, exists = stored != null)
            ChangesTable.insert { row ->
                row[id] = Uuid.random()
                row[batch] = batchId
                row[ChangesTable.ordinal] = ordinal
                row[entityType] = op.entity.value
                row[entityId] = op.id.value
                row[ChangesTable.op] = if (document == null) OP_DELETE else OP_UPSERT
                row[version] = seq
                row[data] = document
            }
            applied +=
                AppliedChange(
                    entityType = op.entity,
                    entityId = op.id,
                    version = SyncSequences.version(seq),
                    data = document,
                )
        }

        CollectionsTable.update({ CollectionsTable.id eq collectionRowId }) { row ->
            row[nextSeq] = seq + 1
        }

        // Projections run inside the transaction on purpose: a host projection that disagrees with
        // the module's data is worse than a refused write.
        projections.forEach { it.onBatch(scope, collection, applied) }

        return applied.map { AppliedVersion(it.entityType, it.entityId, it.version) }
    }

    private fun writeEntity(
        collectionRowId: Uuid,
        key: EntityKey,
        document: JsonObject?,
        seq: Long,
        now: kotlin.time.Instant,
        exists: Boolean,
    ) {
        if (exists) {
            EntitiesTable.update({
                (EntitiesTable.collection eq collectionRowId) and
                    (EntitiesTable.entityType eq key.entityType.value) and
                    (EntitiesTable.entityId eq key.entityId.value)
            }) { row ->
                row[version] = seq
                row[data] = document
                row[isDeleted] = document == null
                row[lastSeq] = seq
                row[updatedAt] = now
            }
        } else {
            EntitiesTable.insert { row ->
                row[id] = Uuid.random()
                row[collection] = collectionRowId
                row[entityType] = key.entityType.value
                row[entityId] = key.entityId.value
                row[version] = seq
                row[data] = document
                row[isDeleted] = document == null
                row[lastSeq] = seq
                row[createdAt] = now
                row[updatedAt] = now
            }
        }
    }

    private fun storedResult(
        clientId: Uuid,
        groupId: Uuid,
    ): PushGroupResult? =
        PushResultsTable
            .selectAll()
            .where { (PushResultsTable.clientId eq clientId) and (PushResultsTable.groupId eq groupId) }
            .singleOrNull()
            ?.get(PushResultsTable.response)

    private fun store(
        collectionRowId: Uuid,
        clientId: Uuid,
        groupId: Uuid,
        status: String,
        result: PushGroupResult,
    ) {
        PushResultsTable.insert { row ->
            row[id] = Uuid.random()
            row[collection] = collectionRowId
            row[PushResultsTable.clientId] = clientId
            row[PushResultsTable.groupId] = groupId
            row[PushResultsTable.status] = status
            row[response] = result
            row[createdAt] = clock.now()
        }
    }

    private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()

    private companion object {
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
        const val STATUS_APPLIED = "applied"
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_REJECTED = "rejected"
    }
}

/**
 * Outcome of applying one group.
 *
 * @property result Answer for the client.
 * @property committedSeq Sequence the group produced, or `null` when nothing was written.
 * @property outcome How the group was answered, in the terms the metrics port counts.
 * @property lockedAt Moment the counter lock was taken, or `null` when the group was answered from
 *   a stored result and no lock was needed. The lock is released by the commit, which happens after
 *   this value leaves the applier, so only the caller can say how long it was held.
 */
internal data class AppliedGroup(
    val result: PushGroupResult,
    val committedSeq: Long?,
    val outcome: PushMetricOutcome,
    val lockedAt: Instant?,
)
