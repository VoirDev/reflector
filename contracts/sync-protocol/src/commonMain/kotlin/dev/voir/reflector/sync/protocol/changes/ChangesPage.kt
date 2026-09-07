package dev.voir.reflector.sync.protocol.changes

import dev.voir.reflector.sync.protocol.Cursor
import kotlinx.serialization.Serializable

/**
 * One page of a collection's change log.
 *
 * @property batches Batches after the requested cursor, in ascending order and without gaps.
 * @property nextCursor Position reached by the last batch of the page, or `null` when the page is
 *   empty. It is not applied on its own: the client advances its cursor batch by batch, together
 *   with the data of that batch.
 * @property hasMore Whether the server withheld further batches because of the page limit. It says
 *   nothing about changes committed after the page was read, so a `false` here does not mean the
 *   client is up to date.
 */
@Serializable
public data class ChangesPage(
    public val batches: List<ChangeBatch>,
    public val nextCursor: Cursor?,
    public val hasMore: Boolean,
)
