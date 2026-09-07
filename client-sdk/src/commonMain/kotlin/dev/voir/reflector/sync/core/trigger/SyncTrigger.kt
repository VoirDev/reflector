package dev.voir.reflector.sync.core.trigger

/**
 * Why synchronisation is being woken up.
 *
 * The engine treats every trigger the same way — it synchronises — so the distinction exists for
 * diagnostics and for the application, which decides which of them it can even produce.
 */
public enum class SyncTrigger {
    /** The application came back to the foreground and the user is looking at the data. */
    FOREGROUND,

    /** The device regained connectivity. */
    NETWORK,

    /** A timer expired. */
    PERIODIC,

    /** A platform background task ran, or the application asked for its own reason. */
    BACKGROUND,
}
