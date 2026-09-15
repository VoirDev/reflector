package dev.voir.reflector.sync.persistence.inbox

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * One downloaded server transaction, waiting to be applied.
 *
 * @property scopeId Scope the batch belongs to.
 * @property collectionId Collection the batch belongs to.
 * @property seq Opaque sequence of the batch, for ordering and for deleting it once applied.
 * @property cursor Position to store once the batch has been applied, served by the server with the
 *   batch. Kept here rather than derived from [seq] when the batch is applied, because it names the
 *   incarnation of the collection as well as the position, and a batch can sit in this table across
 *   a restart.
 * @property receivedOrd Position of the batch in the order the server returned it. Batches must be
 *   applied in exactly that order, and the sequence cannot provide it: it is an opaque string, so
 *   sorting by it would put "10" before "9" and let the cursor jump over a batch.
 * @property originClientId Installation that produced the batch, or `null` when it did not come
 *   from a client. Without it the client cannot tell its own change from somebody else's, and every
 *   echo of its own push would look like a conflict.
 * @property state Whether the batch still has to be applied.
 */
@Entity(tableName = "sync_inbox_batch", primaryKeys = ["scope_id", "collection_id", "seq"])
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncInboxBatchEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "seq") public val seq: String,
    @ColumnInfo(name = "cursor") public val cursor: String,
    @ColumnInfo(name = "received_ord") public val receivedOrd: Long,
    @ColumnInfo(name = "origin_client_id") public val originClientId: Uuid?,
    @ColumnInfo(name = "state") public val state: InboxBatchState,
)
