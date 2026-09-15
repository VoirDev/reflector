package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.ScopeState
import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
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
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.config.SyncLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
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
 * @param log Sink the worker and its coordinators describe their decisions through, already bound
 *   to this collection.
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
    private val log: SyncLogger,
    private val coroutineScope: CoroutineScope,
    private val clock: Clock,
    private val newUuid: () -> Uuid = { Uuid.random() },
) {
    /** Failure of the most recent attempt, kept in memory: a failure from a past process is history. */
    val lastFailure: MutableStateFlow<SyncFailure?> = MutableStateFlow(null)

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val declined = mutableSetOf<ConflictId>()
    private val conflicts = ConflictCoordinator(scope, collection, stores, transactions, adapter, log, newUuid)

    private var limits: SyncLimits? = null
    private var clientId: ClientId? = null

    /**
     * Whether the collection was already known to be stuck at the end of the previous cycle.
     *
     * Kept so that a queue nobody can unblock is reported when it becomes stuck rather than on every
     * timer tick for as long as it stays that way. It lives on the worker because the coordinators
     * are rebuilt every cycle and would have forgotten by the next one.
     */
    private var queueWasBlocked = false

    /** Starts the loop. Cancelling the coroutine scope stops it. */
    fun start() {
        coroutineScope.launch {
            for (request in requests) {
                runCatching { cycle() }
                    .onFailure { failure ->
                        // The only place the stack trace of a fault inside the application's adapter
                        // or its database is ever visible: what reaches the application through
                        // `lastFailure` is a description, and a description of a `NullPointerException`
                        // is of no use to anybody.
                        log.error(SyncLogEvent.CYCLE_FAILED, failure) {
                            "the cycle threw; the transaction was rolled back and the collection is unchanged"
                        }
                        lastFailure.value =
                            SyncFailure.Local(failure.message ?: "the application's code threw", failure)
                    }
            }
        }
    }

    /** Asks the worker to synchronise as soon as it can. */
    fun requestSync() {
        requests.trySend(Unit)
    }

    private suspend fun cycle() {
        val startedAt = clock.now()
        log.debug(SyncLogEvent.CYCLE_STARTED) { "a synchronisation cycle began" }
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
                log,
                { failure -> lastFailure.value = failure },
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
                log,
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
                log,
                clock,
                newUuid,
            )

        reconcileSchema()
        if (needsBootstrap() && !bootstrap(bootstrap)) {
            return
        }
        when (drainQueue(push)) {
            QueueOutcome.STOPPED -> return
            QueueOutcome.RESET -> return resetToServer(bootstrap)
            QueueOutcome.DRAINED -> Unit
        }
        when (val outcome = pull.pull()) {
            is PullOutcome.Interrupted -> return interrupt(outcome.failure)
            PullOutcome.ResetRequired -> return resetToServer(bootstrap)
            PullOutcome.BootstrapRequired -> if (!bootstrap(bootstrap)) return
            PullOutcome.Blocked -> Unit
            PullOutcome.UpToDate -> lastFailure.value = null
        }
        offerConflicts()
        reportQueue(startedAt)
    }

    /**
     * Reports how much work is left at the end of a cycle.
     *
     * The counts are read from the database rather than accumulated in memory, for the same reason
     * the published state is: a number the application resolved a conflict behind would be a
     * different kind of wrong from a number that is merely a moment old. Both queries are the ones
     * the collection's state flow already runs, so the cycle pays nothing new for them.
     */
    private suspend fun reportQueue(startedAt: Instant) {
        val summary =
            transactions.transaction {
                CycleSummary(
                    pending = stores.records.pendingCount(scope, collection),
                    open = stores.conflicts.openIds(scope, collection).size,
                    headState = stores.groups.head(scope, collection)?.state,
                )
            }
        metrics.emit(SyncMetricEvent.QueueObserved(scope, collection, summary.pending, summary.open), log)
        reportBlockedQueue(summary)
        log.debug(
            SyncLogEvent.CYCLE_FINISHED,
            context = {
                mapOf(
                    "pending" to summary.pending.toString(),
                    "conflicts" to summary.open.toString(),
                    "head" to (summary.headState?.name ?: "none"),
                    "tookMs" to (clock.now() - startedAt).inWholeMilliseconds.toString(),
                )
            },
        ) { "the cycle ended" }
    }

    /**
     * Says so, once, when the queue has stopped being something the library can move.
     *
     * A head that is conflicted or refused is the library's one genuinely silent failure: every
     * group behind it waits, the collection still calls itself live, and nothing changes until the
     * application resolves the conflict or corrects the data. A head serving a backoff is
     * deliberately not reported — it is a wait, not a stall, and saying so on every timer tick
     * during an outage would bury the case that matters.
     *
     * @param summary What the end of the cycle found.
     */
    private fun reportBlockedQueue(summary: CycleSummary) {
        val blocked = summary.headState == PushGroupState.FAILED || summary.headState == PushGroupState.CONFLICTED
        if (blocked && !queueWasBlocked) {
            log.warn(
                SyncLogEvent.QUEUE_BLOCKED,
                context = {
                    mapOf(
                        "head" to summary.headState.name,
                        "pending" to summary.pending.toString(),
                        "conflicts" to summary.open.toString(),
                    )
                },
            ) {
                when (summary.headState) {
                    PushGroupState.CONFLICTED -> {
                        "the queue is blocked: its oldest group is waiting for conflicts to be resolved, " +
                            "and every group behind it waits with it"
                    }

                    else -> {
                        "the queue is blocked: its oldest group was refused permanently, and nothing will " +
                            "leave this collection until the application corrects the data"
                    }
                }
            }
        }
        queueWasBlocked = blocked
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
        var previous: SchemaFingerprint? = null
        val rebuilding =
            transactions.transaction {
                val stored = stores.collections.ensure(scope, collection).schemaFingerprint
                if (stored == declared) {
                    return@transaction false
                }
                stores.collections.setSchemaFingerprint(scope, collection, declared)
                if (stored == null) {
                    return@transaction false
                }
                previous = stored
                stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
                true
            }
        // Said after the transaction rather than inside it: a sink is the application's code and
        // must never run with a write lock held. A collection that is about to transfer its whole
        // contents again is worth an explanation — this is one of four unrelated reasons for it, and
        // without naming which, an unexplained bootstrap is unattributable.
        if (rebuilding) {
            log.info(
                SyncLogEvent.SCHEMA_CHANGED,
                context = { mapOf("from" to previous?.value.orEmpty(), "to" to declared.value) },
            ) { "the adapter declares a different schema than this collection was synchronised under" }
            log.info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "schema") }) {
                "the collection will be rebuilt from a snapshot because its schema fingerprint changed"
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

            // The collection was replaced again, during the rebuild. Nothing more is attempted this
            // cycle: the next one starts from a collection that has already forgotten the old one.
            BootstrapOutcome.ResetRequired -> {
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
     * @return What the rest of the cycle should do.
     */
    private suspend fun drainQueue(push: PushCoordinator): QueueOutcome {
        while (true) {
            when (val outcome = push.pushOnce()) {
                PushOutcome.Applied -> {
                    Unit
                }

                PushOutcome.Idle -> {
                    return QueueOutcome.DRAINED
                }

                PushOutcome.Blocked -> {
                    return QueueOutcome.DRAINED
                }

                PushOutcome.ResetRequired -> {
                    return QueueOutcome.RESET
                }

                is PushOutcome.Interrupted -> {
                    interrupt(outcome.failure)
                    return QueueOutcome.STOPPED
                }
            }
        }
    }

    /**
     * What draining the queue means for the rest of the cycle.
     */
    private enum class QueueOutcome {
        /** The queue is empty or waiting; carry on and read the log. */
        DRAINED,

        /** The scope needs attention; end the cycle and keep everything as it is. */
        STOPPED,

        /** The collection on the server is a different one; discard the local copy and rebuild. */
        RESET,
    }

    /**
     * Throws away the local copy of a collection the server no longer has, and rebuilds it.
     *
     * This is the library's answer to a purge, and it is deliberately total. The cursor, the
     * versions, the queue, the open conflicts and the pending edits all describe one incarnation of
     * one collection; when the server replaces it, none of them mean anything, and the ones that
     * would survive a gentler treatment are precisely the ones that would push the erased data back
     * up. The server is the source of truth, and the truth here is that there is nothing.
     *
     * Local changes are abandoned rather than offered as conflicts: a conflict is a disagreement
     * between two versions of something, and after a purge there is no other version to disagree
     * with. A host that cannot afford to lose them has to stop the device from reaching a purged
     * scope, which is what revoking its access does.
     *
     * The records are marked clean rather than deleted, and the application's own rows are left for
     * the bootstrap that follows: its sweep removes everything the new snapshot does not mention,
     * which is the same work, already written and already tested.
     *
     * @param coordinator Bootstrap to rebuild the collection with once it has been emptied.
     */
    private suspend fun resetToServer(coordinator: BootstrapCoordinator) {
        val abandoned =
            transactions.transaction {
                val pending = stores.records.pendingCount(scope, collection)
                stores.groups.deleteCollection(scope, collection)
                stores.conflicts.deleteCollection(scope, collection)
                stores.inbox.clear(scope, collection)
                stores.records.discardLocalChanges(scope, collection)
                stores.collections.forgetIncarnation(scope, collection)
                pending
            }
        declined.clear()
        // Said outside the transaction, like every other report, and said at all because this is the
        // only place the library destroys unsent work without being asked to.
        log.warn(
            SyncLogEvent.COLLECTION_RESET,
            context = { mapOf("abandoned" to abandoned.toString()) },
        ) {
            "the server's collection is not the one this device was following; the local copy and " +
                "$abandoned unsent change(s) were discarded"
        }
        log.info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "collection-reset") }) {
            "the collection will be rebuilt from a snapshot because the server replaced it"
        }
        bootstrap(coordinator)
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
            log.warn(SyncLogEvent.REQUEST_FAILED, failure) {
                "the server's limits could not be read, so nothing is sent or applied this cycle"
            }
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
        log.warn(SyncLogEvent.REQUEST_FAILED, failure) {
            "the scope can no longer be synchronised; the workers stop and the queue is kept"
        }
        scopeState.value =
            when (failure) {
                is SyncTransportFailure.Revoked -> ScopeState.Revoked
                else -> ScopeState.AuthRequired
            }
        recordFailure(failure)
    }

    /**
     * Translates a transport failure into what the application is told.
     *
     * Exhaustive on purpose rather than falling back on a catch-all. A catch-all is how three
     * failures the protocol names — refused credentials, a revoked scope and a cursor outside the
     * retention window — were reported as a server error with the status code zero, which is a
     * value no server has ever answered with and an application cannot act on. A new transport
     * failure should stop the compiler here and be decided about, not be absorbed.
     *
     * @param failure What the transport reported.
     */
    private fun recordFailure(failure: SyncTransportFailure) {
        lastFailure.value =
            when (failure) {
                is SyncTransportFailure.Unreachable -> {
                    scopeState.compareAndSet(ScopeState.Online, ScopeState.Offline)
                    SyncFailure.Network(failure.describe(), failure.cause)
                }

                is SyncTransportFailure.ServerError -> {
                    SyncFailure.Server(failure.statusCode, failure.describe())
                }

                is SyncTransportFailure.RateLimited -> {
                    SyncFailure.Server(TOO_MANY_REQUESTS, failure.describe())
                }

                is SyncTransportFailure.Unauthorized -> {
                    SyncFailure.AuthRequired(failure.describe())
                }

                is SyncTransportFailure.Revoked -> {
                    SyncFailure.Revoked(failure.describe())
                }

                // Reported as the status it arrived as. The application does nothing about it — the
                // library bootstraps on its own — so it needs a case of its own even less than it
                // needs a fictitious status code.
                is SyncTransportFailure.CursorTooOld -> {
                    SyncFailure.Server(GONE, failure.describe())
                }

                // Reported the same way and for the same reason: the library answers it by itself,
                // and by the time the application could read this the collection is already being
                // rebuilt. What was lost with it is in the log, where a person can find it.
                is SyncTransportFailure.CollectionReset -> {
                    SyncFailure.Server(CONFLICT, failure.describe())
                }
            }
    }

    /**
     * Describes a transport failure without producing an empty string.
     *
     * A blank description is the worst of the three possible answers: it says there was a failure
     * and refuses to say anything about it, which reads on a screen as a bug in this library.
     *
     * @return The failure's own message, the message of what caused it, or the name of its case.
     */
    private fun SyncTransportFailure.describe(): String =
        message?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
            ?: this::class.simpleName.orEmpty()

    /**
     * What the end of one cycle found in the database.
     *
     * Read in a single transaction because the three values are reported together and a queue depth
     * that disagreed with the head it belongs to would be worse than a moment-old one.
     *
     * @property pending Local changes that have not reached the server.
     * @property open Conflicts waiting for the application to decide.
     * @property headState State of the oldest group in the queue, or `null` when the queue is empty.
     */
    private data class CycleSummary(
        val pending: Int,
        val open: Int,
        val headState: PushGroupState?,
    )

    private companion object {
        const val TOO_MANY_REQUESTS = 429

        /** What the server answers for a cursor it no longer keeps history for. */
        const val GONE = 410

        /** Status a host answers when the collection a client refers to has been purged. */
        const val CONFLICT = 409
    }
}
