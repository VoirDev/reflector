package dev.voir.reflector.sync.engine.mutation

import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.persistence.record.MutationIntent
import dev.voir.reflector.sync.persistence.record.RecordState
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MutationCoordinatorTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    /** Sink the coordinator's own account of what it did goes to, so a test can read it back. */
    private val logs = RecordingSyncLog()
    private val log = SyncLogger(logs, scope, collection)
    private val wallet = EntityType("wallet")

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)

    private var nextGroup = 0
    private val coordinator =
        MutationCoordinator(stores, transactions, log) {
            nextGroup++
            GroupId(Uuid.parse("00000000-0000-7000-8000-%012d".format(nextGroup)))
        }

    private suspend fun record(index: Int): RecordState =
        assertNotNull(stores.records.find(scope, collection, wallet, entity(index)))

    private fun entity(index: Int): EntityId = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    @Test
    fun `entities marked in one transaction share a group`() =
        runTest {
            coordinator.mutate(scope, collection) {
                markUpserted(wallet, entity(1))
                markUpserted(wallet, entity(2))
            }

            val first = record(1).groupId
            val second = record(2).groupId
            assertNotNull(first)
            assertEquals(first, second)
        }

    @Test
    fun `transactions sharing an entity are merged into one group`() =
        runTest {
            coordinator.mutate(scope, collection) {
                markUpserted(wallet, entity(1))
                markUpserted(wallet, entity(2))
            }
            coordinator.mutate(scope, collection) {
                markUpserted(wallet, entity(2))
                markUpserted(wallet, entity(3))
            }

            val groups =
                (1..3).map { index ->
                    record(index).groupId
                }
            assertEquals(1, groups.toSet().size, "entities edited together must not be split across groups")
            val survivor = assertNotNull(groups.first())
            val ord = assertNotNull(stores.groups.find(survivor)).ord
            assertEquals(1, ord, "merged group keeps the earliest position")
        }

    @Test
    fun `transactions touching different entities stay in separate groups`() =
        runTest {
            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(2)) }

            val first = record(1).groupId
            val second = record(2).groupId
            assertTrue(first != second, "unrelated transactions must stay independently pushable")
        }

    @Test
    fun `an entity edited while its group is in flight keeps that group`() =
        runTest {
            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val marked = record(1)
            val inFlight = assertNotNull(marked.groupId)
            stores.groups.setState(inFlight, PushGroupState.IN_FLIGHT)

            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }

            val record = record(1)
            assertEquals(inFlight, record.groupId, "the envelope on the wire must stay reproducible for a retry")
            assertEquals(2, record.localRev)
            assertTrue(record.isDirty)
        }

    @Test
    fun `no empty group is left behind when every edit stays with an in-flight group`() =
        runTest {
            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val marked = record(1)
            val inFlight = assertNotNull(marked.groupId)
            stores.groups.setState(inFlight, PushGroupState.IN_FLIGHT)

            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }

            assertEquals(inFlight, assertNotNull(stores.groups.head(scope, collection)).groupId)
            stores.groups.delete(inFlight)
            assertNull(
                stores.groups.head(scope, collection),
                "a group with no records would be pushed as an empty envelope",
            )
        }

    @Test
    fun `deleting an entity records the intent without dropping its group`() =
        runTest {
            coordinator.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            coordinator.mutate(scope, collection) { markDeleted(wallet, entity(1)) }

            val record = record(1)
            assertEquals(MutationIntent.DELETE, record.intent)
            assertNotNull(record.groupId)
            assertEquals(2, record.localRev)
        }
}
