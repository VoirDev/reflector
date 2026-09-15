package dev.voir.reflector.sync.protocol.changes

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.Cursor
import kotlinx.serialization.Serializable

/**
 * One server transaction as seen by a reader of the change log.
 *
 * A batch is the unit of atomicity of the pull: its operations are applied together with advancing
 * the cursor to [seq], in one local transaction. Pages are cut only on batch boundaries, so a
 * cursor can never point inside a batch.
 *
 * @property seq Sequence of the batch, for recognising and reporting a position; never a cursor.
 * @property cursor Position to store once this batch has been applied, in the same transaction as
 *   the batch itself. It is served rather than derived from [seq] because a position belongs to one
 *   incarnation of the collection, and a client that built its own would drop that half of it and
 *   carry a cursor from a purged log into the one that replaced it.
 * @property originClientId Installation that produced the batch, or `null` when the change did not
 *   come from a client. A client compares it with its own identifier to recognise its own changes:
 *   applying them back into business tables would undo the user's newer local edit.
 * @property ops Operations of the batch, in the order the server committed them.
 */
@Serializable
public data class ChangeBatch(
    public val seq: BatchSeq,
    public val cursor: Cursor,
    public val originClientId: ClientId?,
    public val ops: List<RemoteOperation>,
)
