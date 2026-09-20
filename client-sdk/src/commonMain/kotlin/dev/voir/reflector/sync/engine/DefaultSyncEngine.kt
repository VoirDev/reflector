package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.ScopeHandle
import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.transport.BlobTransport
import dev.voir.reflector.sync.core.transport.NetworkAvailability
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.trigger.PeriodicTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTriggerSource
import dev.voir.reflector.sync.persistence.SyncDatabase
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Builds a [SyncEngine] over an application's database and its connection to the server.
 *
 * @param database Application's database, carrying the library's tables.
 * @param transactions Transaction boundary of that database.
 * @param transport Connection to the server.
 * @param adapters Bridge to the application's rows, one per synchronised collection. A collection
 *   without an adapter cannot be opened: the library has no other way to read or write its data.
 * @param blobStore Where the application keeps the bytes of the files its documents point at, or
 *   `null` when it synchronises none. Providing one is the whole of opting into files: without it
 *   the adapter is never asked what a document points at, and no blob path in the library is ever
 *   entered.
 * @param blobTransport How files reach the server and the storage its tickets point at. Required
 *   alongside [blobStore]; the bundled [dev.voir.reflector.sync.network.KtorBlobTransport] is what
 *   an application uses unless it has a stack of its own.
 * @param blobFetch What this device does about a file a document names but does not declare a
 *   policy for: [BlobFetch.EAGER] fetches it as soon as the record arrives, [BlobFetch.ON_DEMAND]
 *   waits for [dev.voir.reflector.sync.core.CollectionHandle.fetch]. The default holds every file a
 *   document references, which is the behaviour an application that has not thought about it
 *   expects; an application whose files are large sets it once here and overrides it per reference
 *   through [dev.voir.reflector.sync.core.blob.BlobRef.fetch] where the choice differs. It says
 *   nothing about uploads: a file this device created is always sent.
 * @param network What the application knows about this device's own connection. It changes nothing
 *   about how synchronising behaves; it decides only whether a request that never arrived is
 *   reported as [dev.voir.reflector.sync.core.ScopeState.Offline] or as
 *   [dev.voir.reflector.sync.core.ScopeState.ServerUnreachable]. Without one the library reports
 *   the latter, because that is the part it witnessed.
 * @param eventChannel Optional push channel telling the client when there is something to pull.
 *   Without it everything still works, only later — on the client's own triggers.
 * @param triggerSources Reasons to synchronise supplied by the application. The default is a plain
 *   timer, which is the safety net under everything else: a missed notification, a socket that never
 *   came back, a platform that stopped delivering background events. Foreground and connectivity
 *   belong to the application, which already observes both.
 * @param conflictThreshold Number of open conflicts at which a collection reports
 *   [dev.voir.reflector.sync.core.SyncPhase.NEEDS_ATTENTION] instead of calling itself live. The
 *   default is far above what ordinary use produces, so an application that never looks at it is
 *   not being lied to either.
 * @param metrics Sink for what the library measures about itself — how the queue drains, how far
 *   behind the log it runs, what a bootstrap costs. The default discards everything, because an
 *   application that does not want numbers should not have to produce them.
 * @param log Sink for what the library is doing and why, in order, for one device. It answers the
 *   question [metrics] cannot: not "how is the fleet" but "why did this installation stop". The
 *   default discards everything and is asked nothing, so a release build pays nothing for it; a
 *   development build passes
 *   [dev.voir.reflector.sync.core.log.ConsoleSyncLog] or, on Android,
 *   `AndroidSyncLog`, and sees the engine's decisions.
 * @param coroutineScope Scope the background workers run in. The application owns it, so stopping
 *   synchronisation is a matter of cancelling something it already holds.
 * @param clock Source of local time. It is used for backoff and diagnostics only — order and
 *   versions come from the server, and no decision here depends on the device's clock being right.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 * @return Engine ready to hand out scopes.
 */
public fun SyncEngine(
    database: SyncDatabase,
    transactions: SyncTransactionRunner,
    transport: SyncTransport,
    adapters: Map<CollectionId, CollectionAdapter>,
    coroutineScope: CoroutineScope,
    blobStore: BlobStore? = null,
    blobTransport: BlobTransport? = null,
    blobFetch: BlobFetch = BlobFetch.EAGER,
    network: NetworkAvailability? = null,
    eventChannel: SyncEventChannel? = null,
    triggerSources: List<SyncTriggerSource> = listOf(PeriodicTriggerSource()),
    conflictThreshold: ConflictThreshold = ConflictThreshold.Default,
    metrics: SyncMetrics = SyncMetrics.None,
    log: SyncLog = SyncLog.None,
    clock: Clock = Clock.System,
    newUuid: () -> Uuid = { Uuid.random() },
): SyncEngine {
    // Both or neither. A file store without a transport is the worse of the two halves and is silent
    // about it: every record that names a file would wait for a registration nothing can perform, so
    // a collection would simply stop sending, with nothing conflicted and nothing refused.
    require((blobStore == null) == (blobTransport == null)) {
        if (blobStore == null) {
            "a blob transport was given but no blob store, so nothing would ever be synchronised through it"
        } else {
            "a blob store was given but no blob transport, so no record naming a file could ever be sent"
        }
    }
    return DefaultSyncEngine(
        stores = SyncStores(database),
        transactions = transactions,
        transport = transport,
        adapters = adapters,
        blobStore = blobStore,
        blobTransport = blobTransport,
        blobFetch = blobFetch,
        network = network,
        eventChannel = eventChannel,
        triggerSources = triggerSources,
        conflictThreshold = conflictThreshold,
        metrics = metrics,
        log = log,
        coroutineScope = coroutineScope,
        clock = clock,
        newUuid = newUuid,
    )
}

/**
 * Engine that keeps one scope active at a time.
 *
 * Only one scope may be active because the library does not own the application's rows and cannot
 * tell which of them belong to whom. Signing out therefore wipes what the scope brought in, and
 * signing in as somebody else starts from an empty collection rather than from a mixture.
 */
internal class DefaultSyncEngine(
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
) : SyncEngine {
    private var active: DefaultScopeHandle? = null
    private var activeId: ScopeId? = null

    private val logger = SyncLogger(log)

    init {
        // The first line in any log of this library, and the one that settles the questions a
        // reader would otherwise ask of every line after it: which collections exist, whether a
        // notification channel is installed, and what will wake the workers at all.
        logger.info(
            SyncLogEvent.ENGINE_CREATED,
            context = {
                mapOf(
                    "collections" to adapters.keys.joinToString { it.value },
                    "eventChannel" to (eventChannel != null).toString(),
                    "blobs" to (blobStore != null).toString(),
                    // Reported whether or not files are synchronised at all: "why has this device
                    // downloaded nothing" is a question with two very different answers, and the
                    // configured policy is the one a reader cannot otherwise infer from any line.
                    "blobFetch" to blobFetch.name,
                    "triggerSources" to triggerSources.size.toString(),
                    "conflictThreshold" to conflictThreshold.value.toString(),
                )
            },
        ) { "the synchronisation engine was built" }
    }

    override fun scope(scopeId: ScopeId): ScopeHandle {
        val current = active
        if (current != null && activeId == scopeId) {
            return current
        }
        check(current == null) {
            "scope ${activeId?.value} is still active; sign out before opening ${scopeId.value}"
        }
        return DefaultScopeHandle(
            scopeId = scopeId,
            stores = stores,
            transactions = transactions,
            transport = transport,
            adapters = adapters,
            blobStore = blobStore,
            blobTransport = blobTransport,
            blobFetch = blobFetch,
            network = network,
            eventChannel = eventChannel,
            triggerSources = triggerSources,
            conflictThreshold = conflictThreshold,
            metrics = metrics,
            log = log,
            coroutineScope = coroutineScope,
            clock = clock,
            newUuid = newUuid,
        ).also {
            active = it
            activeId = scopeId
            logger.info(SyncLogEvent.SCOPE_OPENED, context = { mapOf("scope" to scopeId.value) }) {
                "the scope was opened and its workers started"
            }
        }
    }

    override suspend fun signOut(discardPending: Boolean) {
        val handle = active ?: return
        if (!discardPending) {
            val pending = handle.pendingCount()
            check(pending == 0) {
                "$pending local changes have not reached the server; push them or sign out discarding them"
            }
        }
        handle.wipe()
        logger.info(
            SyncLogEvent.SCOPE_WIPED,
            context = { mapOf("scope" to activeId?.value.orEmpty(), "discardPending" to discardPending.toString()) },
        ) { "signed out: the workers are stopped and the scope's local data is gone" }
        active = null
        activeId = null
    }
}
