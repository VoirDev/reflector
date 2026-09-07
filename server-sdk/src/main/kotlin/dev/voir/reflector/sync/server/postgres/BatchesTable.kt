package dev.voir.reflector.sync.server.postgres

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * One committed server transaction per row.
 *
 * The unit of the log is a batch rather than a single change, which is what makes it impossible for
 * a cursor to point inside a transaction: a page of the log can only be cut between batches.
 */
internal object BatchesTable : UuidTable("sync.batches") {
    /** Collection the batch belongs to. */
    val collection = reference("collection_id", CollectionsTable, onDelete = ReferenceOption.CASCADE)

    /** Position of the batch in the collection's log. */
    val seq = long("seq")

    /**
     * Installation whose push produced the batch, or `null` when it came from the host itself.
     *
     * Without it a client cannot recognise the echo of its own change, and every push would come
     * back looking like somebody else's edit.
     */
    val originClientId = uuid("origin_client_id").nullable()

    /** Group the batch was produced from, kept for diagnostics. */
    val clientGroupId = uuid("client_group_id").nullable()

    /** When the batch was committed. */
    val committedAt = timestamp("committed_at")

    init {
        uniqueIndex("uq_batches__collection_id__seq", collection, seq)
    }
}
