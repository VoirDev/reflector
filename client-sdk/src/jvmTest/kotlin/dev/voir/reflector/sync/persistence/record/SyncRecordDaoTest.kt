package dev.voir.reflector.sync.persistence.record

import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Queries of the per-entity metadata.
 *
 * Almost every statement here is a predicate over three revisions and a generation counter, and
 * each of them decides whether a local change survives. Getting one wrong does not fail: it drops
 * an edit the user made, or resurrects a row the server deleted, and neither says anything.
 */
class SyncRecordDaoTest {
    private val dao = openTestDatabase().syncRecordDao()

    private val scope = "user-1"
    private val ledger = "ledger"
    private val wallet = "wallet"

    private fun entity(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))

    private fun group(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-2%011d".format(index))

    private suspend fun store(
        index: Int,
        entityType: String = wallet,
        localRev: Long = 0,
        pushingRev: Long = 0,
        ackedRev: Long = 0,
        groupId: Uuid? = null,
        conflictId: Uuid? = null,
        seenGen: Long = 1,
    ) {
        dao.upsert(
            SyncRecordEntity(
                scopeId = scope,
                collectionId = ledger,
                entityType = entityType,
                entityId = entity(index),
                serverVersion = null,
                localRev = localRev,
                pushingRev = pushingRev,
                ackedRev = ackedRev,
                intent = if (localRev > ackedRev) MutationIntent.UPSERT else null,
                groupId = groupId,
                conflictId = conflictId,
                seenGen = seenGen,
            ),
        )
    }

    private suspend fun find(
        index: Int,
        entityType: String = wallet,
    ) = dao.find(scope, ledger, entityType, entity(index))

    @Test
    fun `the sweep takes the records a bootstrap did not confirm and leaves the ones it did`() =
        runTest {
            store(1, seenGen = 2)
            store(2, seenGen = 1)

            val stale = dao.staleRecords(scope, ledger, generation = 2)

            assertEquals(listOf(entity(2)), stale.map { it.entityId })
            dao.deleteStale(scope, ledger, generation = 2)
            assertNotNull(find(1))
            assertNull(find(2))
        }

    @Test
    fun `a record with unsent changes survives the sweep`() =
        runTest {
            // The entity is missing from the snapshot only because the change that creates it has
            // not been pushed yet. Sweeping it would delete a row the user is still waiting to send.
            store(1, localRev = 1, ackedRev = 0, seenGen = 1, groupId = group(1))

            assertEquals(emptyList(), dao.staleRecords(scope, ledger, generation = 2))
            dao.deleteStale(scope, ledger, generation = 2)
            assertNotNull(find(1))
        }

    @Test
    fun `acknowledging confirms only the revision that was actually sent`() =
        runTest {
            store(1, localRev = 1, groupId = group(1))
            dao.capturePushingRevisions(group(1))

            // The user edits the entity again while the envelope is on the wire.
            dao.markMutated(scope, ledger, wallet, entity(1), MutationIntent.UPSERT, group(1))
            dao.acknowledge(scope, ledger, wallet, entity(1), serverVersion = "10")

            val record = assertNotNull(find(1))
            assertEquals(2, record.localRev)
            assertEquals(1, record.ackedRev, "the push may only confirm the revision it carried")
            dao.releaseCleanRecords(group(1))
            assertEquals(group(1), assertNotNull(find(1)).groupId, "the newer edit keeps the record in a group")
        }

    @Test
    fun `a group releases the records that came out of it clean`() =
        runTest {
            store(1, localRev = 1, pushingRev = 1, ackedRev = 1, groupId = group(1))

            dao.releaseCleanRecords(group(1))

            val record = assertNotNull(find(1))
            assertNull(record.groupId)
            assertNull(record.intent)
        }

    @Test
    fun `the records of a group come out in a stable order`() =
        runTest {
            // Two envelopes built from the same data have to be byte-identical, or an idempotent
            // retry stops being idempotent.
            store(2, entityType = "transaction", groupId = group(1))
            store(2, groupId = group(1))
            store(1, groupId = group(1))

            val ordered = dao.ofGroup(group(1)).map { it.entityType to it.entityId }

            assertEquals(
                listOf("transaction" to entity(2), wallet to entity(1), wallet to entity(2)),
                ordered,
            )
        }

    @Test
    fun `marking a record the library has never seen reports that nothing was updated`() =
        runTest {
            // This is how the mutation path learns it has to insert instead of update; a statement
            // that silently matched nothing would lose the mutation entirely.
            assertEquals(0, dao.markMutated(scope, ledger, wallet, entity(1), MutationIntent.UPSERT, group(1)))

            store(1)
            assertEquals(1, dao.markMutated(scope, ledger, wallet, entity(1), MutationIntent.UPSERT, group(1)))
            assertEquals(1, assertNotNull(find(1)).localRev)
        }

    @Test
    fun `merging two groups moves every record of the absorbed one`() =
        runTest {
            store(1, localRev = 1, groupId = group(1))
            store(2, localRev = 1, groupId = group(2))

            dao.reassignGroup(source = group(2), target = group(1))

            assertEquals(2, dao.ofGroup(group(1)).size)
            assertEquals(emptyList(), dao.ofGroup(group(2)))
        }

    @Test
    fun `a group with an open conflict is counted as unable to leave`() =
        runTest {
            store(1, localRev = 1, groupId = group(1))
            store(2, localRev = 1, groupId = group(1), conflictId = group(9))

            assertEquals(1, dao.openConflictsOfGroup(group(1)))
        }

    @Test
    fun `pending counts the changes the server has not confirmed`() =
        runTest {
            store(1, localRev = 2, ackedRev = 1)
            store(2, localRev = 1, ackedRev = 1)

            assertEquals(1, dao.pendingCount(scope, ledger))
        }
}
