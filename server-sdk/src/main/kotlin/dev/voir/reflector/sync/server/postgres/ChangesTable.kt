package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Changes of a batch, in the order they were applied.
 *
 * [data] holds the state as of this batch rather than the entity's current state. That is what makes
 * reading the log incremental and resumable: a client that stopped halfway can continue, and every
 * operation it applies is complete on its own.
 */
internal object ChangesTable : UuidTable("sync.changes") {
    /** Batch this change belongs to. */
    val batch = reference("batch_id", BatchesTable, onDelete = ReferenceOption.CASCADE)

    /** Position inside the batch; changes are applied in this order. */
    val ordinal = integer("ordinal")

    /** Type of the changed entity. */
    val entityType = varchar("entity_type", CollectionsTable.COLLECTION_LENGTH)

    /** Identifier of the changed entity. */
    val entityId = uuid("entity_id")

    /** Operation code as the protocol spells it. */
    val op = varchar("op", OP_LENGTH)

    /** Version the entity has after this change; equal to the batch's sequence. */
    val version = long("version")

    /** State as of this batch, or `null` for a removal. */
    val data = jsonb<JsonObject>("data", SyncProtocolJson.format).nullable()

    init {
        uniqueIndex("uq_changes__batch_id__ordinal", batch, ordinal)
    }

    /** Longest operation code; the protocol's codes are short words. */
    private const val OP_LENGTH = 10
}
