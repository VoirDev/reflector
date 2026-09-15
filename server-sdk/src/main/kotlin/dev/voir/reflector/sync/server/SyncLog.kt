package dev.voir.reflector.sync.server

/**
 * Sink for what the module has to say about itself, implemented by the host.
 *
 * A port rather than a logging dependency, for the reason every other port here exists: this module
 * has almost no dependencies, and every host it is embedded in already has a logging framework it
 * will not swap for the module's. Ten lines of adapter over SLF4J, or whatever the host uses, turn
 * these records into lines in the log the host already owns — and the module adds nothing to its
 * classpath.
 *
 * It sits beside [SyncMetrics] and answers a different question. Metrics say how a deployment is
 * doing and are built to be aggregated; this says what happened to one request, in order, and is
 * meant to be read.
 *
 * **[isEnabled] is where the level lives, and that is deliberate.** An implementation over the
 * host's framework answers it from that framework, which means the levels in effect are configured
 * wherever every other level in the host is configured — in `logback.xml`, at runtime, per
 * [SyncLogSource]. Turning the module's push path to `DEBUG` in production while the rest of the
 * server stays at `INFO` is then a configuration change and not a deployment:
 *
 * ```xml
 * <logger name="dev.voir.reflector.sync" level="INFO"/>
 * <logger name="dev.voir.reflector.sync.push" level="DEBUG"/>
 * ```
 *
 * Implementations are called on the thread that served the request, always **after** the relevant
 * transaction has committed, and never while the counter lock is held. They must return promptly
 * and must not throw: a log line that could fail a write the database has already accepted would be
 * worse than no log line. Anything thrown is swallowed, exactly as it is for [SyncCommitListener]
 * and [SyncMetrics].
 *
 * Nothing sent here carries a stored document — see [SyncLogRecord].
 */
public fun interface SyncLog {
    /**
     * Reports one record.
     *
     * Called only after [isEnabled] has agreed to the record's level and source, so an
     * implementation does not have to filter again unless it wants to.
     *
     * @param record What happened.
     */
    public fun log(record: SyncLogRecord)

    /**
     * Tells whether records of this level and source are wanted at all.
     *
     * Asked before the module builds a message, so a level that is switched off costs nothing
     * beyond this call — which is what makes it reasonable for a module on a request path to
     * describe every request it serves.
     *
     * The default accepts everything, which is right for a sink that filters afterwards.
     *
     * @param level Severity of the record about to be built.
     * @param source Part of the module it would come from.
     * @return `true` when such a record should be built and reported.
     */
    public fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = true

    /** The sink used when the host supplies none. */
    public companion object {
        /**
         * Sink that wants nothing and is told nothing.
         *
         * The default, so that a module which reports thoroughly costs a host that is not listening
         * one comparison per potential record and no allocation at all.
         */
        public val None: SyncLog = DiscardingSyncLog
    }
}

/**
 * The implementation behind [SyncLog.None].
 *
 * An object rather than a lambda because refusing every level is the whole point, and a `fun
 * interface` instance can only supply [SyncLog.log].
 */
private object DiscardingSyncLog : SyncLog {
    override fun log(record: SyncLogRecord) {
        // Nothing arrives here: isEnabled has already refused every level.
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = false
}
