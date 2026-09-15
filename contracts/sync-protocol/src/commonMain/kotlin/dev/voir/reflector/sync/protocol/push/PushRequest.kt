package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionEpoch
import kotlinx.serialization.Serializable

/**
 * Body of a push request: local changes offered to the server for one collection.
 *
 * Groups inside one request are independent of each other and are applied one by one, each in its
 * own server transaction. A conflict in one group therefore does not roll back the groups that
 * were already applied, which is what makes it safe to send several of them at once.
 *
 * @property clientId Identifier of the sending installation, echoed back in the change log so that
 *   this client can recognise and suppress its own changes.
 * @property groups Groups to apply, in the order the client wants them applied.
 * @property epoch Incarnation of the collection these changes were made against, or `null` from a
 *   client that has never synchronised it and therefore claims nothing. The server refuses the
 *   whole request when it names an incarnation that no longer exists: the alternative is a client
 *   that survived a purge re-creating from its own queue the very data the purge removed, which
 *   the server cannot tell apart from ordinary new writes once it has been applied.
 */
@Serializable
public data class PushRequest(
    public val clientId: ClientId,
    public val groups: List<PushGroup>,
    public val epoch: CollectionEpoch? = null,
)
