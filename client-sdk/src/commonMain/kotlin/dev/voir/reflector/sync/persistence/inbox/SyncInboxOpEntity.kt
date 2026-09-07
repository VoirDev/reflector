package dev.voir.reflector.sync.persistence.inbox

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * One operation of a downloaded batch.
 *
 * The operation code is stored as it arrived rather than as a parsed value: a code this version of
 * the client does not know must survive being written down, so that applying the batch can fail
 * deliberately — and send the collection to a resynchronisation — instead of failing to parse the
 * response.
 *
 * @property scopeId Scope the operation belongs to.
 * @property collectionId Collection the operation belongs to.
 * @property seq Sequence of the batch it belongs to.
 * @property ordinal Position inside the batch; operations are applied in this order.
 * @property entityType Type of the affected entity.
 * @property entityId Identifier of the affected entity.
 * @property op Operation code exactly as it arrived on the wire.
 * @property version Version the entity has after this operation.
 * @property payload State of the entity as of this batch, as JSON text, or `null` for a removal.
 */
@Entity(
    tableName = "sync_inbox_op",
    primaryKeys = ["scope_id", "collection_id", "seq", "ordinal"],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncInboxOpEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "seq") public val seq: String,
    @ColumnInfo(name = "ordinal") public val ordinal: Int,
    @ColumnInfo(name = "entity_type") public val entityType: String,
    @ColumnInfo(name = "entity_id") public val entityId: Uuid,
    @ColumnInfo(name = "op") public val op: String,
    @ColumnInfo(name = "version") public val version: String,
    @ColumnInfo(name = "payload") public val payload: String?,
)
