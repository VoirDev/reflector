package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.server.SyncConfig
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Reads and writes of the collection row, including the counter that orders everything.
 *
 * The lock taken here is the core of the protocol's correctness, and it has one rule that must not
 * be "optimised" away: it is taken in the same transaction that inserts the batch and is held until
 * that transaction commits. Taking the sequence in a short separate transaction, or from a
 * `SEQUENCE`, would let two writers commit in an order different from their sequences — and a
 * reader that has already passed the later one would never come back for the earlier. The change
 * would simply be gone, weeks later, unreproducible from logs.
 *
 * @property config Registered collections and limits.
 * @property clock Source of timestamps, injected so that tests are deterministic.
 */
internal class CollectionRows(
    private val config: SyncConfig,
    private val clock: Clock,
) {
    /**
     * Returns the row of a collection, creating it on first use.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection to look up.
     * @return State of the collection row.
     */
    fun ensure(
        scope: ScopeId,
        collection: CollectionId,
    ): CollectionRow {
        config.require(collection)
        find(scope, collection)?.let { return it }
        // Two hosts may create the same collection at once; the unique constraint decides, and the
        // loser simply reads what the winner wrote.
        CollectionsTable.insertIgnore { row ->
            row[id] = Uuid.random()
            row[scopeId] = scope.value
            row[collectionId] = collection.value
            row[nextSeq] = FIRST_SEQ
            row[retentionFloorSeq] = FIRST_SEQ
            row[createdAt] = clock.now()
        }
        return checkNotNull(find(scope, collection)) { "collection row disappeared right after insert" }
    }

    /**
     * Reads the row of a collection.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection to look up.
     * @return State of the row, or `null` when the collection has never been written to.
     */
    fun find(
        scope: ScopeId,
        collection: CollectionId,
    ): CollectionRow? =
        CollectionsTable
            .selectAll()
            .where { (CollectionsTable.scopeId eq scope.value) and (CollectionsTable.collectionId eq collection.value) }
            .singleOrNull()
            ?.let { row ->
                CollectionRow(
                    id = row[CollectionsTable.id].value,
                    nextSeq = row[CollectionsTable.nextSeq],
                    retentionFloorSeq = row[CollectionsTable.retentionFloorSeq],
                )
            }

    /**
     * Locks the collection row for the rest of the transaction and returns its state.
     *
     * @param id Row to lock.
     * @return State of the locked row.
     */
    fun lock(id: Uuid): CollectionRow =
        CollectionsTable
            .selectAll()
            .where { CollectionsTable.id eq id }
            .forUpdate()
            .single()
            .let { row ->
                CollectionRow(
                    id = row[CollectionsTable.id].value,
                    nextSeq = row[CollectionsTable.nextSeq],
                    retentionFloorSeq = row[CollectionsTable.retentionFloorSeq],
                )
            }

    private companion object {
        /** Sequences start at one, so that zero can mean "before anything was written". */
        const val FIRST_SEQ = 1L
    }
}

/**
 * State of one collection row.
 *
 * @property id Identifier of the row.
 * @property nextSeq Sequence the next batch will take.
 * @property retentionFloorSeq Oldest sequence still served.
 */
internal data class CollectionRow(
    val id: Uuid,
    val nextSeq: Long,
    val retentionFloorSeq: Long,
)

/**
 * Current state of one entity, as the push path needs it.
 *
 * @property version Version the entity currently has.
 * @property data Stored document, or `null` when the entity is a tombstone.
 * @property isDeleted Whether the entity has been deleted.
 */
internal data class EntityRow(
    val version: Long,
    val data: JsonObject?,
    val isDeleted: Boolean,
)

/** Key of one entity inside a collection. */
internal data class EntityKey(
    val entityType: EntityType,
    val entityId: EntityId,
)
