package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.ScopeId
import java.util.concurrent.ConcurrentHashMap

/**
 * Scopes this host has taken away, and the two things taking one away consists of.
 *
 * Revocation is entirely the host's business — the module receives an already authorised
 * [ScopeId] and holds no access state at all — but it is not one action, and a host that performs
 * only the first half leaves data on devices:
 *
 * 1. **Refuse the scope from now on**, with `403` rather than `401`. The codes are the client's
 *    recovery instructions: `401` means "get credentials" and preserves the queued changes for the
 *    user who signs in again, while `403` means "this scope is not yours any more" and makes the
 *    client wipe it.
 * 2. **Tell whoever is connected**, through the `revoked` event on the scope's socket. Without it a
 *    client learns of the revocation only when it next makes a request, which on a device nobody
 *    has opened is however long the device stays closed — and the data sits there meanwhile.
 *
 * A real host calls [revoke] wherever membership changes: somebody removed from a workspace, a
 * subscription that lapsed, an account closed. This one keeps the set in memory, because a sample
 * that dragged in a membership table would teach a lesson about the table rather than about the
 * boundary.
 *
 * @property events Delivery of notifications to connected sockets.
 */
class ScopeRevocations(
    private val events: ScopeEvents,
) {
    private val revoked = ConcurrentHashMap.newKeySet<ScopeId>()

    /**
     * Takes a scope away and tells whoever is connected to it.
     *
     * The order matters: the refusal is recorded first, so that a client woken by the notification
     * cannot slip a request in between the two and be served a scope it has just lost.
     *
     * @param scope Scope that is no longer accessible.
     */
    fun revoke(scope: ScopeId) {
        revoked += scope
        events.revoke(scope)
    }

    /**
     * Says whether a scope has been taken away.
     *
     * @param scope Scope the caller asked for.
     * @return `true` when the scope must be refused with `403`.
     */
    fun isRevoked(scope: ScopeId): Boolean = scope in revoked
}
