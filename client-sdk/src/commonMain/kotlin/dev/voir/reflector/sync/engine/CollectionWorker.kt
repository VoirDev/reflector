package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.ScopeState
import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.bootstrap.BootstrapCoordinator
import dev.voir.reflector.sync.engine.bootstrap.BootstrapOutcome
import dev.voir.reflector.sync.engine.conflict.ConflictCoordinator
import dev.voir.reflector.sync.engine.pull.PullCoordinator
import dev.voir.reflector.sync.engine.pull.PullOutcome
import dev.voir.reflector.sync.engine.push.PushCoordinator
import dev.voir.reflector.sync.engine.push.PushOutcome
import dev.voir.reflector.sync.engine.retry.BackoffPolicy
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.config.SyncLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Drives one collection: decides what to do next and in which order.
 *
 * The order is not arbitrary. A collection that cannot follow the log is bootstrapped first,
 * because nothing else is meaningful until it can. Then the queue is drained, because local changes
 * are what the user is waiting for. Only then is the log read — and reading it right after a push
 * is also how this client learns the server's view of what it has just sent.
 *
 * The worker holds no timers of its own: it reacts to requests, and the backoff of a failed group
 * or collection lives in the database, where it survives a restart.
 *
 * @param scope Scope being synchronised.
 * @param collection Collection being synchronised.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param transport Connection to the server.
 * @param adapter Application's bridge to its own rows.
 * @param scopeState State shared by every collection of the scope.
 * @param metrics Sink the worker and its coordinators report through.
 * @param coroutineScope Scope the worker's loop runs in; cancelling it stops the worker.
 * @param clock Source of local time, used for backoff and diagnostics only.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 */
internal class CollectionWorker(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapter: CollectionAdapter,
    private val scopeState: MutableStateFlow<ScopeState>,
    private val metrics: SyncMetrics,
    private val coroutineScope: CoroutineScope,
    private val clock: Clock,
    private val newUuid: () -> Uuid = { Uuid.random() },
) {
    /** Failure of the most recent attempt, kept in memory: a failure from a past process is history. */
    val lastFailure: MutableStateFlow<SyncFailure?> = MutableStateFlow(null)

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val declined = mutableSetOf<ConflictId>()
    private val conflicts = ConflictCoordinator(scope, collection, stores, transactions, adapter, newUuid)

    private var limits: SyncLimits? = null
    private var clientId: ClientId? = null

    /** Starts the loop. Cancelling the coroutine scope stops it. */
    fun start() {
        coroutineScope.launch {
            for (request in requests) {
                runCatching { cycle() }
                    .onFailure { failure -> lastFailure.value = SyncFailure.Local(failure.message ?: "local failure") }
            }
        }
    }

    /** Asks the worker to synchronise as soon as it can. */
    fun requestSync() {
        requests.trySend(Unit)
    }

    private suspend fun cycle() {
        val limits = limits() ?: return
        val clientId = clientId()

        val push =
            PushCoordinator(
                scope,
                collection,
                clientId,
                stores,
                transactions,
                transport,
                adapter,
                limits,
                BackoffPolicy(),
                metrics,
                clock,
                newUuid,
            )
        val pull =
            PullCoordinator(
                scope,
                collection,
                clientId,
                stores,
                transactions,
                transport,
                adapter,
                limits,
                metrics,
                clock,
                newUuid,
            )
        val bootstrap =
            BootstrapCoordinator(
                scope,
                collection,
                stores,
                transactions,
                transport,
                adapter,
                limits,
                metrics,
                clock,
                newUuid,
            )

        reconcileSchema()
        if (needsBootstrap() && !bootstrap(bootstrap)) {
            return
        }
        if (!drainQueue(push)) {
            return
        }
        when (val outcome = pull.pull()) {
            is PullOutcome.Interrupted -> return interrupt(outcome.failure)
            PullOutcome.BootstrapRequired -> if (!bootstrap(bootstrap)) return
            PullOutcome.Blocked -> Unit
            PullOutcome.UpToDate -> lastFailure.value = null
        }
        offerConflicts()
        reportQueue()
    }

    /**
     * Reports how much work is left at the end of a cycle.
     *
     * The counts are read from the database rather than accumulated in memory, for the same reason
     * the published state is: a number the application resolved a conflict behind would be a
     * different kind of wrong from a number that is merely a moment old. Both queries are the ones
     * the collection's state flow already runs, so the cycle pays nothing new for them.
     */
    private suspend fun reportQueue() {
        val (pending, open) =
            transactions.transaction {
                stores.records.pendingCount(scope, collection) to stores.conflicts.openIds(scope, collection).size
            }
        metrics.emit(SyncMetricEvent.QueueObserved(scope, collection, pending, open))
    }

    /**
     * Compares the shape the adapter declares with the one this collection was synchronised under.
     *
     * Runs first in the cycle, before anything reads or writes the application's tables: everything
     * after it would be operating on rows whose relationship to the cursor is exactly what is in
     * doubt. A collection whose declared shape has changed is sent back to a snapshot, and the new
     * shape is recorded in the same transaction — separately they could leave a collection that has
     * forgotten it needs rebuilding.
     *
     * An adapter that declares nothing is left alone, and so is the first run under a declaration:
     * nothing is known to have changed, and rebuilding every installation because an application
     * started answering a question it used to ignore would be a lie about what happened.
     */
    private suspend fun reconcileSchema() {
        val declared = adapter.schema ?: return
        transactions.transaction {
            val stored = stores.collections.ensure(scope, collection).schemaFingerprint
            if (stored == declared) {
                return@transaction
            }
            stores.collections.setSchemaFingerprint(scope, collection, declared)
            if (stored != null) {
                stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
            }
        }
    }

    private suspend fun needsBootstrap(): Boolean {
        val state = transactions.transaction { stores.collections.ensure(scope, collection) }
        return state.phase == SyncPhase.NEW || state.phase == SyncPhase.RESYNC_REQUIRED
    }

    private suspend fun bootstrap(coordinator: BootstrapCoordinator): Boolean =
        when (val outcome = coordinator.bootstrap()) {
            BootstrapOutcome.Completed -> {
                lastFailure.value = null
                true
            }

            BootstrapOutcome.Blocked -> {
                false
            }

            is BootstrapOutcome.Interrupted -> {
                interrupt(outcome.failure)
                false
            }
        }

    /**
     * Sends groups until the queue is empty or stops moving.
     *
     * @return `false` when the scope itself needs attention and the cycle has to end.
     */
    private suspend fun drainQueue(push: PushCoordinator): Boolean {
        while (true) {
            when (val outcome = push.pushOnce()) {
                PushOutcome.Applied -> {
                    Unit
                }

                PushOutcome.Idle -> {
                    return true
                }

                PushOutcome.Blocked -> {
                    return true
                }

                is PushOutcome.Interrupted -> {
                    interrupt(outcome.failure)
                    return false
                }
            }
        }
    }

    /**
     * Offers conflicts to the application, remembering which ones it declined to decide.
     *
     * Without that memory the adapter would be asked again on every cycle about a conflict it has
     * already handed to its user interface.
     */
    private suspend fun offerConflicts() {
        val open = transactions.transaction { stores.conflicts.openIds(scope, collection) }
        declined.retainAll(open.toSet())
        for (conflictId in open) {
            if (conflictId in declined) {
                continue
            }
            if (!conflicts.offerToAdapter(conflictId)) {
                declined += conflictId
            }
        }
    }

    private suspend fun limits(): SyncLimits? {
        limits?.let { return it }
        return try {
            transport.limits().also { limits = it }
        } catch (failure: SyncTransportFailure) {
            recordFailure(failure)
            null
        }
    }

    private suspend fun clientId(): ClientId {
        clientId?.let { return it }
        return transactions
            .transaction {
                val existing = stores.meta.clientId(scope)
                existing ?: ClientId(newUuid()).also {
                    stores.meta.putClientId(scope, it, clock.now().toEpochMilliseconds())
                }
            }.also { clientId = it }
    }

    private fun interrupt(failure: SyncTransportFailure) {
        scopeState.value =
            when (failure) {
                is SyncTransportFailure.Revoked -> ScopeState.Revoked
                else -> ScopeState.AuthRequired
            }
        recordFailure(failure)
    }

    private fun recordFailure(failure: SyncTransportFailure) {
        lastFailure.value =
            when (failure) {
                is SyncTransportFailure.Unreachable -> {
                    scopeState.compareAndSet(ScopeState.Online, ScopeState.Offline)
                    SyncFailure.Network(failure.message.orEmpty())
                }

                is SyncTransportFailure.ServerError -> {
                    SyncFailure.Server(failure.statusCode, failure.message.orEmpty())
                }

                is SyncTransportFailure.RateLimited -> {
                    SyncFailure.Server(TOO_MANY_REQUESTS, failure.message.orEmpty())
                }

                else -> {
                    SyncFailure.Server(0, failure.message.orEmpty())
                }
            }
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
