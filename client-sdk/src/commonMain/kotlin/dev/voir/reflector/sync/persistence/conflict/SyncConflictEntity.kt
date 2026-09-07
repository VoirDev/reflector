package dev.voir.reflector.sync.persistence.conflict

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * Two irreconcilable states of one entity, recorded until somebody decides between them.
 *
 * Both payloads are materialised at detection time and stored as JSON text. That is the one place
 * where the library keeps a copy of business data, and it is unavoidable: a conflict detected on a
 * pull is written in the same transaction that advances the cursor, and after that the incoming
 * state exists nowhere else — the log has moved on and the local row still holds the user's version.
 *
 * @property conflictId Identifier of the conflict, stable until it is resolved.
 * @property scopeId Scope the conflict belongs to.
 * @property collectionId Collection the conflict belongs to.
 * @property entityType Type of the conflicting entity.
 * @property entityId Identifier of the conflicting entity.
 * @property origin Whether the conflict was found while pulling or while pushing.
 * @property localPayload Local state at detection time as JSON text, or `null` when the entity was
 *   deleted locally.
 * @property serverPayload Server state at detection time as JSON text, or `null` when the server
 *   deleted the entity, which makes keeping the local side a re-creation under the same identifier.
 * @property serverVersion Server version the conflict was detected against; a resolution is applied
 *   on top of it.
 * @property detectedAt Local timestamp of detection, in epoch milliseconds, for diagnostics only.
 */
@Entity(
    tableName = "sync_conflict",
    indices = [Index(value = ["scope_id", "collection_id"])],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncConflictEntity(
    @PrimaryKey @ColumnInfo(name = "conflict_id") public val conflictId: Uuid,
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "entity_type") public val entityType: String,
    @ColumnInfo(name = "entity_id") public val entityId: Uuid,
    @ColumnInfo(name = "origin") public val origin: ConflictOrigin,
    @ColumnInfo(name = "local_payload") public val localPayload: String?,
    @ColumnInfo(name = "server_payload") public val serverPayload: String?,
    @ColumnInfo(name = "server_version") public val serverVersion: String,
    @ColumnInfo(name = "detected_at") public val detectedAt: Long,
)
