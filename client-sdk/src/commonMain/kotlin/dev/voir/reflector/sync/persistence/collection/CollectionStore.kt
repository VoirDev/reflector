package dev.voir.reflector.sync.persistence.collection

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * Typed access to the synchronisation state of collections.
 *
 * Wraps [SyncCollectionDao] so that the engine works with cursors, page tokens and phases instead
 * of the strings SQLite stores. Every mutating call is meant to run inside the transaction of the
 * work it describes.
 *
 * @property dao Generated data access object of the collection table.
 */
internal class CollectionStore(
    private val dao: SyncCollectionDao,
) {
    /**
     * Reads the state of a collection.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Current state, or `null` when the collection has never been synchronised.
     */
    public suspend fun find(
        scope: ScopeId,
        collection: CollectionId,
    ): CollectionState? = dao.find(scope.value, collection.value)?.toState()

    /**
     * Creates the state of a collection if it does not exist yet.
     *
     * Inserts rather than upserts: an existing row carries the cursor, and replacing it would
     * silently restart the collection from nothing.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return State of the collection, existing or freshly created.
     */
    public suspend fun ensure(
        scope: ScopeId,
        collection: CollectionId,
    ): CollectionState {
        val existing = find(scope, collection)
        if (existing != null) {
            return existing
        }
        dao.upsert(
            SyncCollectionEntity(
                scopeId = scope.value,
                collectionId = collection.value,
                cursor = null,
                epoch = null,
                phase = SyncPhase.NEW,
                generation = 0,
                bootstrapPage = null,
                lastPullAt = null,
                lastPushAt = null,
                schemaFingerprint = null,
                lastError = null,
                failureCount = 0,
            ),
        )
        return checkNotNull(find(scope, collection)) { "collection state disappeared right after insert" }
    }

    /**
     * Observes the state of a collection.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Flow emitting the current state and every change to it.
     */
    public fun observe(
        scope: ScopeId,
        collection: CollectionId,
    ): Flow<CollectionState?> = dao.observe(scope.value, collection.value).map { it?.toState() }

    /**
     * Advances the cursor after a batch has been applied.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param cursor Position reached by the applied batch.
     * @param appliedAt Local timestamp of the pull, in epoch milliseconds.
     */
    public suspend fun advanceCursor(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor,
        appliedAt: Long,
    ) {
        dao.advanceCursor(scope.value, collection.value, cursor.value, appliedAt)
    }

    /**
     * Records that a pull reached the end of the log.
     *
     * Called by a pull that caught up whether or not it applied anything, because "the client and
     * the server spoke and there was nothing to do" is the ordinary shape of a cycle on a client
     * that is in step — and it is the answer an application needs to say when it last
     * synchronised. See [SyncCollectionDao.recordPull] for why it writes only the timestamp.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param polledAt Local timestamp of the pull, in epoch milliseconds.
     */
    public suspend fun recordPull(
        scope: ScopeId,
        collection: CollectionId,
        polledAt: Long,
    ) {
        dao.recordPull(scope.value, collection.value, polledAt)
    }

    /**
     * Remembers the position a bootstrap snapshot was taken at.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param cursor Position the server fixed before the first snapshot page.
     */
    public suspend fun setCursor(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
    ) {
        dao.updateCursor(scope.value, collection.value, cursor?.value)
    }

    /**
     * Moves a collection to another lifecycle phase.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param phase Phase to move to.
     */
    public suspend fun setPhase(
        scope: ScopeId,
        collection: CollectionId,
        phase: SyncPhase,
    ) {
        dao.updatePhase(scope.value, collection.value, phase)
    }

    /**
     * Remembers the shape the application declared for its own tables.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param fingerprint Shape declared by the adapter, or `null` to forget the stored one.
     */
    public suspend fun setSchemaFingerprint(
        scope: ScopeId,
        collection: CollectionId,
        fingerprint: SchemaFingerprint?,
    ) {
        dao.updateSchemaFingerprint(scope.value, collection.value, fingerprint?.value)
    }

    /**
     * Starts a bootstrap and returns the generation it runs under.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @return Generation every record confirmed by this bootstrap has to carry.
     */
    public suspend fun beginBootstrap(
        scope: ScopeId,
        collection: CollectionId,
    ): Long {
        dao.beginBootstrap(scope.value, collection.value, SyncPhase.BOOTSTRAPPING)
        return checkNotNull(find(scope, collection)) { "collection state missing while bootstrapping" }.generation
    }

    /**
     * Stores the position of an unfinished snapshot transfer.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param page Token of the next page, or `null` once the snapshot is exhausted.
     */
    public suspend fun updateBootstrapPage(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
    ) {
        dao.updateBootstrapPage(scope.value, collection.value, page?.value)
    }

    /**
     * Finishes a bootstrap: adopts the snapshot's cursor and goes live.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param cursor Cursor the server fixed before the first snapshot page.
     * @param appliedAt Local timestamp of the finished transfer, in epoch milliseconds. A bootstrap
     * is an exchange like any other and records when it happened — see [recordPull].
     */
    public suspend fun finishBootstrap(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor,
        appliedAt: Long,
    ) {
        dao.finishBootstrap(scope.value, collection.value, cursor.value, SyncPhase.LIVE, appliedAt)
    }

    /**
     * Remembers which incarnation of the collection the server answered from.
     *
     * Called on every answer, and writes only when the value differs: the row is observed as a flow,
     * and rewriting it on every page of every pull would wake the application's subscribers for a
     * value that had not changed.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param epoch Incarnation the server named.
     */
    public suspend fun setEpoch(
        scope: ScopeId,
        collection: CollectionId,
        epoch: CollectionEpoch,
    ) {
        dao.setEpoch(scope.value, collection.value, epoch.value)
    }

    /**
     * Forgets the previous incarnation and sends the collection back to a snapshot.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     */
    public suspend fun forgetIncarnation(
        scope: ScopeId,
        collection: CollectionId,
    ) {
        dao.forgetIncarnation(scope.value, collection.value, SyncPhase.RESYNC_REQUIRED)
    }

    /**
     * Records a failed attempt.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param error Description of the failure.
     */
    public suspend fun recordFailure(
        scope: ScopeId,
        collection: CollectionId,
        error: String,
    ) {
        dao.recordFailure(scope.value, collection.value, error)
    }

    /**
     * Records a successful push.
     *
     * @param scope Scope of the collection.
     * @param collection Identifier of the collection.
     * @param pushedAt Local timestamp of the push, in epoch milliseconds.
     */
    public suspend fun recordPush(
        scope: ScopeId,
        collection: CollectionId,
        pushedAt: Long,
    ) {
        dao.recordPush(scope.value, collection.value, pushedAt)
    }

    /**
     * Removes every collection of a scope.
     *
     * @param scope Scope to wipe.
     */
    public suspend fun deleteScope(scope: ScopeId) {
        dao.deleteScope(scope.value)
    }

    private fun SyncCollectionEntity.toState(): CollectionState =
        CollectionState(
            cursor = cursor?.let(::Cursor),
            epoch = epoch?.let(::CollectionEpoch),
            phase = phase,
            generation = generation,
            bootstrapPage = bootstrapPage?.let(::PageToken),
            schemaFingerprint = schemaFingerprint?.let(::SchemaFingerprint),
            failureCount = failureCount,
            lastError = lastError,
            // Stored as epoch milliseconds because that is what SQLite has; handed on as an Instant
            // because that is what the rest of the library and the application speak.
            lastPullAt = lastPullAt?.let(Instant::fromEpochMilliseconds),
            lastPushAt = lastPushAt?.let(Instant::fromEpochMilliseconds),
        )
}
