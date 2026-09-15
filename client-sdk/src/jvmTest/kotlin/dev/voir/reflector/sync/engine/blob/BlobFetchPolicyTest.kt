package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.blob.BlobStore
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.engine.FakeAdapter
import dev.voir.reflector.sync.engine.FakeTransport
import dev.voir.reflector.sync.engine.SyncEngine
import dev.voir.reflector.sync.engine.TEST_EPOCH
import dev.voir.reflector.sync.engine.testCursor
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.config.BlobLimits
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What this device fetches on its own, and what it waits to be asked for.
 *
 * Every test here is about a file the server holds and this device does not, because that is the
 * only situation in which the policy means anything: bytes already on the device are sent whatever
 * it says. What makes the cases worth writing separately is that "nothing happened" is the expected
 * outcome of half of them, and a policy that quietly fetched anyway would look exactly like a
 * policy that worked — until somebody's data allowance ran out.
 */
class BlobFetchPolicyTest {
    private val scopeId = ScopeId("user-1")
    private val ledger = CollectionId("ledger")
    private val wallet = EntityType("wallet")
    private val walletId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000001"))
    private val photo = BlobId(Uuid.parse("00000000-0000-7000-8000-3000000000b1"))

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)
    private val transport = FakeTransport()
    private val blobs = FakeBlobTransport()
    private val adapter = FakeAdapter()
    private val files = FakeBlobStore()
    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        transport.limits =
            transport.limits.copy(
                blobs = BlobLimits(maxBlobBytes = 25L * 1024 * 1024, maxBlobsPerEntity = 8, uploadTicketSeconds = 900),
            )
        transport.onSnapshot = {
            SnapshotPage(testCursor("0"), emptyList(), nextPage = null, hasMore = false, epoch = TEST_EPOCH)
        }
        transport.onPush = { request ->
            val group = request.groups.single()
            PushResponse(
                results =
                    listOf(
                        PushGroupResult.Applied(
                            groupId = group.groupId,
                            versions = group.ops.map { AppliedVersion(it.entity, it.id, EntityVersion("1")) },
                        ),
                    ),
                latestSeq = BatchSeq("1"),
                epoch = TEST_EPOCH,
            )
        }
    }

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
    }

    private fun engine(
        blobFetch: BlobFetch,
        blobStore: BlobStore = files,
    ) = SyncEngine(
        database = database,
        transactions = transactions,
        transport = transport,
        adapters = mapOf<CollectionId, CollectionAdapter>(ledger to adapter),
        coroutineScope = workers,
        blobStore = blobStore,
        blobTransport = blobs,
        blobFetch = blobFetch,
        triggerSources = emptyList(),
    )

    /**
     * Puts a wallet with a photograph on the server and opens the collection that pulls it in.
     *
     * The receiving side, always: a document arrives naming a file whose bytes are on the server and
     * have never been on this device, which is the state every fetch policy is a decision about.
     *
     * @param engineFetch Policy the engine is configured with.
     * @param fetch Policy the document's reference declares, or `null` to leave it to the engine.
     * @param known Whether the server admits to having the file at all.
     * @return Handle of the collection, for the tests that ask it for the file.
     */
    private fun receiveWallet(
        engineFetch: BlobFetch,
        fetch: BlobFetch? = null,
        known: Boolean = true,
    ): CollectionHandle {
        adapter.references[wallet to walletId] = setOf(BlobRef(photo, fetch = fetch))
        if (known) {
            blobs.onServer[photo] =
                BlobInfo(photo, BlobState.READY, BlobContentType("image/jpeg"), SIZE, checksum = null)
        }
        // Served once and then nothing, because the bootstrap has already fixed a cursor by the time
        // the log is first read.
        var served = false
        transport.onChanges = {
            if (served) {
                ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH)
            } else {
                served = true
                changesNamingPhoto()
            }
        }
        return engine(engineFetch).scope(scopeId).collection(ledger)
    }

    private suspend fun await(
        description: String,
        condition: suspend () -> Boolean,
    ) {
        val reached =
            withTimeoutOrNull(AWAIT_TIMEOUT) {
                while (!condition()) {
                    delay(POLL)
                }
                true
            }
        assertNotNull(reached, "never reached $description; blob=" + recordOf(photo))
    }

    private suspend fun recordOf(blobId: BlobId) =
        transactions.transaction { stores.blobs.find(scopeId, ledger, blobId) }

    @Test
    fun `a file left on demand is known about and not fetched`() =
        runBlocking<Unit> {
            val handle = receiveWallet(engineFetch = BlobFetch.ON_DEMAND)

            await("the file being adopted") { recordOf(photo) != null }
            delay(SETTLE)

            // The record is here, the reference is known, and the bytes are deliberately not: a
            // screen drawing this offers a download rather than an error.
            val record = assertNotNull(recordOf(photo))
            assertEquals(BlobTransferState.REMOTE, record.state)
            assertFalse(record.wanted, "nothing has asked for this file")
            assertEquals(0, record.attempts, "nothing was tried, so there is nothing to have failed")
            assertTrue(blobs.ticketed.isEmpty(), "no ticket was asked for, so no download was attempted")
            val published = assertNotNull(handle.blob(photo).first())
            assertFalse(published.wanted)
            // Not counted as incoming: nothing is waiting for it, and an application that showed it
            // as arriving would be promising something no transfer is going to produce.
            assertEquals(0, handle.state.value.incomingBlobs)
        }

    @Test
    fun `a file the application asks for is fetched`() =
        runBlocking<Unit> {
            val handle = receiveWallet(engineFetch = BlobFetch.ON_DEMAND)
            await("the file being adopted") { recordOf(photo) != null }

            handle.fetch(photo)

            await("the file arriving") { recordOf(photo)?.state == BlobTransferState.READY }
            assertTrue(assertNotNull(recordOf(photo)).wanted)
        }

    @Test
    fun `a reference declared eagerly is fetched although the engine waits to be asked`() =
        runBlocking<Unit> {
            receiveWallet(engineFetch = BlobFetch.ON_DEMAND, fetch = BlobFetch.EAGER)

            // The per-reference override, which is the whole reason the policy is not one switch:
            // an application holds the thumbnail and leaves the original alone.
            await("the file arriving") { recordOf(photo)?.state == BlobTransferState.READY }
        }

    @Test
    fun `a reference declared on demand is left although the engine fetches everything`() =
        runBlocking<Unit> {
            receiveWallet(engineFetch = BlobFetch.EAGER, fetch = BlobFetch.ON_DEMAND)

            await("the file being adopted") { recordOf(photo) != null }
            delay(SETTLE)

            assertEquals(BlobTransferState.REMOTE, assertNotNull(recordOf(photo)).state)
            assertTrue(blobs.ticketed.isEmpty(), "the reference declines what the engine would do")
        }

    @Test
    fun `asking for a file the library gave up on starts it again`() =
        runBlocking<Unit> {
            // A server that has never heard of the file: the fetch is given up on rather than
            // retried, and the application is told it will not arrive.
            val handle = receiveWallet(engineFetch = BlobFetch.EAGER, known = false)
            await("the file being given up on") { recordOf(photo)?.state == BlobTransferState.UNAVAILABLE }

            blobs.onServer[photo] =
                BlobInfo(photo, BlobState.READY, BlobContentType("image/jpeg"), SIZE, checksum = null)
            handle.fetch(photo)

            // What a retry button is: the attempts go back to zero and the worker treats it as new
            // work, because the user asking again is the only thing that can restart this.
            await("the file arriving after all") { recordOf(photo)?.state == BlobTransferState.READY }
            assertEquals(0, assertNotNull(recordOf(photo)).attempts)
        }

    @Test
    fun `a file nothing references is not fetched, whoever asks`() =
        runBlocking<Unit> {
            val handle = receiveWallet(engineFetch = BlobFetch.ON_DEMAND)
            await("the file being adopted") { recordOf(photo) != null }
            val stranger = BlobId(Uuid.parse("00000000-0000-7000-8000-3000000000ff"))

            handle.fetch(stranger)

            // The library would have nothing to keep it for, and reconciliation would offer it
            // straight back to the application.
            delay(SETTLE)
            assertNull(recordOf(stranger))
            assertTrue(blobs.ticketed.isEmpty())
        }

    @Test
    fun `an evicted file goes back to being one the server has and can be fetched again`() =
        runBlocking<Unit> {
            val handle = receiveWallet(engineFetch = BlobFetch.ON_DEMAND)
            await("the file being adopted") { recordOf(photo) != null }
            handle.fetch(photo)
            await("the file arriving") { recordOf(photo)?.state == BlobTransferState.READY }

            handle.evict(photo)

            assertTrue(files.removed.contains(photo), "the application was told to drop the bytes")
            val record = assertNotNull(recordOf(photo))
            assertEquals(BlobTransferState.REMOTE, record.state)
            assertFalse(record.wanted)

            handle.fetch(photo)

            await("the file arriving a second time") { recordOf(photo)?.state == BlobTransferState.READY }
        }

    @Test
    fun `a file whose bytes are the only copy is not evicted`() =
        runBlocking<Unit> {
            // Registration gets through and the bytes never do, so the file stays one this device
            // holds and the server does not.
            blobs.holdUploads = true
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val handle = engine(BlobFetch.ON_DEMAND).scope(scopeId).collection(ledger)
            handle.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }
            await("the file being adopted") { recordOf(photo) != null }

            handle.evict(photo)

            // Freeing a cache and losing a user's file are not the same act, and only the state of
            // the row tells them apart.
            assertFalse(files.removed.contains(photo))
            assertTrue(files.bytes.containsKey(photo))
        }

    private fun changesNamingPhoto() =
        ChangesPage(
            batches =
                listOf(
                    ChangeBatch(
                        seq = BatchSeq("1"),
                        cursor = testCursor("1"),
                        originClientId = null,
                        ops =
                            listOf(
                                RemoteOperation.Upsert(
                                    entity = wallet,
                                    id = walletId,
                                    version = EntityVersion("1"),
                                    data = buildJsonObject { put("title", "Cash") },
                                ),
                            ),
                    ),
                ),
            nextCursor = testCursor("1"),
            hasMore = false,
            epoch = TEST_EPOCH,
        )

    private companion object {
        const val SIZE = 2_418_123L
        const val AWAIT_TIMEOUT = 10_000L
        const val POLL = 20L

        /** Long enough for a transfer nobody asked for to have happened, if one was going to. */
        const val SETTLE = 400L
    }
}
