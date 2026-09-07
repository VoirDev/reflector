package dev.voir.reflector.sync.server.postgres

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Collections the module serves, one row each.
 *
 * The row carries the counter that orders everything inside the collection. Handing out a sequence
 * means locking this row until the transaction commits, so writers to one collection serialise —
 * deliberately: a reader must never be able to see a gap, and a gap is exactly what appears if two
 * transactions take sequences 5 and 6 and commit in the other order.
 *
 * Primary keys carry no explicit name here: `UuidTable` declares its own and does not let it be
 * overridden. The physical names live in the Flyway migration, which owns the schema anyway.
 */
internal object CollectionsTable : UuidTable("sync.collections") {
    /** Scope the collection belongs to, already authorised by the host. */
    val scopeId = varchar("scope_id", SCOPE_LENGTH)

    /** Identifier clients address the collection by. */
    val collectionId = varchar("collection_id", COLLECTION_LENGTH)

    /** Sequence the next batch of this collection will take. */
    val nextSeq = long("next_seq")

    /** Oldest sequence still served; a cursor below it is refused as too old. */
    val retentionFloorSeq = long("retention_floor_seq")

    /** When the collection was first written to. */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_collections__scope_id__collection_id", scopeId, collectionId)
    }

    /** Longest scope identifier accepted; hosts key scopes by user or tenant identifiers. */
    const val SCOPE_LENGTH: Int = 200

    /** Longest collection identifier accepted. */
    const val COLLECTION_LENGTH: Int = 100
}
