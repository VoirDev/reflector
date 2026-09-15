package dev.voir.reflector.sync.persistence.collection

import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import kotlin.time.Instant

/**
 * Synchronisation state of a collection, in the terms the engine works in.
 *
 * The stored row keeps primitives, because that is what SQLite has; this is the same state with the
 * protocol's own types, so that a cursor cannot be passed where a page token belongs.
 *
 * @property cursor Position in the change log, or `null` when nothing has been read yet.
 * @property phase Lifecycle phase of the collection.
 * @property generation Bootstrap counter used to sweep what a new snapshot did not confirm.
 * @property bootstrapPage Continuation token of an unfinished snapshot, or `null` when none is in
 *   progress.
 * @property schemaFingerprint Shape the application's tables had when this collection was last
 *   synchronised, or `null` when the adapter declares none.
 * @property failureCount Consecutive failures recorded against the collection.
 * @property lastError Description of the most recent failure, or `null` after a success.
 * @property lastPullAt When the last successful pull finished, or `null` when there has been none.
 *   Local wall-clock time, and purely diagnostic: no decision here reads the device's clock.
 * @property lastPushAt When the last successful push finished, or `null` when there has been none.
 *   Diagnostic in exactly the same way.
 */
internal data class CollectionState(
    public val cursor: Cursor?,
    public val phase: SyncPhase,
    public val generation: Long,
    public val bootstrapPage: PageToken?,
    public val schemaFingerprint: SchemaFingerprint?,
    public val failureCount: Int,
    public val lastError: String?,
    public val lastPullAt: Instant?,
    public val lastPushAt: Instant?,
)
