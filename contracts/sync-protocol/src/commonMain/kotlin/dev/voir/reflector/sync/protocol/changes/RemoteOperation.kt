@file:OptIn(ExperimentalSerializationApi::class)

package dev.voir.reflector.sync.protocol.changes

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Single change delivered to a client inside a batch of the collection's change log.
 *
 * Unlike a push operation, an incoming change may use a code this client does not know: a newer
 * server can extend the log. The unknown case is therefore part of the model rather than a parsing
 * failure, because the client has to react to it deliberately — skipping the operation while the
 * cursor moves past it would lose the change forever, so the collection is resynchronised instead.
 */
@Serializable(with = RemoteOperationSerializer::class)
public sealed class RemoteOperation {
    /**
     * State of an entity as of this batch.
     *
     * The payload is the state at the moment of the batch, not the current state of the entity:
     * that is what makes the pull incremental and resumable.
     *
     * @property entity Type of the changed entity.
     * @property id Identifier of the changed entity.
     * @property version Version the entity has after this batch.
     * @property data State of the entity as of this batch.
     */
    @Serializable
    public data class Upsert(
        public val entity: EntityType,
        public val id: EntityId,
        public val version: EntityVersion,
        public val data: JsonObject,
    ) : RemoteOperation()

    /**
     * Removal of an entity as of this batch.
     *
     * @property entity Type of the removed entity.
     * @property id Identifier of the removed entity.
     * @property version Version the removal produced; it is stored like any other version so that
     *   a later re-creation of the same identifier is based on it.
     */
    @Serializable
    public data class Delete(
        public val entity: EntityType,
        public val id: EntityId,
        public val version: EntityVersion,
    ) : RemoteOperation()

    /**
     * Operation whose code this client does not understand.
     *
     * The raw payload is kept for diagnostics only. The client must not apply, skip or acknowledge
     * such an operation: the batch stays unapplied and the collection goes to a resynchronisation,
     * which is the only outcome that neither loses the change nor guesses its meaning.
     *
     * @property op Operation code as it arrived on the wire.
     * @property raw Complete operation object as it arrived, for logs and diagnostics.
     */
    public data class Unknown(
        public val op: String,
        public val raw: JsonObject,
    ) : RemoteOperation()
}

/**
 * Serializer of [RemoteOperation] that turns an unrecognised operation code into
 * [RemoteOperation.Unknown] instead of failing.
 *
 * A generated polymorphic serializer cannot be used here: it throws on an unknown discriminator,
 * and the client would then be unable to tell "the server speaks a newer protocol" from "the
 * response is corrupt", although only the first case has a safe recovery.
 */
public object RemoteOperationSerializer : KSerializer<RemoteOperation> {
    private const val FIELD_OP = "op"
    private const val OP_UPSERT = "upsert"
    private const val OP_DELETE = "delete"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RemoteOperation")

    /**
     * Reads one operation, dispatching on its `op` field.
     *
     * @param decoder Decoder positioned at the operation object.
     * @return Parsed operation, or [RemoteOperation.Unknown] when the code is not recognised.
     */
    override fun deserialize(decoder: Decoder): RemoteOperation {
        val input = requireNotNull(decoder as? JsonDecoder) { "RemoteOperation requires a JSON decoder" }
        val element = input.decodeJsonElement().jsonObject
        return when (val op = element[FIELD_OP]?.jsonPrimitive?.contentOrNull) {
            OP_UPSERT -> input.json.decodeFromJsonElement(RemoteOperation.Upsert.serializer(), element)
            OP_DELETE -> input.json.decodeFromJsonElement(RemoteOperation.Delete.serializer(), element)
            else -> RemoteOperation.Unknown(op = op.orEmpty(), raw = element)
        }
    }

    /**
     * Writes one operation, adding the `op` field the wire format dispatches on.
     *
     * @param encoder Encoder positioned where the operation object is expected.
     * @param value Operation to write; an unknown one is written back exactly as it arrived.
     */
    override fun serialize(
        encoder: Encoder,
        value: RemoteOperation,
    ) {
        val output = requireNotNull(encoder as? JsonEncoder) { "RemoteOperation requires a JSON encoder" }
        val element =
            when (value) {
                is RemoteOperation.Upsert -> {
                    output.json.encodeToJsonElement(RemoteOperation.Upsert.serializer(), value).withOp(OP_UPSERT)
                }

                is RemoteOperation.Delete -> {
                    output.json.encodeToJsonElement(RemoteOperation.Delete.serializer(), value).withOp(OP_DELETE)
                }

                is RemoteOperation.Unknown -> {
                    value.raw
                }
            }
        output.encodeJsonElement(element)
    }

    private fun JsonElement.withOp(op: String): JsonObject = JsonObject(jsonObject + (FIELD_OP to JsonPrimitive(op)))
}
