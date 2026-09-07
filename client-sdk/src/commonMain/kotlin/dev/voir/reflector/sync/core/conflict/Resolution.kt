package dev.voir.reflector.sync.core.conflict

import kotlinx.serialization.json.JsonObject

/**
 * Decision about a conflict.
 *
 * Whatever the decision, it is applied in one transaction together with the metadata it implies, so
 * a crash can neither lose the decision nor leave the entity dirty without a group to leave in.
 */
public sealed class Resolution {
    /**
     * Keep the local state and push it over the server's version.
     *
     * The local state is not re-read: the entity stays dirty and is sent in its current form, based
     * on the version the conflict was detected against.
     */
    public data object KeepLocal : Resolution()

    /**
     * Take the server's state and drop the local edit.
     *
     * The entity stops being dirty, which also means the user's unsent change is gone — the
     * application is expected to have asked before choosing this.
     */
    public data object TakeServer : Resolution()

    /**
     * Apply a merged state, then push it.
     *
     * The merge is two-sided by construction: the library keeps no common ancestor, so an
     * application that needs a three-way merge has to keep the base state itself.
     *
     * @property data Merged state to write locally and send to the server.
     */
    public data class Merged(
        public val data: JsonObject,
    ) : Resolution()
}
