package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.CollectionSyncState
import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.transport.SyncChannelSignal
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.core.trigger.ManualTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTrigger
import dev.voir.reflector.sync.core.trigger.SyncTriggerSource
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.events.SyncEvent
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.ConflictEntry
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Drives the engine through its public API, with workers on real dispatchers.
 *
 * These tests use [runBlocking] rather than `runTest` on purpose: the work happens on background
 * coroutines and inside Room's own dispatcher, so virtual time would only pretend to wait for it.
 * What the test waits for is exactly what an application would wait for — the published state.
 *
 * The explicit `runBlocking<Unit>` matters: a test whose body ends in an expression would have a
 * non-Unit return type, and JUnit quietly refuses to run such a method instead of failing.
 */
class SyncEngineTest {
    private val scopeId = ScopeId("user-1")
    private val ledger = CollectionId("ledger")
    private val wallet = EntityType("wallet")
    private val walletId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000001"))

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)
    private val transport = FakeTransport()
    private val adapter = FakeAdapter()

    // The engine's workers run on real dispatchers, as they do in an application: the test drives
    // them by waiting for the state they produce, not by advancing virtual time.
    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
    }

    /**
     * Waits for the published state to satisfy [condition], failing with the state it got stuck on.
     *
     * A bare `first { }` would hang forever when the engine never reaches the expected state, and a
     * hanging test says nothing about what went wrong.
     */
    private suspend fun StateFlow<CollectionSyncState>.await(
        description: String,
        condition: (CollectionSyncState) -> Boolean,
    ) {
        val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { first(condition) }
        val stored = stores.collections.find(scopeId, ledger)
        val diagnosis =
            "never reached " + description + "; state=" + value + " stored=" + stored +
                " pushes=" + transport.pushes.size + " snapshots=" + snapshots
        assertNotNull(reached, diagnosis)
    }

    private var snapshots = 0

    private fun emptySnapshot(): SnapshotPage {
        snapshots++
        return SnapshotPage(Cursor("0"), emptyList(), nextPage = null, hasMore = false)
    }

    private fun engine(
        coroutineScope: CoroutineScope,
        adapters: Map<CollectionId, CollectionAdapter> = mapOf(ledger to adapter),
        eventChannel: SyncEventChannel? = null,
        triggerSources: List<SyncTriggerSource> = emptyList(),
        conflictThreshold: ConflictThreshold = ConflictThreshold.Default,
    ): SyncEngine =
        SyncEngine(
            database = database,
            transactions = transactions,
            transport = transport,
            adapters = adapters,
            coroutineScope = coroutineScope,
            eventChannel = eventChannel,
            triggerSources = triggerSources,
            conflictThreshold = conflictThreshold,
        )

    @Test
    fun `a local change reaches the server through the public API`() =
        runBlocking<Unit> {
            // A collection that has never synchronised bootstraps before anything else can happen.
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                val group = request.groups.single()
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Applied(
                                groupId = group.groupId,
                                versions = group.ops.map { AppliedVersion(it.entity, it.id, EntityVersion("42")) },
                            ),
                        ),
                    latestSeq = BatchSeq("42"),
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }

            // The worker runs on its own; waiting for the queue to drain is what the application would do.
            collection.state.await("an empty queue in a live collection") {
                it.pendingCount == 0 && it.phase == SyncPhase.LIVE
            }

            val sent =
                transport.pushes
                    .single()
                    .groups
                    .single()
                    .ops
                    .single()
            assertEquals(walletId, sent.id)
            val record = assertNotNull(stores.records.find(scopeId, ledger, wallet, walletId))
            assertEquals(EntityVersion("42"), record.serverVersion)
        }

    @Test
    fun `a collection nobody answers stops calling itself live`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                val group = request.groups.single()
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Conflict(
                                groupId = group.groupId,
                                conflicts =
                                    group.ops.map {
                                        ConflictEntry(
                                            entity = it.entity,
                                            id = it.id,
                                            serverVersion = EntityVersion("50"),
                                            data = buildJsonObject { put("title", "Savings") },
                                        )
                                    },
                            ),
                        ),
                    latestSeq = BatchSeq("50"),
                )
            }
            // The adapter declines to decide, which is what an application that asks its user does.
            adapter.resolution = null
            val collection =
                engine(workers, conflictThreshold = ConflictThreshold(1)).scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }

            collection.state.await("a collection asking for attention") {
                it.phase == SyncPhase.NEEDS_ATTENTION && it.conflictCount == 1
            }

            // Answering the conflict is the only thing that clears the phase, and it clears it
            // without anything having been written to the collection's row.
            val conflictId = stores.conflicts.openIds(scopeId, ledger).single()
            collection.resolve(conflictId, Resolution.TakeServer)

            collection.state.await("a live collection again") {
                it.phase == SyncPhase.LIVE && it.conflictCount == 0
            }
            assertEquals(SyncPhase.LIVE, assertNotNull(stores.collections.find(scopeId, ledger)).phase)
        }

    @Test
    fun `a changed schema fingerprint sends the collection back to a snapshot`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            adapter.schema = SchemaFingerprint("v1")
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            assertEquals(1, snapshots, "the first run declares a shape, it does not rebuild for it")

            // What a migration looks like from here: the same tables, a different shape, and a
            // cursor that would otherwise keep pointing into rows nobody re-read.
            adapter.schema = SchemaFingerprint("v2")
            collection.requestSync()

            val rebuilt = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { while (snapshots < 2) delay(POLL_MILLIS) }
            assertNotNull(rebuilt, "a declared shape that changed has to force a bootstrap")
            collection.state.await("a live collection again") { it.phase == SyncPhase.LIVE }
        }

    @Test
    fun `opening a collection without an adapter fails loudly`() =
        runBlocking<Unit> {
            val scope = engine(workers, adapters = emptyMap()).scope(scopeId)

            assertFailsWith<IllegalArgumentException> { scope.collection(ledger) }
        }

    @Test
    fun `the same scope is handed out once`() =
        runBlocking<Unit> {
            val engine = engine(workers)

            assertSame(engine.scope(scopeId), engine.scope(scopeId))
            assertFailsWith<IllegalStateException> { engine.scope(ScopeId("user-2")) }
        }

    @Test
    fun `signing out refuses to drop unsent changes silently`() =
        runBlocking<Unit> {
            // A collection that has never synchronised bootstraps before anything else can happen.
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = {
                throw dev.voir.reflector.sync.core.transport.SyncTransportFailure
                    .Unreachable("off")
            }
            val engine = engine(workers)
            val collection = engine.scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            collection.state.await("one pending change") { it.pendingCount == 1 }

            assertFailsWith<IllegalStateException> { engine.signOut(discardPending = false) }

            engine.signOut(discardPending = true)
            assertNull(stores.records.find(scopeId, ledger, wallet, walletId))
            assertNull(stores.collections.find(scopeId, ledger))
            assertTrue(stores.conflicts.openIds(scopeId, ledger).isEmpty())
        }

    @Test
    fun `an invalidate from the channel wakes the collection`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            var pulls = 0
            transport.onChanges = {
                pulls++
                ChangesPage(emptyList(), nextCursor = null, hasMore = false)
            }
            val signals = MutableSharedFlow<SyncChannelSignal>(replay = 1)
            val channel =
                object : SyncEventChannel {
                    override fun signals(scope: ScopeId) = signals
                }
            val collection = engine(workers, eventChannel = channel).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            val before = pulls

            signals.emit(SyncChannelSignal.Received(SyncEvent.Invalidate(ledger, BatchSeq("7"))))

            val woken = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { while (pulls <= before) delay(POLL_MILLIS) }
            assertNotNull(woken, "the notification has to reach the collection's worker")
        }

    @Test
    fun `a trigger from the application wakes the collection`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            var pulls = 0
            transport.onChanges = {
                pulls++
                ChangesPage(emptyList(), nextCursor = null, hasMore = false)
            }
            val triggers = ManualTriggerSource()
            val collection =
                engine(workers, triggerSources = listOf(triggers)).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            val before = pulls

            // This is what an Android lifecycle observer or an iOS foreground notification ends in.
            triggers.fire(SyncTrigger.FOREGROUND)

            val woken = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { while (pulls <= before) delay(POLL_MILLIS) }
            assertNotNull(woken, "a trigger the application produced has to reach the worker")
        }

    private companion object {
        /** Long enough for a local push, short enough that a stuck engine fails instead of hanging. */
        const val AWAIT_TIMEOUT_MILLIS = 10_000L

        /** How often a test looks at a counter it cannot observe as a flow. */
        const val POLL_MILLIS = 20L
    }
}
