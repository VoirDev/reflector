package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogLevel
import dev.voir.reflector.sync.server.SyncLogRecord
import dev.voir.reflector.sync.server.SyncLogSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Routes what the synchronisation module says into the host's own logging.
 *
 * This is the whole of the integration, and it is deliberately the host's file rather than the
 * module's. `server-sdk` depends on no logging library: every backend a host might use is one it
 * already has and will not swap, so the module reports through a port and this turns the records
 * into lines in the log the rest of this application already writes.
 *
 * Each [SyncLogSource] gets its own logger under [root], so the levels in effect are decided by
 * `logback.xml` — externally, per area, and at runtime without a redeploy:
 *
 * ```xml
 * <logger name="dev.voir.reflector.sync" level="INFO"/>
 * <logger name="dev.voir.reflector.sync.push" level="DEBUG"/>
 * ```
 *
 * That is what `isEnabled` is asked for. The module calls it before it formats a message or builds
 * a context map, so a level nobody has switched on costs a comparison SLF4J has already cached.
 *
 * The structured part of a record is appended as `key=value` pairs. A host with a structured
 * backend puts them in the MDC instead and loses nothing, which is the other reason this is ten
 * lines in the host rather than a decision in the library.
 *
 * @property root Prefix of the logger names this writes under.
 */
class Slf4jSyncLog(
    private val root: String = DEFAULT_ROOT,
) : SyncLog {
    override fun log(record: SyncLogRecord) {
        val logger = loggerFor(record.event.source)
        val message = render(record)
        when (record.level) {
            SyncLogLevel.ERROR -> logger.error(message, record.cause)
            SyncLogLevel.WARN -> logger.warn(message, record.cause)
            SyncLogLevel.INFO -> logger.info(message, record.cause)
            SyncLogLevel.DEBUG -> logger.debug(message, record.cause)
            SyncLogLevel.TRACE -> logger.trace(message, record.cause)
        }
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean =
        loggerFor(source).let { logger ->
            when (level) {
                SyncLogLevel.ERROR -> logger.isErrorEnabled
                SyncLogLevel.WARN -> logger.isWarnEnabled
                SyncLogLevel.INFO -> logger.isInfoEnabled
                SyncLogLevel.DEBUG -> logger.isDebugEnabled
                SyncLogLevel.TRACE -> logger.isTraceEnabled
            }
        }

    private fun loggerFor(source: SyncLogSource): Logger = LoggerFactory.getLogger("$root.${source.id}")

    private fun render(record: SyncLogRecord): String =
        buildString {
            append(record.event.name)
            append(' ')
            append(record.message)
            record.scope?.let { append(" scope=${it.value}") }
            record.collection?.let { append(" collection=${it.value}") }
            record.context.forEach { (key, value) -> append(" $key=$value") }
        }

    private companion object {
        /**
         * Logger prefix, which is also what appears in `logback.xml`.
         *
         * The module's own package, so that the configuration reads like every other logger
         * declaration in the host.
         */
        const val DEFAULT_ROOT = "dev.voir.reflector.sync"
    }
}
