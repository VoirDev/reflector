package dev.voir.reflector.sync.protocol.snapshot

import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.PageToken
import kotlinx.serialization.Serializable

/**
 * One page of a bootstrap snapshot.
 *
 * The snapshot is deliberately "dirty": the server fixes [cursor] before the first page and does
 * not hold a repeatable read across the pages. An entity changed between pages comes back in its
 * newer state, which is idempotent under snapshot semantics; repetitions are possible, gaps are
 * not. The client applies the pages, then catches up on the log starting from [cursor].
 *
 * @property cursor Position the log has to be resumed from once the whole snapshot is applied. It
 *   is fixed before the first page, so anything committed during the transfer is replayed from the
 *   log rather than lost.
 * @property items Entities of this page.
 * @property nextPage Token of the next page, or `null` when this is the last one.
 * @property hasMore Whether another page follows.
 * @property epoch Incarnation of the collection being transferred, stored by the client together
 *   with [cursor]. A collection purged midway through a transfer answers the next page with a
 *   refusal rather than with pages of a different log stitched onto the ones already applied.
 */
@Serializable
public data class SnapshotPage(
    public val cursor: Cursor,
    public val items: List<SnapshotItem>,
    public val nextPage: PageToken?,
    public val hasMore: Boolean,
    public val epoch: CollectionEpoch,
)
