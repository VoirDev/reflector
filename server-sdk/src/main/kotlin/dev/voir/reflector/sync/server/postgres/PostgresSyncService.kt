package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionEpoch
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
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import dev.voir.reflector.sync.server.CollectionResetException
import dev.voir.reflector.sync.server.CursorTooOldException
import dev.voir.reflector.sync.server.ProjectionListener
import dev.voir.reflector.sync.server.PushMetricOutcome
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncConfig
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogger
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.SyncService
import dev.voir.reflector.sync.server.emit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * @property log Sink for what each operation decided, and for the host callbacks that threw.
 */
internal class PostgresSyncService(
    private val database: Database,
    private val config: SyncConfig,
    private val clock: Clock,
    private val commitListeners: List<SyncCommitListener>,
    private val projections: List<ProjectionListener>,
    private val metrics: SyncMetrics,
    private val log: SyncLog,
) : SyncService {
    private val logger = SyncLogger(log)
    private val collections = CollectionRows(config, clock)
    private val applier = GroupApplier(config, collections, projections, clock)

    override fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse {
        val spec = config.require(collection)
        val logger = logger.forCollection(scope, collection)
        // Settled before the first group is applied, never per group: a request whose collection
        // was purged must be refused whole. Applying its first group and refusing its second would
        // put part of the erased data back and leave the client unable to say which part.
        val incarnation = requireIncarnation(scope, collection, request.epoch, logger)
        // Each group gets its own transaction: one request may carry several, and a conflict in one
        // must not roll back another the server has already accepted.
        val applied =
            request.groups.map { group ->
                val result =
                    transaction(database) { applier.apply(scope, collection, spec, request.clientId.value, group) }
                // Measured here rather than inside the applier: the lock is released by the commit,
                // and the commit is this block ending. Anything measured before it would report a
                // window shorter than the one other writers actually waited out.
                val lockHeld = result.lockedAt?.let { clock.now() - it }
                metrics.emit(
                    SyncMetricEvent.PushGroupServed(
                        scope = scope,
                        collection = collection,
                        operations = group.ops.size,
                        outcome = result.outcome,
                        lockHeld = lockHeld,
                    ),
                    logger,
                )
                logger.report(group, result, lockHeld)
                result
            }

        // Notifications go out after the data is durable. A delivery failure must not undo an
        // accepted write, and a client that misses one learns about the change on its next pull.
        applied.mapNotNull { it.committedSeq }.forEach { seq ->
            commitListeners.forEach { listener ->
                runCatching { listener.onCommitted(scope, collection, SyncSequences.batchSeq(seq)) }
                    .onFailure { failure ->
                        // The batch is committed and stays committed; what this costs is the
                        // notification. Every client of the scope then waits for its own next poll,
                        // which looks to a user like synchronisation having become slow rather than
                        // like anything being broken — so nothing but this line would ever say it.
                        logger.error(
                            SyncLogEvent.COMMIT_LISTENER_FAILED,
                            failure,
                            { mapOf("seq" to seq.toString()) },
                        ) { "a commit listener threw; the batch is durable but its notification was not delivered" }
                    }
            }
        }

        return PushResponse(
            results = applied.map { it.result },
            latestSeq = SyncSequences.batchSeq(headSequence(scope, collection)),
            // Whichever incarnation the work landed in. A client that claimed none learns it here
            // and sends it from now on; one that claimed this one can be told nothing new.
            epoch = SyncSequences.epoch(incarnation ?: collectionRowId(scope, collection)),
        )
    }

    /**
     * Checks that the client is talking about the collection that exists now.
     *
     * A client claiming no incarnation is one that has never synchronised this collection, and
     * there is nothing to check: it is told which incarnation it ended up in by the answer. A client
     * that does claim one is checked against the stored row **without creating it** — a stale push
     * into a purged scope must not bring that scope's row back into the database it was erased from.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection being written to.
     * @param claimed Incarnation the client believes in, or `null` when it claims none.
     * @param logger Logger already bound to this collection.
     * @return Identifier of the collection row the claim was checked against, or `null` when none
     *   was claimed and nothing was looked up.
     * @throws CollectionResetException When the claim names an incarnation that no longer exists.
     */
    private fun requireIncarnation(
        scope: ScopeId,
        collection: CollectionId,
        claimed: CollectionEpoch?,
        logger: SyncLogger,
    ): Uuid? {
        if (claimed == null) {
            return null
        }
        val row = transaction(database) { collections.find(scope, collection) }
        if (row != null && SyncSequences.epoch(row.id) == claimed) {
            return row.id
        }
        reportReset(scope, collection, claimed, logger)
        throw CollectionResetException(collection, claimed)
    }

    /**
     * Says that somebody is still carrying a collection that was erased.
     *
     * Counted and logged in one place for every path it can arrive on, because the number that
     * matters is per installation rather than per operation: an erasure is finished when these stop
     * arriving, and that is not visible from any one of the three paths alone.
     *
     * @param scope Scope the client addressed.
     * @param collection Collection the client addressed.
     * @param claimed Incarnation the client believes in.
     * @param logger Logger already bound to this collection.
     */
    private fun reportReset(
        scope: ScopeId,
        collection: CollectionId,
        claimed: CollectionEpoch,
        logger: SyncLogger,
    ) {
        metrics.emit(SyncMetricEvent.CollectionResetRefused(scope, collection), logger)
        logger.info(
            SyncLogEvent.COLLECTION_RESET_REFUSED,
            context = { mapOf("epoch" to claimed.value) },
        ) { "a client is still following a collection that was purged; it has to discard it and rebuild" }
    }

    private fun collectionRowId(
        scope: ScopeId,
        collection: CollectionId,
    ): Uuid = transaction(database) { collections.ensure(scope, collection).id }

    override fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage {
        val startedAt = clock.now()
        val logger = logger.forCollection(scope, collection)
        var behindFloor: Long? = null
        val served =
            try {
                transaction(database) {
                    config.require(collection)
                    // A cursor names an incarnation as well as a position, so a client that has one
                    // is checked against the stored row rather than allowed to create it: a reader
                    // arriving after a purge must not put the erased scope back in the database.
                    val position = cursor?.let(SyncSequences::positionOf)
                    val row =
                        when (position) {
                            null -> collections.ensure(scope, collection)
                            else -> collections.find(scope, collection)
                        }
                    if (position != null && position.collectionRowId != row?.id) {
                        // The log this cursor points into no longer exists. Serving the new one from
                        // the same offset would hand the client a page that reads like a
                        // continuation and is nothing of the sort.
                        throw CollectionResetException(collection, SyncSequences.epoch(position.collectionRowId))
                    }
                    checkNotNull(row) { "a collection without a cursor must have been created" }
                    val from = position?.seq ?: (row.retentionFloorSeq - 1)
                    val head = row.nextSeq - 1
                    if (cursor != null && from < row.retentionFloorSeq - 1) {
                        // The changes between the client's position and the window are gone. Serving
                        // what is left would leave it silently missing them; refusing sends it to a
                        // bootstrap. How far behind it was is worked out here, where both positions
                        // are at hand, and reported outside — the ports are the host's code and
                        // neither belongs inside a transaction.
                        behindFloor = row.retentionFloorSeq - 1 - from
                        throw CursorTooOldException(cursor)
                    }
                    if (position != null && from > head) {
                        // The incarnation matches and the position is still ahead of the head, so
                        // this log did not restart — it went backwards, which only a restore from a
                        // backup does. The client's answer is the same as after a purge: what it
                        // holds describes a history the server no longer has.
                        throw CollectionResetException(collection, SyncSequences.epoch(row.id))
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
                                            cursor = SyncSequences.cursor(row.id, batch[BatchesTable.seq]),
                                            originClientId = batch[BatchesTable.originClientId]?.let(::ClientId),
                                            ops = operations[batch[BatchesTable.id].value].orEmpty(),
                                        )
                                    },
                                nextCursor =
                                    batches.lastOrNull()?.let { SyncSequences.cursor(row.id, it[BatchesTable.seq]) },
                                hasMore = hasMore,
                                epoch = SyncSequences.epoch(row.id),
                            ),
                        // How far behind the head the client was when it asked. The subtraction is the
                        // server's to do: to the client a cursor is an opaque token with no arithmetic.
                        cursorLag = head - from,
                    )
                }
            } catch (failure: CollectionResetException) {
                reportReset(scope, collection, failure.epoch, logger)
                throw failure
            } catch (failure: CursorTooOldException) {
                // Each of these is a whole-collection snapshot transfer about to happen. A few are a
                // device that was switched off for a while; a rising count means the retention
                // window is too short for the population, which is the host's decision to revisit
                // and cannot be taken without this number.
                metrics.emit(
                    SyncMetricEvent.CursorRefused(scope, collection, behindFloor ?: 0),
                    logger,
                )
                logger.warn(
                    SyncLogEvent.CURSOR_REFUSED,
                    context = { mapOf("behindFloor" to (behindFloor ?: 0).toString()) },
                ) { "the client's cursor is older than the retained history; it has to rebuild the collection" }
                throw failure
            }

        val took = clock.now() - startedAt
        metrics.emit(
            SyncMetricEvent.ChangesServed(
                scope = scope,
                collection = collection,
                batches = served.page.batches.size,
                cursorLag = served.cursorLag,
                duration = took,
            ),
            logger,
        )
        logger.debug(
            SyncLogEvent.CHANGES_SERVED,
            context = {
                mapOf(
                    "batches" to
                        served.page.batches.size
                            .toString(),
                    "cursorLag" to served.cursorLag.toString(),
                    "hasMore" to served.page.hasMore.toString(),
                    "tookMs" to took.inWholeMilliseconds.toString(),
                )
            },
        ) { "served a page of the change log" }
        return served.page
    }

    override fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage {
        val startedAt = clock.now()
        val logger = logger.forCollection(scope, collection)
        // Decoded before the transaction opens. It is the client's own token coming back, so a token
        // this module did not produce is a client or a proxy mangling it, and saying which token was
        // refused is the difference between a fixable report and "snapshot returns 400".
        val token =
            try {
                page?.let(SnapshotCursorToken::decode)
            } catch (failure: IllegalArgumentException) {
                logger.warn(
                    SyncLogEvent.PAGE_TOKEN_INVALID,
                    failure,
                    { mapOf("token" to page?.value.orEmpty().take(TOKEN_PREFIX_LENGTH)) },
                ) { "a snapshot page token was not produced by this module, or can no longer be parsed" }
                throw failure
            }
        val served =
            try {
                snapshotPage(scope, collection, token, limit)
            } catch (failure: CollectionResetException) {
                reportReset(scope, collection, failure.epoch, logger)
                throw failure
            }

        val took = clock.now() - startedAt
        metrics.emit(SyncMetricEvent.SnapshotServed(scope, collection, served.items.size, took), logger)
        logger.debug(
            SyncLogEvent.SNAPSHOT_SERVED,
            context = {
                mapOf(
                    "items" to served.items.size.toString(),
                    "hasMore" to served.hasMore.toString(),
                    "tookMs" to took.inWholeMilliseconds.toString(),
                )
            },
        ) { "served a page of a snapshot to a client rebuilding the collection" }
        return served
    }

    /**
     * Reads one page of a snapshot.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection to read.
     * @param token Decoded continuation token, or `null` for the first page.
     * @param limit Largest number of entities to return.
     * @return Page of entities together with the cursor the log has to be resumed from.
     * @throws CollectionResetException When the token belongs to an incarnation that is gone.
     */
    private fun snapshotPage(
        scope: ScopeId,
        collection: CollectionId,
        token: SnapshotCursorToken?,
        limit: Int,
    ): SnapshotPage =
        transaction(database) {
            config.require(collection)
            val row =
                when (token) {
                    null -> collections.ensure(scope, collection)
                    else -> collections.find(scope, collection)
                }
            if (token != null && token.collectionRowId != row?.id) {
                // The collection was purged between two pages of this transfer. Continuing would
                // staple the pages of one log onto the pages of another.
                throw CollectionResetException(collection, SyncSequences.epoch(token.collectionRowId))
            }
            checkNotNull(row) { "a snapshot without a token must have created the collection" }
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
                cursor = SyncSequences.cursor(row.id, cursor),
                items = items,
                nextPage =
                    items
                        .lastOrNull()
                        ?.takeIf { hasMore }
                        ?.let { SnapshotCursorToken(row.id, it.entity, it.id, cursor).encode() },
                hasMore = hasMore,
                epoch = SyncSequences.epoch(row.id),
            )
        }

    override fun head(
        scope: ScopeId,
        collection: CollectionId,
    ): Cursor =
        transaction(database) {
            config.require(collection)
            val row = collections.ensure(scope, collection)
            SyncSequences.cursor(row.id, row.nextSeq - 1)
        }

    override fun limits(): SyncLimits =
        SyncLimits(
            maxOperationsPerGroup = config.maxOperationsPerGroup,
            // The smallest of the registered limits: one number is published for every collection,
            // and a client that respected a larger one would be refused by the strictest of them.
            maxDocumentBytes = config.collections.values.minOf { it.maxDocumentBytes },
            maxChangesPageSize = config.maxChangesPageSize,
            retentionDays = config.retention.inWholeDays.toInt(),
            blobs = config.blobs?.toLimits(),
        )

    /**
     * Describes how one group was answered.
     *
     * Called after the group's transaction has committed, which is also the moment the counter lock
     * was released — so [lockHeld] is the window every other writer into this collection actually
     * waited out, not the time this code took to reach here.
     *
     * @param group Group the client sent.
     * @param applied What the module did with it.
     * @param lockHeld How long the counter lock was held, or `null` when none was taken.
     */
    private fun SyncLogger.report(
        group: PushGroup,
        applied: AppliedGroup,
        lockHeld: Duration?,
    ) {
        val context = {
            mapOf(
                "group" to group.groupId.value.toString(),
                "operations" to group.ops.size.toString(),
                "lockHeldMs" to (lockHeld?.inWholeMilliseconds?.toString() ?: "none"),
            )
        }
        when (applied.outcome) {
            PushMetricOutcome.APPLIED -> {
                debug(SyncLogEvent.GROUP_APPLIED, context) { "applied a group and consumed a sequence" }
            }

            PushMetricOutcome.CONFLICT -> {
                debug(SyncLogEvent.GROUP_CONFLICTED, context) {
                    "refused a group: entities in it had moved on, and nothing of it was written"
                }
            }

            PushMetricOutcome.REJECTED -> {
                debug(SyncLogEvent.GROUP_REJECTED, context) {
                    "refused a group for good: ${(applied.result as? PushGroupResult.Rejected)?.error?.message}"
                }
            }

            // Idempotency doing its job. Invisible otherwise, and the hardest thing to understand
            // from the client's side: it believes it is sending new content and is answered about
            // content it sent before.
            PushMetricOutcome.REPEATED -> {
                debug(SyncLogEvent.GROUP_REPEATED, context) {
                    "answered a group from the result stored for its identifier; no work was done"
                }
            }
        }
        if (lockHeld != null && lockHeld >= LOCK_HELD_WARNING) {
            // The module serialises writers into a collection on purpose, and that trade is
            // invisible until it is a queue. This is the line that says the day has arrived, and it
            // is the reason the lock duration is measured at all.
            warn(SyncLogEvent.LOCK_HELD_LONG, context = context) {
                "the collection's counter lock was held for ${lockHeld.inWholeMilliseconds}ms; every other " +
                    "writer into this collection waited that long"
            }
        }
    }

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
    private companion object {
        /**
         * How long the counter lock may be held before it is worth a warning.
         *
         * A push writes a bounded number of rows under it, so anything on this scale is a lock
         * contended by other writers rather than one doing work. Deliberately generous: a warning
         * that fires under ordinary load teaches a host to filter it out.
         */
        val LOCK_HELD_WARNING = 500.milliseconds

        /** How much of a refused page token is quoted; enough to identify it, not to replay it. */
        const val TOKEN_PREFIX_LENGTH = 16
    }

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
