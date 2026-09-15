package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobBinding
import dev.voir.reflector.sync.core.blob.BlobFailure
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.core.blob.BlobStat
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
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
import dev.voir.reflector.sync.protocol.config.BlobLimits
import dev.voir.reflector.sync.protocol.config.SyncLimits
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The file worker moving bytes, and what it does when it cannot.
 *
 * Three of these are about failing rather than succeeding, which is the right proportion: a transfer
 * that works needs no design, and every interesting decision in this worker is about telling apart
 * a file that is merely late from one that is never coming.
 */
class BlobWorkerTest {
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
            SyncLimits(
                maxOperationsPerGroup = 500,
                maxDocumentBytes = 256 * 1024,
                maxChangesPageSize = 500,
                retentionDays = 30,
                blobs = BlobLimits(maxBlobBytes = MAX_BYTES, maxBlobsPerEntity = 8, uploadTicketSeconds = 900),
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

    private lateinit var engine: dev.voir.reflector.sync.core.SyncEngine

    private fun collection() = engineOf().scope(scopeId).collection(ledger)

    private fun engineOf() =
        SyncEngine(
            database = database,
            transactions = transactions,
            transport = transport,
            adapters = mapOf<CollectionId, CollectionAdapter>(ledger to adapter),
            coroutineScope = workers,
            blobStore = files,
            blobTransport = blobs,
            triggerSources = emptyList(),
        ).also { engine = it }

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
        assertNotNull(
            reached,
            "never reached $description; state=" + stateOf(photo) + " failures=" + files.failures,
        )
    }

    private suspend fun stateOf(blobId: BlobId) =
        transactions.transaction { stores.blobs.find(scopeId, ledger, blobId)?.state }

    private suspend fun attach(
        binding: BlobBinding = BlobBinding.DEFERRED,
        size: Long = SIZE,
    ) {
        files.bytes[photo] = BlobStat(size, BlobContentType("image/jpeg"))
        collection().mutate {
            adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
            adapter.references[wallet to walletId] = setOf(BlobRef(photo, binding))
            markUpserted(wallet, walletId)
        }
    }

    @Test
    fun `what a screen draws follows the file rather than the record`() =
        runBlocking<Unit> {
            val handle = collection()
            attach()

            await("the file reaching the server") { stateOf(photo) == BlobTransferState.UPLOADED }

            val published = assertNotNull(handle.blob(photo).first())
            assertEquals(BlobTransferState.UPLOADED, published.state)
            assertEquals(SIZE, published.size)
            assertEquals(SIZE, published.transferred)
            assertNull(published.lastError, "nothing failed, so there is nothing to show about failing")
            // Counted apart from pendingCount on purpose: a record that has not reached the server
            // is work that could be lost, and a photograph that has not is a slow upload.
            assertEquals(0, handle.state.value.incomingBlobs)
        }

    @Test
    fun `a file nobody has mentioned is published as nothing rather than as a state`() =
        runBlocking<Unit> {
            val handle = collection()
            attach()
            await("the file being adopted") { stateOf(photo) != null }

            assertNull(handle.blob(BlobId(Uuid.parse("00000000-0000-7000-8000-3000000000ff"))).first())
        }

    @Test
    fun `diagnostics say the queue is waiting on bytes rather than stuck for no reason`() =
        runBlocking<Unit> {
            blobs.failWith = SyncTransportFailure.Unreachable("the connection dropped")
            val handle = collection()
            attach(binding = BlobBinding.REQUIRED)
            await("the transfer failing") {
                transactions.transaction { stores.blobs.find(scopeId, ledger, photo)?.attempts ?: 0 } > 0
            }

            val diagnostics = handle.diagnostics()

            // Nothing is conflicted and nothing was refused, and yet the collection sends nothing.
            // Without this the only honest description of it would be that it is stuck for no reason.
            assertTrue(diagnostics.queue.isNotEmpty())
            assertFalse(diagnostics.isQueueBlocked)
            assertTrue(diagnostics.isQueueWaitingOnFiles)
            val file = diagnostics.files.single()
            assertEquals(photo, file.blobId)
            assertTrue(file.referenced)
            assertNotNull(file.lastError)
        }

    @Test
    fun `signing out takes the user's files with the rest of the scope`() =
        runBlocking<Unit> {
            attach()
            await("the file reaching the server") { stateOf(photo) == BlobTransferState.UPLOADED }

            engine.signOut(discardPending = true)

            // Not the offer reconciliation makes, which an application may decline: the scope is
            // going, and a signed-out user's photographs left in the store on a shared device are
            // not a policy question.
            assertEquals(listOf(photo), files.removed)
            assertEquals(
                emptyList(),
                transactions.transaction { stores.blobs.allOfScope(scopeId) }.map { it.blobId },
            )
        }

    @Test
    fun `a file this device holds is registered, sent and confirmed`() =
        runBlocking<Unit> {
            attach()

            await("the file reaching the server") { stateOf(photo) == BlobTransferState.UPLOADED }
            assertEquals(listOf(photo), blobs.registered)
            assertEquals(listOf(photo), blobs.completed)
        }

    @Test
    fun `a record that could not be published before its file goes out once the file has landed`() =
        runBlocking<Unit> {
            attach(binding = BlobBinding.REQUIRED)

            // The record waited for the bytes and then followed them, with nothing in between having
            // to be scheduled by hand: the worker wakes the cycle by finishing.
            await("the file reaching the server") { stateOf(photo) == BlobTransferState.UPLOADED }
            await("the record following it") { transport.pushes.isNotEmpty() }
        }

    @Test
    fun `a file larger than the server accepts is refused before any of it moves`() =
        runBlocking<Unit> {
            attach(size = MAX_BYTES + 1)

            await("the file being given up on") { stateOf(photo) == BlobTransferState.UNAVAILABLE }
            // The point of publishing a limit is that nobody pays to transfer a file that was never
            // going to be kept, so nothing may have been registered or sent.
            assertTrue(blobs.registered.isEmpty())
            val failure = assertIs<BlobFailure.TooLarge>(files.failures.single().second)
            assertEquals(MAX_BYTES, failure.limit)
        }

    @Test
    fun `a file the application no longer holds is given up on and reported`() =
        runBlocking<Unit> {
            // There when the store was asked about it, gone by the time the bytes were wanted. The
            // library has to survive that race rather than assume its own last answer still holds.
            files.vanishing = setOf(photo)
            attach()

            await("the file being given up on") { stateOf(photo) == BlobTransferState.UNAVAILABLE }
            assertIs<BlobFailure.SourceMissing>(files.failures.single().second)
            // The reference is deliberately left alone: dropping it would be the library editing the
            // application's document, and a receipt worth asking a user about would vanish silently.
            assertEquals(
                setOf(BlobRef(photo, fetch = BlobFetch.EAGER)),
                transactions.transaction { stores.blobRefs.of(scopeId, ledger, wallet, walletId) },
            )
        }

    @Test
    fun `a transfer that fails is tried again rather than given up on`() =
        runBlocking<Unit> {
            blobs.failWith = SyncTransportFailure.Unreachable("the connection dropped")
            attach()

            await("the failure being recorded") {
                transactions.transaction { stores.blobs.find(scopeId, ledger, photo)?.attempts ?: 0 } > 0
            }
            // Still where it started, and that matters more than it looks: the row only says a file
            // is being uploaded once the server has acknowledged the registration, because the push
            // reads that state to decide whether the server has heard of the file at all.
            assertEquals(BlobTransferState.LOCAL, stateOf(photo))
            assertTrue(files.failures.isEmpty(), "the application is told only when nothing more will be tried")
        }

    @Test
    fun `a file the server no longer has is reported as gone rather than retried`() =
        runBlocking<Unit> {
            adapter.references[wallet to walletId] = setOf(BlobRef(photo))
            var served = false
            transport.onChanges = {
                if (served) {
                    dev.voir.reflector.sync.protocol.changes
                        .ChangesPage(emptyList(), null, false, TEST_EPOCH)
                } else {
                    served = true
                    changesNamingPhoto()
                }
            }
            collection()

            // Nothing was ever registered for it, so the fake answers as a server that collected it.
            await("the file being given up on") { stateOf(photo) == BlobTransferState.UNAVAILABLE }
            assertIs<BlobFailure.Gone>(files.failures.single().second)
        }

    @Test
    fun `a file the server has not accepted yet is waited for rather than failed`() =
        runBlocking<Unit> {
            blobs.onServer[photo] =
                BlobInfo(photo, BlobState.PENDING, BlobContentType("image/jpeg"), SIZE, checksum = null)
            adapter.references[wallet to walletId] = setOf(BlobRef(photo))
            var served = false
            transport.onChanges = {
                if (served) {
                    dev.voir.reflector.sync.protocol.changes
                        .ChangesPage(emptyList(), null, false, TEST_EPOCH)
                } else {
                    served = true
                    changesNamingPhoto()
                }
            }
            collection()

            // The ordinary state of an attachment whose record arrived ahead of its bytes. It is
            // answered by waiting, and must never be reported to the application as a failure.
            await("the file being waited for") {
                transactions.transaction { stores.blobs.find(scopeId, ledger, photo)?.attempts ?: 0 } > 0
            }
            assertEquals(BlobTransferState.REMOTE, stateOf(photo))
            assertTrue(files.failures.isEmpty())
        }

    private fun changesNamingPhoto() =
        dev.voir.reflector.sync.protocol.changes.ChangesPage(
            batches =
                listOf(
                    dev.voir.reflector.sync.protocol.changes.ChangeBatch(
                        seq = BatchSeq("1"),
                        cursor = testCursor("1"),
                        originClientId = null,
                        ops =
                            listOf(
                                dev.voir.reflector.sync.protocol.changes.RemoteOperation.Upsert(
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
        const val MAX_BYTES = 4L * 1024 * 1024
        const val AWAIT_TIMEOUT = 10_000L
        const val POLL = 20L
    }
}
