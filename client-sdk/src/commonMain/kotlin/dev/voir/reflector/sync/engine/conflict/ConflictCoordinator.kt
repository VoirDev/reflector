package dev.voir.reflector.sync.engine.conflict

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.conflict.StoredConflict
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.persistence.record.MutationIntent
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import kotlin.uuid.Uuid

/**
 * Applies decisions about conflicts.
 *
 * A decision touches the application's rows, the record's revisions and the queue, and all three
 * have to move together: a crash between them would leave an entity that is neither the local state
 * nor the server's, with a group nobody will ever send.
 *
 * @param scope Scope being synchronised.
 * @param collection Collection being synchronised.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param adapter Application's bridge to its own rows.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 */
internal class ConflictCoordinator(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val adapter: CollectionAdapter,
    private val newUuid: () -> Uuid,
) {
    /**
     * Asks the application to decide about a conflict, if it can decide without the user.
     *
     * @param conflictId Conflict to offer.
     * @return `true` when the adapter decided and the conflict is gone, `false` when it left the
     *   decision to the user interface.
     */
    suspend fun offerToAdapter(conflictId: ConflictId): Boolean {
        val stored = transactions.transaction { stores.conflicts.find(conflictId) } ?: return true
        val resolution = adapter.resolve(stored.toConflict()) ?: return false
        resolve(conflictId, resolution)
        return true
    }

    /**
     * Applies a decision to a conflict.
     *
     * @param conflictId Conflict to resolve; it must still be open.
     * @param resolution Decision to apply.
     * @throws IllegalArgumentException When the conflict is unknown or has already been resolved.
     */
    suspend fun resolve(
        conflictId: ConflictId,
        resolution: Resolution,
    ) {
        transactions.transaction {
            val stored =
                requireNotNull(stores.conflicts.find(conflictId)) {
                    "conflict $conflictId is unknown or has already been resolved"
                }
            val group = stores.records.find(scope, collection, stored.entityType, stored.entityId)?.groupId

            when (resolution) {
                is Resolution.KeepLocal -> keepLocal(stored)
                is Resolution.TakeServer -> takeServer(stored)
                is Resolution.Merged -> merge(stored, resolution)
            }

            stores.conflicts.delete(conflictId)
            group?.let { reviveIfSettled(it) }
        }
    }

    private suspend fun keepLocal(stored: StoredConflict) {
        // Nothing is written to the application's rows: they already hold the local state. Only the
        // base version moves, so that the next push is built on the version the conflict was about.
        stores.records.keepLocal(
            scope = scope,
            collection = collection,
            entityType = stored.entityType,
            entityId = stored.entityId,
            serverVersion = stored.serverVersion,
            intent = if (stored.local == null) MutationIntent.DELETE else MutationIntent.UPSERT,
        )
    }

    private suspend fun takeServer(stored: StoredConflict) {
        val op =
            stored.server
                ?.let { RemoteOp.Upsert(stored.entityType, stored.entityId, it) }
                ?: RemoteOp.Delete(stored.entityType, stored.entityId)
        adapter.applyRemote(listOf(op))
        stores.records.takeServer(
            scope = scope,
            collection = collection,
            entityType = stored.entityType,
            entityId = stored.entityId,
            serverVersion = stored.serverVersion,
        )
    }

    private suspend fun merge(
        stored: StoredConflict,
        resolution: Resolution.Merged,
    ) {
        adapter.applyRemote(listOf(RemoteOp.Upsert(stored.entityType, stored.entityId, resolution.data)))
        stores.records.keepLocal(
            scope = scope,
            collection = collection,
            entityType = stored.entityType,
            entityId = stored.entityId,
            serverVersion = stored.serverVersion,
            intent = MutationIntent.UPSERT,
        )
    }

    /**
     * Returns a conflicted group to the queue once nothing in it waits for a decision any more.
     *
     * The group is recreated under a new identifier rather than simply flipped back to pending: its
     * content has changed, and the server keeps the answer it produced under the old identifier —
     * re-sending it would replay that stored conflict instead of looking at the resolved data.
     */
    private suspend fun reviveIfSettled(groupId: GroupId) {
        val group = stores.groups.find(groupId) ?: return
        if (group.state != PushGroupState.CONFLICTED || stores.records.openConflictsOfGroup(groupId) > 0) {
            return
        }
        val records = stores.records.ofGroup(groupId)
        if (records.none { it.isDirty }) {
            // Every decision was to take the server's state, so there is nothing left to send.
            stores.groups.delete(groupId)
            return
        }
        val revived = GroupId(newUuid())
        stores.groups.create(scope, collection, revived)
        stores.groups.setOrdinal(revived, group.ord)
        stores.records.reassignGroup(groupId, revived)
        stores.groups.delete(groupId)
    }

    private fun StoredConflict.toConflict(): Conflict =
        Conflict(
            id = conflictId,
            entityType = entityType,
            entityId = entityId,
            origin = origin,
            local = local,
            server = server,
        )
}
