@file:OptIn(ExperimentalSerializationApi::class)

package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/**
 * Single operation offered to the server inside a push group.
 *
 * Every operation carries the version it was based on, and the server applies it only while that
 * version is still current. `null` means the client believes the server does not know the entity
 * yet, so an already existing entity is reported as a conflict rather than overwritten.
 *
 * The set of operations is closed on this side of the protocol: a client only ever writes or
 * removes an entity it owns a local record of.
 */
@Serializable
@JsonClassDiscriminator("op")
public sealed class PushOperation {
    /** Type of the affected entity. */
    public abstract val entity: EntityType

    /** Identifier of the affected entity. */
    public abstract val id: EntityId

    /** Server version the change was based on, or `null` when the entity is believed to be new. */
    public abstract val baseVersion: EntityVersion?

    /**
     * Write of the current state of an entity.
     *
     * The payload is a full snapshot as the client sees it now, not a diff against the moment of
     * editing: bodies are materialised lazily at push time. The server merges it into the stored
     * document by the keys present at the top level, so an omitted field keeps its stored value
     * and an explicit `null` clears it. Nested objects are replaced as a whole.
     *
     * @property entity Type of the affected entity.
     * @property id Identifier of the affected entity.
     * @property baseVersion Version the write was based on, `null` for a newly created entity.
     * @property data Current state of the entity, serialised by the application's adapter.
     * @property blobs Every blob this document references, as the application declared them, or
     *   `null` from a client that does not track blobs at all.
     *
     *   The distinction between `null` and an empty list is contractual and the server acts on it:
     *   `null` leaves the stored references untouched, while `[]` states that the document
     *   references nothing and drops them. Collapsing the two would make the first push from a
     *   client that predates files read as "everything is unreferenced now", and the collector would
     *   then delete blobs that are still in use.
     *
     *   The list carries every reference whether or not its bytes have arrived yet. That is what
     *   lets a record be published ahead of its file: the blob counts as referenced from the moment
     *   the record is pushed, so it cannot be collected out from under a document already naming it.
     */
    @Serializable
    @SerialName("upsert")
    public data class Upsert(
        override val entity: EntityType,
        override val id: EntityId,
        override val baseVersion: EntityVersion?,
        public val data: JsonObject,
        public val blobs: List<BlobId>? = null,
    ) : PushOperation()

    /**
     * Removal of an entity.
     *
     * A delete is versioned exactly like a write, so deleting an entity somebody else has changed
     * in the meantime produces a conflict instead of silently winning.
     *
     * @property entity Type of the affected entity.
     * @property id Identifier of the affected entity.
     * @property baseVersion Version the delete was based on, `null` when the entity is believed to
     *   be unknown to the server, which makes the delete a no-op the server still has to confirm.
     */
    @Serializable
    @SerialName("delete")
    public data class Delete(
        override val entity: EntityType,
        override val id: EntityId,
        override val baseVersion: EntityVersion?,
    ) : PushOperation()
}
