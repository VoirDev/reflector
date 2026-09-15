package dev.voir.reflector.sync.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sink that keeps the most recent records in memory and publishes them.
 *
 * What a debug screen is built on, and what the library's own tests assert against. The records are
 * a [StateFlow], so a screen collecting it redraws as the engine works and a test can wait for a
 * particular line to appear rather than sleeping.
 *
 * The buffer is bounded and drops the oldest record when it is full: this is a diagnostic aid living
 * in the application's process, and one left switched on by accident must not be able to exhaust its
 * memory.
 *
 * Safe to call from several workers at once — each collection has its own — since the buffer is
 * replaced rather than mutated.
 *
 * @property capacity How many records are kept. Must be positive.
 * @property minimumLevel Least severe level to keep.
 * @property sources Sources to keep, defaulting to all of them.
 */
public class RecordingSyncLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val minimumLevel: SyncLogLevel = SyncLogLevel.DEBUG,
    private val sources: Set<SyncLogSource> = SyncLogSource.entries.toSet(),
) : SyncLog {
    init {
        require(capacity > 0) { "capacity has to be positive" }
    }

    private val mutableRecords = MutableStateFlow<List<SyncLogRecord>>(emptyList())

    /** Records kept so far, oldest first. */
    public val records: StateFlow<List<SyncLogRecord>> = mutableRecords.asStateFlow()

    override fun log(record: SyncLogRecord) {
        mutableRecords.update { kept -> (kept + record).takeLast(capacity) }
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = minimumLevel.includes(level) && source in sources

    /** Forgets everything recorded so far. */
    public fun clear() {
        mutableRecords.value = emptyList()
    }

    private companion object {
        /** Enough to hold several cycles of a busy collection, small enough to be forgettable. */
        const val DEFAULT_CAPACITY = 500
    }
}
