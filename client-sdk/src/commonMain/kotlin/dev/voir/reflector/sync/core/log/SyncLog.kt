package dev.voir.reflector.sync.core.log

/**
 * Sink for what the library has to say about itself, implemented by the application.
 *
 * A port rather than a logging dependency, and on this side the reason is not only taste. The
 * client is multiplatform: there is no logging framework common to the JVM, Android and iOS, so any
 * library that picks one imposes it — and its transitive weight — on every consumer and every
 * target. A `fun interface` and a default that discards impose nothing, and the application wires
 * the ten lines it already knows how to write.
 *
 * It sits beside [dev.voir.reflector.sync.core.metrics.SyncMetrics] and answers a different
 * question. Metrics say how a population of clients is doing and are meant to be aggregated;
 * this says what one device did, in order, and is meant to be read. A dashboard cannot explain why
 * a particular installation stopped pushing on Tuesday, and a log cannot tell you that conflicts
 * across the fleet have doubled.
 *
 * Implementations are called on the worker's coroutine, never inside a database transaction. They
 * must return promptly and must not throw: a log line that could fail a synchronisation which
 * otherwise succeeded would be worse than no log line. Anything thrown is swallowed, which also
 * means a broken sink reports nothing and says nothing about it.
 *
 * Nothing sent here carries an entity's document — see [SyncLogRecord].
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
     * Asked before the library builds a message, so a level that is switched off costs nothing
     * beyond this call. It is what makes verbose levels safe to leave in the code: the `DEBUG` line
     * describing every push is not formatted unless somebody is listening for it.
     *
     * It is also where external control belongs. An implementation over a host's logging framework
     * answers from that framework, so the level is configured where every other level in the
     * application is configured — and can be changed at runtime, per source, without the library
     * knowing anything about it.
     *
     * The default accepts everything, which is right for a sink that filters afterwards or writes
     * unconditionally.
     *
     * @param level Severity of the record about to be built.
     * @param source Part of the library it would come from.
     * @return `true` when such a record should be built and reported.
     */
    public fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = true

    /** The sink used when the application supplies none. */
    public companion object {
        /**
         * Sink that wants nothing and is told nothing.
         *
         * The default, so that a library which reports thoroughly costs an application that is not
         * listening one comparison per potential record and no allocation at all.
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
