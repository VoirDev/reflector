package dev.voir.reflector.sync.engine.bootstrap

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.FakeAdapter
import dev.voir.reflector.sync.engine.FakeTransport
import dev.voir.reflector.sync.engine.RecordingMetrics
import dev.voir.reflector.sync.engine.TestClock
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BootstrapCoordinatorTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    /** Sink the coordinator's own account of what it did goes to, so a test can read it back. */
    private val logs = RecordingSyncLog()
    private val log = SyncLogger(logs, scope, collection)
    private val wallet = EntityType("wallet")

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

    private val coordinator =
        BootstrapCoordinator(
            scope = scope,
            collection = collection,
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

    private fun entity(index: Int): EntityId = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun body(title: String): JsonObject = buildJsonObject { put("title", title) }

    private fun item(
        index: Int,
        version: String,
        title: String,
    ) = SnapshotItem(wallet, entity(index), EntityVersion(version), body(title))

    private fun snapshot(
        vararg items: SnapshotItem,
        cursor: String = "1100",
    ) = SnapshotPage(Cursor(cursor), items.toList(), nextPage = null, hasMore = false)

    @Test
    fun `a snapshot is applied and the collection goes live at the fixed cursor`() =
        runTest {
            transport.onSnapshot = { snapshot(item(1, "10", "Cash"), item(2, "11", "Card")) }

            assertEquals(BootstrapOutcome.Completed, coordinator.bootstrap())

            assertEquals(body("Cash"), adapter.bodies[wallet to entity(1)])
            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(SyncPhase.LIVE, state.phase)
            assertEquals(Cursor("1100"), state.cursor)
            assertNull(state.bootstrapPage)
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(2)))
            assertEquals(EntityVersion("11"), record.serverVersion)
        }

    @Test
    fun `what the snapshot no longer mentions is swept`() =
        runTest {
            transport.onSnapshot = { snapshot(item(1, "10", "Cash"), item(2, "11", "Card")) }
            coordinator.bootstrap()
            adapter.applied.clear()
            transport.onSnapshot = { snapshot(item(1, "12", "Cash"), cursor = "1200") }

            coordinator.bootstrap()

            assertNull(adapter.bodies[wallet to entity(2)], "an entity gone from the server has to go locally too")
            assertNull(stores.records.find(scope, collection, wallet, entity(2)))
            assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertTrue(adapter.applied.any { ops -> ops.any { it is RemoteOp.Delete } })
        }

    @Test
    fun `a record with unsent changes survives the sweep`() =
        runTest {
            transport.onSnapshot = { snapshot(item(1, "10", "Cash")) }
            coordinator.bootstrap()
            adapter.bodies[wallet to entity(2)] = body("Made offline")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            transport.onSnapshot = { snapshot(item(1, "12", "Cash"), cursor = "1200") }

            coordinator.bootstrap()

            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(2)))
            assertTrue(record.isDirty, "a change the server has never seen is not a leftover to sweep")
            assertEquals(body("Made offline"), adapter.bodies[wallet to entity(2)])
        }

    @Test
    fun `a local edit on an entity the server has moved becomes a conflict`() =
        runTest {
            transport.onSnapshot = { snapshot(item(1, "10", "Cash")) }
            coordinator.bootstrap()
            adapter.bodies[wallet to entity(1)] = body("Mine")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            adapter.applied.clear()
            transport.onSnapshot = { snapshot(item(1, "20", "Theirs"), cursor = "1200") }

            coordinator.bootstrap()

            assertEquals(body("Mine"), adapter.bodies[wallet to entity(1)], "the unsent edit must not be overwritten")
            val record = assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            val conflict = assertNotNull(stores.conflicts.find(assertNotNull(record.conflictId)))
            assertEquals(body("Theirs"), conflict.server)
            assertEquals(EntityVersion("20"), conflict.serverVersion)
        }

    @Test
    fun `an interrupted bootstrap resumes and keeps the cursor the first page fixed`() =
        runTest {
            transport.onSnapshot = { page ->
                if (page == null) {
                    SnapshotPage(Cursor("1100"), listOf(item(1, "10", "Cash")), PageToken("p2"), hasMore = true)
                } else {
                    throw SyncTransportFailure.Unreachable("offline")
                }
            }
            assertIs<BootstrapOutcome.Blocked>(coordinator.bootstrap())
            val interrupted = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(SyncPhase.BOOTSTRAPPING, interrupted.phase)
            assertEquals(PageToken("p2"), interrupted.bootstrapPage)

            // The server would fix a later position now; the resumed bootstrap must ignore it, because
            // everything committed in between exists only in the log it is about to read.
            transport.onSnapshot = { page ->
                assertEquals(PageToken("p2"), page)
                SnapshotPage(Cursor("9999"), listOf(item(2, "11", "Card")), nextPage = null, hasMore = false)
            }

            assertEquals(BootstrapOutcome.Completed, coordinator.bootstrap())

            val state = assertNotNull(stores.collections.find(scope, collection))
            assertEquals(Cursor("1100"), state.cursor, "resuming must not skip what was committed during the transfer")
            assertEquals(SyncPhase.LIVE, state.phase)
            assertNotNull(stores.records.find(scope, collection, wallet, entity(1)))
            assertNotNull(stores.records.find(scope, collection, wallet, entity(2)))
        }
}
