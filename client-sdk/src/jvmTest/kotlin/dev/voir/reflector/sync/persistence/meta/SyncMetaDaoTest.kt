package dev.voir.reflector.sync.persistence.meta

import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Queries of the per-scope identity.
 *
 * One row, and everything about echo suppression rests on it: a client identifier that changed
 * without a bootstrap makes the device's own past changes arrive as somebody else's and turns every
 * one of them into a conflict.
 */
class SyncMetaDaoTest {
    private val dao = openTestDatabase().syncMetaDao()

    private val scope = "user-1"
    private val clientId = Uuid.parse("00000000-0000-7000-8000-0000000000c1")

    @Test
    fun `the identity of a scope survives the trip through storage`() =
        runTest {
            dao.insert(SyncMetaEntity(scope, clientId, createdAt = 1_700))

            val meta = assertNotNull(dao.find(scope))
            assertEquals(clientId, meta.clientId)
            assertEquals(1_700, meta.createdAt)
            assertNull(dao.find("user-2"), "an identity belongs to one scope")
        }

    @Test
    fun `an identity is never quietly replaced`() =
        runTest {
            dao.insert(SyncMetaEntity(scope, clientId, createdAt = 1_700))

            // Replacing instead of failing would break echo suppression without a single symptom
            // until the client started treating its own changes as conflicts.
            assertFails {
                dao.insert(SyncMetaEntity(scope, Uuid.parse("00000000-0000-7000-8000-0000000000c2"), 1_800))
            }

            assertEquals(clientId, assertNotNull(dao.find(scope)).clientId)
        }

    @Test
    fun `wiping a scope removes its identity`() =
        runTest {
            dao.insert(SyncMetaEntity(scope, clientId, createdAt = 1_700))

            dao.delete(scope)

            assertNull(dao.find(scope))
        }
}
