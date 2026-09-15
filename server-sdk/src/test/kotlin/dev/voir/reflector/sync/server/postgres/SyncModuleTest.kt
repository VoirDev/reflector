package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.server.CursorTooOldException
import dev.voir.reflector.sync.server.SyncCommitListener
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

class SyncModuleTest {
    private val clock = MutableClock()
    private val module = PostgresFixture.module(clock)
    private val service = module.service
    private val scope = PostgresFixture.scope
    private val ledger = PostgresFixture.ledger
    private val wallet = PostgresFixture.wallet
    private val client = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    private fun entity(index: Int) = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun group(vararg ops: PushOperation) = PushGroup(GroupId(Uuid.random()), ops.toList())

    private fun push(group: PushGroup) = service.push(scope, ledger, PushRequest(client, listOf(group)))

    private fun upsert(
        index: Int,
        base: EntityVersion? = null,
        data: JsonObject,
    ) = PushOperation.Upsert(wallet, entity(index), base, data)

    private fun body(title: String) = buildJsonObject { put("title", title) }

    @Test
    fun `an applied group becomes one batch in the log`() {
        val response = push(group(upsert(1, data = body("Cash"))))

        val applied = assertIs<PushGroupResult.Applied>(response.results.single())
        assertEquals(EntityVersion("1"), applied.versions.single().version)

        val page = service.changes(scope, ledger, cursor = null, limit = 10)
        val batch = page.batches.single()
        assertEquals(client, batch.originClientId, "the client has to recognise its own change")
        val operation = assertIs<RemoteOperation.Upsert>(batch.ops.single())
        assertEquals(body("Cash"), operation.data)
        assertEquals(batch.cursor, assertNotNull(page.nextCursor), "the page ends where its last batch does")
        assertEquals(
            page.epoch,
            module.service.head(scope, ledger).let { service.changes(scope, ledger, it, 10).epoch },
        )
    }

    @Test
    fun `re-sending a group returns the stored answer instead of applying it twice`() {
        val group = group(upsert(1, data = body("Cash")))

        val first = push(group)
        val second = push(group)

        assertEquals(first.results, second.results, "a client that lost the answer must get the same one")
        assertEquals(1, service.changes(scope, ledger, null, 10).batches.size, "the work must not be repeated")
    }

    @Test
    fun `a patch merges by top-level key and an explicit null clears a field`() {
        push(
            group(
                upsert(
                    1,
                    data =
                        buildJsonObject {
                            put("title", "Cash")
                            put("currency", "EUR")
                        },
                ),
            ),
        )
        val version = EntityVersion("1")

        push(
            group(
                upsert(
                    1,
                    base = version,
                    data =
                        buildJsonObject {
                            put("currency", JsonNull)
                            put("comment", "kept")
                        },
                ),
            ),
        )

        val document = assertNotNull(module.queries.document(scope, ledger, wallet, entity(1))).data
        assertEquals("Cash", document["title"]?.toString()?.trim('"'), "an untouched key keeps its value")
        assertEquals(JsonNull, document["currency"], "an explicit null clears the field rather than being dropped")
        assertEquals("kept", document["comment"]?.toString()?.trim('"'))
    }

    @Test
    fun `a stale base version is refused and nothing of the group is applied`() {
        push(group(upsert(1, data = body("Cash"))))

        val response =
            push(
                group(
                    upsert(1, base = EntityVersion("999"), data = body("Renamed")),
                    upsert(2, data = body("Second")),
                ),
            )

        val conflict = assertIs<PushGroupResult.Conflict>(response.results.single())
        assertEquals(EntityVersion("1"), conflict.conflicts.single().serverVersion)
        assertEquals(body("Cash"), conflict.conflicts.single().data)
        assertNull(
            module.queries.document(scope, ledger, wallet, entity(2)),
            "a group is atomic: the non-conflicting half must not slip through",
        )
        assertEquals(1, service.changes(scope, ledger, null, 10).batches.size)
    }

    @Test
    fun `an unregistered entity type is refused`() {
        val response =
            service.push(
                scope,
                ledger,
                PushRequest(
                    client,
                    listOf(
                        PushGroup(
                            GroupId(Uuid.random()),
                            listOf(PushOperation.Upsert(EntityType("invoice"), entity(1), null, body("x"))),
                        ),
                    ),
                ),
            )

        val rejected = assertIs<PushGroupResult.Rejected>(response.results.single())
        assertEquals(RejectCode.UNKNOWN_ENTITY_TYPE, rejected.error.code)
    }

    @Test
    fun `a document larger than the collection allows is refused`() {
        val oversized = buildJsonObject { put("title", "x".repeat(PostgresFixture.MAX_DOCUMENT_BYTES)) }

        val response = push(group(upsert(1, data = oversized)))

        val rejected = assertIs<PushGroupResult.Rejected>(response.results.single())
        assertEquals(RejectCode.TOO_LARGE, rejected.error.code)
    }

    @Test
    fun `a deletion leaves the log with a removal and takes the entity out of snapshots`() {
        push(group(upsert(1, data = body("Cash"))))

        push(group(PushOperation.Delete(wallet, entity(1), EntityVersion("1"))))

        val removal =
            service
                .changes(scope, ledger, null, 10)
                .batches
                .last()
                .ops
                .single()
        assertIs<RemoteOperation.Delete>(removal)
        assertTrue(service.snapshot(scope, ledger, page = null, limit = 10).items.isEmpty())
        assertNull(module.queries.document(scope, ledger, wallet, entity(1)))
    }

    @Test
    fun `a snapshot pages by key and every page carries the same cursor`() {
        repeat(5) { index -> push(group(upsert(index, data = body("Wallet $index")))) }
        val head = service.head(scope, ledger)

        val first = service.snapshot(scope, ledger, page = null, limit = 2)
        val second = service.snapshot(scope, ledger, page = first.nextPage, limit = 2)
        val third = service.snapshot(scope, ledger, page = second.nextPage, limit = 2)

        assertEquals(head, first.cursor)
        assertEquals(head, second.cursor, "a later page must not hand out a newer position")
        assertEquals(head, third.cursor)
        val seen = (first.items + second.items + third.items).map { it.id }
        assertEquals(seen.size, seen.toSet().size, "keyset paging must not repeat an entity")
        assertEquals(5, seen.size)
        assertTrue(!third.hasMore)
    }

    @Test
    fun `a cursor below the retention floor is refused instead of served partially`() {
        push(group(upsert(1, data = body("Cash"))))
        val stale = assertNotNull(service.changes(scope, ledger, null, 10).nextCursor)
        push(group(upsert(2, data = body("Card"))))

        // Time moves past the window, and the sweep raises the floor before deleting anything.
        clock.instant = clock.instant.plus(31.days)
        PostgresFixture.module(clock, retention = 30.days).maintenance.trim()

        assertFailsWith<CursorTooOldException> { service.changes(scope, ledger, stale, 10) }
    }

    @Test
    fun `a commit listener sees every applied batch and only after it is durable`() {
        val seen = mutableListOf<String>()
        val listener = SyncCommitListener { _, _, seq -> seen += seq.value }
        val listening = PostgresFixture.module(clock, commitListeners = listOf(listener))

        listening.service.push(scope, ledger, PushRequest(client, listOf(group(upsert(1, data = body("Cash"))))))

        assertEquals(listOf("1"), seen)
        assertEquals(
            1,
            listening.service
                .changes(scope, ledger, null, 10)
                .batches.size,
        )
    }
}
