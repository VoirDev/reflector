package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.core.blob.BlobSyncState
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.diagnostics.CollectionDiagnostics
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Handle of one synchronised collection: the unit of consistency, cursor and push queue.
 *
 * Everything the application does with the library goes through a collection handle: local changes
 * are announced inside [mutate], progress is observed through [state], and conflicts the adapter
 * refused to decide are resolved through [resolve].
 */
public interface CollectionHandle {
    /** Progress of the collection: its phase, queue depth, open conflicts and last failure. */
    public val state: StateFlow<CollectionSyncState>

    /**
     * Conflicts waiting for the application to decide.
     *
     * Only conflicts the adapter declined to resolve appear here; the ones it decided are applied
     * without ever reaching this flow.
     */
    public val conflicts: Flow<List<Conflict>>

    /**
     * Groups of local changes the server refused for good, and what it objected to.
     *
     * Read from storage, so it survives a restart: the adapter's `onRejected` is told once, at the
     * moment of the refusal, while the group stays at the head of the queue — blocking everything
     * behind it — until the data changes or [discardLocalChanges] throws it away. This is what an
     * interface lists when it has to say what cannot be synced and why. Empty while nothing is
     * refused.
     */
    public val refusals: Flow<List<RefusedGroup>>

    /**
     * Publishes the progress of one file.
     *
     * What a photograph in a list binds to, and per file rather than as a list of every file in the
     * collection because the screen drawing one already knows which identifier it is drawing.
     *
     * Two of the states it reports are not errors and have to be drawn as ordinary:
     * [dev.voir.reflector.sync.core.blob.BlobTransferState.REMOTE] and
     * [dev.voir.reflector.sync.core.blob.BlobTransferState.DOWNLOADING] are what an attachment looks
     * like on a device whose record arrived ahead of its bytes, which is the ordinary result of
     * [dev.voir.reflector.sync.core.blob.BlobBinding.DEFERRED].
     *
     * @param id File to observe.
     * @return Progress of the file, or `null` while the library knows nothing about it — which is
     *   the state before any document has been seen to name it, and after it has been let go of.
     *   There is also a brief `null` right after a pulled document first names a file: the document
     *   and its reference are applied in one transaction, but whether this device already holds
     *   the bytes is asked of the application's store only afterwards, and the file is described
     *   once that answer is in. A screen that draws `null` as "no file yet" is right for that
     *   moment too.
     */
    public fun blob(id: BlobId): Flow<BlobSyncState?>

    /**
     * Publishes the progress of every file some document of this collection still points at.
     *
     * For a screen that reports on files rather than drawing one — what is still on its way out,
     * what is arriving, what keeps failing. Files nothing references any more are left out: they
     * are on their way to being offered back to the application and are not the owner's to worry
     * about.
     *
     * The same caveats as [blob] apply to each entry: [BlobTransferState.REMOTE] and
     * [BlobTransferState.DOWNLOADING] are ordinary, and a [BlobSyncState.lastError] on a file that
     * is still moving is a failed attempt with another one coming.
     */
    public val blobs: Flow<Map<BlobId, BlobSyncState>>

    /**
     * Asks for a file's bytes to be brought to this device.
     *
     * What a screen calls when it opens a record whose file was left behind under
     * [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND]. It records the wish and returns; the
     * transfer is the worker's, and its progress is read through [blob]. The wish is durable, so a
     * download interrupted by the process dying resumes rather than waiting to be asked again, and
     * the file stays on the device afterwards until [evict] gives it up — which is why opening the
     * same record a second time shows the file at once.
     *
     * Idempotent, and safe to call on every screen that draws the file. Calling it for a file whose
     * bytes are already here marks it wanted and does nothing else. Calling it for a file the
     * library has given up on — [dev.voir.reflector.sync.core.blob.BlobTransferState.UNAVAILABLE]
     * after a download that kept failing — starts again from zero attempts, which is what makes
     * this the call behind a retry button. What it does not do is shorten a backoff that is still
     * running: a screen redrawing itself is not a reason to try a failing transfer sooner.
     *
     * A file no document of this collection references is not fetched, whoever asks. The library
     * would have nothing to keep it for, and reconciliation would offer it straight back. A
     * collection that synchronises no files at all — one whose engine was given no
     * [dev.voir.reflector.sync.core.blob.BlobStore] — ignores the call, as it ignores every other
     * blob path.
     *
     * @param id File to fetch.
     */
    public suspend fun fetch(id: BlobId)

    /**
     * Gives up a file's bytes on this device, leaving the file itself on the server.
     *
     * The other half of [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND]: a device that
     * fetches on demand eventually wants the space back. The application's store is told to remove
     * the bytes and the file returns to being one the server has and this device does not, so the
     * same record can be opened again later and fetched again.
     *
     * Refused, with a line in the log, for a file whose bytes are the only copy — one this device
     * created and has not finished uploading. Discarding those would not be freeing a cache, it
     * would be losing the user's file.
     *
     * A file some document references [dev.voir.reflector.sync.core.blob.BlobFetch.EAGER]ly comes
     * straight back: the declaration says this device keeps it, so the next reconciliation wants it
     * again and fetches it. Eviction is for what was fetched on demand.
     *
     * @param id File to give up.
     */
    public suspend fun evict(id: BlobId)

    /**
     * Tries a file again now, whichever way it was moving.
     *
     * The call behind a "try again" button on a screen listing files. A file given up on —
     * [BlobTransferState.UNAVAILABLE] — starts again from zero attempts: as an upload when this
     * device holds its bytes, as a download otherwise. A file still moving has its backoff cut short
     * and its attempts forgotten, which is the difference from [fetch]: a person asking is a reason
     * to try sooner, a screen redrawing itself is not.
     *
     * A file the library has never heard of, or one already where it belongs, is left alone.
     *
     * @param id File to try again.
     */
    public suspend fun retry(id: BlobId)

    /**
     * Runs a block of local changes as one transaction of the application's database.
     *
     * The block is the boundary of atomicity for synchronisation as well: entities marked inside it
     * are guaranteed to reach the server together. That guarantee is what forces the library to
     * merge push groups that share an entity — once two changes were made in one transaction, they
     * can no longer be sent apart.
     *
     * The application writes its own rows inside the block and announces them through
     * [MutationScope]; the library never discovers changes on its own.
     *
     * @param block Changes to perform, with the marking API in scope.
     * @return Whatever the block returns.
     */
    public suspend fun <R> mutate(block: suspend MutationScope.() -> R): R

    /**
     * Takes rows the application already holds, and the library has never been told about, into
     * synchronisation, so that each is sent to the server as created.
     *
     * For data that existed before there was anybody to synchronise it for — a device used without
     * an account whose owner now signs in, a store migrated in from elsewhere. [mutate] is the
     * wrong tool there: a block is one group, and a group cannot be split without breaking the
     * atomicity it was made for, so a block marking every row of a large table would be refused as
     * too large and would then block the queue behind it.
     *
     * These rows were never changed together, so nothing ties them to each other. They are queued
     * as several groups, each within the server's limit on operations per group, in the order
     * given — which is the order they reach the server, so a row another one refers to belongs
     * earlier in the list, or in an earlier call. All of them are queued in one transaction, so a
     * process that dies part-way leaves none of them queued rather than some.
     *
     * Only rows the library has no record of are taken. A row it already tracks — synchronised,
     * waiting to be sent, or pulled from the server — is left exactly as it is, which makes a
     * repeated call with the same identifiers queue nothing the second time.
     *
     * The limit is the server's, so this waits until this device has heard it, which the first
     * cycle after the collection is opened asks for. Offline, or with credentials the server no
     * longer accepts, that wait lasts until the connection is back; a caller that cannot wait that
     * long bounds it with a timeout of its own. Nothing is queued before the wait ends.
     *
     * Bodies are read through the adapter at push time, as for every other change, and the files
     * each document names are declared with it. A row whose identifier the server already holds is
     * a conflict, settled as any other: the library cannot tell one from a row it never saw.
     *
     * Must not be called from inside the adapter's callbacks: it may wait on a cycle, and they run
     * inside that cycle.
     *
     * @param entityType Type of every row in [ids].
     * @param ids Rows to take in, in the order they should reach the server. Duplicates count once.
     * @return How many rows were queued: those the library had no record of.
     */
    public suspend fun adopt(
        entityType: EntityType,
        ids: List<EntityId>,
    ): Int

    /**
     * Asks the workers to synchronise now instead of waiting for the next trigger.
     *
     * The call returns once the request is accepted, not once synchronisation has finished:
     * progress is observed through [state].
     */
    public suspend fun requestSync()

    /**
     * Throws away what is known about the collection and rebuilds it from the server.
     *
     * The library cannot detect when the application's own tables stopped agreeing with what it
     * synchronised — a migration that rewrites rows, a repair after a bug, an import from elsewhere.
     * In those cases a bootstrap is cheaper than trusting a cursor whose data has changed underneath
     * it. Local changes that never reached the server survive: they are pushed afterwards.
     */
    public suspend fun requestResync()

    /**
     * Throws away every change this device has not managed to send, and rebuilds the collection
     * from the server.
     *
     * The way out of a queue the server keeps refusing. [requestResync] cannot be it: it keeps the
     * unsent changes and pushes them again afterwards, so a group the server refused is refused
     * again and everything behind it stays where it was. Only the user can decide that what this
     * device holds is worth less than a working queue, which is why the library never does this on
     * its own — except for a purged collection, where there is nothing left to keep the changes for.
     *
     * The discard happens before this returns, in one transaction, with or without a connection: the
     * queue, the open conflicts and the pending edits are gone, [CollectionSyncState.pendingCount]
     * drops to zero, and entities created on this device that the server never confirmed are deleted
     * through the adapter at once. A cycle running at the time is cancelled rather than waited for.
     * Changes made after this returns are new changes and are kept.
     *
     * What the server does have — an entity edited or deleted here — needs the server's copy, so
     * those rows still show the discarded change until the snapshot that follows overwrites or
     * restores them. Until then [CollectionSyncState.phase] stays [SyncPhase.RESYNC_REQUIRED] or
     * [SyncPhase.BOOTSTRAPPING]; the discard is complete when it is [SyncPhase.LIVE] again. An edit
     * made to such a row in the meantime is made on top of the discarded change, so it is not pushed
     * as though it were based on the server's state: the snapshot opens a conflict for it instead.
     * Files that only this device held go with the documents that pointed at them.
     *
     * Must not be called from inside the adapter's callbacks: it waits for the running cycle to stop,
     * and they run inside that cycle.
     *
     * @throws IllegalStateException When called from inside one of the adapter's callbacks.
     */
    public suspend fun discardLocalChanges()

    /**
     * Applies a decision to a conflict that was waiting for the application.
     *
     * The decision is applied in one transaction together with the resulting change of local
     * metadata, so a crash cannot leave a resolved conflict with unresolved bookkeeping.
     *
     * @param conflictId Conflict to resolve; it must still be open.
     * @param resolution Decision to apply.
     * @throws IllegalArgumentException When the conflict is unknown or has already been resolved.
     */
    public suspend fun resolve(
        conflictId: ConflictId,
        resolution: Resolution,
    )

    /**
     * Reads everything the library knows about this collection, at this moment.
     *
     * For a developer rather than for a user interface. [state] is what a screen binds to and is
     * kept deliberately small; this is what answers the question that state provokes — the queue
     * itself, the error each group was last refused with, how far the cursor has got, and when a
     * pull or a push last succeeded. Most of it is durable and therefore survives the restart after
     * which a published `lastFailure` is gone.
     *
     * Reads the database, so it is not free and is not something to call on every frame. The values
     * describe a moment that has passed by the time they are read: they are for a debug screen, a
     * bug report or a log line, not for driving the engine.
     *
     * @return Snapshot of the collection's synchronisation state.
     */
    public suspend fun diagnostics(): CollectionDiagnostics
}
