package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.server.PushMetricOutcome
import dev.voir.reflector.sync.server.SyncMetricEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * What the module tells a host about itself.
 *
 * These are not tests of arithmetic: they check that the numbers a host would build a dashboard on
 * describe the work that was actually done — a repeat is not counted as a write, a lock that was
 * never taken is not reported as held, and a client's distance from the head is measured against
 * the head it asked at.
 */
class SyncMetricsTest {
    private val clock = MutableClock()
    private val metrics = RecordingMetrics()
    private val module = PostgresFixture.module(clock, metrics = metrics)
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

    private fun upsert(index: Int) =
        PushOperation.Upsert(wallet, entity(index), baseVersion = null, data = buildJsonObject { put("title", "Cash") })

    private inline fun <reified T : SyncMetricEvent> events(): List<T> = metrics.events.filterIsInstance<T>()

    @Test
    fun `an applied group reports what it carried and that the counter lock was held`() {
        push(group(upsert(1), upsert(2)))

        val served = events<SyncMetricEvent.PushGroupServed>().single()
        assertEquals(2, served.operations)
        assertEquals(PushMetricOutcome.APPLIED, served.outcome)
        assertEquals(scope, served.scope)
        assertEquals(ledger, served.collection)
        assertNotNull(served.lockHeld, "a group that wrote a batch held the lock to the commit")
    }

    @Test
    fun `a repeated group is counted as a repeat and holds no lock`() {
        val group = group(upsert(1))
        push(group)
        push(group)

        val outcomes = events<SyncMetricEvent.PushGroupServed>().map { it.outcome }
        assertEquals(listOf(PushMetricOutcome.APPLIED, PushMetricOutcome.REPEATED), outcomes)
        assertNull(
            events<SyncMetricEvent.PushGroupServed>().last().lockHeld,
            "an answer served from the stored result does no work and takes no lock",
        )
    }

    @Test
    fun `a page of the log reports how far behind the client was`() {
        repeat(3) { index -> push(group(upsert(index))) }

        val first = service.changes(scope, ledger, cursor = null, limit = 10)
        service.changes(scope, ledger, cursor = first.nextCursor, limit = 10)

        val served = events<SyncMetricEvent.ChangesServed>()
        assertEquals(3, served.first().batches)
        assertEquals(3, served.first().cursorLag, "a client with no cursor is behind by the whole log")
        assertEquals(0, served.last().cursorLag, "a client that caught up is not behind at all")
    }

    @Test
    fun `trimming reports what it removed and what the collection still carries`() {
        push(group(upsert(1)))
        clock.instant = clock.instant.plus(kotlin.time.Duration.parse("60d"))
        push(group(upsert(2)))

        module.maintenance.trim()

        val trimmed = events<SyncMetricEvent.HistoryTrimmed>().single { it.collection == ledger }
        assertEquals(1, trimmed.removedBatches, "the batch outside the window is the one that goes")
        assertEquals(1, trimmed.retainedSpan, "what is left is the batch inside the window")
        assertTrue(events<SyncMetricEvent.CursorRefused>().isEmpty(), "nobody asked with a stale cursor")
    }
}
