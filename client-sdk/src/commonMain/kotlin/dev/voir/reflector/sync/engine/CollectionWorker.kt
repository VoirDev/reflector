package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.ScopeState
import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.NetworkAvailability
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.blob.BlobReconciler
import dev.voir.reflector.sync.engine.blob.BlobReferences
import dev.voir.reflector.sync.engine.blob.BlobWorker
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
import dev.voir.reflector.sync.persistence.record.MutationIntent
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.config.SyncLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
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
 * @param network What the application knows about this device's own connection, or `null` when it
 *   supplies nothing — used only to tell a device with no network apart from a server that is not
 *   answering.
 * @param scopeState State shared by every collection of the scope.
 * @param metrics Sink the worker and its coordinators report through.
 * @param log Sink the worker and its coordinators describe their decisions through, already bound
 *   to this collection.
 * @param coroutineScope Scope the worker's loop runs in; cancelling it stops the worker.
 * @param clock Source of local time, used for backoff and diagnostics only.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 * @param blobStore Application's own file store, or `null` when it synchronises no files. Its
 *   presence is the whole of opting in: without one the adapter is never asked what a document
 *   points at and no blob path in the library is entered.
 * @param blobTransport Connection to the server's file endpoints and to the storage its tickets
 *   point at. Required when [blobStore] is given, and unused otherwise.
 * @param blobFetch Policy for a reference that declares none of its own: whether this device
 *   fetches the file as soon as a document names it, or waits to be asked.
 */
internal class CollectionWorker(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapter: CollectionAdapter,
    private val network: NetworkAvailability?,
    private val scopeState: MutableStateFlow<ScopeState>,
    private val metrics: SyncMetrics,
    private val log: SyncLogger,
    private val coroutineScope: CoroutineScope,
    private val clock: Clock,
    private val newUuid: () -> Uuid = { Uuid.random() },
    private val blobStore: BlobStore? = null,
    private val blobTransport: BlobTransport? = null,
    private val blobFetch: BlobFetch = BlobFetch.EAGER,
) {
    /** Failure of the most recent attempt, kept in memory: a failure from a past process is history. */
    val lastFailure: MutableStateFlow<SyncFailure?> = MutableStateFlow(null)

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Held by a cycle for as long as it runs, and by a discard while it rewrites the collection.
     *
     * A discard cannot share the collection with a cycle: a pull applied over a record the discard
     * has just cleaned, or a bootstrap that ends by declaring the collection live after the discard
     * asked for it to be rebuilt, would each leave rows showing a change nothing will ever overwrite.
     */
    private val cycleLock = Mutex()

    /** The cycle running now, or `null` between cycles; what a discard cancels instead of waiting for. */
    @Volatile
    private var runningCycle: Job? = null
    private val declined = mutableSetOf<ConflictId>()
    private val references = blobStore?.let { BlobReferences(scope, collection, stores, adapter, blobFetch) }
    private val reconciler =
        blobStore?.let { BlobReconciler(scope, collection, stores, transactions, it, log) }

    /**
     * Moves the bytes, on a coroutine of its own.
     *
     * Built on the first cycle rather than at construction, because it needs the limits the server
     * publishes and those are not known until something has asked for them.
     */
    private var blobWorker: BlobWorker? = null

    /** Whether the server has said it serves no files, so that it is complained about only once. */
    private var blobsUnsupported = false
    private val conflicts =
        ConflictCoordinator(scope, collection, stores, transactions, adapter, log, newUuid, references)

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
                cycleLock.withLock {
                    // A child of its own, so that a discard can cancel the cycle without stopping
                    // the loop: everything a cycle writes is written in transactions, so one cut
                    // short leaves exactly what a process that died at the same point would.
                    val cycle = launch(CycleMarker()) { runCycle() }
                    runningCycle = cycle
                    cycle.join()
                    runningCycle = null
                }
                // Outside the cycle rather than at the end of it, and deliberately: what this decides
                // is derived from the reference rows, not from what the cycle managed to achieve, and
                // a cycle stops early for half a dozen ordinary reasons. A push that was refused is
                // exactly the moment at which a file the same cycle just referenced still has to be
                // looked into — leaving that until the next trigger would strand it for a timer.
                runCatching { reconciler?.reconcile() }
                    .onFailure { failure ->
                        log.error(SyncLogEvent.BLOB_RELEASE_FAILED, failure) {
                            "reconciling files threw; what it had already decided stands and the rest is retried"
                        }
                    }
                // Whatever reconciliation decided is work for the file worker, so it is woken here
                // rather than left for a timer: a photograph adopted a moment ago is one somebody is
                // very likely looking at an empty frame for.
                blobWorker?.requestTransfers()
            }
        }
    }

    /** Asks the worker to synchronise as soon as it can. */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /**
     * Throws away every unsent change now, and asks for the collection to be rebuilt.
     *
     * A cycle running at the time is cancelled rather than waited for: what it is pushing is what is
     * being thrown away, and what it is downloading the rebuild downloads again. Returns once the
     * discard has committed; the rebuild follows in the next cycle.
     *
     * @throws IllegalStateException When called from inside the cycle — from the adapter's callbacks
     *   — which would wait for the very cycle it runs in.
     */
    suspend fun discardLocalChanges() {
        check(currentCoroutineContext()[CycleMarker] == null) {
            "discardLocalChanges() cannot be called from inside the adapter's callbacks"
        }
        runningCycle?.cancel()
        cycleLock.withLock { discard() }
        requestSync()
    }

    /**
     * Runs one cycle, turning a fault in it into a reported failure rather than the end of the loop.
     */
    private suspend fun runCycle() {
        try {
            cycle()
        } catch (cancelled: CancellationException) {
            // A discard cut the cycle short, or the worker is stopping. Neither is a failure.
            throw cancelled
        } catch (failure: Throwable) {
            // The only place the stack trace of a fault inside the application's adapter or its
            // database is ever visible: what reaches the application through `lastFailure` is a
            // description, and a description of a `NullPointerException` is of no use to anybody.
            log.error(SyncLogEvent.CYCLE_FAILED, failure) {
                "the cycle threw; the transaction was rolled back and the collection is unchanged"
            }
            lastFailure.value = SyncFailure.Local(failure.message ?: "the application's code threw", failure)
        }
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
                references,
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
                references,
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
                references,
            )

        startBlobWorker(limits)
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

            // The cycle's one reliable report of whether the server is reachable. A push that
            // fails schedules its own retry and says nothing about the scope, and the limits are
            // read once and cached, so without this a device that lost its connection after the
            // first cycle stayed "online" until it was restarted.
            is PullOutcome.Blocked -> recordFailure(outcome.failure)

            // And the other half of the same report: a log read to its end is an answer from the
            // server, whatever the socket or an earlier request said.
            PullOutcome.UpToDate -> {
                recordSuccess()
                lastFailure.value = null
            }
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
                    outgoingFiles =
                        stores.blobs
                            .waiting(
                                scope,
                                collection,
                                BlobTransferState.LOCAL,
                                Long.MAX_VALUE,
                                COUNT_LIMIT,
                            ).size,
                    incomingFiles =
                        stores.blobs
                            .waiting(
                                scope,
                                collection,
                                BlobTransferState.REMOTE,
                                Long.MAX_VALUE,
                                COUNT_LIMIT,
                            ).size,
                )
            }
        metrics.emit(
            SyncMetricEvent.QueueObserved(
                scope = scope,
                collection = collection,
                pendingCount = summary.pending,
                conflictCount = summary.open,
                pendingBlobs = summary.outgoingFiles,
                incomingBlobs = summary.incomingFiles,
            ),
            log,
        )
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
     * Starts the file worker once, as soon as the server's limits are known.
     *
     * A file larger than the server accepts has to be refused before a byte of it moves, which is
     * the whole reason the limit is published — so the worker cannot exist before this client has
     * been told what it is.
     *
     * A server that serves no files at all is a misconfiguration and is reported as one, loudly and
     * once. It does **not** stop the collection: documents have nothing to do with files, and taking
     * synchronisation down entirely because photographs cannot be transferred would turn a partial
     * outage into a total one. What it does mean is that records naming a file are held back, since
     * a server that has never heard of one refuses the document that names it.
     *
     * @param limits Limits the server published.
     */
    private fun startBlobWorker(limits: SyncLimits) {
        if (blobWorker != null || blobsUnsupported) {
            return
        }
        val store = blobStore ?: return
        val transport = blobTransport ?: return
        val blobLimits = limits.blobs
        if (blobLimits == null) {
            blobsUnsupported = true
            log.error(SyncLogEvent.BLOB_UNAVAILABLE) {
                "this application synchronises files and the server it is talking to serves none; " +
                    "documents continue to synchronise and records naming a file will not be sent"
            }
            return
        }
        blobWorker =
            BlobWorker(
                scope = scope,
                collection = collection,
                stores = stores,
                transactions = transactions,
                transport = transport,
                blobStore = store,
                limits = blobLimits,
                metrics = metrics,
                log = log,
                coroutineScope = coroutineScope,
                clock = clock,
                // A group held back by a file — waiting for it to be registered, or for its bytes —
                // has no other way of learning that it may now go.
                onProgressed = ::requestSync,
            ).also { it.start() }
    }

    /** Asks the file worker to move what it can, if this collection synchronises files at all. */
    fun requestTransfers() {
        blobWorker?.requestTransfers()
    }

    /**
     * Records that the application wants a file's bytes here, and wakes the worker.
     *
     * Applied outside the cycle rather than by requesting one, because there is nothing for a cycle
     * to do about it: no document changed, nothing has to be pushed or pulled, and the only party
     * with work is the file worker. A collection that synchronises no files ignores the request.
     *
     * @param blobId File the application wants.
     */
    suspend fun fetchBlob(blobId: BlobId) {
        val reconciler = reconciler ?: return
        reconciler.request(blobId)
        blobWorker?.requestTransfers()
    }

    /**
     * Gives up a file's bytes on this device, leaving the file on the server.
     *
     * @param blobId File to give up.
     */
    suspend fun evictBlob(blobId: BlobId) {
        reconciler?.evict(blobId)
    }

    /**
     * Puts a file back to work now and wakes the worker for it.
     *
     * @param blobId File to try again.
     */
    suspend fun retryBlob(blobId: BlobId) {
        val reconciler = reconciler ?: return
        if (reconciler.retry(blobId)) {
            blobWorker?.requestTransfers()
        }
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

    /**
     * Decides whether this cycle has to transfer a snapshot before anything else.
     *
     * A collection left in [SyncPhase.BOOTSTRAPPING] is one whose transfer stopped part-way — a
     * refused request, a dropped connection, a restart — and it has no cursor to pull from yet. It
     * used to be treated as live: the next cycle pulled from no position at all, found nothing,
     * and reported the collection as up to date while it was still empty. The transfer is resumed
     * instead, from the page it had reached.
     *
     * @return `true` when the collection has no usable position in the change log.
     */
    private suspend fun needsBootstrap(): Boolean {
        val state = transactions.transaction { stores.collections.ensure(scope, collection) }
        return when (state.phase) {
            SyncPhase.NEW, SyncPhase.BOOTSTRAPPING, SyncPhase.RESYNC_REQUIRED -> true
            SyncPhase.LIVE, SyncPhase.NEEDS_ATTENTION -> false
        }
    }

    /**
     * Transfers a snapshot and reports what the attempt says about the scope's connection.
     *
     * @param coordinator Bootstrap to run.
     * @return `true` when the collection follows the log again and the cycle may carry on.
     */
    private suspend fun bootstrap(coordinator: BootstrapCoordinator): Boolean =
        when (val outcome = coordinator.bootstrap()) {
            BootstrapOutcome.Completed -> {
                recordSuccess()
                lastFailure.value = null
                true
            }

            // Classified like a blocked pull, because it is one: a collection that has never
            // finished its snapshot reaches the server through nothing else, so leaving this out
            // kept a device that lost its connection mid-transfer reporting itself online.
            is BootstrapOutcome.Blocked -> {
                recordFailure(outcome.failure)
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
                    recordSuccess()
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
        // Before the rows go, because afterwards there is nothing left to say which files belonged
        // to this collection. Not eviction but the same act as wiping the rest: what these files
        // belonged to no longer exists.
        reconciler?.erase()
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
     * Throws away this device's unsent work because the application asked.
     *
     * The same discarding [resetToServer] does, without the parts that belong to a collection the
     * server replaced: the incarnation is still the one this device follows, so it is kept, and the
     * files the snapshot still names are kept rather than downloaded again.
     *
     * What can be undone without the server is undone here, in the same transaction: an entity the
     * server has never confirmed is one it does not have, so its row is deleted now rather than left
     * on screen until a snapshot happens to arrive — which, on a device without a connection, could
     * be days. An entity the server does have needs the server's copy, so its row is left to the
     * rebuild, and the phase written beside the discard says so until it is done.
     */
    private suspend fun discard() {
        val outcome =
            transactions.transaction {
                val pending = stores.records.pendingCount(scope, collection)
                val unconfirmed = stores.records.unconfirmed(scope, collection)
                // A creation deleted again before it was sent has no row left to delete.
                val created = unconfirmed.filter { it.intent == MutationIntent.UPSERT }
                if (created.isNotEmpty()) {
                    adapter.applyRemote(created.map { RemoteOp.Delete(it.entityType, it.entityId) })
                }
                unconfirmed.forEach { references?.clear(it.entityType, it.entityId) }
                stores.records.deleteUnconfirmed(scope, collection)
                stores.groups.deleteCollection(scope, collection)
                stores.conflicts.deleteCollection(scope, collection)
                stores.inbox.clear(scope, collection)
                stores.records.abandonLocalChanges(scope, collection)
                // Written in the same transaction: a process that dies after this has a collection
                // that still knows it must be rebuilt, rather than one that has forgotten its
                // changes and kept rows nothing will ever overwrite.
                stores.collections.setPhase(scope, collection, SyncPhase.RESYNC_REQUIRED)
                pending to created.size
            }
        val (abandoned, removed) = outcome
        declined.clear()
        lastFailure.value = null
        log.warn(
            SyncLogEvent.LOCAL_CHANGES_DISCARDED,
            context = { mapOf("abandoned" to abandoned.toString(), "removed" to removed.toString()) },
        ) {
            "the application asked for $abandoned unsent change(s) to be discarded; $removed entity(ies) " +
                "the server never had were deleted at once"
        }
        log.info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "discarded") }) {
            "the collection will be rebuilt from a snapshot because its local changes were discarded"
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

    /**
     * Returns the server's limits, asking for them only until the first answer.
     *
     * @return The limits, or `null` when they could not be read and the cycle has to end here.
     */
    private suspend fun limits(): SyncLimits? {
        limits?.let { return it }
        return try {
            transport.limits().also {
                limits = it
                // Server-wide rather than the scope's, so it says the server answered and nothing
                // about whether this user may still read the scope.
                recordSuccess(provesAccess = false)
            }
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
     * Records that the server answered, which is the only proof the scope is reachable again.
     *
     * Without this the scope's state could only return to [ScopeState.Online] through the event
     * channel reconnecting. A request that failed during a blip the socket survived — a handover
     * between networks, a device waking from sleep — or a stale request that failed after the socket
     * had already come back, left the scope reporting an unreachable server for the rest of the
     * process while every later cycle succeeded.
     *
     * Each move is a compare-and-set from one state, so a scope another worker or the channel has
     * moved in the meantime is left where they put it, and [ScopeState.Revoked] is never left at all.
     * A failure another collection records a moment later wins, which is the right way round: the
     * state is about the most recent answer, and a wrongly reported connection corrects itself on
     * the next request either way.
     *
     * @param provesAccess Whether the request was one only an accepted credential for this scope can
     *   get answered: a push, a read of the log or a snapshot. Such an answer also ends
     *   [ScopeState.AuthRequired] — it means the user signed in again and the application's token
     *   provider now hands out a token the server takes, which is exactly the moment the queue kept
     *   for them can leave. The server-wide limits say nothing about that and must not.
     */
    private fun recordSuccess(provesAccess: Boolean = true) {
        scopeState.compareAndSet(ScopeState.Offline, ScopeState.Online)
        scopeState.compareAndSet(ScopeState.ServerUnreachable, ScopeState.Online)
        if (provesAccess) {
            scopeState.compareAndSet(ScopeState.AuthRequired, ScopeState.Online)
        }
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
                    // Which of the two it is, is the application's to answer: the library only saw
                    // a request that did not arrive. Without a monitor it says what it witnessed.
                    val reached =
                        if (network?.hasNetwork() == false) {
                            ScopeState.Offline
                        } else {
                            ScopeState.ServerUnreachable
                        }
                    scopeState.compareAndSet(ScopeState.Online, reached)
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

                // Files fail on their own path and are reported to the application through its own
                // file store, with a reason it can act on. Reaching here at all means a blob request
                // failed outside a transfer, which is a collection-level fault like any other.
                is SyncTransportFailure.BlobGone -> {
                    SyncFailure.Server(NOT_FOUND, failure.describe())
                }

                is SyncTransportFailure.BlobRefused -> {
                    SyncFailure.Server(CONFLICT, failure.describe())
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
     * @property outgoingFiles Files waiting to be sent, counted up to a ceiling: this is a gauge for
     *   a dashboard, and a device with a hundred thousand of them needs a number, not all of them.
     * @property incomingFiles Files waiting to be fetched, counted the same way.
     */
    private data class CycleSummary(
        val pending: Int,
        val open: Int,
        val headState: PushGroupState?,
        val outgoingFiles: Int = 0,
        val incomingFiles: Int = 0,
    )

    /**
     * Marks the coroutine a cycle runs in, and everything the adapter is called from inside it.
     *
     * Read by [discardLocalChanges], which waits for the running cycle to stop: called from inside
     * that cycle it would cancel itself instead, and do nothing anybody could see.
     */
    private class CycleMarker : AbstractCoroutineContextElement(CycleMarker) {
        /** Key the marker is looked up by. */
        companion object Key : CoroutineContext.Key<CycleMarker>
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429

        /** Ceiling on the file gauge: past this the exact number stops telling anybody anything. */
        const val COUNT_LIMIT = 1000

        /** What the server answers for a cursor it no longer keeps history for. */
        const val GONE = 410

        /** What the server answers for a file it does not have. */
        const val NOT_FOUND = 404

        /** Status a host answers when the collection a client refers to has been purged, and also
         * what it answers when it refuses something about a file. The two are told apart by which
         * path they arrived on, not by the number. */
        const val CONFLICT = 409
    }
}
