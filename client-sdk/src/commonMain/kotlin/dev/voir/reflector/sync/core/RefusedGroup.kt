package dev.voir.reflector.sync.core

import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.GroupId

/**
 * A group of local changes the server refused for good, and what it objected to.
 *
 * The same refusal [dev.voir.reflector.sync.core.adapter.CollectionAdapter.onRejected] reports, but
 * read back from storage rather than handed over once. The adapter is told at the moment of the
 * refusal and never again, while the group stays at the head of the queue — across restarts — until
 * the data changes or the application discards it. An application that kept what it was told in
 * memory could, after a restart, say that its queue is stuck but not why; this is the why, for as
 * long as it is true.
 *
 * @property groupId Group that was refused, which is also the identifier it appears under in the
 *   library's log records and in diagnostics.
 * @property entityType Entity type the refusal named, or `null` when it was about the group as a
 *   whole — an oversized envelope belongs to no single row of it.
 * @property entityId Entity the refusal named, or `null` when it was about the group as a whole.
 * @property rejection Why it was refused.
 */
public data class RefusedGroup(
    public val groupId: GroupId,
    public val entityType: EntityType?,
    public val entityId: EntityId?,
    public val rejection: SyncRejection,
)
