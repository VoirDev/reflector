package dev.voir.reflector.sync.engine.pull

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.FakeAdapter
import dev.voir.reflector.sync.engine.FakeTransport
import dev.voir.reflector.sync.engine.RecordingMetrics
import dev.voir.reflector.sync.engine.TEST_EPOCH
import dev.voir.reflector.sync.engine.TestClock
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.engine.testCursor
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.changes.RemoteOperationSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PullCoordinatorTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    /** Sink the coordinator's own account of what it did goes to, so a test can read it back. */
    private val logs = RecordingSyncLog()
    private val log = SyncLogger(logs, scope, collection)
    private val wallet = EntityType("wallet")
    private val us = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))
    private val them = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c2"))

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)
    private val transport = FakeTransport()
    private val adapter = FakeAdapter()
    private val clock = TestClock()

    private var generated = 0
    private val uuids = { Uuid.parse("00000000-0000-7000-8000-%012d".format(++generated)) }

    private val metrics = RecordingMetrics()

    private val mutations = MutationCoordinator(stores, transactions, log) { GroupId(uuids()) }

    /**
     * Builds a coordinator over the storage the tests share.
     *
     * A factory rather than a single instance, because a restart after a crash has to rebuild one:
     * calling it again is what stands in for the process coming back, and it only works if the
     * coordinator keeps nothing across the interruption that is not in the database.
     */
    private fun newCoordinator(): PullCoordinator =
        PullCoordinator(
            scope = scope,
            collection = collection,
            clientId = us,
            stores = stores,
            transactions = transactions,
            transport = transport,
            adapter = adapter,
            limits = transport.limits,
            metrics = metrics,
            log = log,
            clock = clock,
            newUuid = uuids,
        )

    private val coordinator = newCoordinator()

    private fun entity(index: Int): EntityId = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun body(title: String): JsonObject = buildJsonObject { put("title", title) }

    private suspend fun goLive() {
        stores.collections.ensure(scope, collection)
        stores.collections.setPhase(scope, collection, SyncPhase.LIVE)
    }

    private fun page(
        batch: ChangeBatch,
        hasMore: Boolean = false,
    ): ChangesPage = ChangesPage(listOf(batch), batch.cursor, hasMore, epoch = TEST_EPOCH)

    private fun upsert(
        index: Int,
        version: String,
        title: String,
    ) = RemoteOperation.Upsert(wallet, entity(index), EntityVersion(version), body(title))

    @Test
    fun `a batch is applied together with its cursor`() =
        runTest {
            goLive()
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Cash")))) }

            assertEquals(PullOutcome.UpToDate, coordinator.pull())

            assertEquals(body("Cash"), adapter.bodies[wallet to entity(1)])
            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(testCursor("10"), state.cursor)
            assertNull(stores.inbox.oldestPending(scope, collection), "an applied batch must leave the inbox")
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertEquals(EntityVersion("10"), record.serverVersion)
        }

    @Test
    fun `an echo of this client's own push moves the version without touching the rows`() =
        runTest {
            goLive()
            adapter.bodies[wallet to entity(1)] = body("Cash renamed")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), us, listOf(upsert(1, "10", "Cash")))) }

            coordinator.pull()

            assertTrue(adapter.applied.isEmpty(), "applying an echo would undo the newer local edit")
            assertEquals(body("Cash renamed"), adapter.bodies[wallet to entity(1)])
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertEquals(EntityVersion("10"), record.serverVersion)
            assertTrue(record.isDirty, "the newer edit still has to be pushed")
        }

    @Test
    fun `somebody else's change over a local edit becomes a conflict`() =
        runTest {
            goLive()
            adapter.bodies[wallet to entity(1)] = body("Mine")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Theirs")))) }

            coordinator.pull()

            assertTrue(adapter.applied.isEmpty(), "the incoming state must not overwrite an unsent edit")
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            val conflict = assertNotNull(stores.conflicts.find(assertNotNull(record.conflictId)))
            assertEquals(body("Mine"), conflict.local)
            assertEquals(body("Theirs"), conflict.server)
            assertEquals(EntityVersion("10"), conflict.serverVersion)
            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(
                testCursor("10"),
                state.cursor,
                "the cursor moves on, which is why the conflict must be durable",
            )
        }

    @Test
    fun `an operation the client does not understand sends the collection to a bootstrap`() =
        runTest {
            goLive()
            val raw =
                SyncProtocolJson.format.decodeFromString(
                    RemoteOperationSerializer,
                    """{"op":"evict","entity":"wallet","id":"00000000-0000-7000-8000-100000000001","version":"10"}""",
                )
            transport.onChanges = { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(raw))) }

            assertEquals(PullOutcome.BootstrapRequired, coordinator.pull())

            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(SyncPhase.RESYNC_REQUIRED, state.phase)
            assertNull(state.cursor, "the cursor must not pass an operation that was never applied")
            assertNull(stores.inbox.oldestPending(scope, collection))
        }

    @Test
    fun `a change already known by version is not applied again`() =
        runTest {
            goLive()
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Cash")))) }
            coordinator.pull()
            adapter.applied.clear()
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("11"), testCursor("11"), them, listOf(upsert(1, "10", "Cash")))) }

            coordinator.pull()

            assertTrue(adapter.applied.isEmpty(), "the same version cannot carry anything new")
            assertEquals(testCursor("11"), assertNotNull(stores.collections.find(scope, collection)).cursor)
        }

    @Test
    fun `a stale cursor sends the collection to a bootstrap`() =
        runTest {
            goLive()
            transport.onChanges = { throw SyncTransportFailure.CursorTooOld("cursor fell out of retention") }

            assertEquals(PullOutcome.BootstrapRequired, coordinator.pull())

            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(SyncPhase.RESYNC_REQUIRED, state.phase)
        }

    @Test
    fun `pages are drained until the server has nothing more`() =
        runTest {
            goLive()
            transport.onChanges = { cursor ->
                when (cursor) {
                    null -> {
                        page(
                            ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Cash"))),
                            hasMore = true,
                        )
                    }

                    else -> {
                        page(ChangeBatch(BatchSeq("11"), testCursor("11"), them, listOf(upsert(2, "11", "Card"))))
                    }
                }
            }

            assertEquals(PullOutcome.UpToDate, coordinator.pull())

            assertEquals(body("Cash"), adapter.bodies[wallet to entity(1)])
            assertEquals(body("Card"), adapter.bodies[wallet to entity(2)])
            assertEquals(testCursor("11"), assertNotNull(stores.collections.find(scope, collection)).cursor)
        }

    @Test
    fun `a removal is applied and leaves a tombstone`() =
        runTest {
            goLive()
            adapter.bodies[wallet to entity(1)] = body("Cash")
            transport.onChanges = {
                page(
                    ChangeBatch(
                        BatchSeq("12"),
                        testCursor("12"),
                        them,
                        listOf(RemoteOperation.Delete(wallet, entity(1), EntityVersion("12"))),
                    ),
                )
            }

            coordinator.pull()

            assertIs<RemoteOp.Delete>(adapter.applied.single().single())
            assertNull(adapter.bodies[wallet to entity(1)])
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertEquals(EntityVersion("12"), record.serverVersion, "a tombstone keeps the version a re-creation needs")
        }

    @Test
    fun `an interrupted apply is repeated after a restart and the cursor never passes it`() =
        runTest {
            goLive()
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Cash")))) }
            adapter.beforeApply = { error("killed between receiving the batch and applying it") }

            assertFailsWith<IllegalStateException> { coordinator.pull() }

            // What a killed process leaves behind: the page is downloaded and durable, none of it is
            // applied, and the cursor is still behind it. A cursor that had moved here would be the
            // silent failure the inbox exists to prevent — nothing later re-reads what it passed.
            assertTrue(adapter.applied.isEmpty())
            assertNull(assertNotNull(stores.collections.find(scope, collection)).cursor)
            assertNotNull(stores.inbox.oldestPending(scope, collection), "the batch has to outlive the crash")

            // The restart finds the server unreachable, so the batch can only come from the inbox.
            adapter.beforeApply = { }
            transport.onChanges = { throw SyncTransportFailure.Unreachable("offline") }

            assertEquals(PullOutcome.Blocked, newCoordinator().pull())

            assertEquals(body("Cash"), adapter.bodies[wallet to entity(1)])
            assertEquals(testCursor("10"), assertNotNull(stores.collections.find(scope, collection)).cursor)
            assertNull(stores.inbox.oldestPending(scope, collection))
            assertEquals(1, adapter.applied.size, "the batch is applied once, not once per attempt")
        }

    @Test
    fun `a crash between two batches keeps the first and repeats only the second`() =
        runTest {
            goLive()
            transport.onChanges = {
                ChangesPage(
                    listOf(
                        ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Cash"))),
                        ChangeBatch(BatchSeq("11"), testCursor("11"), them, listOf(upsert(2, "11", "Card"))),
                    ),
                    testCursor("11"),
                    hasMore = false,
                    epoch = TEST_EPOCH,
                )
            }
            adapter.beforeApply = { ops ->
                if (ops.any { it is RemoteOp.Upsert && it.id == entity(2) }) error("killed while draining")
            }

            assertFailsWith<IllegalStateException> { coordinator.pull() }

            assertEquals(
                testCursor("10"),
                assertNotNull(stores.collections.find(scope, collection)).cursor,
                "a batch and the cursor it produces commit together, so the first one survives alone",
            )
            assertNull(adapter.bodies[wallet to entity(2)])

            adapter.beforeApply = { }
            transport.onChanges = { ChangesPage(emptyList(), testCursor("11"), hasMore = false, epoch = TEST_EPOCH) }

            assertEquals(PullOutcome.UpToDate, newCoordinator().pull())

            assertEquals(body("Card"), adapter.bodies[wallet to entity(2)])
            assertEquals(testCursor("11"), assertNotNull(stores.collections.find(scope, collection)).cursor)
            assertEquals(
                1,
                adapter.applied.count { ops -> ops.any { it is RemoteOp.Upsert && it.id == entity(1) } },
                "resuming must not apply the batch that was already committed",
            )
        }

    @Test
    fun `a second change over an entity already in conflict updates it instead of asking twice`() =
        runTest {
            goLive()
            adapter.bodies[wallet to entity(1)] = body("Mine")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }

            transport.onChanges =
                { page(ChangeBatch(BatchSeq("10"), testCursor("10"), them, listOf(upsert(1, "10", "Theirs")))) }
            coordinator.pull()
            transport.onChanges =
                { page(ChangeBatch(BatchSeq("11"), testCursor("11"), them, listOf(upsert(1, "11", "Theirs again")))) }
            coordinator.pull()

            // One entity, one disagreement, however many times it arrives. A second row would take
            // the record's only `conflict_id` and leave the first with nothing pointing at it —
            // counted in the collection's state for good, and impossible to answer usefully.
            val open = stores.conflicts.openIds(scope, collection)
            assertEquals(1, open.size)
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertEquals(open.single(), record.conflictId)

            // The version has to move with it: a decision is applied on top of the version stored
            // with the conflict, and a stale one would be refused as a conflict all over again.
            val conflict = assertNotNull(stores.conflicts.find(open.single()))
            assertEquals(body("Theirs again"), conflict.server)
            assertEquals(EntityVersion("11"), conflict.serverVersion)
            assertEquals(body("Mine"), conflict.local, "the local side is still what the user has")
        }
}
