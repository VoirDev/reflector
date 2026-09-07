package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.ClientId
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
 */
@Serializable
public data class PushRequest(
    public val clientId: ClientId,
    public val groups: List<PushGroup>,
)
