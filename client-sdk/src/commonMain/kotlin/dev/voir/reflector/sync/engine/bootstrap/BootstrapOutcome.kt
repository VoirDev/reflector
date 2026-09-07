package dev.voir.reflector.sync.engine.bootstrap

import dev.voir.reflector.sync.core.transport.SyncTransportFailure

/**
 * Result of one bootstrap attempt.
 */
internal sealed class BootstrapOutcome {
    /** The snapshot was applied in full and the collection follows the log again. */
    data object Completed : BootstrapOutcome()

    /**
     * The bootstrap did not finish and will be resumed.
     *
     * Resuming is cheap because the position inside the snapshot is stored as it goes: an
     * interrupted bootstrap continues from its last page rather than from the beginning.
     */
    data object Blocked : BootstrapOutcome()

    /**
     * The scope itself has to react before anything can be read.
     *
     * @property failure Reason the scope is not usable.
     */
    data class Interrupted(
        val failure: SyncTransportFailure,
    ) : BootstrapOutcome()
}
