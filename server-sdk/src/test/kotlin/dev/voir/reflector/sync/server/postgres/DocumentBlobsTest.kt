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
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.DocumentPage
import dev.voir.reflector.sync.server.StoredDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the host reads back about the files a stored document references.
 *
 * The case this exists for is a host that copies documents out — a backup — and pushes them back
 * later: the references are the client's declaration and cannot be derived from the body, so unless
 * the read hands them over, a restored document would reference nothing and its files would be
 * collected. Both reads are covered, since a backup pages through `documents` while a screen that
 * shows one record calls `document`.
 */
class DocumentBlobsTest {
    private val statements = AtomicInteger()
    private val storage = FakeBlobStorage()
    private val module = module(PostgresFixture.database)
    private val scope = PostgresFixture.scope
    private val ledger = PostgresFixture.ledger
    private val wallet = PostgresFixture.wallet
    private val client = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))

    // Fixed identifiers whose order is known, so that the order of what is read back is asserted
    // rather than assumed: wherever both are declared, they are declared out of order.
    private val first = BlobId(Uuid.parse("00000000-0000-7000-8000-b00000000001"))
    private val second = BlobId(Uuid.parse("00000000-0000-7000-8000-b00000000002"))

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    @Test
    fun `a document reads back the blobs it was pushed with, ordered by identifier`() {
        register(listOf(first, second))

        push(upsert(1, blobs = listOf(second, first)))

        assertEquals(listOf(first, second), document(1).blobs)
        assertEquals(listOf(first, second), page().single().blobs)
    }

    @Test
    fun `a later declaration replaces what is read back`() {
        register(listOf(first, second))
        push(upsert(1, blobs = listOf(second, first)))

        push(upsert(1, base = EntityVersion("1"), blobs = listOf(first)))

        assertEquals(listOf(first), document(1).blobs)
        assertEquals(listOf(first), page().single().blobs)
    }

    @Test
    fun `an upsert that declares nothing keeps the references already stored`() {
        register(listOf(first, second))
        push(upsert(1, blobs = listOf(second, first)))

        push(upsert(1, base = EntityVersion("1"), blobs = null, data = body("Card")))

        val stored = document(1)
        assertEquals(body("Card"), stored.data, "the write itself landed")
        assertEquals(listOf(first, second), stored.blobs)
        assertEquals(listOf(first, second), page().single().blobs)
    }

    @Test
    fun `a document that references nothing reads back empty`() {
        register(listOf(first))
        push(upsert(1, blobs = null), upsert(2, blobs = emptyList()), upsert(3, blobs = listOf(first)))

        assertEquals(emptyList(), document(1).blobs)
        assertEquals(emptyList(), document(2).blobs)
        assertEquals(
            listOf(emptyList(), emptyList(), listOf(first)),
            page().map { it.blobs },
            "each document of a page gets its own references and nobody else's",
        )
    }

    @Test
    fun `a page reads the references of all its documents in one statement`() {
        val counted = module(PostgresFixture.countingDatabase(statements))
        register(listOf(first, second))
        push(
            upsert(1, blobs = listOf(first)),
            upsert(2, blobs = listOf(second)),
            upsert(3, blobs = listOf(first, second)),
            upsert(4, blobs = listOf(second)),
        )

        // A first read on a fresh connection may ask the database about itself; that is not what
        // this test is about, so it is paid before anything is counted.
        counted.queries.documents(scope, ledger, wallet, page = null, limit = 1)

        val (one, forOne) = statementsOf { counted.queries.documents(scope, ledger, wallet, page = null, limit = 1) }
        val (four, forFour) = statementsOf { counted.queries.documents(scope, ledger, wallet, page = null, limit = 4) }

        assertEquals(1, one.documents.size)
        assertEquals(listOf(listOf(first), listOf(second), listOf(first, second), listOf(second)), four.documents.map { it.blobs })
        assertTrue(forOne > 0, "the counter has to see the reads at all for the comparison to mean anything")
        assertEquals(forOne, forFour, "a page of four documents must cost what a page of one does")
    }

    private fun module(database: Database) =
        PostgresFixture.module(
            database = database,
            clock = MutableClock(Instant.fromEpochMilliseconds(1_700_000_000_000)),
            blobs = BlobConfig(),
            blobStorage = storage,
        )

    private fun statementsOf(read: () -> DocumentPage): Pair<DocumentPage, Int> {
        val before = statements.get()
        val result = read()
        return result to statements.get() - before
    }

    private fun register(ids: List<BlobId>) {
        val service = assertNotNull(module.blobs)
        ids.forEach { id ->
            service.register(scope, ledger, BlobDescriptor(id, BlobContentType("image/jpeg"), SIZE))
            storage.put(storage.keyOf(scope, ledger, id), SIZE)
            service.markUploaded(scope, ledger, id)
        }
    }

    private fun document(index: Int): StoredDocument =
        assertNotNull(module.queries.document(scope, ledger, wallet, entity(index)))

    private fun page(): List<StoredDocument> =
        module.queries
            .documents(scope, ledger, wallet, page = null, limit = 100)
            .documents

    private fun push(vararg ops: PushOperation) {
        val result =
            module.service.push(scope, ledger, PushRequest(client, listOf(PushGroup(GroupId(Uuid.random()), ops.toList()))))
        assertIs<PushGroupResult.Applied>(result.results.single())
    }

    private fun upsert(
        index: Int,
        base: EntityVersion? = null,
        blobs: List<BlobId>?,
        data: JsonObject = body("Cash"),
    ) = PushOperation.Upsert(wallet, entity(index), base, data, blobs)

    private fun body(title: String) = buildJsonObject { put("title", title) }

    private fun entity(index: Int) = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private companion object {
        const val SIZE = 2_418_123L
    }
}
