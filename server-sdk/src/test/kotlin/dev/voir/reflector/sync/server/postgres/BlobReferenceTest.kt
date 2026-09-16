package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.server.BlobConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a push does to the references between documents and files.
 *
 * The subject throughout is when a blob becomes garbage, because that is the only thing these rows
 * decide and getting it wrong deletes a file a document still names. Two cases carry most of the
 * weight: a client that says nothing about blobs must not be read as saying there are none, and a
 * blob whose bytes are still on their way must not be treated as a blob that does not exist.
 */
class BlobReferenceTest {
    private val clock = MutableClock(Instant.fromEpochMilliseconds(1_700_000_000_000))
    private val storage = FakeBlobStorage()
    private val module =
        PostgresFixture.module(
            clock = clock,
            blobs = BlobConfig(maxBlobsPerEntity = MAX_PER_ENTITY),
            blobStorage = storage,
        )
    private val service = module.service
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
    fun `a document that names a blob stops it being garbage`() {
        val blob = registered()

        push(group(upsert(1, blobs = listOf(blob))))

        assertEquals(listOf(blob), referencesOf(1))
        assertNull(unreferencedSince(blob), "a blob a document names is not a candidate for collection")
    }

    @Test
    fun `a blob nothing names again becomes garbage from that moment`() {
        val blob = registered()
        push(group(upsert(1, blobs = listOf(blob))))
        clock.instant = clock.instant.plus(ONE_HOUR)

        push(group(upsert(1, base = EntityVersion("1"), blobs = emptyList())))

        assertEquals(emptyList(), referencesOf(1))
        assertEquals(clock.instant, unreferencedSince(blob), "the clock starts when the last document let go")
    }

    @Test
    fun `a client that says nothing about blobs is not read as saying there are none`() {
        val blob = registered()
        push(group(upsert(1, blobs = listOf(blob))))

        // What an installation older than files sends. Reading its silence as an empty set would
        // collect files that are still in use, which is the one mistake this whole field exists to
        // make impossible.
        push(group(upsert(1, base = EntityVersion("1"), blobs = null)))

        assertEquals(listOf(blob), referencesOf(1))
        assertNull(unreferencedSince(blob))
    }

    @Test
    fun `deleting the document lets go of what it named`() {
        val blob = registered()
        push(group(upsert(1, blobs = listOf(blob))))

        push(group(PushOperation.Delete(wallet, entity(1), EntityVersion("1"))))

        assertEquals(emptyList(), referencesOf(1))
        assertNotNull(unreferencedSince(blob))
    }

    @Test
    fun `a blob two documents name survives one of them letting go`() {
        val blob = registered()
        push(group(upsert(1, blobs = listOf(blob)), upsert(2, blobs = listOf(blob))))

        push(group(upsert(1, base = EntityVersion("1"), blobs = emptyList())))

        assertEquals(listOf(blob), referencesOf(2))
        assertNull(unreferencedSince(blob), "the other document still names it")
    }

    @Test
    fun `replacing the file lets go of the old one and takes up the new`() {
        val old = registered()
        val new = registered()
        push(group(upsert(1, blobs = listOf(old))))

        push(group(upsert(1, base = EntityVersion("1"), blobs = listOf(new))))

        assertEquals(listOf(new), referencesOf(1))
        assertNotNull(unreferencedSince(old))
        assertNull(unreferencedSince(new))
    }

    @Test
    fun `a record may be published while its file is still on its way`() {
        // Registered and not yet accepted, which is the ordinary state of an attachment under the
        // default binding. Refusing here would make publishing a record ahead of its file impossible,
        // and that is the behaviour the whole design rests on.
        val uploading = registered(accept = false)

        val result = push(group(upsert(1, blobs = listOf(uploading))))

        assertIs<PushGroupResult.Applied>(result.results.single())
        assertEquals(listOf(uploading), referencesOf(1))
        assertNull(unreferencedSince(uploading), "a reference counts from the push, not from the upload")
    }

    @Test
    fun `a document that names a blob nobody registered is refused whole`() {
        val known = registered()
        val invented = BlobId(Uuid.random())

        val result = push(group(upsert(1, blobs = listOf(known)), upsert(2, blobs = listOf(invented))))

        val rejected = assertIs<PushGroupResult.Rejected>(result.results.single())
        assertEquals(RejectCode.BLOB_MISSING, rejected.error.code)
        assertEquals(entity(2), rejected.error.id, "the refusal names the document that cannot be satisfied")
        // The group is atomic: the half of it that was satisfiable must not have landed either, so
        // the blob it named is still waiting for a document and still a candidate for collection.
        assertTrue(service.changes(scope, ledger, null, 10).batches.isEmpty())
        assertEquals(emptyList(), referencesOf(1))
        assertNotNull(unreferencedSince(known))
    }

    @Test
    fun `a document that names more files than allowed is refused`() {
        val tooMany = List(MAX_PER_ENTITY + 1) { registered() }

        val result = push(group(upsert(1, blobs = tooMany)))

        val rejected = assertIs<PushGroupResult.Rejected>(result.results.single())
        assertEquals(RejectCode.TOO_LARGE, rejected.error.code)
    }

    private fun registered(accept: Boolean = true): BlobId {
        val descriptor =
            BlobDescriptor(
                blobId = BlobId(Uuid.random()),
                contentType = BlobContentType("image/jpeg"),
                size = SIZE,
            )
        blobs.register(scope, ledger, descriptor)
        if (accept) {
            storage.put(storage.keyOf(scope, ledger, descriptor.blobId), SIZE)
            blobs.markUploaded(scope, ledger, descriptor.blobId)
        }
        return descriptor.blobId
    }

    private fun referencesOf(index: Int): List<BlobId> =
        transaction(PostgresFixture.database) {
            BlobRefsTable
                .selectAll()
                .where { BlobRefsTable.entityId eq entity(index).value }
                .map { BlobId(it[BlobRefsTable.blobId]) }
        }

    private fun unreferencedSince(blob: BlobId): Instant? =
        transaction(PostgresFixture.database) {
            BlobsTable
                .selectAll()
                .where { BlobsTable.blobId eq blob.value }
                .single()[BlobsTable.unreferencedSince]
        }

    private fun entity(index: Int) = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun group(vararg ops: PushOperation) = PushGroup(GroupId(Uuid.random()), ops.toList())

    private fun push(group: PushGroup) = service.push(scope, ledger, PushRequest(client, listOf(group)))

    private fun upsert(
        index: Int,
        base: EntityVersion? = null,
        blobs: List<BlobId>?,
        data: JsonObject = buildJsonObject { put("title", "Cash") },
    ) = PushOperation.Upsert(wallet, entity(index), base, data, blobs)

    private companion object {
        const val SIZE = 2_418_123L
        const val MAX_PER_ENTITY = 3
        val ONE_HOUR = kotlin.time.Duration.parse("1h")
    }
}
