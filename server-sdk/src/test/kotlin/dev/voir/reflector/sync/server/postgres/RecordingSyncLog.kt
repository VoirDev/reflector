package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogLevel
import dev.voir.reflector.sync.server.SyncLogRecord
import dev.voir.reflector.sync.server.SyncLogSource

/**
 * Sink that keeps what the module reported, so that a test can assert on it.
 *
 * Accepts every level: a test that has to remember to switch one on would pass for the wrong
 * reason the day somebody changes the level a record is written at.
 */
class RecordingSyncLog : SyncLog {
    /** Everything reported so far, in order. */
    val records: MutableList<SyncLogRecord> = mutableListOf()

    override fun log(record: SyncLogRecord) {
        records += record
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = true

    /**
     * Returns the records reported under one event.
     *
     * @param event Event to filter by.
     * @return Matching records, in the order they were reported.
     */
    fun of(event: SyncLogEvent): List<SyncLogRecord> = records.filter { it.event == event }
}
