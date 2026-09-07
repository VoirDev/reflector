package dev.voir.reflector.sync.engine.bootstrap

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Rebuilds a collection from a snapshot when the log can no longer be followed.
 *
 * The snapshot is deliberately "dirty": the server fixes the cursor before the first page and does
 * not hold a repeatable read across them. An entity changed halfway through comes back in its newer
 * state, which is harmless because every state is a full one — repetitions are idempotent, and gaps
 * cannot happen. Whatever was committed during the transfer is then replayed from the log starting
 * at the fixed cursor.
 *
 * What the snapshot did not mention is removed afterwards by generation sweep. Dirty records are
 * excluded from it: they hold changes that never reached the server, and a resynchronisation is not
 * a reason to lose them.
 *
 * @param scope Scope being synchronised.
 * @param collection Collection being rebuilt.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param transport Connection to the server.
 * @param adapter Application's bridge to its own rows.
 * @param limits Limits published by the server; the snapshot is read in pages of the same size as
 *   the log, because the server publishes no separate limit for it.
 * @param metrics Sink for what a completed bootstrap transferred and how long it took.
 * @param clock Source of local time, used only for diagnostics.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 */
internal class BootstrapCoordinator(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapter: CollectionAdapter,
    private val limits: SyncLimits,
    private val metrics: SyncMetrics,
    private val clock: Clock,
    private val newUuid: () -> Uuid,
) {
    /**
     * Runs a bootstrap, resuming an interrupted one when there is something to resume.
     *
     * @return What the worker should do next.
     */
    suspend fun bootstrap(): BootstrapOutcome {
        val startedAt = clock.now()
        val start = transactions.transaction { begin() }
        var page = start.page
        var cursor = start.cursor
        var items = 0

        while (true) {
            val snapshot =
                try {
                    transport.snapshot(scope, collection, page, limits.maxChangesPageSize)
                } catch (failure: SyncTransportFailure) {
                    return handleFailure(failure)
                }

            val fixedCursor = cursor ?: snapshot.cursor
            transactions.transaction {
                applyPage(snapshot.items, start.generation)
                // The cursor is stored from the first page on, not at the end: a bootstrap that
                // resumes after a restart has to continue from the position the server fixed then.
                // Everything committed since lives only in the log, and asking for a fresh position
                // now would skip exactly that.
                stores.collections.setCursor(scope, collection, fixedCursor)
                stores.collections.updateBootstrapPage(scope, collection, snapshot.nextPage)
            }
            cursor = fixedCursor
            items += snapshot.items.size

            if (!snapshot.hasMore) {
                break
            }
            page = snapshot.nextPage ?: break
        }

        transactions.transaction {
            sweep(start.generation)
            stores.collections.finishBootstrap(
                scope = scope,
                collection = collection,
                cursor = cursor ?: error("a finished snapshot must have produced a cursor"),
            )
        }
        // Only a bootstrap that finished is reported, and it reports its own pages alone: a run
        // resumed after an interruption did not transfer what the run before it already applied,
        // and adding those in would describe work nobody did.
        metrics.emit(SyncMetricEvent.BootstrapCompleted(scope, collection, items, clock.now() - startedAt))
        return BootstrapOutcome.Completed
    }

    private suspend fun begin(): Start {
        val state = stores.collections.ensure(scope, collection)
        val resumable = state.phase == SyncPhase.BOOTSTRAPPING && state.bootstrapPage != null
        if (resumable) {
            return Start(generation = state.generation, page = state.bootstrapPage, cursor = state.cursor)
        }
        // Anything downloaded before is meaningless once a snapshot replaces the collection.
        stores.inbox.clear(scope, collection)
        return Start(generation = stores.collections.beginBootstrap(scope, collection), page = null, cursor = null)
    }

    private suspend fun applyPage(
        items: List<SnapshotItem>,
        generation: Long,
    ) {
        val toApply = mutableListOf<RemoteOp>()
        for (item in items) {
            val record = stores.records.find(scope, collection, item.entity, item.id)
            when {
                record == null || !record.isDirty -> {
                    toApply += RemoteOp.Upsert(item.entity, item.id, item.data)
                    stores.records.rememberServerVersion(
                        scope = scope,
                        collection = collection,
                        entityType = item.entity,
                        entityId = item.id,
                        serverVersion = item.version,
                        generation = generation,
                    )
                }

                record.serverVersion == item.version -> {
                    // A local edit on top of exactly this server state. Nothing to apply, and the
                    // edit still has to be pushed, so only the generation is refreshed.
                    stores.records.rememberServerVersion(
                        scope = scope,
                        collection = collection,
                        entityType = item.entity,
                        entityId = item.id,
                        serverVersion = item.version,
                        generation = generation,
                    )
                }

                else -> {
                    recordConflict(item, record.conflictId)
                }
            }
        }
        if (toApply.isNotEmpty()) {
            adapter.applyRemote(toApply)
        }
    }

    /**
     * Records a conflict between a local edit and a snapshot that has moved past its base.
     *
     * A bootstrap has no origin information, so an entity that was edited locally and has a
     * different version on the server is a genuine conflict: applying the snapshot would drop the
     * user's change, and ignoring it would push over a state nobody has seen.
     *
     * @param item Snapshot entry that disagrees with the local state.
     * @param existing Conflict the record already holds — a resynchronisation can well run over an
     *   entity that is already waiting for a decision, and the snapshot brings that decision a newer
     *   server state rather than a second question about the same one.
     */
    private suspend fun recordConflict(
        item: SnapshotItem,
        existing: ConflictId?,
    ) {
        val conflictId =
            stores.conflicts.open(
                scope = scope,
                collection = collection,
                existing = existing,
                conflictId = ConflictId(newUuid()),
                entityType = item.entity,
                entityId = item.id,
                origin = ConflictOrigin.PULL,
                local = adapter.snapshot(item.entity, item.id),
                server = item.data,
                serverVersion = item.version,
                detectedAt = clock.now().toEpochMilliseconds(),
            )
        stores.records.setConflict(scope, collection, item.entity, item.id, conflictId)
    }

    private suspend fun sweep(generation: Long) {
        val stale = stores.records.staleRecords(scope, collection, generation)
        if (stale.isEmpty()) {
            return
        }
        adapter.applyRemote(stale.map { RemoteOp.Delete(it.entityType, it.entityId) })
        stores.records.deleteStale(scope, collection, generation)
    }

    private suspend fun handleFailure(failure: SyncTransportFailure): BootstrapOutcome =
        when (failure) {
            is SyncTransportFailure.Unauthorized, is SyncTransportFailure.Revoked -> {
                BootstrapOutcome.Interrupted(failure)
            }

            else -> {
                transactions.transaction {
                    stores.collections.recordFailure(scope, collection, failure.message.orEmpty())
                }
                BootstrapOutcome.Blocked
            }
        }

    /**
     * Where a bootstrap starts from.
     *
     * @property generation Generation every confirmed record carries, and the sweep compares against.
     * @property page Page to continue with, or `null` to start from the beginning.
     * @property cursor Position fixed by the server, or `null` when the first page has not arrived.
     */
    private data class Start(
        val generation: Long,
        val page: PageToken?,
        val cursor: Cursor?,
    )
}
