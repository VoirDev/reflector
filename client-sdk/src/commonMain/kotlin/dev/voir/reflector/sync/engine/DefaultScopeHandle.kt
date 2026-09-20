package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.ScopeHandle
import dev.voir.reflector.sync.core.ScopeState
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.NetworkAvailability
import dev.voir.reflector.sync.core.transport.SyncChannelSignal
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.trigger.SyncTriggerSource
import dev.voir.reflector.sync.engine.conflict.ConflictCoordinator
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.events.SyncEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The application's handle on one scope.
 *
 * Collections inside a scope are independent — own cursor, own queue, own worker — so the handle is
 * little more than a registry of them. What they do share is the connection state and the answer to
 * "may this user still read this scope at all", which is why that lives here.
 *
 * @param scopeId Scope being synchronised.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param transport Connection to the server.
 * @param adapters Application's bridge per collection; a collection without one cannot be opened.
 * @param blobStore Application's own file store, or `null` when it synchronises no files.
 * @param blobTransport Connection to the server's file endpoints, required alongside [blobStore].
 * @param blobFetch Policy for a reference that declares none of its own.
 * @param network What the application knows about this device's own connection, or `null` when it
 *   supplies nothing. It changes nothing about how the scope behaves — only whether an unreachable
 *   server is reported as this device being offline or as the server not answering.
 * @param eventChannel Optional push channel; without it the scope still synchronises, only on its
 *   own triggers rather than on the server's.
 * @param triggerSources Reasons to synchronise that the application supplies — returning to the
 *   foreground, regaining a network, a background task, a timer.
 * @param conflictThreshold Number of open conflicts at which a collection of this scope reports
 *   that it needs the application's attention.
 * @param metrics Sink the workers of this scope report their measurements through.
 * @param log Sink the workers of this scope describe their decisions through.
 * @param coroutineScope Scope the workers run in.
 * @param clock Source of local time, used for backoff and diagnostics only.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 */
internal class DefaultScopeHandle(
    private val scopeId: ScopeId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapters: Map<CollectionId, CollectionAdapter>,
    private val blobStore: BlobStore?,
    private val blobTransport: BlobTransport?,
    private val blobFetch: BlobFetch,
    private val network: NetworkAvailability?,
    private val eventChannel: SyncEventChannel?,
    private val triggerSources: List<SyncTriggerSource>,
    private val conflictThreshold: ConflictThreshold,
    private val metrics: SyncMetrics,
    private val log: SyncLog,
    private val coroutineScope: CoroutineScope,
    private val clock: Clock,
    private val newUuid: () -> Uuid,
) : ScopeHandle {
    private val logger = SyncLogger(log, scopeId)
    private val mutableState = MutableStateFlow<ScopeState>(ScopeState.Online)
    private val handles = mutableMapOf<CollectionId, CollectionHandle>()
    private val workersByCollection = mutableMapOf<CollectionId, CollectionWorker>()

    // The workers live on a job of their own so that the scope can be stopped without touching the
    // application's scope. Wiping while they run would be a race the library always loses: a cycle
    // in flight re-creates the collection row it needs right after it has been deleted.
    private val workers = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val workerScope = CoroutineScope(coroutineScope.coroutineContext + workers)

    override val state: StateFlow<ScopeState> = mutableState.asStateFlow()

    init {
        // Every collection's worker can move this, so it is reported from the one place that sees
        // all of them rather than from each of the several that write it. Scope state is what says
        // whether anything can make progress at all, so a transition is always worth a line.
        workerScope.launch {
            var previous = mutableState.value
            mutableState.collect { current ->
                if (current != previous) {
                    logger.info(
                        SyncLogEvent.SCOPE_STATE_CHANGED,
                        context = { mapOf("from" to previous.describe(), "to" to current.describe()) },
                    ) { current.explain() }
                    previous = current
                }
            }
        }
        eventChannel?.let { channel -> workerScope.launch { listen(channel) } }
        triggerSources.forEach { source ->
            workerScope.launch {
                // Every trigger means the same thing to the engine — look for work. What differs is
                // only who noticed, and that is the application's business.
                source.triggers().collect { workersByCollection.values.forEach { worker -> worker.requestSync() } }
            }
        }
    }

    override fun collection(id: CollectionId): CollectionHandle =
        handles.getOrPut(id) {
            val adapter =
                requireNotNull(adapters[id]) {
                    "collection ${id.value} has no adapter; register one before opening it"
                }
            val worker =
                CollectionWorker(
                    scope = scopeId,
                    collection = id,
                    stores = stores,
                    transactions = transactions,
                    transport = transport,
                    adapter = adapter,
                    network = network,
                    scopeState = mutableState,
                    metrics = metrics,
                    log = logger.forCollection(id),
                    coroutineScope = workerScope,
                    clock = clock,
                    newUuid = newUuid,
                    blobStore = blobStore,
                    blobTransport = blobTransport,
                    blobFetch = blobFetch,
                ).also {
                    it.start()
                    workersByCollection[id] = it
                }
            DefaultCollectionHandle(
                scope = scopeId,
                collection = id,
                stores = stores,
                transactions = transactions,
                mutations =
                    MutationCoordinator(stores, transactions, logger.forCollection(id)) { GroupId(newUuid()) },
                conflictCoordinator =
                    ConflictCoordinator(
                        scopeId,
                        id,
                        stores,
                        transactions,
                        adapter,
                        logger.forCollection(id),
                        newUuid,
                    ),
                worker = worker,
                conflictThreshold = conflictThreshold,
                coroutineScope = coroutineScope,
            ).also { worker.requestSync() }
        }

    /**
     * Reacts to the server's notifications.
     *
     * The channel carries no data, only the fact that there is data, so every signal ends in the
     * same place: waking the worker that owns the collection. A reconnect wakes all of them, because
     * notifications sent while the socket was down are gone and nothing later will mention them.
     */
    private suspend fun listen(channel: SyncEventChannel) {
        channel.signals(scopeId).collect { signal ->
            when (signal) {
                is SyncChannelSignal.Connected -> {
                    // From either way of not having reached the server: the socket being up is
                    // proof that both the device and the server are there.
                    mutableState.compareAndSet(ScopeState.Offline, ScopeState.Online)
                    mutableState.compareAndSet(ScopeState.ServerUnreachable, ScopeState.Online)
                    workersByCollection.values.forEach { it.requestSync() }
                }

                is SyncChannelSignal.Received -> {
                    handle(signal.event)
                }
            }
        }
    }

    private suspend fun handle(event: SyncEvent) {
        logger.debug(
            SyncLogEvent.CHANNEL_EVENT_RECEIVED,
            context = { mapOf("event" to event::class.simpleName.orEmpty()) },
        ) { "the server sent a notification" }
        when (event) {
            is SyncEvent.Invalidate -> {
                workersByCollection[event.collection]?.requestSync()
            }

            is SyncEvent.Resync -> {
                logger
                    .forCollection(event.collection)
                    .info(SyncLogEvent.RESYNC_REQUIRED, context = { mapOf("reason" to "server-asked") }) {
                        "the server says this collection can no longer be followed incrementally"
                    }
                // The server says the collection cannot be followed incrementally any more. The phase
                // is written down rather than acted on here: the worker owns the order of things, and
                // a bootstrap started from under it would race with whatever it is doing.
                transactions.transaction {
                    stores.collections.setPhase(scopeId, event.collection, SyncPhase.RESYNC_REQUIRED)
                }
                workersByCollection[event.collection]?.requestSync()
            }

            is SyncEvent.Revoked -> {
                mutableState.value = ScopeState.Revoked
            }

            // Straight to the file worker rather than to the cycle. The record naming this file
            // arrived long ago and nothing about the log has changed; what has changed is only that
            // the bytes became fetchable, and a pull would not discover that.
            is SyncEvent.BlobReady -> {
                workersByCollection[event.collection]?.requestTransfers()
            }

            // An event this client does not understand may well be a newer way of saying "there is
            // something to pull". An extra pull is harmless; a missed one is not.
            is SyncEvent.Unknown -> {
                workersByCollection.values.forEach { it.requestSync() }
            }
        }
    }

    private fun ScopeState.describe(): String = this::class.simpleName.orEmpty()

    /**
     * Explains what a scope state means for the application, for the line that reports reaching it.
     *
     * The states differ in who has to act, which is the only thing worth saying about them here:
     * nobody, the user, or nobody ever again.
     *
     * @return One sentence describing the consequence of being in this state.
     */
    private fun ScopeState.explain(): String =
        when (this) {
            ScopeState.Online -> {
                "the scope is connected and its workers may push and pull"
            }

            ScopeState.Offline -> {
                "the device has no network; local changes keep queueing, which is normal"
            }

            ScopeState.ServerUnreachable -> {
                "the device has a network but the server did not answer; local changes keep queueing"
            }

            ScopeState.AuthRequired -> {
                "the credentials were refused and could not be renewed; the workers stop and the queue is kept " +
                    "until the user signs in again"
            }

            ScopeState.Revoked -> {
                "access to the scope has been revoked; its local data is wiped"
            }
        }

    /** Number of local changes across every opened collection of the scope. */
    suspend fun pendingCount(): Int =
        transactions.transaction {
            adapters.keys.sumOf { collection -> stores.records.pendingCount(scopeId, collection) }
        }

    /**
     * Stops the workers and removes every trace of the scope from the local database.
     *
     * Stopping comes first and is awaited: a cycle that is still running would write back rows the
     * wipe has just deleted, and the leftovers would look like a half-synchronised collection.
     */
    suspend fun wipe() {
        workers.cancelAndJoin()
        logger.debug(SyncLogEvent.SCOPE_WIPED) { "the workers have stopped; removing the scope's local data" }
        removeFiles()
        transactions.transaction {
            adapters.keys.forEach { collection -> stores.inbox.clear(scopeId, collection) }
            stores.records.deleteScope(scopeId)
            stores.groups.deleteScope(scopeId)
            stores.conflicts.deleteScope(scopeId)
            stores.collections.deleteScope(scopeId)
            stores.meta.deleteScope(scopeId)
            stores.blobs.deleteScope(scopeId)
            stores.blobRefs.deleteScope(scopeId)
        }
    }

    /**
     * Tells the application to remove every file this scope brought in.
     *
     * Not the offer that reconciliation makes when a document stops pointing at something — that one
     * an application is free to decline, and keeping a detached photograph for an undo stack is a
     * correct answer to it. This is the same act as wiping the rows: the scope is going, and leaving
     * a signed-out user's photographs in the application's store on a shared device is not a policy
     * question. It is also what a revoked scope reaches, because an application answers that by
     * signing out.
     *
     * Before the rows and after the workers have stopped: afterwards there is nothing left to say
     * which files belonged to this scope, and a worker still running would be writing rows into what
     * is being erased.
     *
     * A store that throws is logged and passed over. The rows go regardless — a scope that could not
     * be wiped because one file refused to be deleted would leave the user signed in.
     */
    private suspend fun removeFiles() {
        val store = blobStore ?: return
        val files = transactions.transaction { stores.blobs.allOfScope(scopeId) }
        for (file in files) {
            runCatching { store.remove(file.blobId) }
                .onFailure { failure ->
                    logger.warn(
                        SyncLogEvent.BLOB_RELEASE_FAILED,
                        failure,
                        context = { mapOf("blob" to file.blobId.value.toString()) },
                    ) { "the application could not remove a file of a scope that is being wiped" }
                }
        }
        if (files.isNotEmpty()) {
            logger.info(SyncLogEvent.BLOB_RELEASED, context = { mapOf("files" to files.size.toString()) }) {
                "the scope's files were handed back to the application before its data was removed"
            }
        }
    }
}
