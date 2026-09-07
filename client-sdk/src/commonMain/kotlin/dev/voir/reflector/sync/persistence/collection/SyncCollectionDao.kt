package dev.voir.reflector.sync.persistence.collection

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlinx.coroutines.flow.Flow

/** Access to the synchronisation state of collections. */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
public interface SyncCollectionDao {
    /**
     * Reads the state of one collection.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Stored state, or `null` when the collection has never been synchronised.
     */
    @Query("SELECT * FROM sync_collection WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun find(
        scopeId: String,
        collectionId: String,
    ): SyncCollectionEntity?

    /**
     * Observes the state of one collection.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @return Flow emitting the current state and every change to it, starting with `null` while
     *   the collection has not been synchronised yet.
     */
    @Query("SELECT * FROM sync_collection WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public fun observe(
        scopeId: String,
        collectionId: String,
    ): Flow<SyncCollectionEntity?>

    /**
     * Inserts or replaces the state of a collection.
     *
     * @param collection State to store.
     */
    @Upsert
    public suspend fun upsert(collection: SyncCollectionEntity)

    /**
     * Advances the cursor after a batch has been applied.
     *
     * Must be called in the same transaction that applied the batch: a cursor ahead of the data is
     * indistinguishable from a cursor behind it, and the changes in between are never re-read.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param cursor Position reached by the applied batch.
     * @param appliedAt Local timestamp of the pull, in epoch milliseconds, for diagnostics.
     */
    @Query(
        "UPDATE sync_collection SET cursor = :cursor, last_pull_at = :appliedAt, " +
            "last_error = NULL, failure_count = 0 " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun advanceCursor(
        scopeId: String,
        collectionId: String,
        cursor: String,
        appliedAt: Long,
    )

    /**
     * Sets the cursor without touching anything else.
     *
     * Used during a bootstrap to remember the position the snapshot was taken at. It has to be
     * durable from the first page on: a bootstrap that resumes after a restart must continue from
     * the position the server fixed then, not from the one it would fix now — everything committed
     * in between lives only in the log.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param cursor Position to remember.
     */
    @Query("UPDATE sync_collection SET cursor = :cursor WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun updateCursor(
        scopeId: String,
        collectionId: String,
        cursor: String?,
    )

    /**
     * Changes the lifecycle phase of a collection.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param phase Phase to move to.
     */
    @Query("UPDATE sync_collection SET phase = :phase WHERE scope_id = :scopeId AND collection_id = :collectionId")
    public suspend fun updatePhase(
        scopeId: String,
        collectionId: String,
        phase: SyncPhase,
    )

    /**
     * Remembers the shape the application's tables have now.
     *
     * Written together with whatever the change of shape caused — in practice, the move to
     * [SyncPhase.RESYNC_REQUIRED] — so that a crash between the two cannot leave a collection that
     * has already recorded the new shape and no longer knows it has to rebuild.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param fingerprint Shape declared by the adapter.
     */
    @Query(
        "UPDATE sync_collection SET schema_fingerprint = :fingerprint " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun updateSchemaFingerprint(
        scopeId: String,
        collectionId: String,
        fingerprint: String?,
    )

    /**
     * Starts a bootstrap: bumps the generation and forgets any unfinished snapshot position.
     *
     * The generation is what makes the sweep possible afterwards — records the new snapshot did not
     * mention keep the previous one and are removed at the end.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param phase Phase to enter, always [SyncPhase.BOOTSTRAPPING]; passed as a parameter rather
     *   than written into the statement so that renaming the constant breaks the compilation
     *   instead of the query.
     */
    @Query(
        "UPDATE sync_collection SET generation = generation + 1, phase = :phase, " +
            "bootstrap_page = NULL WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun beginBootstrap(
        scopeId: String,
        collectionId: String,
        phase: SyncPhase,
    )

    /**
     * Stores the continuation token of an unfinished snapshot transfer.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param page Token of the next page, or `null` once the last page has been applied.
     */
    @Query(
        "UPDATE sync_collection SET bootstrap_page = :page " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun updateBootstrapPage(
        scopeId: String,
        collectionId: String,
        page: String?,
    )

    /**
     * Finishes a bootstrap: adopts the cursor the snapshot was taken at and goes live.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param cursor Cursor the server fixed before the first snapshot page.
     * @param phase Phase to enter, always [SyncPhase.LIVE].
     */
    @Query(
        "UPDATE sync_collection SET cursor = :cursor, phase = :phase, bootstrap_page = NULL, " +
            "last_error = NULL, failure_count = 0 " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun finishBootstrap(
        scopeId: String,
        collectionId: String,
        cursor: String,
        phase: SyncPhase,
    )

    /**
     * Records a failed attempt and grows the failure counter the backoff is computed from.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param error Description of the failure.
     */
    @Query(
        "UPDATE sync_collection SET last_error = :error, failure_count = failure_count + 1 " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun recordFailure(
        scopeId: String,
        collectionId: String,
        error: String,
    )

    /**
     * Records a successful push.
     *
     * @param scopeId Scope of the collection.
     * @param collectionId Identifier of the collection.
     * @param pushedAt Local timestamp of the push, in epoch milliseconds, for diagnostics.
     */
    @Query(
        "UPDATE sync_collection SET last_push_at = :pushedAt, last_error = NULL, failure_count = 0 " +
            "WHERE scope_id = :scopeId AND collection_id = :collectionId",
    )
    public suspend fun recordPush(
        scopeId: String,
        collectionId: String,
        pushedAt: Long,
    )

    /**
     * Removes every collection of a scope, used when the scope's data is wiped.
     *
     * @param scopeId Scope to wipe.
     */
    @Query("DELETE FROM sync_collection WHERE scope_id = :scopeId")
    public suspend fun deleteScope(scopeId: String)
}
