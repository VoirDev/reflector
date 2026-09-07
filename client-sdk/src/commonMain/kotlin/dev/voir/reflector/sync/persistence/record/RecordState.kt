package dev.voir.reflector.sync.persistence.record

import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId

/**
 * Synchronisation metadata of one entity, in the terms the engine works in.
 *
 * @property entityType Type of the entity.
 * @property entityId Identifier of the entity.
 * @property serverVersion Version the server last confirmed, or `null` when it does not know the
 *   entity yet.
 * @property localRev Local revision, incremented by every local mutation.
 * @property pushingRev Revision captured when the current envelope was assembled.
 * @property ackedRev Revision the server has confirmed.
 * @property intent Operation the last local mutation implies, or `null` when the record is clean.
 * @property groupId Push group the record belongs to, or `null` when it is clean.
 * @property conflictId Open conflict of the record, or `null` when there is none.
 */
internal data class RecordState(
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val serverVersion: EntityVersion?,
    public val localRev: Long,
    public val pushingRev: Long,
    public val ackedRev: Long,
    public val intent: MutationIntent?,
    public val groupId: GroupId?,
    public val conflictId: ConflictId?,
) {
    /** Whether the record holds a local change the server has not confirmed. */
    public val isDirty: Boolean get() = localRev > ackedRev
}
