package dev.voir.reflector.sync.core.log

import android.util.Log

/**
 * Sink that writes to logcat.
 *
 * Where an Android developer already looks, and it costs nothing: `android.util.Log` is part of the
 * platform, so this adds no dependency to the artifact or to the application.
 *
 * It is worth preferring over [ConsoleSyncLog] on Android for one reason beyond the destination.
 * Levels are checked with `Log.isLoggable`, so which of them reach logcat is controlled from outside
 * the application and without rebuilding it:
 *
 * ```text
 * adb shell setprop log.tag.ReflectorSync DEBUG
 * adb shell setprop log.tag.ReflectorSync.push VERBOSE
 * ```
 *
 * Each [SyncLogSource] gets its own tag under [tag], so one area can be made verbose while the rest
 * stays quiet — which matters on a collection whose pull traffic would otherwise bury the push
 * decisions being investigated. A tag is limited to 23 characters on older releases, and the
 * default leaves room for the longest source under it.
 *
 * `Log.isLoggable` answers `true` for `INFO` and above by default, so an application that installs
 * this and sets nothing still gets the levels that mean something is stuck or broken.
 *
 * @property tag Prefix of the logcat tags this writes under.
 */
public class AndroidSyncLog(
    private val tag: String = DEFAULT_TAG,
) : SyncLog {
    override fun log(record: SyncLogRecord) {
        val message = record.format()
        val tag = tagOf(record.event.source)
        when (record.level) {
            SyncLogLevel.ERROR -> Log.e(tag, message, record.cause)
            SyncLogLevel.WARN -> Log.w(tag, message, record.cause)
            SyncLogLevel.INFO -> Log.i(tag, message, record.cause)
            SyncLogLevel.DEBUG -> Log.d(tag, message, record.cause)
            SyncLogLevel.TRACE -> Log.v(tag, message, record.cause)
        }
    }

    override fun isEnabled(
        level: SyncLogLevel,
        source: SyncLogSource,
    ): Boolean = Log.isLoggable(tagOf(source), priorityOf(level))

    private fun tagOf(source: SyncLogSource): String = "$tag.${source.id}"

    private fun priorityOf(level: SyncLogLevel): Int =
        when (level) {
            SyncLogLevel.ERROR -> Log.ERROR

            SyncLogLevel.WARN -> Log.WARN

            SyncLogLevel.INFO -> Log.INFO

            SyncLogLevel.DEBUG -> Log.DEBUG

            // Logcat's least severe priority is VERBOSE, which is what this library calls TRACE.
            SyncLogLevel.TRACE -> Log.VERBOSE
        }

    private companion object {
        /**
         * Tag prefix used when the application names none.
         *
         * Thirteen characters, so that the longest source under it stays inside the 23-character
         * limit that `setprop` keys are subject to on releases before API 26.
         */
        const val DEFAULT_TAG = "ReflectorSync"
    }
}
