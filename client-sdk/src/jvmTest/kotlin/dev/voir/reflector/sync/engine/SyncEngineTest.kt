package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.CollectionSyncState
import dev.voir.reflector.sync.core.ConflictThreshold
import dev.voir.reflector.sync.core.RefusedGroup
import dev.voir.reflector.sync.core.ScopeState
import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogLevel
import dev.voir.reflector.sync.core.log.SyncLogRecord
import dev.voir.reflector.sync.core.transport.NetworkAvailability
import dev.voir.reflector.sync.core.transport.SyncChannelSignal
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.core.trigger.ManualTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTrigger
import dev.voir.reflector.sync.core.trigger.SyncTriggerSource
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.events.SyncEvent
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.ConflictEntry
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.protocol.push.RejectError
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    /**
     * Waits until the scope reports a connection state, so a test never reads one before the worker
     * has classified the failure that produces it.
     *
     * @param description What was being waited for, for the failure message.
     * @param expected State the scope has to reach.
     */
    private suspend fun StateFlow<ScopeState>.awaitState(
        description: String,
        expected: ScopeState,
    ) {
        val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { first { it == expected } }
        assertNotNull(reached, "never reached $description; state=$value pushes=${transport.pushes.size}")
    }

    /** Cycles the engine has reported finishing, which is how a test waits for one to happen. */
    private fun cyclesFinished(): Int = logs.records.value.count { it.event == SyncLogEvent.CYCLE_FINISHED }

    /**
     * Waits until the engine has reported a record matching [predicate].
     *
     * @param description What was being waited for, for the failure message.
     * @param predicate Record the test is waiting for.
     */
    private suspend fun awaitRecord(
        description: String,
        predicate: (SyncLogRecord) -> Boolean,
    ): Unit = awaitRecords(description) { logs.records.value.any(predicate) }

    /**
     * Waits until [condition] holds of everything reported so far.
     *
     * @param description What was being waited for, for the failure message.
     * @param condition Condition over the records collected up to now.
     */
    private suspend fun awaitRecords(
        description: String,
        condition: () -> Boolean,
    ) {
        val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) { logs.records.first { condition() } }
        assertNotNull(reached, "never reached $description; reported=" + logs.records.value.map { it.event })
    }

    private var snapshots = 0

    private fun emptySnapshot(): SnapshotPage {
        snapshots++
        return SnapshotPage(testCursor("0"), emptyList(), nextPage = null, hasMore = false, epoch = TEST_EPOCH)
    }

    /**
     * A snapshot holding the one wallet the server has, as it has it.
     *
     * @param title Title the server holds for the wallet.
     */
    private fun walletSnapshot(title: String): SnapshotPage {
        snapshots++
        val item = SnapshotItem(wallet, walletId, EntityVersion("1"), buildJsonObject { put("title", title) })
        return SnapshotPage(testCursor("1"), listOf(item), nextPage = null, hasMore = false, epoch = TEST_EPOCH)
    }

    /** A server that applies every group it is sent. */
    private fun applyingEverything(request: PushRequest): PushResponse {
        val group = request.groups.single()
        return PushResponse(
            results =
                listOf(
                    PushGroupResult.Applied(
                        groupId = group.groupId,
                        versions = group.ops.map { AppliedVersion(it.entity, it.id, EntityVersion("42")) },
                    ),
                ),
            latestSeq = BatchSeq("42"),
            epoch = TEST_EPOCH,
        )
    }

    /** Makes every request to the server fail as though it could not be reached. */
    private fun goOffline() {
        transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
        transport.onChanges = { throw SyncTransportFailure.Unreachable("offline") }
        transport.onSnapshot = { throw SyncTransportFailure.Unreachable("offline") }
    }

    private fun engine(
        coroutineScope: CoroutineScope,
        adapters: Map<CollectionId, CollectionAdapter> = mapOf(ledger to adapter),
        eventChannel: SyncEventChannel? = null,
        triggerSources: List<SyncTriggerSource> = emptyList(),
        conflictThreshold: ConflictThreshold = ConflictThreshold.Default,
        network: NetworkAvailability? = null,
    ): SyncEngine =
        SyncEngine(
            database = database,
            transactions = transactions,
            transport = transport,
            adapters = adapters,
            coroutineScope = coroutineScope,
            network = network,
            eventChannel = eventChannel,
            triggerSources = triggerSources,
            conflictThreshold = conflictThreshold,
            log = logs,
        )

    /** Everything the engine said while a test ran, so that a test can assert on what it reported. */
    private val logs = RecordingSyncLog()

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
                    epoch = TEST_EPOCH,
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
    fun `a collection the server purged is discarded locally, unsent changes included`() =
        runBlocking<Unit> {
            // First the collection is ordinary: it bootstraps, and a local edit reaches the server.
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                val group = request.groups.single()
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Applied(
                                groupId = group.groupId,
                                versions = group.ops.map { AppliedVersion(it.entity, it.id, EntityVersion("1")) },
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                    epoch = TEST_EPOCH,
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            collection.state.await("a live collection with nothing queued") {
                it.pendingCount == 0 && it.phase == SyncPhase.LIVE
            }
            assertEquals(TEST_EPOCH, assertNotNull(stores.collections.find(scopeId, ledger)).epoch)

            // Then the scope is purged on the server, and this device edits again while it is away.
            // The queue it builds is what a client would otherwise push back into the empty
            // collection — one entity at a time, undoing the erasure.
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Edited offline") }
                markUpserted(wallet, walletId)
            }
            transport.onPush = { throw SyncTransportFailure.CollectionReset("purged") }
            transport.onChanges = { throw SyncTransportFailure.CollectionReset("purged") }
            transport.onSnapshot = {
                snapshots++
                SnapshotPage(
                    Cursor("$NEW_EPOCH_VALUE.0"),
                    emptyList(),
                    nextPage = null,
                    hasMore = false,
                    epoch = CollectionEpoch(NEW_EPOCH_VALUE),
                )
            }
            collection.requestSync()

            awaitRecord("the collection being discarded") { it.event == SyncLogEvent.COLLECTION_RESET }
            collection.state.await("a live collection rebuilt from the new incarnation") {
                it.phase == SyncPhase.LIVE && it.pendingCount == 0
            }

            val stored = assertNotNull(stores.collections.find(scopeId, ledger))
            assertEquals(CollectionEpoch(NEW_EPOCH_VALUE), stored.epoch, "the device follows the new collection")
            assertNull(
                stores.records.find(scopeId, ledger, wallet, walletId),
                "a record the new snapshot does not mention is swept with the collection it belonged to",
            )
            assertNull(
                adapter.bodies[wallet to walletId],
                "the application's own row goes with it: the server is the source of truth, and it has none",
            )
            assertTrue(
                transport.pushes.none { it.epoch == CollectionEpoch(NEW_EPOCH_VALUE) },
                "the abandoned edit must not be sent into the collection that replaced the purged one",
            )
        }

    @Test
    fun `a refused group is explained by diagnostics, and the explanation survives a restart`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error =
                                    RejectError(
                                        code = RejectCode.VALIDATION,
                                        entity = wallet,
                                        id = walletId,
                                        message = "currency is required",
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                    epoch = TEST_EPOCH,
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the refusal") { it.event == SyncLogEvent.PUSH_REJECTED }

            val diagnostics = collection.diagnostics()
            assertTrue(diagnostics.isQueueBlocked, "a refused head stops everything behind it")
            val head = diagnostics.queue.first()
            assertEquals(PushGroupState.FAILED, head.state)
            assertEquals(1, head.operations)
            assertEquals("currency is required", head.lastError, "the sentence that says what has to change")
            assertEquals(SyncPhase.LIVE, diagnostics.phase, "a blocked queue does not change the phase")

            // The part that a published `lastFailure` cannot do: the queue has been stuck since
            // before this process started, which is the case where nobody was watching when it
            // happened. A second engine over the same database is that restart.
            val afterRestart = engine(workers).scope(scopeId).collection(ledger)
            assertNull(afterRestart.state.value.lastFailure, "an in-memory failure does not survive a restart")
            val restored = afterRestart.diagnostics()
            assertEquals("currency is required", restored.queue.first().lastError)
            assertEquals(head.groupId, restored.queue.first().groupId)

            // And what the adapter was told once is still readable: which entity, and why.
            assertEquals(
                listOf(
                    RefusedGroup(
                        groupId = head.groupId,
                        entityType = wallet,
                        entityId = walletId,
                        rejection = SyncRejection.Validation("currency is required"),
                    ),
                ),
                afterRestart.refusals.first(),
                "a refusal outlives the process that was told about it",
            )
        }

    @Test
    fun `discarding local changes clears a refused queue and rebuilds from the server`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error =
                                    RejectError(
                                        code = RejectCode.VALIDATION,
                                        entity = wallet,
                                        id = walletId,
                                        message = "currency is required",
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                    epoch = TEST_EPOCH,
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the refusal") { it.event == SyncLogEvent.PUSH_REJECTED }
            assertTrue(collection.diagnostics().isQueueBlocked, "the refused head blocks the queue")
            assertEquals(1, collection.refusals.first().size, "the refusal is published")
            val pushesBefore = transport.pushes.size
            val snapshotsBefore = snapshots

            collection.discardLocalChanges()

            awaitRecord("the changes being discarded") { record ->
                record.event == SyncLogEvent.LOCAL_CHANGES_DISCARDED && record.level == SyncLogLevel.WARN
            }
            collection.state.await("a live collection with nothing waiting") {
                it.phase == SyncPhase.LIVE && it.pendingCount == 0
            }

            val diagnostics = collection.diagnostics()
            assertTrue(diagnostics.queue.isEmpty(), "the refused group is gone, not retried")
            assertTrue(collection.refusals.first().isEmpty(), "nothing is reported refused any more")
            assertTrue(snapshots > snapshotsBefore, "the collection was rebuilt from a snapshot")
            assertEquals(pushesBefore, transport.pushes.size, "the refused change is not sent again")
            assertNull(
                adapter.bodies[wallet to walletId],
                "a row the server never accepted goes with the change that created it",
            )
        }

    @Test
    fun `discarding without a connection removes what the server never had at once`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            goOffline()
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the push that could not leave") { it.event == SyncLogEvent.RETRY_SCHEDULED }

            collection.discardLocalChanges()

            // All of it before the call returned, and none of it needing the server.
            assertNull(adapter.bodies[wallet to walletId], "a row the server never had is deleted at once")
            val diagnostics = collection.diagnostics()
            assertTrue(diagnostics.queue.isEmpty(), "the queue is empty")
            assertEquals(0, diagnostics.pendingCount, "nothing is waiting to be sent")
            // Either: the cycle the discard asks for may already have begun the snapshot it cannot fetch.
            assertTrue(
                diagnostics.phase == SyncPhase.RESYNC_REQUIRED || diagnostics.phase == SyncPhase.BOOTSTRAPPING,
                "the rebuild is still owed, but the phase was ${diagnostics.phase}",
            )
            assertNull(stores.records.find(scopeId, ledger, wallet, walletId), "the record went with the row")

            val pushesBefore = transport.pushes.size
            transport.onChanges = { ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH) }
            transport.onSnapshot = { emptySnapshot() }
            collection.requestSync()
            collection.state.await("the rebuilt collection") { it.phase == SyncPhase.LIVE }
            assertEquals(pushesBefore, transport.pushes.size, "the discarded change is never sent")
        }

    @Test
    fun `an edit the server has is undone by the rebuild and a later change is kept`() =
        runBlocking<Unit> {
            val otherId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000002"))
            transport.onSnapshot = { walletSnapshot("Cash") }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            goOffline()
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Discarded") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the push that could not leave") { it.event == SyncLogEvent.RETRY_SCHEDULED }

            collection.discardLocalChanges()
            // The attempts that could not leave are recorded too; only what follows the discard counts.
            val attempted = transport.pushes.size
            // Made after the discard returned, so it is a change of its own and not part of it.
            collection.mutate {
                adapter.bodies[wallet to otherId] = buildJsonObject { put("title", "Savings") }
                markUpserted(wallet, otherId)
            }
            assertEquals(
                buildJsonObject { put("title", "Discarded") },
                adapter.bodies[wallet to walletId],
                "the server's copy is needed to undo an edit, so the row waits for the rebuild",
            )

            transport.onChanges = { ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH) }
            transport.onSnapshot = { walletSnapshot("Cash") }
            transport.onPush = { request -> applyingEverything(request) }
            collection.requestSync()
            collection.state.await("the rebuilt collection with its queue drained") {
                it.phase == SyncPhase.LIVE && it.pendingCount == 0
            }

            assertEquals(buildJsonObject { put("title", "Cash") }, adapter.bodies[wallet to walletId])
            val sent = transport.pushes.drop(attempted).flatMap { push -> push.groups.flatMap { it.ops } }.map { it.id }
            assertEquals(listOf(otherId), sent, "only the change made after the discard is sent")
        }

    @Test
    fun `an edit made on top of a discarded change before the rebuild is a conflict`() =
        runBlocking<Unit> {
            transport.onSnapshot = { walletSnapshot("Cash") }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            goOffline()
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Discarded") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the push that could not leave") { it.event == SyncLogEvent.RETRY_SCHEDULED }

            collection.discardLocalChanges()
            val attempted = transport.pushes.size
            // The row still shows the discarded title, and this edit is made on top of it.
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Discarded, then edited") }
                markUpserted(wallet, walletId)
            }

            transport.onChanges = { ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH) }
            transport.onSnapshot = { walletSnapshot("Cash") }
            collection.requestSync()
            collection.state.await("a conflict about the edit") { it.conflictCount == 1 }

            val conflict = collection.conflicts.first().single()
            assertEquals(walletId, conflict.entityId)
            // It may still be sent — a snapshot's conflict does not hold the queue — but never as an
            // update of the server's copy, which the server would apply over the user's back.
            val sent =
                transport.pushes
                    .drop(attempted)
                    .flatMap { push -> push.groups.flatMap { it.ops } }
                    .filter { it.id == walletId }
            assertTrue(sent.all { it.baseVersion == null }, "the edit claims no server state as its base")
        }

    @Test
    fun `a queue serving a backoff is not reported as blocked`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the scheduled retry") { it.event == SyncLogEvent.RETRY_SCHEDULED }

            // The distinction the property exists for: this queue is waiting, not stopped. Nobody
            // has to do anything about it, and reporting it as stuck would make the signal useless
            // for the case that does need somebody.
            val diagnostics = collection.diagnostics()
            assertFalse(diagnostics.isQueueBlocked, "a backoff is a wait, not a stall")
            val head = diagnostics.queue.first()
            assertEquals(PushGroupState.PENDING, head.state)
            assertEquals(1, head.attempts)
            assertNotNull(head.nextRetryAt)
        }

    @Test
    fun `a server that does not answer is not reported as the device being offline`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val scope = engine(workers).scope(scopeId)
            val collection = scope.collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }

            transport.onChanges = { throw SyncTransportFailure.Unreachable("no answer") }
            collection.requestSync()
            // Nothing was supplied that could say whether this device has a network, so the only
            // honest report is the part the library witnessed: the request did not arrive.
            // Claiming the device is offline without having looked would be a guess.
            scope.state.awaitState("a server that did not answer", ScopeState.ServerUnreachable)
        }

    @Test
    fun `a device the application says has no network is reported as offline`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val scope = engine(workers, network = { false }).scope(scopeId)
            val collection = scope.collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }

            transport.onChanges = { throw SyncTransportFailure.Unreachable("no route") }
            collection.requestSync()
            // The same transport failure as the case above. What separates them is the only thing
            // that can: the platform answering for the device itself.
            scope.state.awaitState("a device with no network", ScopeState.Offline)
        }

    @Test
    fun `a server that answers again is reported online while the socket stays up`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val signals = MutableSharedFlow<SyncChannelSignal>(replay = 1)
            val channel =
                object : SyncEventChannel {
                    override fun signals(scope: ScopeId) = signals
                }
            signals.emit(SyncChannelSignal.Connected)
            val scope = engine(workers, eventChannel = channel).scope(scopeId)
            val collection = scope.collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }

            // A request lost to a blip the socket survived: a handover, a device waking from sleep.
            transport.onChanges = { throw SyncTransportFailure.Unreachable("no answer") }
            collection.requestSync()
            scope.state.awaitState("a server that did not answer", ScopeState.ServerUnreachable)

            // The socket never dropped, so it never announces a reconnect. Only the server
            // answering can end the state, and it used to stay put until the process restarted.
            transport.onChanges = { ChangesPage(emptyList(), nextCursor = null, hasMore = false, epoch = TEST_EPOCH) }
            collection.requestSync()
            scope.state.awaitState("a scope the server answers again", ScopeState.Online)
        }

    @Test
    fun `a failure after the socket reconnected is cleared by the next successful cycle`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val signals = MutableSharedFlow<SyncChannelSignal>(replay = 1)
            val channel =
                object : SyncEventChannel {
                    override fun signals(scope: ScopeId) = signals
                }
            val scope = engine(workers, eventChannel = channel).scope(scopeId)
            val collection = scope.collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }

            // The reconnect is processed first and the pull it wakes fails afterwards — a request
            // still riding the old route. The failure is the last word, so the scope reports it.
            transport.onChanges = { throw SyncTransportFailure.Unreachable("stale route") }
            signals.emit(SyncChannelSignal.Connected)
            scope.state.awaitState("the failure that followed the reconnect", ScopeState.ServerUnreachable)

            transport.onChanges = { ChangesPage(emptyList(), nextCursor = null, hasMore = false, epoch = TEST_EPOCH) }
            collection.requestSync()
            scope.state.awaitState("a scope whose next cycle succeeded", ScopeState.Online)
        }

    @Test
    fun `a snapshot that cannot reach the server is reported, and its completion ends the report`() =
        runBlocking<Unit> {
            var reachable = false
            transport.onSnapshot = {
                if (!reachable) throw SyncTransportFailure.Unreachable("the connection dropped")
                emptySnapshot()
            }
            val scope = engine(workers).scope(scopeId)
            val collection = scope.collection(ledger)

            // A collection that has never finished its snapshot reaches the server through nothing
            // else, so a blocked transfer has to say so just as a blocked pull does.
            scope.state.awaitState("a transfer that did not arrive", ScopeState.ServerUnreachable)
            collection.state.await("the failure reported to the collection") { it.lastFailure is SyncFailure.Network }

            reachable = true
            collection.requestSync()
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            scope.state.awaitState("a scope whose snapshot arrived", ScopeState.Online)
        }

    @Test
    fun `a scope that needed the user is online again once the server accepts its credentials`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            val scope = engine(workers).scope(scopeId)
            val collection = scope.collection(ledger)
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }

            transport.onChanges = { throw SyncTransportFailure.Unauthorized("the token has expired") }
            collection.requestSync()
            scope.state.awaitState("a scope that needs the user", ScopeState.AuthRequired)

            // The user signed in again and the token provider now hands out a token the server
            // takes. The queue was kept for exactly this moment; the state has to follow it.
            transport.onChanges = { ChangesPage(emptyList(), nextCursor = null, hasMore = false, epoch = TEST_EPOCH) }
            collection.requestSync()
            scope.state.awaitState("a scope whose credentials were accepted", ScopeState.Online)
        }

    @Test
    fun `refused credentials are reported as needing the user, not as a server error`() =
        runBlocking<Unit> {
            transport.onSnapshot = { throw SyncTransportFailure.Unauthorized("the token has expired") }
            val collection = engine(workers).scope(scopeId).collection(ledger)

            collection.requestSync()

            // Previously this arrived as `Server(statusCode = 0)` — a status no server answers with,
            // for the one failure the application is expected to act on.
            collection.state.await("a failure the application can act on") { it.lastFailure != null }
            val failure = assertIs<SyncFailure.AuthRequired>(collection.state.value.lastFailure)
            assertEquals("the token has expired", failure.message)
        }

    @Test
    fun `a snapshot that stopped part-way is resumed rather than taken for a live collection`() =
        runBlocking<Unit> {
            var attempts = 0
            transport.onSnapshot = {
                if (attempts++ == 0) throw SyncTransportFailure.Unreachable("the connection dropped")
                emptySnapshot()
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)
            awaitRecord("the dropped transfer") { it.event == SyncLogEvent.REQUEST_FAILED }

            // The collection is left mid-bootstrap, with no cursor. The next cycle used to pull from
            // that non-position, hear that there was nothing new, and stay in BOOTSTRAPPING for good
            // — clearing whatever failure the interrupted transfer had reported on the way.
            collection.requestSync()
            collection.state.await("a live collection") { it.phase == SyncPhase.LIVE }
            assertEquals(2, attempts, "the transfer was attempted again")
        }

    @Test
    fun `a fault in the application's own code arrives with the throwable that caused it`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            adapter.beforeApply = { throw IllegalStateException("the ledger dao is not open") }
            transport.onChanges = {
                ChangesPage(
                    batches =
                        listOf(
                            ChangeBatch(
                                seq = BatchSeq("1"),
                                cursor = testCursor("1"),
                                originClientId = null,
                                ops =
                                    listOf(
                                        RemoteOperation.Upsert(
                                            entity = wallet,
                                            id = walletId,
                                            version = EntityVersion("1"),
                                            data = buildJsonObject { put("title", "Cash") },
                                        ),
                                    ),
                            ),
                        ),
                    nextCursor = testCursor("1"),
                    hasMore = false,
                    epoch = TEST_EPOCH,
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)

            collection.requestSync()

            // A description of a fault in code this library does not own is worth very little: the
            // stack is the whole of what makes it actionable, and it used to be discarded. The
            // instance is not asserted on, because coroutine stack-trace recovery hands on a copy
            // carrying the original as its own cause; what matters is that a throwable arrives.
            collection.state.await("the local failure") { it.lastFailure is SyncFailure.Local }
            val failure = assertIs<SyncFailure.Local>(collection.state.value.lastFailure)
            val cause = assertIs<IllegalStateException>(failure.cause)
            assertEquals("the ledger dao is not open", cause.message)
            assertTrue(cause.stackTrace.isNotEmpty(), "the stack is the whole point of carrying it")
        }

    @Test
    fun `a queue blocked on a refusal is reported when it becomes stuck and not on every cycle`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(code = RejectCode.VALIDATION, message = "currency is required"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                    epoch = TEST_EPOCH,
                )
            }
            val collection = engine(workers).scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                markUpserted(wallet, walletId)
            }
            awaitRecord("the queue reported as blocked") { it.event == SyncLogEvent.QUEUE_BLOCKED }

            // Every later cycle finds the same refused group at the head and takes the same decision.
            // Saying so each time would bury the transition under a line per timer tick, for as long
            // as the application leaves the data unfixed — which can be weeks. Each cycle is waited
            // for rather than merely requested: the request channel is conflated, so firing three in
            // a row could collapse into one and prove nothing.
            repeat(TRAILING_CYCLES) {
                val before = cyclesFinished()
                collection.requestSync()
                awaitRecords("a further cycle to finish") { cyclesFinished() > before }
            }

            val blocked = logs.records.value.filter { it.event == SyncLogEvent.QUEUE_BLOCKED }
            assertEquals(1, blocked.size, "the transition is the event, not the state")
            assertEquals(SyncLogLevel.WARN, blocked.single().level)
            assertEquals(PushGroupState.FAILED.name, blocked.single().context["head"])
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
                    epoch = TEST_EPOCH,
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
                ChangesPage(emptyList(), nextCursor = null, hasMore = false, epoch = TEST_EPOCH)
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
                ChangesPage(emptyList(), nextCursor = null, hasMore = false, epoch = TEST_EPOCH)
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
        /** Incarnation the fake server answers from once the collection has been purged and re-created. */
        const val NEW_EPOCH_VALUE = "0199fd1a-0000-7000-8000-00000000000f"

        /** Long enough for a local push, short enough that a stuck engine fails instead of hanging. */
        const val AWAIT_TIMEOUT_MILLIS = 10_000L

        /** Extra cycles run over an already-stuck queue, to prove the report does not repeat. */
        const val TRAILING_CYCLES = 3

        /** How often a test looks at a counter it cannot observe as a flow. */
        const val POLL_MILLIS = 20L
    }
}
