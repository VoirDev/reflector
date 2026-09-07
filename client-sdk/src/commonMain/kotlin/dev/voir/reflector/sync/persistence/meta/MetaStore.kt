package dev.voir.reflector.sync.persistence.meta

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Typed access to the per-scope identity of this installation.
 *
 * @property dao Generated data access object of the identity table.
 */
internal class MetaStore(
    private val dao: SyncMetaDao,
) {
    /**
     * Reads the client identifier of a scope.
     *
     * @param scope Scope to read.
     * @return Stored identifier, or `null` when the scope has never been opened here.
     */
    public suspend fun clientId(scope: ScopeId): ClientId? = dao.find(scope.value)?.let { ClientId(it.clientId) }

    /**
     * Stores a freshly generated client identifier.
     *
     * @param scope Scope the identity belongs to.
     * @param clientId Identifier to store.
     * @param createdAt Local timestamp of generation, in epoch milliseconds.
     */
    public suspend fun putClientId(
        scope: ScopeId,
        clientId: ClientId,
        createdAt: Long,
    ) {
        dao.insert(SyncMetaEntity(scopeId = scope.value, clientId = clientId.value, createdAt = createdAt))
    }

    /**
     * Removes the identity of a scope.
     *
     * @param scope Scope to wipe.
     */
    public suspend fun deleteScope(scope: ScopeId) {
        dao.delete(scope.value)
    }
}
