package dev.voir.reflector.sync.core.log

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncLoggerTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    @Test
    fun `a level nothing is listening for costs nothing to describe`() {
        // The contract that makes it reasonable for the engine to narrate every decision it takes:
        // a DEBUG line in a release build must not format a message or build a context map.
        var built = 0
        val logger = SyncLogger(RefusingLog, scope, collection)

        logger.debug(SyncLogEvent.PUSH_SENT, context = {
            built++
            emptyMap()
        }) {
            built++
            "this must never be built"
        }

        assertEquals(0, built, "the message and the context were built for a sink that refused the level")
    }

    @Test
    fun `a record carries the scope and collection the logger is bound to`() {
        val logs = RecordingSyncLog()

        SyncLogger(logs, scope, collection).warn(SyncLogEvent.QUEUE_BLOCKED) { "stuck" }

        val record = logs.records.value.single()
        assertEquals(scope, record.scope)
        assertEquals(collection, record.collection)
        assertEquals(SyncLogLevel.WARN, record.level)
    }

    @Test
    fun `a sink that throws costs its own record and nothing else`() {
        // A diagnostic that can fail a synchronisation which had already succeeded would be worse
        // than no diagnostic, so a broken implementation is absorbed rather than propagated.
        val logger = SyncLogger(SyncLog { error("the application's sink is broken") }, scope, collection)

        logger.info(SyncLogEvent.BOOTSTRAP_FINISHED) { "done" }
    }

    @Test
    fun `the buffer keeps the most recent records and forgets the rest`() {
        val logs = RecordingSyncLog(capacity = 2)

        repeat(5) { index -> SyncLogger(logs, scope, collection).info(SyncLogEvent.CYCLE_STARTED) { "cycle $index" } }

        assertEquals(listOf("cycle 3", "cycle 4"), logs.records.value.map { it.message })
    }

    @Test
    fun `a threshold includes everything at least as severe as itself`() {
        assertTrue(SyncLogLevel.WARN.includes(SyncLogLevel.ERROR))
        assertTrue(SyncLogLevel.WARN.includes(SyncLogLevel.WARN))
        assertFalse(SyncLogLevel.WARN.includes(SyncLogLevel.INFO))
    }

    /** Sink that wants nothing, so that a test can prove nothing was built for it. */
    private object RefusingLog : SyncLog {
        override fun log(record: SyncLogRecord): Unit = error("a refused level must never reach the sink")

        override fun isEnabled(
            level: SyncLogLevel,
            source: SyncLogSource,
        ): Boolean = false
    }
}
