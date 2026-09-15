package dev.voir.reflector.sync.persistence.blob

import dev.voir.reflector.sync.core.blob.BlobBinding
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.openTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Queries over the metadata of files and what points at them.
 *
 * The queries worth testing here are the ones that encode a rule rather than a lookup: a file
 * nothing points at is not work however urgent its state looks, a file nothing wants is a download
 * nobody asked for, and a file nothing points at any more is what the application is offered to
 * delete. Getting any of them wrong is silent — the first two leave a transfer running that should
 * not, or none where one is expected, and the last offers the user's photograph for deletion while
 * a document still names it.
 */
class SyncBlobDaoTest {
    private val database = openTestDatabase()
    private val dao = database.syncBlobDao()
    private val refs = database.syncBlobRefDao()

    private val scope = "user-1"
    private val ledger = "ledger"
    private val wallet = "wallet"

    private fun blob(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-3%011d".format(index))

    private fun entity(index: Int): Uuid = Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))

    private suspend fun store(
        index: Int,
        state: BlobTransferState = BlobTransferState.LOCAL,
        attempts: Int = 0,
        nextRetryAt: Long? = null,
        wanted: Boolean = true,
    ) {
        dao.upsert(
            SyncBlobEntity(
                scopeId = scope,
                collectionId = ledger,
                blobId = blob(index),
                state = state,
                wanted = wanted,
                contentType = "image/jpeg",
                size = SIZE,
                checksum = null,
                attempts = attempts,
                nextRetryAt = nextRetryAt,
            ),
        )
    }

    private suspend fun reference(
        entityIndex: Int,
        blobIndex: Int,
        binding: BlobBinding = BlobBinding.DEFERRED,
        fetch: BlobFetch = BlobFetch.EAGER,
        generation: Long = 1,
    ) {
        refs.upsert(
            SyncBlobRefEntity(
                scopeId = scope,
                collectionId = ledger,
                entityType = wallet,
                entityId = entity(entityIndex),
                blobId = blob(blobIndex),
                binding = binding,
                fetch = fetch,
                seenGen = generation,
            ),
        )
    }

    @Test
    fun `a file nothing points at is not work`() =
        runTest {
            store(1, BlobTransferState.LOCAL)

            // Registered but never named by a document — an attachment the user picked and then
            // abandoned. Uploading it would spend the user's data on a file nothing will ever fetch.
            assertTrue(dao.waiting(scope, ledger, BlobTransferState.LOCAL, NOW, 10).isEmpty())
        }

    @Test
    fun `a file a document points at is work`() =
        runTest {
            store(1, BlobTransferState.LOCAL)
            reference(entityIndex = 1, blobIndex = 1)

            assertEquals(
                listOf(blob(1)),
                dao.waiting(scope, ledger, BlobTransferState.LOCAL, NOW, 10).map { it.blobId },
            )
        }

    @Test
    fun `a file serving a backoff is passed over until its moment`() =
        runTest {
            store(1, BlobTransferState.LOCAL, attempts = 2, nextRetryAt = NOW + 1000)
            reference(entityIndex = 1, blobIndex = 1)

            assertTrue(dao.waiting(scope, ledger, BlobTransferState.LOCAL, NOW, 10).isEmpty())
            assertEquals(1, dao.waiting(scope, ledger, BlobTransferState.LOCAL, NOW + 1000, 10).size)
        }

    @Test
    fun `work is ordered so that a failing transfer cannot starve a fresh one`() =
        runTest {
            store(1, BlobTransferState.LOCAL, attempts = 5)
            store(2, BlobTransferState.LOCAL, attempts = 0)
            reference(entityIndex = 1, blobIndex = 1)
            reference(entityIndex = 2, blobIndex = 2)

            val order = dao.waiting(scope, ledger, BlobTransferState.LOCAL, NOW, 10).map { it.blobId }

            // A file that has already failed five times goes behind one that has not tried at all.
            // Otherwise a large file on a bad connection holds up every photograph behind it.
            assertEquals(listOf(blob(2), blob(1)), order)
        }

    @Test
    fun `a file two documents point at survives one of them letting go`() =
        runTest {
            store(1, BlobTransferState.READY)
            reference(entityIndex = 1, blobIndex = 1)
            reference(entityIndex = 2, blobIndex = 1)

            refs.clear(scope, ledger, wallet, entity(1))

            assertTrue(dao.unreferenced(scope, ledger).isEmpty(), "the other document still names it")
        }

    @Test
    fun `a file the last document let go of is offered for removal`() =
        runTest {
            store(1, BlobTransferState.READY)
            reference(entityIndex = 1, blobIndex = 1)

            refs.clear(scope, ledger, wallet, entity(1))

            assertEquals(listOf(blob(1)), dao.unreferenced(scope, ledger).map { it.blobId })
        }

    @Test
    fun `a failure keeps its count so that a hopeless transfer cannot retry forever`() =
        runTest {
            store(1)

            dao.recordFailure(scope, ledger, blob(1), "connection reset", NOW + 500)
            dao.recordFailure(scope, ledger, blob(1), "connection reset", NOW + 1500)

            val stored = assertNotNull(dao.find(scope, ledger, blob(1)))
            assertEquals(2, stored.attempts)
            assertEquals(NOW + 1500, stored.nextRetryAt)
        }

    @Test
    fun `moving to a new state forgets the last failure and its backoff`() =
        runTest {
            store(1)
            dao.recordFailure(scope, ledger, blob(1), "connection reset", NOW + 500)

            dao.setState(scope, ledger, blob(1), BlobTransferState.UPLOADED)

            val stored = assertNotNull(dao.find(scope, ledger, blob(1)))
            assertEquals(BlobTransferState.UPLOADED, stored.state)
            assertEquals(0, stored.attempts)
            assertNull(stored.nextRetryAt)
            assertNull(stored.lastError)
        }

    @Test
    fun `only the files a document cannot be published without are asked for`() =
        runTest {
            store(1)
            store(2)
            reference(entityIndex = 1, blobIndex = 1, binding = BlobBinding.DEFERRED)
            reference(entityIndex = 1, blobIndex = 2, binding = BlobBinding.REQUIRED)

            val required = refs.required(scope, ledger, wallet, entity(1))

            // The whole of what the default binding means in the queue: a deferred reference is in
            // the table and never holds a push back.
            assertEquals(listOf(blob(2)), required.map { it.blobId })
        }

    @Test
    fun `a sweep takes the references of documents the snapshot no longer mentions`() =
        runTest {
            store(1)
            store(2)
            reference(entityIndex = 1, blobIndex = 1, generation = 1)
            reference(entityIndex = 2, blobIndex = 2, generation = 2)

            refs.sweep(scope, ledger, generation = 2)

            // A document the bootstrap did not return takes its references with it, exactly as it
            // takes its record — and the file it named becomes something to offer the application.
            assertEquals(listOf(blob(1)), dao.unreferenced(scope, ledger).map { it.blobId })
            assertTrue(refs.of(scope, ledger, wallet, entity(1)).isEmpty())
            assertEquals(1, refs.of(scope, ledger, wallet, entity(2)).size)
        }

    @Test
    fun `a file nothing wants is not fetched`() =
        runTest {
            store(1, BlobTransferState.REMOTE, wanted = false)
            reference(entityIndex = 1, blobIndex = 1, fetch = BlobFetch.ON_DEMAND)

            // Referenced, on the server, and deliberately still there: this is the resting state of
            // a file under the on-demand policy, and the worker must leave it alone.
            assertTrue(dao.waiting(scope, ledger, BlobTransferState.REMOTE, NOW, 10).isEmpty())
            assertEquals(0, dao.observeCount(scope, ledger, BlobTransferState.REMOTE).first())
        }

    @Test
    fun `a file the application asked for is fetched`() =
        runTest {
            store(1, BlobTransferState.REMOTE, wanted = false)
            reference(entityIndex = 1, blobIndex = 1, fetch = BlobFetch.ON_DEMAND)

            dao.setWanted(scope, ledger, blob(1), wanted = true)

            assertEquals(
                listOf(blob(1)),
                dao.waiting(scope, ledger, BlobTransferState.REMOTE, NOW, 10).map { it.blobId },
            )
            assertEquals(1, dao.observeCount(scope, ledger, BlobTransferState.REMOTE).first())
        }

    @Test
    fun `asking for a file does not shorten the backoff it is serving`() =
        runTest {
            store(1, BlobTransferState.REMOTE, attempts = 2, nextRetryAt = NOW + 1000, wanted = false)
            reference(entityIndex = 1, blobIndex = 1, fetch = BlobFetch.ON_DEMAND)

            dao.setWanted(scope, ledger, blob(1), wanted = true)

            // The wish is recorded and the failures that produced the delay are not forgotten: a
            // screen redrawing itself is not new information about a transfer that keeps failing.
            val stored = assertNotNull(dao.find(scope, ledger, blob(1)))
            assertEquals(2, stored.attempts)
            assertEquals(NOW + 1000, stored.nextRetryAt)
            assertTrue(dao.waiting(scope, ledger, BlobTransferState.REMOTE, NOW, 10).isEmpty())
        }

    @Test
    fun `a file some document declares eagerly becomes wanted`() =
        runTest {
            store(1, BlobTransferState.REMOTE, wanted = false)
            store(2, BlobTransferState.REMOTE, wanted = false)
            reference(entityIndex = 1, blobIndex = 1, fetch = BlobFetch.ON_DEMAND)
            reference(entityIndex = 2, blobIndex = 2, fetch = BlobFetch.EAGER)

            dao.wantEagerlyReferenced(scope, ledger)

            assertEquals(false, assertNotNull(dao.find(scope, ledger, blob(1))).wanted)
            assertEquals(true, assertNotNull(dao.find(scope, ledger, blob(2))).wanted)
        }

    @Test
    fun `one eager document is enough for a file another declares on demand`() =
        runTest {
            store(1, BlobTransferState.REMOTE, wanted = false)
            reference(entityIndex = 1, blobIndex = 1, fetch = BlobFetch.ON_DEMAND)
            reference(entityIndex = 2, blobIndex = 1, fetch = BlobFetch.EAGER)

            dao.wantEagerlyReferenced(scope, ledger)

            // Eager wins where two documents disagree: it is the only reading under which a record
            // that cannot be drawn without its file reliably has it.
            assertEquals(true, assertNotNull(dao.find(scope, ledger, blob(1))).wanted)
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val SIZE = 2_418_123L
    }
}
