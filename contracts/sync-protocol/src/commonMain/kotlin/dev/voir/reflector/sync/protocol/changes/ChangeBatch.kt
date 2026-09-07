package dev.voir.reflector.sync.protocol.changes

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import kotlinx.serialization.Serializable

/**
 * One server transaction as seen by a reader of the change log.
 *
 * A batch is the unit of atomicity of the pull: its operations are applied together with advancing
 * the cursor to [seq], in one local transaction. Pages are cut only on batch boundaries, so a
 * cursor can never point inside a batch.
 *
 * @property seq Sequence of the batch, which becomes the client's cursor once the batch is applied.
 * @property originClientId Installation that produced the batch, or `null` when the change did not
 *   come from a client. A client compares it with its own identifier to recognise its own changes:
 *   applying them back into business tables would undo the user's newer local edit.
 * @property ops Operations of the batch, in the order the server committed them.
 */
@Serializable
public data class ChangeBatch(
    public val seq: BatchSeq,
    public val originClientId: ClientId?,
    public val ops: List<RemoteOperation>,
)
