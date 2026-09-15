package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Stored outcome of every push group.
 *
 * Idempotency rests on this table. A client that never saw the answer re-sends the same group, and
 * without a stored result the work would be applied a second time — which for a state-based
 * protocol means a second batch, a second version, and an echo the client cannot explain.
 */
internal object PushResultsTable : UuidTable("sync.push_results") {
    /** Collection the group was addressed to. */
    val collection = reference("collection_id", CollectionsTable, onDelete = ReferenceOption.CASCADE)

    /** Installation that sent the group. */
    val clientId = uuid("client_id")

    /** Group identifier, which is the idempotency key. */
    val groupId = uuid("group_id")

    /** Outcome code, kept readable for diagnostics rather than parsed back. */
    val status = varchar("status", STATUS_LENGTH)

    /** The answer that was produced, returned verbatim on a repeat. */
    val response = jsonb<PushGroupResult>("response", SyncProtocolJson.format)

    /** When the group was applied. */
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("uq_push_results__client_id__group_id", clientId, groupId)
        // The idempotency constraint above leads with the client, so it cannot serve a purge, which
        // deletes by collection — as does the cascade from the collection row.
        index("ix_push_results__collection_id", isUnique = false, collection)
    }

    /** Longest status code stored. */
    private const val STATUS_LENGTH = 20
}
