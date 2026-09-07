package dev.voir.reflector.sync.engine.mutation

import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * Turns a local transaction of the application into queued synchronisation work.
 *
 * The whole difficulty here is that bodies are materialised lazily: a push sends the state an entity
 * has at push time, not the state it had when it was edited. So if an entity is edited in
 * transaction T1 and again in T2, the state as of T1 no longer exists anywhere, and the two
 * transactions can no longer be sent as separate groups without inventing data. Groups therefore
 * merge whenever they share an entity, which makes them the connected components of "was edited
 * together, directly or transitively".
 *
 * The degradation is honest: an entity that is edited constantly pulls its group along and, in the
 * limit, the queue becomes one large group — that is exactly the price of state-based semantics,
 * and no bookkeeping can avoid it.
 *
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param newGroupId Source of group identifiers; injected so that tests can make them predictable.
 */
internal class MutationCoordinator(
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val newGroupId: () -> GroupId,
) {
    /**
     * Runs [block] as one transaction and queues everything it marked.
     *
     * @param scope Scope of the collection.
     * @param collection Collection being changed.
     * @param block Work of the application, with the marking API in scope.
     * @return Whatever the block returns.
     */
    suspend fun <R> mutate(
        scope: ScopeId,
        collection: CollectionId,
        block: suspend MutationRecorder.() -> R,
    ): R =
        transactions.transaction {
            val recorder = MutationRecorder()
            val result = recorder.block()
            queue(scope, collection, recorder)
            result
        }

    private suspend fun queue(
        scope: ScopeId,
        collection: CollectionId,
        recorder: MutationRecorder,
    ) {
        if (recorder.marks.isEmpty()) {
            return
        }
        val generation = stores.collections.ensure(scope, collection).generation

        // An entity whose group is already on the wire keeps that group: rebuilding the envelope for
        // a retry must produce the same operations, or the atomicity the group was created for is
        // gone. Its new edit stays dirty and is collected into a fresh group once the push settles.
        val inFlight = mutableMapOf<EntityKey, GroupId>()
        val mergeable = linkedSetOf<GroupId>()
        for (key in recorder.marks.keys) {
            val groupId = stores.records.groupOf(scope, collection, key.entityType, key.entityId) ?: continue
            when (stores.groups.find(groupId)?.state) {
                PushGroupState.PENDING -> mergeable += groupId

                PushGroupState.IN_FLIGHT -> inFlight[key] = groupId

                // A conflicted or failed group is not a place to add work to: it is waiting for a
                // decision, and a new edit must not silently join what the user has yet to resolve.
                else -> Unit
            }
        }

        // A group is created only when something actually needs one: if every marked entity stayed
        // with a group already on the wire, an empty group would be left behind for the push worker
        // to send with no operations in it.
        val target =
            if (recorder.marks.keys.any { it !in inFlight }) {
                mergeInto(scope, collection, mergeable)
            } else {
                null
            }
        for ((key, intent) in recorder.marks) {
            stores.records.markMutation(
                scope = scope,
                collection = collection,
                entityType = key.entityType,
                entityId = key.entityId,
                intent = intent,
                groupId = inFlight[key] ?: checkNotNull(target) { "no group for a freshly marked entity" },
                generation = generation,
            )
        }
    }

    private suspend fun mergeInto(
        scope: ScopeId,
        collection: CollectionId,
        mergeable: Set<GroupId>,
    ): GroupId {
        if (mergeable.isEmpty()) {
            val created = newGroupId()
            stores.groups.create(scope, collection, created)
            return created
        }
        val groups = mergeable.mapNotNull { stores.groups.find(it) }
        // The survivor takes the earliest position of the merged groups, so that merging never moves
        // a change behind something that was queued after it.
        val survivor = groups.minBy { it.ord }
        for (group in groups) {
            if (group.groupId != survivor.groupId) {
                stores.records.reassignGroup(group.groupId, survivor.groupId)
                stores.groups.delete(group.groupId)
            }
        }
        return survivor.groupId
    }
}
