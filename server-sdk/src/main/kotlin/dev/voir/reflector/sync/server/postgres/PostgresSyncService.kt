package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import dev.voir.reflector.sync.server.CursorTooOldException
import dev.voir.reflector.sync.server.ProjectionListener
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncConfig
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.SyncService
import dev.voir.reflector.sync.server.emit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The synchronisation module on PostgreSQL.
 *
 * Every operation owns its transaction. The host must not wrap these calls in one of its own: the
 * counter lock taken by a push would then be held for the whole surrounding unit of work, turning a
 * short serialisation point into a queue.
 *
 * @property database Database the module's schema lives in.
 * @property config Registered collections and limits.
 * @property clock Source of timestamps, injected so that tests are deterministic.
 * @property commitListeners Notified after a batch commits; this is what a host's event channel is
 *   built on.
 * @property projections Notified inside the batch's transaction.
 * @property metrics Sink for what each operation cost, reported after the work is durable.
 */
internal class PostgresSyncService(
    private val database: Database,
    private val config: SyncConfig,
    private val clock: Clock,
    private val commitListeners: List<SyncCommitListener>,
    private val projections: List<ProjectionListener>,
    private val metrics: SyncMetrics,
) : SyncService {
    private val collections = CollectionRows(config, clock)
    private val applier = GroupApplier(config, collections, projections, clock)

    override fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse {
        val spec = config.require(collection)
        // Each group gets its own transaction: one request may carry several, and a conflict in one
        // must not roll back another the server has already accepted.
        val applied =
            request.groups.map { group ->
                val result =
                    transaction(database) { applier.apply(scope, collection, spec, request.clientId.value, group) }
                // Measured here rather than inside the applier: the lock is released by the commit,
                // and the commit is this block ending. Anything measured before it would report a
                // window shorter than the one other writers actually waited out.
                metrics.emit(
                    SyncMetricEvent.PushGroupServed(
                        scope = scope,
                        collection = collection,
                        operations = group.ops.size,
                        outcome = result.outcome,
                        lockHeld = result.lockedAt?.let { clock.now() - it },
                    ),
                )
                result
            }

        // Notifications go out after the data is durable. A delivery failure must not undo an
        // accepted write, and a client that misses one learns about the change on its next pull.
        applied.mapNotNull { it.committedSeq }.forEach { seq ->
            commitListeners.forEach { listener ->
                runCatching { listener.onCommitted(scope, collection, SyncSequences.batchSeq(seq)) }
            }
        }

        return PushResponse(
            results = applied.map { it.result },
            latestSeq = SyncSequences.batchSeq(headSequence(scope, collection)),
        )
    }

    override fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage {
        val startedAt = clock.now()
        val served =
            transaction(database) {
                config.require(collection)
                val row = collections.ensure(scope, collection)
                val from = cursor?.let(SyncSequences::sequenceOf) ?: (row.retentionFloorSeq - 1)
                if (cursor != null && from < row.retentionFloorSeq - 1) {
                    // The changes between the client's position and the window are gone. Serving what
                    // is left would leave it silently missing them; refusing sends it to a bootstrap.
                    metrics.emit(
                        SyncMetricEvent.CursorRefused(scope, collection, row.retentionFloorSeq - 1 - from),
                    )
                    throw CursorTooOldException(cursor)
                }

                val capped = limit.coerceIn(1, config.maxChangesPageSize)
                val rows =
                    BatchesTable
                        .selectAll()
                        .where { (BatchesTable.collection eq row.id) and (BatchesTable.seq greater from) }
                        .orderBy(BatchesTable.seq to SortOrder.ASC)
                        .limit(capped + 1)
                        .toList()

                val hasMore = rows.size > capped
                val batches = rows.take(capped)
                val operations = operationsOf(batches.map { it[BatchesTable.id].value })

                ServedChanges(
                    page =
                        ChangesPage(
                            batches =
                                batches.map { batch ->
                                    ChangeBatch(
                                        seq = SyncSequences.batchSeq(batch[BatchesTable.seq]),
                                        originClientId = batch[BatchesTable.originClientId]?.let(::ClientId),
                                        ops = operations[batch[BatchesTable.id].value].orEmpty(),
                                    )
                                },
                            nextCursor = batches.lastOrNull()?.let { SyncSequences.cursor(it[BatchesTable.seq]) },
                            hasMore = hasMore,
                        ),
                    // How far behind the head the client was when it asked. The subtraction is the
                    // server's to do: to the client a cursor is an opaque token with no arithmetic.
                    cursorLag = (row.nextSeq - 1) - from,
                )
            }

        metrics.emit(
            SyncMetricEvent.ChangesServed(
                scope = scope,
                collection = collection,
                batches = served.page.batches.size,
                cursorLag = served.cursorLag,
                duration = clock.now() - startedAt,
            ),
        )
        return served.page
    }

    override fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage {
        val startedAt = clock.now()
        val served =
            transaction(database) {
                config.require(collection)
                val row = collections.ensure(scope, collection)
                val token = page?.let(SnapshotCursorToken::decode)
                // The cursor is fixed before the first page and then carried in the page token. Anything
                // committed during the transfer is replayed from it, which is why repeats are harmless
                // and gaps impossible.
                val cursor = token?.cursor ?: (row.nextSeq - 1)
                val capped = limit.coerceIn(1, config.maxChangesPageSize)

                val rows =
                    EntitiesTable
                        .selectAll()
                        .where { entitiesAfter(row.id, token) }
                        .orderBy(EntitiesTable.entityType to SortOrder.ASC, EntitiesTable.entityId to SortOrder.ASC)
                        .limit(capped + 1)
                        .toList()

                val hasMore = rows.size > capped
                val items =
                    rows.take(capped).map { entity ->
                        SnapshotItem(
                            entity = EntityType(entity[EntitiesTable.entityType]),
                            id = EntityId(entity[EntitiesTable.entityId]),
                            version = SyncSequences.version(entity[EntitiesTable.version]),
                            data = checkNotNull(entity[EntitiesTable.data]) { "a live entity must have a document" },
                        )
                    }

                SnapshotPage(
                    cursor = SyncSequences.cursor(cursor),
                    items = items,
                    nextPage =
                        items
                            .lastOrNull()
                            ?.takeIf { hasMore }
                            ?.let { SnapshotCursorToken(it.entity, it.id, cursor).encode() },
                    hasMore = hasMore,
                )
            }

        metrics.emit(
            SyncMetricEvent.SnapshotServed(scope, collection, served.items.size, clock.now() - startedAt),
        )
        return served
    }

    override fun head(
        scope: ScopeId,
        collection: CollectionId,
    ): Cursor = SyncSequences.cursor(headSequence(scope, collection))

    override fun limits(): SyncLimits =
        SyncLimits(
            maxOperationsPerGroup = config.maxOperationsPerGroup,
            // The smallest of the registered limits: one number is published for every collection,
            // and a client that respected a larger one would be refused by the strictest of them.
            maxDocumentBytes = config.collections.values.minOf { it.maxDocumentBytes },
            maxChangesPageSize = config.maxChangesPageSize,
            retentionDays = config.retention.inWholeDays.toInt(),
        )

    /**
     * A page of the log together with what serving it revealed about the client.
     *
     * The lag is worked out inside the reading transaction, where the head and the client's position
     * are both at hand, and reported outside it, where the host's code may safely run.
     *
     * @property page Answer for the client.
     * @property cursorLag Sequences between the client's cursor and the head when it asked.
     */
    private data class ServedChanges(
        val page: ChangesPage,
        val cursorLag: Long,
    )

    private fun headSequence(
        scope: ScopeId,
        collection: CollectionId,
    ): Long =
        transaction(database) {
            config.require(collection)
            collections.ensure(scope, collection).nextSeq - 1
        }

    private fun operationsOf(batchIds: List<Uuid>): Map<Uuid, List<RemoteOperation>> {
        if (batchIds.isEmpty()) {
            return emptyMap()
        }
        return ChangesTable
            .selectAll()
            .where { ChangesTable.batch inList batchIds }
            .orderBy(ChangesTable.batch to SortOrder.ASC, ChangesTable.ordinal to SortOrder.ASC)
            .groupBy({ it[ChangesTable.batch].value }) { change ->
                val entity = EntityType(change[ChangesTable.entityType])
                val id = EntityId(change[ChangesTable.entityId])
                val version = SyncSequences.version(change[ChangesTable.version])
                when (val data = change[ChangesTable.data]) {
                    null -> RemoteOperation.Delete(entity, id, version)
                    else -> RemoteOperation.Upsert(entity, id, version, data)
                }
            }
    }

    /**
     * Keyset predicate for a snapshot page.
     *
     * Deleted entities are excluded: a client that bootstraps learns about a removal from the
     * absence, and one that is still inside the retention window learns about it from the log.
     */
    private fun entitiesAfter(
        collectionRowId: Uuid,
        token: SnapshotCursorToken?,
    ) = when (token) {
        null -> {
            (EntitiesTable.collection eq collectionRowId) and (EntitiesTable.isDeleted eq false)
        }

        else -> {
            (EntitiesTable.collection eq collectionRowId) and
                (EntitiesTable.isDeleted eq false) and
                (
                    (EntitiesTable.entityType greater token.entityType.value) or
                        (
                            (EntitiesTable.entityType eq token.entityType.value) and
                                (EntitiesTable.entityId greater token.entityId.value)
                        )
                )
        }
    }
}
