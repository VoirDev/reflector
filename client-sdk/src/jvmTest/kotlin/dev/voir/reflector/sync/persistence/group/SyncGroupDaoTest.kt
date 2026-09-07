package dev.voir.reflector.sync.persistence.group

import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Queries of the push queue.
 *
 * The queue's whole contract is the order it hands groups out in: they are sent one at a time and
 * strictly by ordinal, because two groups can depend on each other in ways the library cannot see.
 * That order is a predicate in a single statement, which is exactly the kind of thing the compiler
 * accepts and reality does not — the state of the head belongs to the caller's decision, and adding
 * it to this predicate is how the ordering was lost once already.
 */
class SyncGroupDaoTest {
    private val dao = openTestDatabase().syncGroupDao()

    private val scope = "user-1"
    private val ledger = "ledger"
    private val other = "notes"

    private fun id(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-2%011d".format(index))

    private suspend fun insert(
        index: Int,
        ord: Long,
        state: PushGroupState = PushGroupState.PENDING,
        nextRetryAt: Long? = null,
        collection: String = ledger,
    ): Uuid {
        val groupId = id(index)
        dao.insert(SyncGroupEntity(groupId, scope, collection, ord, state, 0, nextRetryAt, 0, null))
        return groupId
    }

    private suspend fun head() = dao.head(scope, ledger)

    @Test
    fun `the head of the queue is its oldest group`() =
        runTest {
            insert(2, ord = 2)
            val first = insert(1, ord = 1)

            assertEquals(first, assertNotNull(head()).groupId)
        }

    @Test
    fun `the head is the head whatever state it is in`() =
        runTest {
            // The one thing this statement must not do is filter by state. A conflicted or failed
            // group is what the queue is waiting on, and handing back the group behind it would send
            // a change that may depend on one the server never received.
            val stuck = insert(1, ord = 1, state = PushGroupState.CONFLICTED)
            insert(2, ord = 2)

            assertEquals(stuck, assertNotNull(head()).groupId)
        }

    @Test
    fun `the head carries what the caller needs to decide whether it can be sent`() =
        runTest {
            // Readiness is not decided here: the answer differs per state — a conflict is waited
            // for, a lost answer is resent, a refusal needs the application — so the row is handed
            // over whole.
            insert(1, ord = 1, state = PushGroupState.FAILED, nextRetryAt = 100)

            val group = assertNotNull(head())
            assertEquals(PushGroupState.FAILED, group.state)
            assertEquals(100, group.nextRetryAt)
        }

    @Test
    fun `the head of one collection is not the head of another`() =
        runTest {
            insert(1, ord = 1, collection = other)
            val mine = insert(2, ord = 1)

            assertEquals(mine, assertNotNull(head()).groupId)
        }

    @Test
    fun `the next pending group after a position is the one merging looks for`() =
        runTest {
            insert(1, ord = 1)
            insert(2, ord = 2, state = PushGroupState.IN_FLIGHT)
            val next = insert(3, ord = 3)

            assertEquals(
                next,
                assertNotNull(dao.nextPendingAfter(scope, ledger, PushGroupState.PENDING, ord = 1)).groupId,
            )
            assertNull(
                dao.nextPendingAfter(scope, ledger, PushGroupState.PENDING, ord = 3),
                "nothing follows the last one",
            )
        }

    @Test
    fun `a new group takes the ordinal after the last one of its collection`() =
        runTest {
            assertEquals(1, dao.nextOrdinal(scope, ledger))
            insert(1, ord = 1)
            insert(2, ord = 2, collection = other)

            assertEquals(2, dao.nextOrdinal(scope, ledger))
            assertEquals(3, dao.nextOrdinal(scope, other), "queues are numbered per collection, not per scope")
        }

    @Test
    fun `a failed attempt grows the count the backoff is computed from`() =
        runTest {
            val groupId = insert(1, ord = 1)

            dao.recordAttempt(groupId, PushGroupState.PENDING, nextRetryAt = 500, error = "offline")
            dao.recordAttempt(groupId, PushGroupState.PENDING, nextRetryAt = 900, error = "offline")

            val group = assertNotNull(dao.find(groupId))
            assertEquals(2, group.attempts)
            assertEquals(900, group.nextRetryAt)
            assertEquals("offline", group.lastError)
        }
}
