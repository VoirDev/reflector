package dev.voir.reflector.sync.persistence.conflict

import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Queries of the conflict table.
 *
 * Conflicts are offered to the application oldest first, and the order is the one thing a user can
 * notice here: deciding about the newer state of an entity before the older one inverts what they
 * are shown.
 */
class SyncConflictDaoTest {
    private val dao = openTestDatabase().syncConflictDao()

    private val scope = "user-1"
    private val ledger = "ledger"

    private fun id(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-3%011d".format(index))

    private fun entity(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))

    private suspend fun record(
        index: Int,
        detectedAt: Long,
        scopeId: String = scope,
    ) {
        dao.insert(
            SyncConflictEntity(
                conflictId = id(index),
                scopeId = scopeId,
                collectionId = ledger,
                entityType = "wallet",
                entityId = entity(index),
                origin = ConflictOrigin.PULL,
                localPayload = """{"title":"Mine"}""",
                serverPayload = """{"title":"Theirs"}""",
                serverVersion = "10",
                detectedAt = detectedAt,
            ),
        )
    }

    @Test
    fun `open conflicts come out oldest first`() =
        runTest {
            record(2, detectedAt = 200)
            record(1, detectedAt = 100)
            record(3, detectedAt = 300)

            assertEquals(listOf(id(1), id(2), id(3)), dao.openIds(scope, ledger))
        }

    @Test
    fun `a resolved conflict leaves the list`() =
        runTest {
            record(1, detectedAt = 100)
            record(2, detectedAt = 200)

            dao.delete(id(1))

            assertEquals(listOf(id(2)), dao.openIds(scope, ledger))
            assertNull(dao.find(id(1)))
        }

    @Test
    fun `both states are stored as they were at detection time`() =
        runTest {
            // The one place the library keeps a copy of business data, because after the cursor has
            // moved the incoming state exists nowhere else.
            record(1, detectedAt = 100)

            val conflict = assertNotNull(dao.find(id(1)))
            assertEquals("""{"title":"Mine"}""", conflict.localPayload)
            assertEquals("""{"title":"Theirs"}""", conflict.serverPayload)
            assertEquals(ConflictOrigin.PULL, conflict.origin)
            assertEquals(entity(1), conflict.entityId, "the identifier survives the trip through the blob column")
        }

    @Test
    fun `wiping a scope leaves another scope's conflicts alone`() =
        runTest {
            record(1, detectedAt = 100)
            record(2, detectedAt = 100, scopeId = "user-2")

            dao.deleteScope(scope)

            assertNull(dao.find(id(1)))
            assertNotNull(dao.find(id(2)))
        }
}
