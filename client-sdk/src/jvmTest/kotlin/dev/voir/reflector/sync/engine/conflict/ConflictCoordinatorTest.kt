package dev.voir.reflector.sync.engine.conflict

import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.engine.FakeAdapter
import dev.voir.reflector.sync.engine.FakeTransport
import dev.voir.reflector.sync.engine.TEST_EPOCH
import dev.voir.reflector.sync.engine.TestClock
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.engine.push.PushCoordinator
import dev.voir.reflector.sync.engine.retry.BackoffPolicy
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.persistence.record.RecordState
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.push.ConflictEntry
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ConflictCoordinatorTest {
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

    private val mutations = MutationCoordinator(stores, transactions, log) { GroupId(uuids()) }

    private val push =
        PushCoordinator(
            scope = scope,
            collection = collection,
            clientId = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1")),
            stores = stores,
            transactions = transactions,
            transport = transport,
            adapter = adapter,
            limits = transport.limits,
            backoff = BackoffPolicy(random = Random(1)),
            metrics = SyncMetrics.None,
            log = log,
            clock = clock,
            newUuid = uuids,
        )

    private val coordinator = ConflictCoordinator(scope, collection, stores, transactions, adapter, log, uuids)

    private fun entity(index: Int): EntityId = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun body(title: String): JsonObject = buildJsonObject { put("title", title) }

    private suspend fun record(index: Int): RecordState =
        assertNotNull(stores.records.find(scope, collection, wallet, entity(index)))

    /** Pushes the given entities as one group and answers with a conflict on each of them. */
    private suspend fun conflictOn(vararg indexes: Int) {
        for (index in indexes) {
            adapter.bodies[wallet to entity(index)] = body("Mine $index")
        }
        mutations.mutate(scope, collection) { indexes.forEach { markUpserted(wallet, entity(it)) } }
        transport.onPush = { request ->
            PushResponse(
                results =
                    listOf(
                        PushGroupResult.Conflict(
                            groupId = request.groups.single().groupId,
                            conflicts =
                                indexes.map { index ->
                                    ConflictEntry(
                                        entity = wallet,
                                        id = entity(index),
                                        serverVersion = EntityVersion("50"),
                                        data = body("Theirs $index"),
                                    )
                                },
                        ),
                    ),
                latestSeq = BatchSeq("50"),
                epoch = TEST_EPOCH,
            )
        }
        push.pushOnce()
    }

    private suspend fun conflictIdOf(index: Int): ConflictId = assertNotNull(record(index).conflictId)

    @Test
    fun `keeping the local state re-queues it on top of the server's version`() =
        runTest {
            conflictOn(1)
            val conflicted = assertNotNull(record(1).groupId)
            val ord = assertNotNull(stores.groups.find(conflicted)).ord

            coordinator.resolve(conflictIdOf(1), Resolution.KeepLocal)

            val record = record(1)
            assertTrue(record.isDirty, "the kept change still has to reach the server")
            assertEquals(EntityVersion("50"), record.serverVersion, "the next push is based on the conflicting version")
            assertNull(record.conflictId)
            val revived = assertNotNull(stores.groups.find(assertNotNull(record.groupId)))
            assertEquals(PushGroupState.PENDING, revived.state)
            assertEquals(ord, revived.ord, "the revived group keeps its place in the queue")
            assertTrue(revived.groupId != conflicted, "the server already answered under the old identifier")
            assertNull(stores.groups.find(conflicted))
        }

    @Test
    fun `taking the server's state applies it and clears the local change`() =
        runTest {
            conflictOn(1)
            val conflicted = assertNotNull(record(1).groupId)

            coordinator.resolve(conflictIdOf(1), Resolution.TakeServer)

            assertEquals(body("Theirs 1"), adapter.bodies[wallet to entity(1)])
            val record = record(1)
            assertFalse(record.isDirty, "the local change was dropped by the decision")
            assertNull(record.groupId)
            assertNull(record.conflictId)
            assertNull(stores.groups.find(conflicted), "an empty group has nothing left to send")
        }

    @Test
    fun `a merged state is written locally and queued for the server`() =
        runTest {
            conflictOn(1)

            coordinator.resolve(conflictIdOf(1), Resolution.Merged(body("Merged")))

            assertEquals(body("Merged"), adapter.bodies[wallet to entity(1)])
            val record = record(1)
            assertTrue(record.isDirty)
            assertEquals(EntityVersion("50"), record.serverVersion)
            val group = assertNotNull(stores.groups.find(assertNotNull(record.groupId)))
            assertEquals(PushGroupState.PENDING, group.state)
        }

    @Test
    fun `a group with several conflicts waits for all of them`() =
        runTest {
            conflictOn(1, 2)
            val conflicted = assertNotNull(record(1).groupId)

            coordinator.resolve(conflictIdOf(1), Resolution.KeepLocal)

            val group = assertNotNull(stores.groups.find(conflicted))
            assertEquals(PushGroupState.CONFLICTED, group.state, "the group is atomic and cannot leave in halves")

            coordinator.resolve(conflictIdOf(2), Resolution.KeepLocal)

            assertNull(stores.groups.find(conflicted))
            assertEquals(record(1).groupId, record(2).groupId, "both changes still travel together")
        }

    @Test
    fun `the adapter may decide on its own`() =
        runTest {
            conflictOn(1)
            adapter.resolution = Resolution.TakeServer

            assertTrue(coordinator.offerToAdapter(conflictIdOf(1)))

            assertEquals(body("Theirs 1"), adapter.bodies[wallet to entity(1)])
            assertNull(record(1).conflictId)
        }

    @Test
    fun `the adapter may leave the decision to the user`() =
        runTest {
            conflictOn(1)
            adapter.resolution = null
            val conflictId = conflictIdOf(1)

            assertFalse(coordinator.offerToAdapter(conflictId))

            assertNotNull(stores.conflicts.find(conflictId), "an undecided conflict has to stay for the user interface")
            assertEquals(conflictId, record(1).conflictId)
        }
}
