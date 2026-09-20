package dev.voir.reflector.sync.persistence.collection

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Queries of the per-collection state.
 *
 * Each of these statements writes one part of a row and leaves the rest alone, and which part it
 * leaves alone is the whole point: a cursor moved without clearing the failure count, or a
 * bootstrap started without bumping the generation, is a row that no longer describes anything.
 */
class SyncCollectionDaoTest {
    private val dao = openTestDatabase().syncCollectionDao()

    private val scope = "user-1"
    private val ledger = "ledger"

    private suspend fun seed(
        cursor: String? = null,
        phase: SyncPhase = SyncPhase.LIVE,
        generation: Long = 1,
        bootstrapPage: String? = null,
    ) {
        dao.upsert(
            SyncCollectionEntity(
                scopeId = scope,
                collectionId = ledger,
                cursor = cursor,
                phase = phase,
                epoch = null,
                generation = generation,
                bootstrapPage = bootstrapPage,
                lastPullAt = null,
                lastPushAt = null,
                schemaFingerprint = null,
                lastError = null,
                failureCount = 0,
            ),
        )
    }

    private suspend fun state() = assertNotNull(dao.find(scope, ledger))

    @Test
    fun `failures accumulate into the count the backoff is computed from`() =
        runTest {
            seed()

            dao.recordFailure(scope, ledger, "offline")
            dao.recordFailure(scope, ledger, "still offline")

            assertEquals(2, state().failureCount)
            assertEquals("still offline", state().lastError)
        }

    @Test
    fun `advancing the cursor clears the failures that preceded it`() =
        runTest {
            seed()
            dao.recordFailure(scope, ledger, "offline")

            dao.advanceCursor(scope, ledger, cursor = "10", appliedAt = 1_700)

            assertEquals("10", state().cursor)
            assertEquals(0, state().failureCount, "a success ends the backoff, or the next one starts from it")
            assertNull(state().lastError)
            assertEquals(1_700, state().lastPullAt)
        }

    @Test
    fun `starting a bootstrap bumps the generation and forgets an unfinished transfer`() =
        runTest {
            seed(bootstrapPage = "p7")

            dao.beginBootstrap(scope, ledger, SyncPhase.BOOTSTRAPPING)

            // The generation is what the sweep afterwards compares against: without the bump every
            // record would look confirmed by the new snapshot and nothing would ever be swept.
            assertEquals(2, state().generation)
            assertEquals(SyncPhase.BOOTSTRAPPING, state().phase)
            assertNull(state().bootstrapPage)
        }

    @Test
    fun `finishing a bootstrap adopts the fixed cursor and drops the page token`() =
        runTest {
            seed(phase = SyncPhase.BOOTSTRAPPING, bootstrapPage = "p7")
            dao.recordFailure(scope, ledger, "one page failed on the way")

            dao.finishBootstrap(
                scope,
                ledger,
                cursor = "1100",
                phase = SyncPhase.LIVE,
                appliedAt = 1_700_000_000_000,
            )

            assertEquals("1100", state().cursor)
            assertEquals(SyncPhase.LIVE, state().phase)
            assertNull(state().bootstrapPage)
            assertEquals(0, state().failureCount)
            assertEquals(1_700_000_000_000, state().lastPullAt)
        }

    @Test
    fun `a pull that caught up records its time without disturbing anything else`() =
        runTest {
            seed(phase = SyncPhase.LIVE)
            dao.recordFailure(scope, ledger, "a push was refused")

            dao.recordPull(scope, ledger, polledAt = 1_700_000_000_000)

            assertEquals(1_700_000_000_000, state().lastPullAt)
            assertNull(state().cursor, "catching up on an empty log must not invent a cursor")
            // A pull succeeds on every cycle a client is online. Clearing these here would reset
            // the backoff of a push that keeps failing, and hide the failure entirely.
            assertEquals(1, state().failureCount)
            assertEquals("a push was refused", state().lastError)
        }

    @Test
    fun `the cursor of an unfinished transfer is remembered without touching the phase`() =
        runTest {
            seed(phase = SyncPhase.BOOTSTRAPPING)

            dao.updateCursor(scope, ledger, "1100")

            assertEquals("1100", state().cursor)
            assertEquals(
                SyncPhase.BOOTSTRAPPING,
                state().phase,
                "a remembered cursor does not mean the snapshot landed",
            )
        }

    @Test
    fun `wiping a scope removes its collections`() =
        runTest {
            seed()
            dao.upsert(
                SyncCollectionEntity(
                    "user-2",
                    ledger,
                    null,
                    null,
                    SyncPhase.LIVE,
                    1,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                ),
            )

            dao.deleteScope(scope)

            assertNull(dao.find(scope, ledger))
            assertNotNull(dao.find("user-2", ledger), "another scope's data is not this scope's to remove")
        }
}
