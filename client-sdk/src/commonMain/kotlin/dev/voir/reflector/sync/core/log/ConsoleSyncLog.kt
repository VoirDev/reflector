package dev.voir.reflector.sync.core.log

/**
 * Sink that prints to the platform's standard output.
 *
 * The one-line answer to "I imported the SDK and want to see what it is doing", on every target the
 * library builds for and with no dependency anywhere:
 *
 * ```kotlin
 * SyncEngine(
 *     …,
 *     log = if (isDebugBuild) ConsoleSyncLog(minimumLevel = SyncLogLevel.DEBUG) else SyncLog.None,
 * )
 * ```
 *
 * It is a development tool and says so: `println` is unstructured, unbuffered and unfiltered beyond
 * [minimumLevel], and on Android it does not reach logcat's own filtering, which is what
 * `AndroidSyncLog` is for. An application shipping to users routes records into whatever it already
 * has instead.
 *
 * @property minimumLevel Least severe level to print. The default stops at [SyncLogLevel.DEBUG],
 *   which is the level that explains the engine's decisions without being proportional to the data.
 * @property sources Sources to print, defaulting to all of them. Narrowing this is how a queue that
 *   will not drain is investigated on a collection whose log traffic would otherwise bury it.
 */
public class ConsoleSyncLog(
    private val minimumLevel: SyncLogLevel = SyncLogLevel.DEBUG,
    private val sources: Set<SyncLogSource> = SyncLogSource.entries.toSet(),
) : SyncLog {
    override fun log(record: SyncLogRecord) {
        println(record.format())
        // Printed rather than attached: there is nowhere to attach it to, and a failure inside the
        // application's adapter is unreadable without its stack.
        record.cause?.printStackTrace()
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = minimumLevel.includes(level) && source in sources
}
