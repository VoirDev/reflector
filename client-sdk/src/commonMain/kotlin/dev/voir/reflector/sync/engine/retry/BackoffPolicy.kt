package dev.voir.reflector.sync.engine.retry

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Delay before the next attempt after a failure.
 *
 * Exponential with full jitter. The jitter is not decoration: without it every client that lost
 * connection at the same moment — which is what a server outage looks like — comes back in the same
 * instant and knocks the server over again.
 *
 * @property base Delay after the first failure.
 * @property max Ceiling the exponential growth is clamped to.
 * @property random Source of jitter; injected so that tests are deterministic.
 */
internal class BackoffPolicy(
    private val base: Duration = 1.seconds,
    private val max: Duration = 5.minutes,
    private val random: Random = Random.Default,
) {
    /**
     * Returns how long to wait before the next attempt.
     *
     * @param attempts Number of consecutive failures so far, one after the first failure.
     * @return Delay to wait, between zero and the exponentially grown ceiling.
     */
    fun nextDelay(attempts: Int): Duration {
        val exponent = (attempts - 1).coerceIn(0, MAX_EXPONENT)
        val ceiling = minOf(base * (1 shl exponent), max)
        return random.nextLong(ceiling.inWholeMilliseconds + 1).milliseconds
    }

    private companion object {
        /** Beyond this the ceiling is reached anyway, and shifting further would overflow. */
        const val MAX_EXPONENT = 20
    }
}
