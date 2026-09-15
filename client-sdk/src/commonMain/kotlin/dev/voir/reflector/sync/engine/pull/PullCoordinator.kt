package dev.voir.reflector.sync.engine.pull

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.inbox.InboxOperation
import dev.voir.reflector.sync.persistence.inbox.StoredBatch
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.config.SyncLimits
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Reads the change log and applies it to the application's tables.
 *
 * The pull is two-phase on purpose. A page is first written to the inbox, and only then are its
 * batches applied one at a time, each together with advancing the cursor. That buys two things: a
 * crash while applying costs a repeated apply rather than a repeated download, and one server
 * transaction becomes exactly one local transaction — so atomicity holds at the level of a batch
 * instead of at the level of a whole synchronisation cycle.
 *
 * @param scope Scope being synchronised.
 * @param collection Collection being synchronised.
 * @param clientId Identity of this installation, used to recognise the echo of its own pushes.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param transport Connection to the server.
 * @param adapter Application's bridge to its own rows.
 * @param limits Limits published by the server.
 * @param metrics Sink for how much was read and how long it took.
 * @param log Sink for what the log delivered and what was done with it, bound to this collection.
 * @param clock Source of local time, used only for diagnostics.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 */
internal class PullCoordinator(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val clientId: ClientId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapter: CollectionAdapter,
    private val limits: SyncLimits,
    private val metrics: SyncMetrics,
    private val log: SyncLogger,
    private val clock: Clock,
    private val newUuid: () -> Uuid,
) {
    /**
     * Reads everything the server has after the current cursor and applies it.
     *
     * @return What the worker should do next.
     */
    suspend fun pull(): PullOutcome {
        val startedAt = clock.now()
        var applied = 0

        // Whatever a previous run downloaded but did not manage to apply is applied first: it is
        // already paid for, and the cursor cannot move past it anyway.
        drainInbox { applied++ }?.let { return it }

        while (true) {
            val state = transactions.transaction { stores.collections.ensure(scope, collection) }
            if (state.phase == SyncPhase.NEW || state.phase == SyncPhase.RESYNC_REQUIRED) {
                return PullOutcome.BootstrapRequired
            }

            val page =
                try {
                    transport.changes(scope, collection, state.cursor, limits.maxChangesPageSize)
                } catch (failure: SyncTransportFailure) {
                    return handleFailure(failure)
                }

            log.debug(
                SyncLogEvent.PULL_PAGE_RECEIVED,
                context = {
                    mapOf(
                        "batches" to page.batches.size.toString(),
                        "operations" to page.batches.sumOf { it.ops.size }.toString(),
                        "hasMore" to page.hasMore.toString(),
                    )
                },
            ) { "a page of the change log arrived and goes to the inbox before anything is applied" }

            transactions.transaction {
                stores.inbox.store(scope, collection, page.batches.map { it.toStored() })
            }

            drainInbox { applied++ }?.let { return it }

            if (!page.hasMore) {
                // Only a pull that reached the end reports: one that stopped on a failure or on a
                // required bootstrap read an unknown share of what was there.
                metrics.emit(
                    SyncMetricEvent.PullCompleted(scope, collection, applied, clock.now() - startedAt),
                    log,
                )
                return PullOutcome.UpToDate
            }
        }
    }

    /**
     * Applies the batches waiting in the inbox.
     *
     * @param onApplied Called once for every batch that reached the application's tables, so that
     *   the caller can count what a pull actually delivered.
     * @return A terminal outcome when applying cannot continue, or `null` when the inbox is empty.
     */
    private suspend fun drainInbox(onApplied: () -> Unit): PullOutcome? {
        while (true) {
            val batch = transactions.transaction { stores.inbox.oldestPending(scope, collection) } ?: return null
            val unknown = batch.ops.firstOrNull { operationOf(it) == null }
            if (unknown != null) {
                log.error(
                    SyncLogEvent.UNKNOWN_OPERATION,
                    context = { mapOf("operation" to unknown.op, "seq" to batch.seq.value.toString()) },
                ) {
                    "the log carries an operation code this client does not understand; the collection is " +
                        "rebuilt rather than skipping past a change it cannot apply"
                }
                log.info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "unknown-operation") }) {
                    "the collection will be rebuilt from a snapshot because the log carried an unknown operation"
                }
                // Skipping is not an option: the cursor would move past the operation and the change
                // would be lost for good. Refusing the batch and resynchronising is the only way out
                // that neither loses it nor guesses what it meant.
                transactions.transaction {
                    stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
                    stores.collections.recordFailure(scope, collection, "unknown operation '${unknown.op}' in the log")
                    stores.inbox.clear(scope, collection)
                }
                return PullOutcome.BootstrapRequired
            }
            transactions.transaction { apply(batch) }
            log.trace(
                SyncLogEvent.BATCH_APPLIED,
                context = {
                    mapOf(
                        "seq" to batch.seq.value.toString(),
                        "operations" to batch.ops.size.toString(),
                        "ownEcho" to (batch.originClientId == clientId).toString(),
                    )
                },
            ) { "a batch was applied to the application's tables and the cursor moved with it" }
            onApplied()
        }
    }

    private suspend fun apply(batch: StoredBatch) {
        val generation = stores.collections.ensure(scope, collection).generation
        val isOwnEcho = batch.originClientId != null && batch.originClientId == clientId
        val toApply = mutableListOf<RemoteOp>()

        for (op in batch.ops) {
            val record = stores.records.find(scope, collection, op.entityType, op.entityId)
            when {
                // Already known: the same version cannot mean anything new.
                record?.serverVersion == op.version -> {
                    Unit
                }

                record == null || !record.isDirty -> {
                    toApply += operationOf(op) ?: continue
                    stores.records.rememberServerVersion(
                        scope = scope,
                        collection = collection,
                        entityType = op.entityType,
                        entityId = op.entityId,
                        serverVersion = op.version,
                        generation = generation,
                    )
                }

                isOwnEcho -> {
                    // This client's own change coming back. The rows already hold it, and applying it
                    // again would overwrite an edit the user has made since. Only the version moves.
                    stores.records.rememberServerVersion(
                        scope = scope,
                        collection = collection,
                        entityType = op.entityType,
                        entityId = op.entityId,
                        serverVersion = op.version,
                        generation = generation,
                    )
                }

                else -> {
                    recordConflict(op, record?.conflictId)
                }
            }
        }

        if (toApply.isNotEmpty()) {
            adapter.applyRemote(toApply)
        }
        stores.collections.advanceCursor(
            scope = scope,
            collection = collection,
            cursor = batch.seq.asCursor(),
            appliedAt = clock.now().toEpochMilliseconds(),
        )
        stores.inbox.deleteApplied(scope, collection, batch.seq)
    }

    /**
     * Records a conflict found while applying somebody else's change over a local edit.
     *
     * This is the branch that makes conflicts durable at the moment they appear. The cursor moves on
     * regardless, so an incoming state that is neither applied nor written down is simply gone —
     * applying it would silently destroy the user's edit, and dropping it would silently destroy the
     * server's.
     *
     * @param op Incoming operation that disagrees with the local state.
     * @param existing Conflict the record already holds, which this change brings up to date rather
     *   than adding to: the entity has one disagreement, however many ways it arrives.
     */
    private suspend fun recordConflict(
        op: InboxOperation,
        existing: ConflictId?,
    ) {
        val conflictId =
            stores.conflicts.open(
                scope = scope,
                collection = collection,
                existing = existing,
                conflictId = ConflictId(newUuid()),
                entityType = op.entityType,
                entityId = op.entityId,
                origin = ConflictOrigin.PULL,
                local = adapter.snapshot(op.entityType, op.entityId),
                server = op.payload,
                serverVersion = op.version,
                detectedAt = clock.now().toEpochMilliseconds(),
            )
        log.warn(
            SyncLogEvent.CONFLICT_OPENED,
            context = {
                mapOf(
                    "entity" to op.entityType.value,
                    "id" to op.entityId.value.toString(),
                    "origin" to ConflictOrigin.PULL.name,
                    "serverVersion" to op.version.value,
                )
            },
        ) { "an incoming change disagrees with a local edit; both sides are kept until somebody decides" }
        // The record keeps the version it was based on: a resolution is applied on top of the
        // version stored with the conflict, and overwriting it here would leave the next push with
        // a base the user never saw.
        stores.records.setConflict(scope, collection, op.entityType, op.entityId, conflictId)
    }

    private suspend fun handleFailure(failure: SyncTransportFailure): PullOutcome =
        when (failure) {
            is SyncTransportFailure.CursorTooOld -> {
                log.warn(SyncLogEvent.CURSOR_TOO_OLD, failure) {
                    "the server no longer keeps history back to this client's cursor"
                }
                log.info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "cursor-too-old") }) {
                    "the collection will be rebuilt from a snapshot because its cursor fell out of the window"
                }
                transactions.transaction {
                    stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
                    stores.inbox.clear(scope, collection)
                }
                PullOutcome.BootstrapRequired
            }

            is SyncTransportFailure.Unauthorized, is SyncTransportFailure.Revoked -> {
                PullOutcome.Interrupted(failure)
            }

            else -> {
                log.debug(
                    SyncLogEvent.REQUEST_FAILED,
                    context = { mapOf("failure" to (failure::class.simpleName ?: "unknown")) },
                ) { "the change log could not be read this cycle: ${failure.message.orEmpty()}" }
                transactions.transaction {
                    stores.collections.recordFailure(scope, collection, failure.message.orEmpty())
                }
                PullOutcome.Blocked
            }
        }

    /**
     * Translates a stored operation into the form the adapter understands.
     *
     * @return The change to apply, or `null` when the operation code is not known to this client.
     */
    private fun operationOf(op: InboxOperation): RemoteOp? =
        when (op.op) {
            OP_UPSERT -> op.payload?.let { RemoteOp.Upsert(op.entityType, op.entityId, it) }
            OP_DELETE -> RemoteOp.Delete(op.entityType, op.entityId)
            else -> null
        }

    private fun ChangeBatch.toStored(): StoredBatch =
        StoredBatch(
            seq = seq,
            originClientId = originClientId,
            ops =
                ops.map { operation ->
                    when (operation) {
                        is RemoteOperation.Upsert -> {
                            InboxOperation(
                                entityType = operation.entity,
                                entityId = operation.id,
                                op = OP_UPSERT,
                                version = operation.version,
                                payload = operation.data,
                            )
                        }

                        is RemoteOperation.Delete -> {
                            InboxOperation(
                                entityType = operation.entity,
                                entityId = operation.id,
                                op = OP_DELETE,
                                version = operation.version,
                                payload = null,
                            )
                        }

                        is RemoteOperation.Unknown -> {
                            unknownOperation(operation)
                        }
                    }
                },
        )

    /**
     * Stores an operation whose code this client does not know, so that applying it can fail on
     * purpose later instead of failing to parse the response now.
     */
    private fun unknownOperation(operation: RemoteOperation.Unknown): InboxOperation =
        InboxOperation(
            entityType = EntityType(""),
            entityId = EntityId(Uuid.NIL),
            op = operation.op,
            version = EntityVersion(""),
            payload = operation.raw,
        )

    private companion object {
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
    }
}
