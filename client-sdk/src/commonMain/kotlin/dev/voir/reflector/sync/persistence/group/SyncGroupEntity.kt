package dev.voir.reflector.sync.persistence.group

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverters
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlin.uuid.Uuid

/**
 * One group of local changes that has to reach the server atomically.
 *
 * A group exists because changes made in one local transaction cannot be sent apart: bodies are
 * materialised at push time, so the state a record had at the moment of the first transaction is no
 * longer reconstructable once a second one has touched it. Groups therefore grow by merging
 * whenever a mutation touches an entity that already belongs to a pending group.
 *
 * @property groupId Identifier of the group, which is also the idempotency key of its push.
 * @property scopeId Scope the group belongs to.
 * @property collectionId Collection the group belongs to.
 * @property ord Monotonic local ordinal; groups are pushed strictly in this order.
 * @property state Where the group is in the queue.
 * @property attempts Consecutive failed attempts, used to compute the backoff.
 * @property nextRetryAt Local timestamp before which the group must not be retried, in epoch
 *   milliseconds, or `null` when it may be sent immediately.
 * @property dependencyMerges How many times the group was merged after the server refused it as
 *   depending on another group. Bounded: without a ceiling, a server that keeps answering the same
 *   way would make the client merge forever.
 * @property lastError Description of the most recent failure, or `null` when there was none.
 */
@Entity(
    tableName = "sync_group",
    indices = [Index(value = ["scope_id", "collection_id", "state", "ord"])],
)
@ColumnTypeConverters(SyncColumnConverters::class)
public data class SyncGroupEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") public val groupId: Uuid,
    @ColumnInfo(name = "scope_id") public val scopeId: String,
    @ColumnInfo(name = "collection_id") public val collectionId: String,
    @ColumnInfo(name = "ord") public val ord: Long,
    @ColumnInfo(name = "state") public val state: PushGroupState,
    @ColumnInfo(name = "attempts") public val attempts: Int,
    @ColumnInfo(name = "next_retry_at") public val nextRetryAt: Long?,
    @ColumnInfo(name = "dependency_merges") public val dependencyMerges: Int,
    @ColumnInfo(name = "last_error") public val lastError: String?,
)
