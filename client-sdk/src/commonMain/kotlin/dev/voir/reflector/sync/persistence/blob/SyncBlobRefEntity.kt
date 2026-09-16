package dev.voir.reflector.sync.persistence.blob

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.Index
import dev.voir.reflector.sync.core.blob.BlobBinding
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * A pointer from one of the application's documents to one file.
 *
 * Written in the transaction that materialises a document for a push or applies one that arrived,
 * and replaced whole for that entity each time. There is no "reference added" or "reference removed"
 * to lose, which matters here more than elsewhere: the only consequence of losing one is offering
 * the application a file to delete while a document still names it.
 *
 * @property scopeId Scope the reference belongs to.
 * @property collectionId Collection the reference belongs to.
 * @property entityType Type of the entity whose document holds the reference.
 * @property entityId Identifier of that entity, stored as a 16-byte blob.
 * @property blobId File the document points at, stored as a 16-byte blob.
 * @property binding Whether the record may be published before this file is usable.
 * @property fetch Whether this device downloads the file without being asked. Stored resolved: the
 *   application may leave [dev.voir.reflector.sync.core.blob.BlobRef.fetch] unstated, and the
 *   engine's configured policy is applied before the row is written, so nothing downstream has to
 *   know what the default was.
 *
 *   The column default is eager, so that a reference written before the column existed keeps the
 *   behaviour the installation already had: everything a document names is held on the device.
 * @property seenGen Bootstrap generation that last confirmed the reference. It takes part in the
 *   sweep exactly as a record's does, so a reference to a document the snapshot no longer mentions
 *   goes away with the document.
 */
@Entity(
    tableName = "sync_blob_ref",
    primaryKeys = ["scope_id", "collection_id", "entity_type", "entity_id", "blob_id"],
    indices = [Index(value = ["scope_id", "collection_id", "blob_id"])],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncBlobRefEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "entity_type") public val entityType: String,
    @ColumnInfo(name = "entity_id") public val entityId: Uuid,
    @ColumnInfo(name = "blob_id") public val blobId: Uuid,
    @ColumnInfo(name = "binding") public val binding: BlobBinding,
    @ColumnInfo(name = "fetch", defaultValue = "'EAGER'") public val fetch: BlobFetch,
    @ColumnInfo(name = "seen_gen") public val seenGen: Long,
)
