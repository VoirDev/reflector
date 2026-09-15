package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.ScopeHandle
import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetrics
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
    eventChannel: SyncEventChannel? = null,
    triggerSources: List<SyncTriggerSource> = listOf(PeriodicTriggerSource()),
    conflictThreshold: ConflictThreshold = ConflictThreshold.Default,
    metrics: SyncMetrics = SyncMetrics.None,
    log: SyncLog = SyncLog.None,
    clock: Clock = Clock.System,
    newUuid: () -> Uuid = { Uuid.random() },
): SyncEngine =
    DefaultSyncEngine(
        stores = SyncStores(database),
        transactions = transactions,
        transport = transport,
        adapters = adapters,
        eventChannel = eventChannel,
        triggerSources = triggerSources,
        conflictThreshold = conflictThreshold,
        metrics = metrics,
        log = log,
        coroutineScope = coroutineScope,
        clock = clock,
        newUuid = newUuid,
    )

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
