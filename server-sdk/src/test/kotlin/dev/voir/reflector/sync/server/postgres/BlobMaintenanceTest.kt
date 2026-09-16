package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.UnknownBlobException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What maintenance does about files: collecting what nothing points at, confirming uploads nobody
 * confirmed, and taking files with a collection when it is erased.
 *
 * The question underneath all of it is which way to fail. A file deleted while a document still
 * names it is a broken attachment on every device and cannot be repaired; a file left behind is
 * storage nobody is using. The sweep is built to fail the second way, and two tests below are about
 * exactly that preference rather than about the happy path.
 */
class BlobMaintenanceTest {
    private val clock = MutableClock(Instant.fromEpochMilliseconds(1_700_000_000_000))
    private val storage = FakeBlobStorage()
    private val module =
        PostgresFixture.module(
            clock = clock,
            retention = RETENTION,
            blobs = BlobConfig(uploadTicketLife = TICKET_LIFE),
            blobStorage = storage,
        )
    private val blobs = assertNotNull(module.blobs)
    private val scope = PostgresFixture.scope
    private val ledger = PostgresFixture.ledger
    private val wallet = PostgresFixture.wallet
    private val client = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    @Test
    fun `a file nothing has pointed at for longer than the window is collected`() {
        val blob = uploaded()
        clock.instant += RETENTION + 1.hours

        val report = module.maintenance.collectBlobs()

        assertEquals(1, report.candidates)
        assertEquals(1, report.released)
        assertEquals(listOf(storage.keyOf(scope, ledger, blob)), storage.released)
        assertFailsWith<UnknownBlobException> { blobs.download(scope, ledger, blob) }
    }

    @Test
    fun `a file a document still names is never a candidate`() {
        val blob = uploaded()
        push(upsert(1, blobs = listOf(blob)))
        clock.instant += RETENTION * 10

        val report = module.maintenance.collectBlobs()

        // No window makes a referenced file collectable. The clock only starts once the last
        // document has let go of it.
        assertTrue(report.isEmpty)
        assertEquals(BlobState.READY, blobs.download(scope, ledger, blob).blob.state)
    }

    @Test
    fun `a file let go of recently is kept until the window has passed`() {
        val blob = uploaded()
        push(upsert(1, blobs = listOf(blob)))
        push(upsert(1, base = EntityVersion("1"), blobs = emptyList()))
        clock.instant += RETENTION - 1.hours

        assertTrue(module.maintenance.collectBlobs().isEmpty)

        // A client whose cursor is still inside the window may yet apply the batch that referenced
        // it, so the file has to outlive the log that mentions it.
        clock.instant += 2.hours
        assertEquals(1, module.maintenance.collectBlobs().released)
    }

    @Test
    fun `a dry run says what it would take and takes nothing`() {
        val blob = uploaded()
        clock.instant += RETENTION + 1.hours

        val report = module.maintenance.collectBlobs(dryRun = true)

        assertTrue(report.dryRun)
        assertEquals(1, report.candidates)
        assertEquals(0, report.released)
        assertTrue(storage.released.isEmpty(), "a dry run must not reach the storage at all")
        assertEquals(BlobState.READY, blobs.download(scope, ledger, blob).blob.state)
    }

    @Test
    fun `a host that keeps the object rather than deleting it is not a failure`() {
        val blob = uploaded()
        // A lifecycle rule, a legal hold, a backup window: the host is told the file is free and
        // decides to keep it anyway. That is a policy, and the module has no opinion about it.
        storage.retained = setOf(storage.keyOf(scope, ledger, blob))
        clock.instant += RETENTION + 1.hours

        val report = module.maintenance.collectBlobs()

        // The module's own account is unchanged: it let go of one file and handed over its key.
        assertEquals(1, report.candidates)
        assertEquals(1, report.released)
        assertEquals(listOf(storage.keyOf(scope, ledger, blob)), storage.released)
        // Its rows go regardless, and must: the row has to be gone before the object could be, or a
        // push could reference a file that is about to be disposed of.
        assertFailsWith<UnknownBlobException> { blobs.download(scope, ledger, blob) }
    }

    @Test
    fun `an upload nobody confirmed is confirmed once its object is found`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        // The bytes arrived; the device died before it could say so.
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), SIZE)
        clock.instant += TICKET_LIFE + 1.hours

        val confirmed = module.maintenance.confirmPendingUploads()

        assertEquals(1, confirmed)
        assertEquals(BlobState.READY, blobs.download(scope, ledger, descriptor.blobId).blob.state)
    }

    @Test
    fun `an upload that never arrived is left alone rather than failing the sweep`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        clock.instant += TICKET_LIFE + 1.hours

        val confirmed = module.maintenance.confirmPendingUploads()

        // An abandoned upload is an ordinary thing for a user to produce. It stays unusable and is
        // collected later like any other file nothing points at.
        assertEquals(0, confirmed)
        assertEquals(BlobState.PENDING, blobs.download(scope, ledger, descriptor.blobId).blob.state)
    }

    @Test
    fun `an upload still inside its ticket is not touched`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), SIZE)

        assertEquals(0, module.maintenance.confirmPendingUploads())
    }

    @Test
    fun `purging a collection takes its files out of storage too`() {
        val blob = uploaded()
        push(upsert(1, blobs = listOf(blob)))

        val report = module.maintenance.purgeCollection(scope, ledger)

        // An erasure that never told the host about the photographs would not be an erasure. What
        // the host then does about them is its own, and its storage log — not this report — is the
        // evidence an auditor would accept.
        assertEquals(1, report.blobs)
        assertEquals(listOf(storage.keyOf(scope, ledger, blob)), storage.released)
    }

    @Test
    fun `a purge hands over the files of a collection that was never pushed to`() {
        val blob = uploaded()

        val report = module.maintenance.purgeCollection(scope, ledger)

        // Registered, uploaded, and no document ever named it. It is still the scope's data and an
        // erasure has to reach it, which it would not if the handover were driven by references.
        assertEquals(1, report.blobs)
        assertEquals(listOf(storage.keyOf(scope, ledger, blob)), storage.released)
        assertFailsWith<UnknownBlobException> { blobs.download(scope, ledger, blob) }
    }

    private fun descriptor(): BlobDescriptor =
        BlobDescriptor(
            blobId = BlobId(Uuid.random()),
            contentType = BlobContentType("image/jpeg"),
            size = SIZE,
        )

    private fun uploaded(): BlobId {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), SIZE)
        blobs.markUploaded(scope, ledger, descriptor.blobId)
        return descriptor.blobId
    }

    private fun push(op: PushOperation) =
        module.service.push(scope, ledger, PushRequest(client, listOf(PushGroup(GroupId(Uuid.random()), listOf(op)))))

    private fun upsert(
        index: Int,
        base: EntityVersion? = null,
        blobs: List<BlobId>?,
    ) = PushOperation.Upsert(
        wallet,
        EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))),
        base,
        buildJsonObject { put("title", "Cash") },
        blobs,
    )

    private companion object {
        const val SIZE = 2_418_123L
        val RETENTION = 30.days
        val TICKET_LIFE = 1.hours
    }
}
