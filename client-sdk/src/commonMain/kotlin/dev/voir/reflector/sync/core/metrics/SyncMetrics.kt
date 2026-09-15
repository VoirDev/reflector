package dev.voir.reflector.sync.core.metrics

import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger

/**
 * Sink for what the library measures about itself, implemented by the application.
 *
 * A port rather than a metrics library, for the same reason as
 * [dev.voir.reflector.sync.core.TokenProvider] and
 * [dev.voir.reflector.sync.core.trigger.SyncTriggerSource]: every application already has somewhere
 * to send numbers, and none of them agree on what it is. Ten lines over a `MutableStateFlow` make a
 * debug screen; the same ten lines over the application's own client make it a dashboard.
 *
 * Every failure mode this library has is slow rather than loud — a queue that stops draining, a
 * cursor that falls behind, conflicts nobody answers. Without numbers, "synchronisation feels stuck"
 * cannot be confirmed or denied, which is what this port exists to fix.
 *
 * Implementations are called from the collection's worker, on its coroutine, never inside a database
 * transaction. They must return promptly and must not throw: a metric that could fail a
 * synchronisation which already succeeded would be worse than no metric at all. Anything thrown is
 * therefore swallowed by the library, which also means a broken implementation reports nothing and
 * says nothing about it.
 */
public fun interface SyncMetrics {
    /**
     * Reports one thing that happened.
     *
     * @param event What happened, with the numbers that describe it.
     */
    public fun record(event: SyncMetricEvent)

    /** The sink used when the application supplies none. */
    public companion object {
        /** Sink that discards everything, so that measuring stays optional. */
        public val None: SyncMetrics = SyncMetrics { }
    }
}

/**
 * Reports an event, absorbing whatever the application's implementation does with it.
 *
 * The contract says an implementation must not throw; this is what makes a violation cost the
 * application its metrics rather than its synchronisation. It costs it the metric and no more than
 * that: the throwable goes to the log, so a sink that has been broken since a refactoring is
 * something a developer can find rather than something that quietly stopped counting.
 *
 * @param event Event to report.
 * @param log Sink told when the application's implementation throws.
 */
internal fun SyncMetrics.emit(
    event: SyncMetricEvent,
    log: SyncLogger,
) {
    runCatching { record(event) }
        .onFailure { failure ->
            log.warn(SyncLogEvent.METRICS_SINK_FAILED, failure) {
                "the metrics sink threw on ${event::class.simpleName}; the measurement is lost"
            }
        }
}
