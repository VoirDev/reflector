package dev.voir.reflector.sync.engine.pull

import dev.voir.reflector.sync.core.transport.SyncTransportFailure

/**
 * Result of one pull, in the terms the collection's worker decides on.
 */
internal sealed class PullOutcome {
    /** Everything the server had at the moment of reading has been applied. */
    data object UpToDate : PullOutcome()

    /** The pull could not finish and will be retried after a backoff. */
    data object Blocked : PullOutcome()

    /**
     * Incremental reading is no longer possible and the collection needs a snapshot.
     *
     * Reached when the cursor fell out of retention, when the server asked for it, or when the log
     * carried an operation this client does not understand.
     */
    data object BootstrapRequired : PullOutcome()

    /**
     * The collection on the server has been replaced and the local copy of it is meaningless.
     *
     * Stronger than [BootstrapRequired]: that one keeps what never reached the server, this one
     * cannot, because there is no longer a server state for those changes to be changes of.
     */
    data object ResetRequired : PullOutcome()

    /**
     * The scope itself has to react before anything can be read.
     *
     * @property failure Reason the scope is not usable.
     */
    data class Interrupted(
        val failure: SyncTransportFailure,
    ) : PullOutcome()
}
