package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.BlobConflictException
import dev.voir.reflector.sync.server.BlobListener
import dev.voir.reflector.sync.server.BlobNotStoredException
import dev.voir.reflector.sync.server.BlobTooLargeException
import dev.voir.reflector.sync.server.StoredBlob
import dev.voir.reflector.sync.server.SyncBlobService
import dev.voir.reflector.sync.server.UnknownBlobException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What the module does about files, against a real database and a fake bucket.
 *
 * The recurring subject here is that the module is not in the data path. It never sees the bytes, so
 * every claim about them is somebody else's, and almost every test below is about the module
 * refusing to take one of those claims on trust — or about it being asked the same thing twice,
 * because every one of these operations is reached from a device that may have died halfway through
 * the last attempt.
 */
class BlobServiceTest {
    private val scope: ScopeId = PostgresFixture.scope
    private val ledger: CollectionId = PostgresFixture.ledger
    private val storage = FakeBlobStorage()
    private val accepted = mutableListOf<StoredBlob>()
    private val clock = MutableClock()

    private val blobs: SyncBlobService =
        assertNotNull(
            PostgresFixture
                .module(
                    clock = clock,
                    blobs = BlobConfig(maxBlobBytes = MAX_BLOB_BYTES),
                    blobStorage = storage,
                    blobListeners = listOf(BlobListener { _, _, blob -> accepted += blob }),
                ).blobs,
        )

    @BeforeTest
    fun clean() {
        // Before rather than after: a class that only tidied up behind itself would still be at the
        // mercy of whatever ran before it and left rows of its own.
        PostgresFixture.reset()
    }

    @Test
    fun `registering a blob hands out permission to send it`() {
        val descriptor = descriptor()

        val registration = blobs.register(scope, ledger, descriptor)

        assertEquals(BlobState.PENDING, registration.blob.state)
        assertEquals(SIZE, registration.blob.size)
        val upload = assertNotNull(registration.upload)
        assertEquals(BlobTicketMethod.PUT, upload.method)
    }

    @Test
    fun `registering again hands out a fresh ticket for the same object`() {
        val descriptor = descriptor()
        val first = blobs.register(scope, ledger, descriptor)

        val second = blobs.register(scope, ledger, descriptor)

        // A device that crashed mid-transfer asks again. It must get a usable ticket rather than a
        // refusal, and it must not get a second object: the key is chosen once, for the blob's life.
        assertNotNull(second.upload)
        assertEquals(first.blob, second.blob)
        assertEquals(storage.tickets[0].headers, storage.tickets[1].headers)
    }

    @Test
    fun `a blob larger than the published limit is refused before any ticket exists`() {
        val oversized = descriptor(size = MAX_BLOB_BYTES + 1)

        assertFailsWith<BlobTooLargeException> { blobs.register(scope, ledger, oversized) }

        // The point of publishing a limit is that nobody pays to transfer a file that was never
        // going to be kept, so the refusal has to come before the permission to send.
        assertTrue(storage.tickets.isEmpty())
    }

    @Test
    fun `a blob whose object never arrived is not accepted`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)

        val failure =
            assertFailsWith<BlobNotStoredException> { blobs.markUploaded(scope, ledger, descriptor.blobId) }

        // The device saying it finished and the device having finished are different facts, and only
        // the storage can tell them apart.
        assertTrue(failure.message.orEmpty().contains("no object"))
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `a stored object of the wrong size is not accepted`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE - 1)

        assertFailsWith<BlobNotStoredException> { blobs.markUploaded(scope, ledger, descriptor.blobId) }

        assertEquals(BlobState.PENDING, blobs.download(scope, ledger, descriptor.blobId).blob.state)
    }

    @Test
    fun `a storage that reports no checksum does not fail the verification`() {
        val descriptor = descriptor(checksum = BlobChecksum("sha256:9f86d0"))
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE, checksum = null)

        val info = blobs.markUploaded(scope, ledger, descriptor.blobId)

        // Plenty of storages cannot answer. "Cannot say" is not "disagrees", and treating it as a
        // refusal would make checksums unusable against exactly those deployments.
        assertEquals(BlobState.READY, info.state)
    }

    @Test
    fun `a stored object with the wrong checksum is not accepted`() {
        val descriptor = descriptor(checksum = BlobChecksum("sha256:9f86d0"))
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE, checksum = BlobChecksum("sha256:00"))

        assertFailsWith<BlobNotStoredException> { blobs.markUploaded(scope, ledger, descriptor.blobId) }
    }

    @Test
    fun `the host is told exactly once that a blob became usable`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE)

        blobs.markUploaded(scope, ledger, descriptor.blobId)
        blobs.markUploaded(scope, ledger, descriptor.blobId)

        // Three paths lead here — the device, the storage's own notification, and the sweep — and
        // the host's thumbnailer must run once however many of them arrive.
        assertEquals(1, accepted.size)
        assertEquals(descriptor.blobId, accepted.single().blobId)
    }

    @Test
    fun `a blob whose bytes have not landed is answered with its state and no ticket`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)

        val download = blobs.download(scope, ledger, descriptor.blobId)

        // The ordinary condition of an attachment whose record arrived ahead of its bytes. A client
        // answers it by waiting, so it must not arrive as a failure.
        assertEquals(BlobState.PENDING, download.blob.state)
        assertNull(download.download)
    }

    @Test
    fun `a usable blob is answered with permission to fetch it`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE)
        blobs.markUploaded(scope, ledger, descriptor.blobId)

        val download = blobs.download(scope, ledger, descriptor.blobId)

        assertEquals(BlobState.READY, download.blob.state)
        assertEquals(BlobTicketMethod.GET, assertNotNull(download.download).method)
    }

    @Test
    fun `a blob nobody registered here is unknown`() {
        assertFailsWith<UnknownBlobException> { blobs.download(scope, ledger, BlobId(Uuid.random())) }
    }

    @Test
    fun `one identifier cannot come to name two different files`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)
        storage.put(storage.keyOf(scope, ledger, descriptor.blobId), size = SIZE)
        blobs.markUploaded(scope, ledger, descriptor.blobId)

        // A blob is immutable. Accepting this would change what every device that already fetched it
        // is holding, without any of them ever being told.
        assertFailsWith<BlobConflictException> {
            blobs.register(scope, ledger, descriptor.copy(size = SIZE + 10))
        }
    }

    @Test
    fun `re-declaring a blob nothing has accepted replaces what it was said to be`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)

        val second = blobs.register(scope, ledger, descriptor.copy(size = SIZE + 10))

        // The user picked a different file under the same identifier. Nothing has accepted the old
        // declaration and nobody can have fetched it, so keeping it would strand the blob between a
        // declaration nobody meant and bytes that can never match it.
        assertEquals(SIZE + 10, second.blob.size)
    }

    @Test
    fun `a blob of another collection is not reachable through this one`() {
        val descriptor = descriptor()
        blobs.register(scope, ledger, descriptor)

        assertFailsWith<UnknownBlobException> {
            blobs.download(scope, PostgresFixture.archive, descriptor.blobId)
        }
    }

    @Test
    fun `a deployment that serves files publishes their limits`() {
        val limits =
            PostgresFixture
                .module(
                    clock = clock,
                    blobs = BlobConfig(maxBlobBytes = MAX_BLOB_BYTES, maxBlobsPerEntity = 4),
                    blobStorage = storage,
                ).service
                .limits()

        val published = assertNotNull(limits.blobs)
        assertEquals(MAX_BLOB_BYTES, published.maxBlobBytes)
        assertEquals(4, published.maxBlobsPerEntity)
    }

    @Test
    fun `a deployment that serves no files says so rather than publishing zeroes`() {
        val limits = PostgresFixture.module(clock = clock).service.limits()

        // An application configured for files against a server with no storage behind it has a
        // misconfiguration worth learning about at start-up. Zeroed limits would instead present it
        // as every file being too large, which is a different and much more confusing bug.
        assertNull(limits.blobs)
        assertNull(PostgresFixture.module(clock = clock).blobs)
    }

    @Test
    fun `limits for files nobody can store are refused at assembly`() {
        // The two halves are one decision. Either of them alone surfaces as a failure on a user's
        // first attachment, which is a long way from the line that was actually wrong.
        assertFailsWith<IllegalArgumentException> {
            PostgresFixture.module(clock = clock, blobs = BlobConfig())
        }
        assertFailsWith<IllegalArgumentException> {
            PostgresFixture.module(clock = clock, blobStorage = storage)
        }
    }

    @Test
    fun `the host can read what the module knows about a file`() {
        val module =
            PostgresFixture.module(
                clock = clock,
                blobs = BlobConfig(maxBlobBytes = MAX_BLOB_BYTES),
                blobStorage = storage,
            )
        val service = assertNotNull(module.blobs)
        val first = descriptor()
        val second = descriptor()
        service.register(scope, ledger, first)
        service.register(scope, ledger, second)
        storage.put(storage.keyOf(scope, ledger, first.blobId), SIZE)
        service.markUploaded(scope, ledger, first.blobId)

        val one = assertNotNull(module.queries.blob(scope, ledger, first.blobId))
        val page = module.queries.blobs(scope, ledger, page = null, limit = 10)

        // What no client can see: the key the storage chose, and a file registered but never sent.
        assertEquals(BlobState.READY, one.state)
        assertEquals(storage.keyOf(scope, ledger, first.blobId), one.storageKey)
        assertEquals(2, page.blobs.size)
        assertTrue(page.blobs.any { it.state == BlobState.PENDING })
        assertNull(page.nextPage)
    }

    @Test
    fun `reading files pages by identifier`() {
        val module =
            PostgresFixture.module(
                clock = clock,
                blobs = BlobConfig(maxBlobBytes = MAX_BLOB_BYTES),
                blobStorage = storage,
            )
        val service = assertNotNull(module.blobs)
        repeat(3) { service.register(scope, ledger, descriptor()) }

        val first = module.queries.blobs(scope, ledger, page = null, limit = 2)
        val second = module.queries.blobs(scope, ledger, page = first.nextPage, limit = 2)

        assertEquals(2, first.blobs.size)
        assertEquals(1, second.blobs.size)
        assertNull(second.nextPage)
        // Keyset rather than offset, so a file registered between the two reads cannot make one
        // disappear from the second page.
        assertTrue(first.blobs.none { it.blobId in second.blobs.map { other -> other.blobId } })
    }

    private fun descriptor(
        size: Long = SIZE,
        checksum: BlobChecksum? = null,
    ): BlobDescriptor =
        BlobDescriptor(
            blobId = BlobId(Uuid.random()),
            contentType = BlobContentType("image/jpeg"),
            size = size,
            checksum = checksum,
        )

    private companion object {
        const val SIZE = 2_418_123L
        const val MAX_BLOB_BYTES = 4L * 1024 * 1024
    }
}
