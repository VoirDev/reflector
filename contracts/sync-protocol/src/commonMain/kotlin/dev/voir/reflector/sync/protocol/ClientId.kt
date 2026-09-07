package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Stable identifier of one client installation.
 *
 * Echo suppression depends on it: the server marks every batch with the identifier of the client
 * that produced it, and a client applying its own change back into business tables would undo the
 * user's newer edit. The value is generated once, on first access to a scope, and survives process
 * restarts; losing it requires a bootstrap, because past changes of this installation would then
 * arrive as somebody else's and be taken for conflicts.
 *
 * @property value UUID value stored locally by the client. Nothing orders installations, so its
 *   version is immaterial and none is required.
 */
@Serializable
@JvmInline
public value class ClientId(
    public val value: Uuid,
)
