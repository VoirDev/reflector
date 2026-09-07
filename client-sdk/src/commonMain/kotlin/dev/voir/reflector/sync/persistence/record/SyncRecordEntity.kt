package dev.voir.reflector.sync.persistence.record

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.Index
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * Synchronisation metadata of one entity. The entity's own data stays in the application's tables.
 *
 * A record is dirty while `localRev > ackedRev`. Three revisions are kept rather than one flag
 * because a record may be edited while its push is in flight: the push captures [pushingRev] when
 * the envelope is assembled and acknowledges only up to it, so an edit made in the meantime leaves
 * the record dirty and lands in the next group instead of being silently acknowledged.
 *
 * @property scopeId Scope the record belongs to.
 * @property collectionId Collection the record belongs to.
 * @property entityType Type of the entity.
 * @property entityId Identifier of the entity, stored as a 16-byte blob.
 * @property serverVersion Version the server last confirmed, or `null` when the server does not
 *   know the entity yet. Sent as the base version of the next push.
 * @property localRev Local revision, incremented by every local mutation.
 * @property pushingRev Revision captured when the current envelope was assembled.
 * @property ackedRev Revision the server has confirmed.
 * @property intent Operation the last local mutation implies, or `null` when the record is clean.
 * @property groupId Push group the dirty record belongs to, or `null` when it is clean.
 * @property conflictId Open conflict of this record, or `null` when there is none.
 * @property seenGen Bootstrap generation that last confirmed the record exists on the server.
 *   Records left behind by a bootstrap are swept by comparing it with the collection's generation.
 */
@Entity(
    tableName = "sync_record",
    primaryKeys = ["scope_id", "collection_id", "entity_type", "entity_id"],
    indices = [Index(value = ["scope_id", "collection_id", "group_id"])],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncRecordEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "entity_type") public val entityType: String,
    @ColumnInfo(name = "entity_id") public val entityId: Uuid,
    @ColumnInfo(name = "server_version") public val serverVersion: String?,
    @ColumnInfo(name = "local_rev") public val localRev: Long,
    @ColumnInfo(name = "pushing_rev") public val pushingRev: Long,
    @ColumnInfo(name = "acked_rev") public val ackedRev: Long,
    @ColumnInfo(name = "intent") public val intent: MutationIntent?,
    @ColumnInfo(name = "group_id") public val groupId: Uuid?,
    @ColumnInfo(name = "conflict_id") public val conflictId: Uuid?,
    @ColumnInfo(name = "seen_gen") public val seenGen: Long,
)
