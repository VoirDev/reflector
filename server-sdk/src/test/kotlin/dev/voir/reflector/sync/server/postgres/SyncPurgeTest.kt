package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.CollectionResetException
import dev.voir.reflector.sync.server.PurgeReport
import dev.voir.reflector.sync.server.SyncMetricEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SyncPurgeTest {
    private val clock = MutableClock()
    private val metrics = RecordingMetrics()
    private val module = PostgresFixture.module(clock, metrics = metrics)
    private val service = module.service
    private val maintenance = module.maintenance
    private val scope = PostgresFixture.scope
    private val other = ScopeId("user-2")
    private val ledger = PostgresFixture.ledger
    private val archive = PostgresFixture.archive
    private val wallet = PostgresFixture.wallet
    private val client = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    private fun entity(index: Int) = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun group(vararg ops: PushOperation) = PushGroup(GroupId(Uuid.random()), ops.toList())

    private fun push(
        group: PushGroup,
        scope: ScopeId = this.scope,
        collection: CollectionId = ledger,
    ) = service.push(scope, collection, PushRequest(client, listOf(group)))

    private fun upsert(
        index: Int,
        base: EntityVersion? = null,
        data: JsonObject,
    ) = PushOperation.Upsert(wallet, entity(index), base, data)

    private fun body(title: String) = buildJsonObject { put("title", title) }

    /** Rows left in each of the module's tables, in the order they are declared in the schema. */
    private fun storedRows(): Map<String, Long> =
        transaction(PostgresFixture.database) {
            mapOf(
                "collections" to CollectionsTable.selectAll().count(),
                "batches" to BatchesTable.selectAll().count(),
                "changes" to ChangesTable.selectAll().count(),
                "entities" to EntitiesTable.selectAll().count(),
                "pushResults" to PushResultsTable.selectAll().count(),
            )
        }

    @Test
    fun `purging a collection leaves no row of it anywhere in the schema`() {
        push(group(upsert(1, data = body("Cash"))))
        push(group(upsert(2, data = body("Card"))))
        // A tombstone as well as a live entity: the protocol's own deletion is the thing a purge has
        // to go beyond, and a purge that left tombstones behind would look correct from the outside.
        push(group(PushOperation.Delete(wallet, entity(1), EntityVersion("1"))))

        val report = maintenance.purgeCollection(scope, ledger)

        assertEquals(
            PurgeReport(collections = 1, batches = 3, changes = 3, entities = 2, pushResults = 3),
            report,
        )
        assertEquals(
            mapOf("collections" to 0L, "batches" to 0L, "changes" to 0L, "entities" to 0L, "pushResults" to 0L),
            storedRows(),
            "a purge is physical: nothing of the collection may survive it",
        )
        assertNull(module.queries.document(scope, ledger, wallet, entity(2)))
        assertTrue(service.changes(scope, ledger, cursor = null, limit = 10).batches.isEmpty())
        assertTrue(service.snapshot(scope, ledger, page = null, limit = 10).items.isEmpty())
    }

    @Test
    fun `purging a scope takes every collection it has and leaves other scopes alone`() {
        push(group(upsert(1, data = body("Cash"))))
        push(group(upsert(2, data = body("Archived"))), collection = archive)
        push(group(upsert(3, data = body("Somebody else's"))), scope = other)

        val report = maintenance.purgeScope(scope)

        assertEquals(2, report.collections, "both collections of the scope have to go")
        assertEquals(2, report.batches)
        assertEquals(2, report.entities)
        assertNull(module.queries.document(scope, ledger, wallet, entity(1)))
        assertNull(module.queries.document(scope, archive, wallet, entity(2)))
        assertNotNull(
            module.queries.document(other, ledger, wallet, entity(3)),
            "a purge is bounded by its scope; another tenant's data is not the module's to remove",
        )
    }

    @Test
    fun `purging a collection leaves the other collections of the same scope untouched`() {
        push(group(upsert(1, data = body("Cash"))))
        push(group(upsert(2, data = body("Archived"))), collection = archive)

        maintenance.purgeCollection(scope, ledger)

        assertNull(module.queries.document(scope, ledger, wallet, entity(1)))
        assertNotNull(module.queries.document(scope, archive, wallet, entity(2)))
    }

    @Test
    fun `a cursor from before a purge is refused instead of being answered with an empty page`() {
        push(group(upsert(1, data = body("Cash"))))
        push(group(upsert(2, data = body("Card"))))
        val stale = assertNotNull(service.changes(scope, ledger, cursor = null, limit = 10).nextCursor)

        maintenance.purgeCollection(scope, ledger)
        // Enough new batches that the old cursor is no longer beyond the head: the position alone
        // says nothing, and only the incarnation the cursor names gives the collection away.
        repeat(3) { index -> push(group(upsert(10 + index, data = body("After the purge")))) }

        assertFailsWith<CollectionResetException> { service.changes(scope, ledger, stale, 10) }
        assertEquals(
            "1",
            service
                .changes(scope, ledger, cursor = null, limit = 10)
                .batches
                .first()
                .seq.value,
            "a purged collection starts counting again",
        )
    }

    @Test
    fun `a push naming the purged incarnation is refused whole and writes nothing`() {
        push(group(upsert(1, data = body("Cash"))))
        val epoch = service.changes(scope, ledger, cursor = null, limit = 10).epoch

        maintenance.purgeCollection(scope, ledger)

        assertFailsWith<CollectionResetException> {
            service.push(
                scope,
                ledger,
                PushRequest(client, listOf(group(upsert(1, data = body("Back from the dead")))), epoch),
            )
        }
        assertNull(
            module.queries.document(scope, ledger, wallet, entity(1)),
            "a client that survived the purge must not put its copy back",
        )
        assertTrue(
            transaction(PostgresFixture.database) { CollectionsTable.selectAll().empty() },
            "a refused push must not bring the erased collection's row back either",
        )
    }

    @Test
    fun `a client that claims no incarnation is served and told which one it reached`() {
        val response = push(group(upsert(1, data = body("Cash"))))

        assertIs<PushGroupResult.Applied>(response.results.single())
        assertEquals(
            response.epoch,
            service.changes(scope, ledger, cursor = null, limit = 10).epoch,
            "the epoch a push reports has to be the one its reader will see",
        )
    }

    @Test
    fun `a group re-sent after a purge is applied again because its stored answer went with it`() {
        val group = group(upsert(1, data = body("Cash")))
        push(group)

        maintenance.purgeCollection(scope, ledger)
        // Sent as a client that claims nothing, which is what one looks like after it has rebuilt.
        val response = push(group)

        assertIs<PushGroupResult.Applied>(response.results.single())
        assertEquals(
            body("Cash"),
            assertNotNull(module.queries.document(scope, ledger, wallet, entity(1))).data,
            "idempotency is history, and a purge removes history: the client's copy comes back",
        )
    }

    @Test
    fun `purging something that was never written removes nothing and is not an error`() {
        val report = maintenance.purgeCollection(scope, ledger)

        assertEquals(PurgeReport.Empty, report)
        assertTrue(report.isEmpty)
    }

    @Test
    fun `a purge is reported once per collection with what it removed`() {
        push(group(upsert(1, data = body("Cash"))))
        push(group(upsert(2, data = body("Archived"))), collection = archive)
        metrics.events.clear()

        maintenance.purgeScope(scope)

        val purged = metrics.events.filterIsInstance<SyncMetricEvent.CollectionPurged>()
        assertEquals(setOf(ledger, archive), purged.map { it.collection }.toSet())
        assertTrue(purged.all { it.scope == scope && it.removedEntities == 1 && it.removedBatches == 1 })
    }
}
