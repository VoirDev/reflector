package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Resolves a caller's credentials into a scope they may access.
 *
 * This is the host's job and only the host's. The module receives a [ScopeId] that is already
 * authorised and never re-checks it — putting the same rules in both places is how they drift apart,
 * and a synchronisation library is the last component that should hold an opinion about who may read
 * what.
 */
fun interface ScopeAuthorizer {
    /**
     * Returns the scope the credentials grant access to.
     *
     * @param token Bearer token as it arrived, or `null` when the request carried none.
     * @return Scope the caller may synchronise, or `null` when the credentials are not accepted.
     */
    fun authorize(token: String?): ScopeId?
}

/**
 * Authorizer of the demonstration host: the token *is* the scope.
 *
 * A real host would verify a signature and look up a session. This one exists to make the boundary
 * visible in code, not to be copied.
 */
class TokenIsScopeAuthorizer : ScopeAuthorizer {
    override fun authorize(token: String?): ScopeId? = token?.takeIf { it.isNotBlank() }?.let(::ScopeId)
}
