package dev.voir.reflector.sync.engine.blob

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.blob.BlobBinding
import dev.voir.reflector.sync.core.blob.BlobFailure
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
import dev.voir.reflector.sync.protocol.changes.ChangeBatch
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.config.BlobLimits
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.discardingSink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What the engine records about files, before anything moves a byte.
 *
 * Reconciliation is invisible from outside: no request is made and no file is touched, and all it
 * produces is rows. That is exactly why it is worth testing on its own — every transfer the library
 * will ever make is decided here, and a wrong decision shows up much later as a photograph that
 * never arrives or a file deleted while a document still names it.
 */
class BlobReconciliationTest {
    private val scopeId = ScopeId("user-1")
    private val ledger = CollectionId("ledger")
    private val wallet = EntityType("wallet")
    private val walletId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000001"))
    private val photo = BlobId(Uuid.parse("00000000-0000-7000-8000-3000000000b1"))

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)
    private val transport = FakeTransport()
    private val adapter = FakeAdapter()
    private val files = FakeBlobStore()
    private val blobTransport = FakeBlobTransport()
    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // A server that serves files says so through its limits, and a client configured for them
        // against one that does not is a misconfiguration the engine reports rather than guesses at.
        transport.limits =
            transport.limits.copy(
                blobs = BlobLimits(maxBlobBytes = 25L * 1024 * 1024, maxBlobsPerEntity = 8, uploadTicketSeconds = 900),
            )
    }

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
    }

    private fun engine(blobStore: BlobStore? = files): dev.voir.reflector.sync.core.SyncEngine =
        SyncEngine(
            database = database,
            transactions = transactions,
            transport = transport,
            adapters = mapOf<CollectionId, CollectionAdapter>(ledger to adapter),
            coroutineScope = workers,
            blobStore = blobStore,
            // Both or neither: a store with no transport would leave every record naming a file
            // waiting for a registration nothing can perform, and the engine refuses to be built
            // that way.
            blobTransport = blobStore?.let { blobTransport },
            triggerSources = emptyList(),
        )

    /** Accepts whatever is sent, so that a test about references is not also a test about pushing. */
    private fun acceptPushes() {
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

    private fun emptySnapshot() =
        SnapshotPage(testCursor("0"), emptyList(), nextPage = null, hasMore = false, epoch = TEST_EPOCH)

    /** Waits for something the engine writes, failing with what it got stuck on rather than hanging. */
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
        assertNotNull(reached, "never reached $description; blobs=" + blobRows() + " removed=" + files.removed)
    }

    private suspend fun blobRows() = transactions.transaction { stores.blobs.all(scopeId, ledger) }

    private suspend fun stateOf(blobId: BlobId) =
        transactions.transaction { stores.blobs.find(scopeId, ledger, blobId)?.state }

    @Test
    fun `a document that names a file this device holds leaves it waiting to be sent`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val collection = engine().scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }

            // Adopted as a file this device holds, which is what separates an upload from a
            // download and is the only question reconciliation answers.
            await("the file being adopted") { stateOf(photo) != null }
            await("the file being registered") { blobTransport.registered.contains(photo) }
            // Read back with the policy resolved: the application left it unstated and the engine's
            // default is what the row carries, so nothing downstream has to know what it was.
            assertEquals(
                setOf(BlobRef(photo, fetch = BlobFetch.EAGER)),
                transactions.transaction { stores.blobRefs.of(scopeId, ledger, wallet, walletId) },
            )
        }

    @Test
    fun `the envelope names the files its documents point at`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val collection = engine().scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }

            await("the group reaching the server") { transport.pushes.isNotEmpty() }
            val op =
                assertIs<PushOperation.Upsert>(
                    transport.pushes
                        .first()
                        .groups
                        .single()
                        .ops
                        .single(),
                )
            assertEquals(listOf(photo), op.blobs)
        }

    @Test
    fun `a client that synchronises no files says nothing about them`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            val collection = engine(blobStore = null).scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }

            await("the group reaching the server") { transport.pushes.isNotEmpty() }
            val op =
                assertIs<PushOperation.Upsert>(
                    transport.pushes
                        .first()
                        .groups
                        .single()
                        .ops
                        .single(),
                )
            // Absent, not empty. The server reads it as "leave the stored references alone", which
            // is the only reading under which an installation older than files cannot cause the
            // collector to delete what it is still using.
            assertNull(op.blobs)
            assertTrue(blobRows().isEmpty(), "an engine without a file store enters no blob path at all")
        }

    @Test
    fun `a document that arrives naming a file this device lacks leaves it waiting to be fetched`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            adapter.references[wallet to walletId] = setOf(BlobRef(photo))
            blobTransport.onServer[photo] =
                dev.voir.reflector.sync.protocol.blob.BlobInfo(
                    blobId = photo,
                    state = dev.voir.reflector.sync.protocol.blob.BlobState.READY,
                    contentType = BlobContentType("image/jpeg"),
                    size = SIZE,
                )
            // Served once and then nothing. Keyed on how many reads have happened rather than on the
            // cursor, because the bootstrap has already fixed one by the time the log is first read.
            var served = false
            transport.onChanges = {
                if (served) {
                    ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH)
                } else {
                    served = true
                    changesNaming(photo)
                }
            }
            engine().scope(scopeId).collection(ledger)

            // Adopted as a file this device does not hold and then fetched, which is the whole of
            // the receiving path: the record arrived first and the bytes followed it.
            await("the file arriving") { stateOf(photo) == BlobTransferState.READY }
            // Read back with the policy resolved: the application left it unstated and the engine's
            // default is what the row carries, so nothing downstream has to know what it was.
            assertEquals(
                setOf(BlobRef(photo, fetch = BlobFetch.EAGER)),
                transactions.transaction { stores.blobRefs.of(scopeId, ledger, wallet, walletId) },
            )
        }

    @Test
    fun `a file no document names any more is offered back to the application`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val collection = engine().scope(scopeId).collection(ledger)
            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }
            await("the file being adopted") { stateOf(photo) != null }

            collection.mutate {
                adapter.references[wallet to walletId] = emptySet()
                markUpserted(wallet, walletId)
            }

            // Offered, not deleted: an application keeping the file for an undo stack is correct and
            // does nothing here. What the library stops doing is accounting for it.
            await("the application being offered its file") { files.removed.contains(photo) }
            await("the library forgetting the file") { stateOf(photo) == null }
        }

    @Test
    fun `a record that cannot be published without its file waits for it`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            // Registered but never transferred, which is precisely the window this binding refuses
            // to publish in — a deferred record would have gone out at the registration.
            blobTransport.holdUploads = true
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val collection = engine().scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo, BlobBinding.REQUIRED))
                markUpserted(wallet, walletId)
            }

            await("the file being registered") { blobTransport.registered.contains(photo) }
            // Registered is not enough for this binding, and that is the whole difference between
            // the two: the record stays until the bytes are on the server, which is the cost this
            // binding announces.
            delay(SETTLE)
            assertTrue(transport.pushes.isEmpty(), "the record must not reach the server before its file")
        }

    @Test
    fun `a record goes out once its file is registered, long before the bytes follow`() =
        runBlocking<Unit> {
            transport.onSnapshot = { emptySnapshot() }
            acceptPushes()
            // The registration gets through and the bytes never do, which is the gap the default
            // binding lives in.
            blobTransport.holdUploads = true
            files.bytes[photo] = BlobStat(SIZE, BlobContentType("image/jpeg"))
            val collection = engine().scope(scopeId).collection(ledger)

            collection.mutate {
                adapter.bodies[wallet to walletId] = buildJsonObject { put("title", "Cash") }
                adapter.references[wallet to walletId] = setOf(BlobRef(photo))
                markUpserted(wallet, walletId)
            }

            // The case that decides the default: a transaction must not wait on a receipt. What it
            // does wait for is one round trip — the server refuses a document naming a file it has
            // never heard of, so the registration has to come first. A transfer does not.
            await("the record reaching the server") { transport.pushes.isNotEmpty() }
            assertTrue(blobTransport.registered.contains(photo))
            assertTrue(blobTransport.completed.isEmpty(), "the bytes never reached the server")
        }

    private fun changesNaming(blobId: BlobId) =
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
        const val SETTLE = 400L
    }
}

/**
 * The application's own file store, faked.
 *
 * @property bytes Files this device is pretending to hold, by identifier.
 * @property removed Files the library offered back, in order.
 */
class FakeBlobStore : BlobStore {
    val bytes: MutableMap<BlobId, BlobStat> = mutableMapOf()
    val removed: MutableList<BlobId> = mutableListOf()

    /** Failures the library reported, so that a test can assert what the application was told. */
    val failures: MutableList<Pair<BlobId, BlobFailure>> = mutableListOf()

    /**
     * Files that go missing after the first time they are described.
     *
     * Models the race the library actually has to survive: the file was there when the store was
     * asked about it and gone by the time the bytes were wanted, because something else cleared a
     * cache in between.
     */
    var vanishing: Set<BlobId> = emptySet()

    private val described = mutableSetOf<BlobId>()

    override suspend fun stat(blobId: BlobId): BlobStat? {
        if (blobId in vanishing && !described.add(blobId)) {
            return null
        }
        return bytes[blobId]
    }

    override suspend fun read(blobId: BlobId): RawSource = kotlinx.io.Buffer()

    override suspend fun write(
        blobId: BlobId,
        stat: BlobStat,
    ): RawSink = discardingSink().buffered()

    override suspend fun finish(
        blobId: BlobId,
        complete: Boolean,
    ) = Unit

    override suspend fun remove(blobId: BlobId) {
        removed += blobId
        bytes -= blobId
    }

    override suspend fun onFailed(
        blobId: BlobId,
        failure: BlobFailure,
    ) {
        failures += blobId to failure
    }
}
