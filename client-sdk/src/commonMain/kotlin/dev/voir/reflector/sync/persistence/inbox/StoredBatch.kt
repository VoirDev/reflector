package dev.voir.reflector.sync.persistence.inbox

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.Cursor

/**
 * One downloaded server transaction with its operations.
 *
 * @property seq Sequence of the batch, which becomes the cursor once it is applied.
 * @property originClientId Installation that produced the batch, or `null` when it did not come
 *   from a client. Compared with this installation's identifier to recognise an echo of its own
 *   push, which must update versions without touching the application's rows.
 * @property ops Operations of the batch, in the order the server committed them.
 */
internal data class StoredBatch(
    public val seq: BatchSeq,
    public val cursor: Cursor,
    public val originClientId: ClientId?,
    public val ops: List<InboxOperation>,
)
