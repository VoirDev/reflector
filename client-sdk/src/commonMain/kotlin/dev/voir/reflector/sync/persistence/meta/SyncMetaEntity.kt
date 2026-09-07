package dev.voir.reflector.sync.persistence.meta

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * Per-scope identity of this installation.
 *
 * The client identifier is generated once, on first access to a scope, and never changes afterwards
 * while the data survives: echo suppression is built on it, and a new identifier would make the
 * device's own past changes arrive as somebody else's and be taken for conflicts. Losing this row
 * therefore has to be followed by a bootstrap, not by a plain reconnect.
 *
 * @property scopeId Scope the identity belongs to.
 * @property clientId Identifier this installation announces to the server.
 * @property createdAt Local timestamp of generation, in epoch milliseconds, for diagnostics only.
 */
@Entity(tableName = "sync_meta")
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncMetaEntity(
    @PrimaryKey @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "client_id") public val clientId: Uuid,
    @ColumnInfo(name = "created_at") public val createdAt: Long,
)
