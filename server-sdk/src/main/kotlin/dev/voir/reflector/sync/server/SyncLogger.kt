package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * The module's own view of a [SyncLog], bound to what it is working on.
 *
 * Almost every record belongs to a scope and a collection, and repeating both at each call site is
 * how they end up missing from half of them. An operation binds one of these once and uses it
 * throughout.
 *
 * The reporting functions are inline and take the message as a lambda, so a level that is switched
 * off costs one call to [SyncLog.isEnabled] and nothing else: no string is concatenated and no
 * context map is built.
 *
 * @property delegate Sink the host supplied.
 * @property scope Scope every record from this logger belongs to, or `null` for module-wide work.
 * @property collection Collection every record belongs to, or `null` for module-wide work.
 */
internal class SyncLogger(
    val delegate: SyncLog,
    val scope: ScopeId? = null,
    val collection: CollectionId? = null,
) {
    /**
     * Returns a logger for one collection of one scope.
     *
     * @param scope Scope the new logger reports about.
     * @param collection Collection the new logger reports about.
     * @return Logger bound to that collection.
     */
    fun forCollection(
        scope: ScopeId,
        collection: CollectionId,
    ): SyncLogger = SyncLogger(delegate, scope, collection)

    /**
     * Reports a record, if anything is listening for it.
     *
     * Whatever the host's sink does with it is absorbed: the contract says an implementation must
     * not throw, and this is what makes a violation cost a log line rather than a batch the
     * database has already accepted.
     *
     * @param level Severity of the record.
     * @param event What happened.
     * @param cause Throwable behind it, when there is one.
     * @param context Values worth extracting, built only when the record is wanted.
     * @param message Description for a person, built only when the record is wanted.
     */
    inline fun log(
        level: SyncLogLevel,
        event: SyncLogEvent,
        cause: Throwable? = null,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ) {
        if (!delegate.isEnabled(level, event.source)) {
            return
        }
        val record =
            SyncLogRecord(
                level = level,
                event = event,
                message = message(),
                scope = scope,
                collection = collection,
                context = context(),
                cause = cause,
            )
        runCatching { delegate.log(record) }
    }

    /**
     * Reports something that failed and that the host has to know about.
     *
     * @param event What happened.
     * @param cause Throwable behind it, when there is one.
     * @param context Values worth extracting.
     * @param message Description for a person.
     */
    inline fun error(
        event: SyncLogEvent,
        cause: Throwable? = null,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ): Unit = log(SyncLogLevel.ERROR, event, cause, context, message)

    /**
     * Reports a capacity problem, present or approaching.
     *
     * @param event What happened.
     * @param cause Throwable behind it, when there is one.
     * @param context Values worth extracting.
     * @param message Description for a person.
     */
    inline fun warn(
        event: SyncLogEvent,
        cause: Throwable? = null,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ): Unit = log(SyncLogLevel.WARN, event, cause, context, message)

    /**
     * Reports a significant, infrequent step.
     *
     * @param event What happened.
     * @param context Values worth extracting.
     * @param message Description for a person.
     */
    inline fun info(
        event: SyncLogEvent,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ): Unit = log(SyncLogLevel.INFO, event, cause = null, context = context, message = message)

    /**
     * Reports one request answered.
     *
     * @param event What happened.
     * @param context Values worth extracting.
     * @param message Description for a person.
     */
    inline fun debug(
        event: SyncLogEvent,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ): Unit = log(SyncLogLevel.DEBUG, event, cause = null, context = context, message = message)

    /**
     * Reports per-group or per-batch detail underneath a request.
     *
     * @param event What happened.
     * @param context Values worth extracting.
     * @param message Description for a person.
     */
    inline fun trace(
        event: SyncLogEvent,
        context: () -> Map<String, String> = { emptyMap() },
        message: () -> String,
    ): Unit = log(SyncLogLevel.TRACE, event, cause = null, context = context, message = message)
}
