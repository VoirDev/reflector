package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.CursorTooOldException
import dev.voir.reflector.sync.server.SyncCommitListener
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

class SyncLogTest {
    private val clock = MutableClock()
    private val logs = RecordingSyncLog()
    private val scope = PostgresFixture.scope
    private val ledger = PostgresFixture.ledger
    private val wallet = PostgresFixture.wallet
    private val client = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    private fun entity(index: Int) = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun pushOne(
        module: dev.voir.reflector.sync.server.postgres.SyncModule,
        index: Int = 1,
    ) = module.service.push(
        scope,
        ledger,
        PushRequest(
            client,
            listOf(
                PushGroup(
                    GroupId(Uuid.random()),
                    listOf(PushOperation.Upsert(wallet, entity(index), null, buildJsonObject { put("title", "Cash") })),
                ),
            ),
        ),
    )

    @Test
    fun `a commit listener that throws is reported, and the batch stays committed`() {
        val module =
            PostgresFixture.module(
                clock = clock,
                commitListeners = listOf(SyncCommitListener { _, _, _ -> error("the event bus is down") }),
                log = logs,
            )

        val response = pushOne(module)

        // The contract this listener has always documented and nothing has ever honoured. What the
        // failure costs is the notification, so every client of the scope falls back on its own
        // poll — which looks like synchronisation being slow rather than like anything being broken.
        val reported = logs.of(SyncLogEvent.COMMIT_LISTENER_FAILED).single()
        assertEquals(SyncLogLevel.ERROR, reported.level)
        assertEquals(scope, reported.scope)
        assertEquals(ledger, reported.collection)
        assertEquals("the event bus is down", reported.cause?.message)
        assertTrue(response.results.single() is dev.voir.reflector.sync.protocol.push.PushGroupResult.Applied)
        assertEquals(
            1,
            module.service
                .changes(scope, ledger, null, 10)
                .batches.size,
            "the batch is durable",
        )
    }

    @Test
    fun `a refused cursor says how far behind the window it was`() {
        val module = PostgresFixture.module(clock = clock, retention = 1.days, log = logs)
        pushOne(module)
        // Taken after the first batch and before the second, so that the sweep leaves a batch this
        // cursor has not seen: a cursor at the floor is still served, and only one behind it is not.
        val stale = assertNotNull(module.service.changes(scope, ledger, null, 10).nextCursor)
        pushOne(module, index = 2)
        clock.instant += 2.days
        pushOne(module, index = 3)
        module.maintenance.trim()

        assertFailsWith<CursorTooOldException> { module.service.changes(scope, ledger, stale, 10) }

        // Each of these is a whole-collection transfer about to happen; how far behind says whether
        // the window is merely short for one device or too short for the population.
        val refused = logs.of(SyncLogEvent.CURSOR_REFUSED).single()
        assertEquals(SyncLogLevel.WARN, refused.level)
        assertEquals(ledger, refused.collection)
        assertTrue(refused.context.getValue("behindFloor").toLong() > 0)
        assertEquals(scope, refused.scope)
    }

    @Test
    fun `a repeated group is reported as answered from the stored result`() {
        val module = PostgresFixture.module(clock = clock, log = logs)
        val group =
            PushGroup(
                GroupId(Uuid.random()),
                listOf(PushOperation.Upsert(wallet, entity(1), null, buildJsonObject { put("title", "Cash") })),
            )
        val request = PushRequest(client, listOf(group))
        module.service.push(scope, ledger, request)

        module.service.push(scope, ledger, request)

        // Invisible everywhere else, and the hardest thing to understand from the client's side: it
        // believes it is sending new content and is answered about content it sent before.
        assertEquals(1, logs.of(SyncLogEvent.GROUP_APPLIED).size)
        val repeated = logs.of(SyncLogEvent.GROUP_REPEATED).single()
        assertEquals("none", repeated.context["lockHeldMs"], "a repeat takes no lock and does no work")
    }

    @Test
    fun `a page token this module did not produce is reported before it is refused`() {
        val module = PostgresFixture.module(clock = clock, log = logs)

        assertFailsWith<IllegalArgumentException> {
            module.service.snapshot(scope, ledger, PageToken("not-a-token"), 10)
        }

        val reported = logs.of(SyncLogEvent.PAGE_TOKEN_INVALID).single()
        assertEquals(SyncLogLevel.WARN, reported.level)
        assertTrue("not-a-token".startsWith(reported.context.getValue("token")))
    }
}
