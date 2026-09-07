@file:OptIn(ExperimentalSerializationApi::class)

package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.GroupId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Outcome of applying one push group.
 *
 * The three outcomes are deliberately distinct paths on the client. An applied group is done, a
 * conflicted group waits for the application to resolve it, and a rejected group is a business
 * failure that retrying unchanged cannot fix — treating the last one as transient would produce a
 * poison message blocking the collection's queue forever.
 */
@Serializable
@JsonClassDiscriminator("status")
public sealed class PushGroupResult {
    /** Identifier of the group this outcome belongs to. */
    public abstract val groupId: GroupId

    /**
     * The group was applied in full.
     *
     * The client stores the returned versions and stops treating the records as dirty; it does
     * **not** advance the collection cursor, because its own changes come back through the normal
     * pull and are applied idempotently there.
     *
     * @property groupId Identifier of the applied group.
     * @property versions New server version of every entity the group changed.
     */
    @Serializable
    @SerialName("applied")
    public data class Applied(
        override val groupId: GroupId,
        public val versions: List<AppliedVersion>,
    ) : PushGroupResult()

    /**
     * The group was rejected because at least one entity had moved on.
     *
     * Nothing of the group was applied, since a group is atomic. Non-conflicting records of the
     * group stay dirty and wait for the conflicting ones to be resolved.
     *
     * @property groupId Identifier of the conflicted group.
     * @property conflicts Current server state of every entity that caused the conflict.
     */
    @Serializable
    @SerialName("conflict")
    public data class Conflict(
        override val groupId: GroupId,
        public val conflicts: List<ConflictEntry>,
    ) : PushGroupResult()

    /**
     * The group was refused for a reason other than a stale version.
     *
     * Whether a retry makes sense depends on the code: [RejectCode.DEPENDENCY] asks the client to
     * merge the group with the next one and send it again, while every other code is terminal and
     * has to be surfaced to the application.
     *
     * @property groupId Identifier of the refused group.
     * @property error Reason for the refusal.
     */
    @Serializable
    @SerialName("rejected")
    public data class Rejected(
        override val groupId: GroupId,
        public val error: RejectError,
    ) : PushGroupResult()
}
