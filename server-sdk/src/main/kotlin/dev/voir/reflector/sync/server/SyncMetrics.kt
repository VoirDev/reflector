package dev.voir.reflector.sync.server

/**
 * Sink for what the module measures about itself, implemented by the host.
 *
 * A port rather than a metrics dependency, and for a stronger reason than on the client: this module
 * has almost no dependencies at all, and every host it is embedded in already has a metrics
 * backend that it will not swap for the module's. Ten lines of adapter turn these events into
 * whatever that backend counts.
 *
 * One measurement here matters more than the rest. Writers into a collection are serialised by the
 * counter lock, deliberately and by design — and the day that serialisation becomes the bottleneck
 * will be found either on a dashboard or by users. [SyncMetricEvent.PushGroupServed] carries how
 * long the lock was held, which is the number that says which of the two it will be.
 *
 * Implementations are called on the thread that served the request, always **after** the relevant
 * transaction has committed, and never while the counter lock is held. They must return promptly
 * and must not throw: a metric that could fail a write the database has already accepted would be
 * worse than no metric. Anything thrown is swallowed, exactly as it is for [SyncCommitListener],
 * which also means a broken implementation reports nothing and says nothing about it.
 */
public fun interface SyncMetrics {
    /**
     * Reports one thing that happened.
     *
     * @param event What happened, with the numbers that describe it.
     */
    public fun record(event: SyncMetricEvent)

    /** The sink used when the host supplies none. */
    public companion object {
        /** Sink that discards everything, so that measuring stays optional. */
        public val None: SyncMetrics = SyncMetrics { }
    }
}

/**
 * Reports an event, absorbing whatever the host's implementation does with it.
 *
 * The contract says an implementation must not throw; this is what makes a violation cost the host
 * its metrics rather than a committed batch.
 *
 * @param event Event to report.
 */
internal fun SyncMetrics.emit(event: SyncMetricEvent) {
    runCatching { record(event) }
}
