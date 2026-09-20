package dev.voir.reflector.sync.persistence.collection

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.persistence.SyncColumnConverters

/**
 * Synchronisation state of one collection inside one scope.
 *
 * The row is the collection's whole bookkeeping: where its log reading stands, which lifecycle
 * phase it is in, and how the last attempt went. It lives in the application's database, in the
 * same transaction domain as the application's own rows — otherwise applying a batch and advancing
 * the cursor could not be one transaction, and a crash between them would leave the cursor ahead of
 * the data with no way to notice.
 *
 * @property scopeId Scope the collection belongs to.
 * @property collectionId Identifier of the collection.
 * @property cursor Opaque position in the change log, or `null` when nothing has been read yet.
 *   Advanced only together with applying the batch it points at.
 * @property epoch Incarnation of the collection on the server this row's cursor, versions and
 *   queue belong to, or `null` before the first answer from it. A server that answers with a
 *   different one has replaced the collection since — the cursor no longer means what it meant,
 *   and everything stored under it is discarded rather than carried across.
 * @property phase Lifecycle phase, which decides who is allowed to run.
 * @property generation Bootstrap counter, incremented at the start of every bootstrap. Records
 *   touched by the current bootstrap carry it, and everything left behind is swept afterwards.
 * @property bootstrapPage Continuation token of an unfinished snapshot transfer, or `null` when no
 *   bootstrap is in progress.
 * @property lastPullAt Local timestamp of the last pull that reached the end of the log, in epoch
 *   milliseconds, whether or not it applied anything — a client in step pulls nothing on most
 *   cycles, and this is what lets an application say when it last synchronised rather than when
 *   data last moved. A finished bootstrap writes it too. Purely diagnostic: client clocks take part
 *   in no decision about order or versions.
 * @property lastPushAt Local timestamp of the last push the server applied, in epoch milliseconds.
 *   Unlike [lastPullAt] it moves only when there was something to send, because a client with an
 *   empty queue does not push at all. Also purely diagnostic.
 * @property schemaFingerprint Shape of the application's tables as it was when this collection was
 *   last synchronised, or `null` when the adapter declares none. A value different from the one the
 *   adapter declares now means the rows have been rewritten underneath the cursor, and the
 *   collection is rebuilt from a snapshot.
 * @property lastError Description of the most recent failure, or `null` after a success.
 * @property failureCount Consecutive failures, used to compute the backoff of the collection.
 */
@Entity(tableName = "sync_collection", primaryKeys = ["scope_id", "collection_id"])
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncCollectionEntity(
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "cursor") public val cursor: String?,
    @ColumnInfo(name = "epoch") public val epoch: String?,
    @ColumnInfo(name = "phase") public val phase: SyncPhase,
    @ColumnInfo(name = "generation") public val generation: Long,
    @ColumnInfo(name = "bootstrap_page") public val bootstrapPage: String?,
    @ColumnInfo(name = "last_pull_at") public val lastPullAt: Long?,
    @ColumnInfo(name = "last_push_at") public val lastPushAt: Long?,
    @ColumnInfo(name = "schema_fingerprint") public val schemaFingerprint: String?,
    @ColumnInfo(name = "last_error") public val lastError: String?,
    @ColumnInfo(name = "failure_count") public val failureCount: Int,
)
