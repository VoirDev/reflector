package dev.voir.reflector.sync.core

/**
 * What the application has paused in a scope.
 *
 * A pause is the application's decision and carries no reason: the library neither knows nor asks
 * why a scope should stop sending or stop talking to the server, and it does nothing on its own to
 * end one. While a pause lasts, the work it covers is not attempted at all — which is what sets it
 * apart from a failure: a group that is never sent uses no attempt, serves no backoff and records
 * no error, so it leaves exactly as it would have when the pause is lifted.
 *
 * Local changes keep being recorded under either value. A pause holds work back; it never refuses
 * or discards any.
 */
public enum class SyncPause {
    /**
     * Nothing leaves the device: neither the push queue nor file uploads.
     *
     * Everything that reads keeps going — the change log, snapshots and file downloads — so the
     * device still receives what the server holds while its own changes wait.
     */
    UPLOADS,

    /**
     * The scope's workers make no requests at all: no push, no read of the log, no snapshot and no
     * file transfer in either direction.
     *
     * The event channel, when there is one, is left connected. It carries no data, and what its
     * notifications ask for is a cycle, which a scope paused this way does not run.
     */
    ALL,
}
