package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage

/**
 * The synchronisation module, as the host application calls it.
 *
 * Five operations cover the whole protocol; the host builds its own transport on top of them and the
 * module knows nothing about it. Authentication and authorisation are equally none of its business:
 * a [ScopeId] arrives **already authorised**, and re-checking access here would mean the same rules
 * living in two places, drifting apart at the first change.
 *
 * The API is blocking. Exposed on JDBC is blocking by nature, and pretending otherwise would only
 * move the thread that waits; staying blocking also keeps the module callable from Java.
 */
public interface SyncService {
    /**
     * Applies groups of client changes.
     *
     * Groups are applied one at a time, each in its own transaction, and independently of each
     * other: a conflict in one must not roll back another that was already accepted. Re-sending a
     * group returns the stored outcome instead of applying it twice.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to write to; it must be registered in the module's configuration.
     * @param request Groups offered by the client.
     * @return Outcome of every group of the request, in the order they were sent.
     * @throws UnknownCollectionException When the collection is not registered.
     */
    public fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse

    /**
     * Reads a page of the collection's change log.
     *
     * Pages are cut on batch boundaries only, so a cursor can never point inside a server
     * transaction, and the log is returned without gaps: a change committed later can never receive
     * a sequence smaller than one already handed out.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to read.
     * @param cursor Position to continue from, or `null` to start at the oldest retained batch.
     * @param limit Largest number of batches to return; clamped to the configured maximum.
     * @return Page of batches after the cursor.
     * @throws CursorTooOldException When the cursor has fallen out of the retention window.
     * @throws UnknownCollectionException When the collection is not registered.
     */
    public fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage

    /**
     * Reads a page of a bootstrap snapshot.
     *
     * The snapshot is deliberately not repeatable-read: the cursor is fixed before the first page
     * and entities changed during the transfer come back in their newer state. Repetitions are
     * harmless because every state is complete; gaps are impossible because the client replays the
     * log from the fixed cursor afterwards.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to read.
     * @param page Continuation token, or `null` for the first page.
     * @param limit Largest number of entities to return; clamped to the configured maximum.
     * @return Page of entities together with the cursor the log has to be resumed from.
     * @throws UnknownCollectionException When the collection is not registered.
     */
    public fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage

    /**
     * Returns the newest position of a collection's log.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection to inspect.
     * @return Cursor a client reading now would end up at.
     * @throws UnknownCollectionException When the collection is not registered.
     */
    public fun head(
        scope: ScopeId,
        collection: CollectionId,
    ): Cursor

    /**
     * Returns the limits clients have to respect.
     *
     * Published so that a client can recognise an oversized envelope before sending it: a group
     * cannot be split without breaking the atomicity it exists for, so the failure has to happen on
     * the client, where the data can still be changed.
     *
     * @return Limits and retention window in effect.
     */
    public fun limits(): SyncLimits
}
