package dev.voir.reflector.sync.core.transport

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
 * The engine's view of the server.
 *
 * Four calls cover the whole protocol. The port exists so that the engine can be driven by a
 * deterministic fake in tests: almost every rule worth testing here is about the order of pushes,
 * pulls and crashes, and none of that is reproducible against a real server.
 *
 * Implementations report failures by throwing [SyncTransportFailure]; anything else that escapes is
 * treated as a defect rather than as a transient network condition.
 */
public interface SyncTransport {
    /**
     * Offers local changes to the server.
     *
     * @param scope Scope of the collection.
     * @param collection Collection the groups belong to.
     * @param request Groups to apply.
     * @return Outcome of every group of the request.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse

    /**
     * Reads a page of the change log.
     *
     * @param scope Scope of the collection.
     * @param collection Collection to read.
     * @param cursor Position to continue from, or `null` to start at the beginning of the retained
     *   history.
     * @param limit Largest number of batches to return.
     * @return Page of batches, cut only on batch boundaries.
     * @throws SyncTransportFailure When the request could not be completed, in particular
     *   [SyncTransportFailure.CursorTooOld] when the position has fallen out of retention.
     */
    public suspend fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage

    /**
     * Reads a page of a bootstrap snapshot.
     *
     * @param scope Scope of the collection.
     * @param collection Collection to read.
     * @param page Continuation token, or `null` for the first page.
     * @param limit Largest number of entities to return.
     * @return Page of entities together with the cursor the log has to be resumed from.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage

    /**
     * Reads the limits the server enforces.
     *
     * Fetched once at start-up and cached: a group cannot be split without breaking its atomicity,
     * so an envelope that would exceed the limits has to be recognised before it is sent.
     *
     * @return Limits and retention window published by the server.
     * @throws SyncTransportFailure When the request could not be completed.
     */
    public suspend fun limits(): SyncLimits
}
