package dev.voir.reflector.sync.core.trigger

import kotlinx.coroutines.flow.Flow

/**
 * Source of reasons to synchronise, supplied by the application.
 *
 * The library deliberately owns no platform integration. Returning to the foreground, regaining a
 * network and running in the background are all things an application already observes and already
 * configures — Android through its lifecycle and `WorkManager`, iOS through notifications and
 * `BGTaskScheduler`. A library that reached for those APIs itself would need the application's
 * manifest entries, its background modes and its initialisation order, and would still be wrong for
 * anything that is not a plain foreground app.
 *
 * So the platform side stays a few lines in the application, and the engine takes a flow.
 */
public fun interface SyncTriggerSource {
    /**
     * Returns the triggers this source produces.
     *
     * The flow is collected for as long as the scope is open, so an implementation is expected to
     * be cold and to stop when collection stops.
     *
     * @return Flow of reasons to synchronise.
     */
    public fun triggers(): Flow<SyncTrigger>
}
