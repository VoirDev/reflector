package dev.voir.reflector.sync.core.trigger

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Wakes synchronisation on a timer.
 *
 * A timer is the safety net under everything else: a missed notification, a socket that never
 * reconnected, a platform that stopped delivering background events. It is deliberately slow —
 * catching up a few minutes late costs nothing, while polling often costs battery on every device
 * that had nothing to do.
 *
 * @property interval How long to wait between triggers.
 */
public class PeriodicTriggerSource(
    private val interval: Duration = DEFAULT_INTERVAL,
) : SyncTriggerSource {
    override fun triggers(): Flow<SyncTrigger> =
        flow {
            while (true) {
                delay(interval)
                emit(SyncTrigger.PERIODIC)
            }
        }

    private companion object {
        /** Slow enough to be invisible on a battery, fast enough to bound how stale data can get. */
        val DEFAULT_INTERVAL: Duration = 15.minutes
    }
}
