package dev.voir.reflector.sync.persistence.blob

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * Synchronisation metadata of one file. The bytes stay in the application's own store.
 *
 * Everything a transfer needs to be resumed after the process died is a column here rather than a
 * field in memory: a device that is killed mid-upload continues from the row's state on the next
 * run instead of starting the queue again.
 *
 * No ticket is ever stored. A presigned URL is a bearer capability with a short life, and a database
 * row is the wrong place for either half of that sentence.
 *
 * @property scopeId Scope the file belongs to.
 * @property collectionId Collection the file belongs to.
 * @property blobId Identifier the application generated, stored as a 16-byte blob.
 * @property state Where the bytes are, as far as this device knows.
 * @property wanted Whether this device is trying to hold the bytes.
 *
 *   The fact and the intent are kept in separate columns because they are separate questions: the
 *   state says where the bytes are, and this says whether anything is trying to change that. The
 *   download queue is exactly the rows where both are true, which is the whole of
 *   [dev.voir.reflector.sync.core.blob.BlobFetch] in the schema.
 *
 *   It is set by an eager reference, by the application asking for the file, and by the bytes being
 *   here already — a file this device holds is wanted by construction, which is what keeps the one
 *   predicate correct for uploads as well. Nothing but eviction and the row's own removal clears
 *   it: a request that a document change could silently discard would lose a user's tap.
 *
 *   The column default is the one an integration's migration needs rather than one this library
 *   relies on: a row that predates the column has nobody's wish recorded on it, and the eager
 *   references among them are picked up by the first reconciliation after the upgrade.
 * @property contentType Media type the application declared, or `null` before it has.
 * @property size Length in octets the application declared, or `null` before it has.
 * @property checksum Digest the application declared, or `null` when it computes none.
 * @property attempts Transfers tried since the last success, which the backoff grows with.
 * @property nextRetryAt When the next attempt may start, or `null` when one may start now.
 * @property lastError What the last attempt failed with, kept so that a queue stuck since a
 *   previous process can still say why.
 * @property transferred Octets moved in the current direction, for progress only.
 */
@Entity(
    tableName = "sync_blob",
    primaryKeys = ["scope_id", "collection_id", "blob_id"],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncBlobEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "blob_id") public val blobId: Uuid,
    @ColumnInfo(name = "state") public val state: BlobTransferState,
    @ColumnInfo(name = "wanted", defaultValue = "0") public val wanted: Boolean,
    @ColumnInfo(name = "content_type") public val contentType: String?,
    @ColumnInfo(name = "size") public val size: Long?,
    @ColumnInfo(name = "checksum") public val checksum: String?,
    @ColumnInfo(name = "attempts") public val attempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") public val nextRetryAt: Long? = null,
    @ColumnInfo(name = "last_error") public val lastError: String? = null,
    @ColumnInfo(name = "transferred") public val transferred: Long = 0,
)
