package dev.voir.reflector.sync.core.log

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * The library's own view of a [SyncLog], bound to what it is working on.
 *
 * Every record the engine produces belongs to a scope and almost every one to a collection, and
 * repeating both at each call site is how they end up missing from half of them. A worker builds one
 * of these once and its coordinators take it instead of the port.
 *
 * The reporting functions are inline and take the message as a lambda, so a level that is switched
 * off costs one call to [SyncLog.isEnabled] and nothing else: no string is concatenated and no
 * context map is built. That is what makes it reasonable for the engine to describe every decision
 * it takes.
 *
 * @property delegate Sink the application supplied.
 * @property scope Scope every record from this logger belongs to.
 * @property collection Collection every record belongs to, or `null` for scope-wide work.
 */
internal class SyncLogger(
    val delegate: SyncLog,
    val scope: ScopeId? = null,
    val collection: CollectionId? = null,
) {
    /**
     * Returns a logger for one collection of the same scope.
     *
     * @param collection Collection the new logger reports about.
     * @return Logger bound to that collection.
     */
    fun forCollection(collection: CollectionId): SyncLogger = SyncLogger(delegate, scope, collection)

    /**
     * Reports a record, if anything is listening for it.
     *
     * Whatever the application's sink does with it is absorbed: the contract says an implementation
     * must not throw, and this is what makes a violation cost a log line rather than a
     * synchronisation that had already succeeded.
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
     * Reports something that failed and will not recover on its own.
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
     * Reports something stuck or unusual that the application will eventually have to deal with.
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
     * Reports a decision the engine took.
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
     * Reports per-entity or per-batch detail underneath a decision.
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
