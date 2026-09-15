package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionEpoch
import kotlinx.serialization.Serializable

/**
 * Body of a push response: the outcome of every group of the request.
 *
 * @property results Outcome per group, in the order the groups were sent.
 * @property latestSeq Sequence of the newest batch of the collection at the moment of the answer.
 *   It is a hint that wakes the pull worker and **never** a cursor: adopting it as one would skip
 *   changes committed by others between the client's cursor and this position.
 * @property epoch Incarnation of the collection the changes were applied to. A client that had
 *   none learns it here; one that had it can only receive its own back, because a request naming
 *   any other is refused before anything is written.
 */
@Serializable
public data class PushResponse(
    public val results: List<PushGroupResult>,
    public val latestSeq: BatchSeq,
    public val epoch: CollectionEpoch,
)
