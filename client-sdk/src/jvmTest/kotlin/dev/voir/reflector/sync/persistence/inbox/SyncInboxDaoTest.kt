package dev.voir.reflector.sync.persistence.inbox

import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Queries of the inbox tables.
 *
 * Room checks these statements against the schema when the module is compiled, so a mistyped column
 * cannot reach here. What it cannot check is what they mean — and the meaning of this DAO is an
 * ordering: batches are applied in the order they arrived, and an order that is quietly wrong shows
 * up three layers up as data that was never applied.
 */
class SyncInboxDaoTest {
    private val dao = openTestDatabase().syncInboxDao()

    private val scope = "user-1"
    private val ledger = "ledger"
    private val other = "notes"

    private fun entity(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))

    private fun batch(
        seq: String,
        ord: Long,
        collection: String = ledger,
        state: InboxBatchState = InboxBatchState.PENDING,
    ) = SyncInboxBatchEntity(scope, collection, seq, "epoch.$seq", ord, null, state)

    private fun op(
        seq: String,
        ordinal: Int,
        collection: String = ledger,
    ) = SyncInboxOpEntity(scope, collection, seq, ordinal, "wallet", entity(ordinal), "upsert", seq, "{}")

    private suspend fun oldest(collection: String = ledger) =
        dao.oldestPendingBatch(scope, collection, InboxBatchState.PENDING)

    @Test
    fun `the oldest batch is the one that arrived first, not the one that sorts first`() =
        runTest {
            // The sequence is an opaque string: sorted as text, "10" comes before "9", which would
            // hand out the second batch first and let the cursor pass the one skipped.
            dao.insertBatches(listOf(batch("9", ord = 1), batch("10", ord = 2)))

            assertEquals("9", assertNotNull(oldest()).seq)
        }

    @Test
    fun `the operations of a batch come out in the order the server committed them`() =
        runTest {
            dao.insertBatches(listOf(batch("9", ord = 1)))
            dao.insertOps(listOf(op("9", ordinal = 2), op("9", ordinal = 0), op("9", ordinal = 1)))

            assertEquals(listOf(0, 1, 2), dao.opsOf(scope, ledger, "9").map { it.ordinal })
        }

    @Test
    fun `an applied batch is not offered again`() =
        runTest {
            dao.insertBatches(
                listOf(
                    batch("9", ord = 1, state = InboxBatchState.APPLIED),
                    batch("10", ord = 2),
                ),
            )

            assertEquals("10", assertNotNull(oldest()).seq)
        }

    @Test
    fun `the next arrival ordinal continues after the batches of that collection alone`() =
        runTest {
            assertEquals(1, dao.nextReceivedOrdinal(scope, ledger))
            dao.insertBatches(listOf(batch("9", ord = 1), batch("10", ord = 2)))

            assertEquals(3, dao.nextReceivedOrdinal(scope, ledger))
            assertEquals(1, dao.nextReceivedOrdinal(scope, other), "collections number their arrivals apart")
        }

    @Test
    fun `deleting a batch leaves the same sequence in another collection alone`() =
        runTest {
            dao.insertBatches(listOf(batch("9", ord = 1), batch("9", ord = 1, collection = other)))
            dao.insertOps(listOf(op("9", ordinal = 0), op("9", ordinal = 0, collection = other)))

            dao.deleteOps(scope, ledger, "9")
            dao.deleteBatch(scope, ledger, "9")

            assertNull(oldest())
            assertNotNull(oldest(other))
            assertEquals(1, dao.opsOf(scope, other, "9").size)
        }

    @Test
    fun `clearing a collection empties both its batches and its operations`() =
        runTest {
            dao.insertBatches(listOf(batch("9", ord = 1), batch("10", ord = 2)))
            dao.insertOps(listOf(op("9", ordinal = 0), op("10", ordinal = 0)))

            dao.deleteAllOps(scope, ledger)
            dao.deleteBatches(scope, ledger)

            assertNull(oldest())
            assertEquals(emptyList(), dao.opsOf(scope, ledger, "9"), "operations must not outlive their batch")
        }
}
