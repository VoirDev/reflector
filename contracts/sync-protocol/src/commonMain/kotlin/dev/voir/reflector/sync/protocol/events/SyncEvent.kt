@file:OptIn(ExperimentalSerializationApi::class)

package dev.voir.reflector.sync.protocol.events

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
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
 * Message pushed to a client over the event channel.
 *
 * The channel is only an alarm clock: data always travels over the HTTP pull, which keeps one code
 * path for applying changes and makes falling back to polling trivial. An event may therefore be
 * missed without losing data, and a client performs an unconditional pull after every reconnect.
 */
@Serializable(with = SyncEventSerializer::class)
public sealed class SyncEvent {
    /**
     * A collection has changes the client has not pulled yet.
     *
     * @property collection Collection to pull.
     * @property latestSeq Newest sequence of that collection, useful for diagnostics and for
     *   skipping a pull that would return nothing. It is never adopted as a cursor.
     */
    @Serializable
    public data class Invalidate(
        public val collection: CollectionId,
        public val latestSeq: BatchSeq,
    ) : SyncEvent()

    /**
     * A collection has to be bootstrapped from scratch.
     *
     * Sent when the client's position can no longer be served incrementally, for example after its
     * cursor fell out of the retention window.
     *
     * @property collection Collection to bootstrap.
     */
    @Serializable
    public data class Resync(
        public val collection: CollectionId,
    ) : SyncEvent()

    /**
     * A blob has become usable and can now be fetched.
     *
     * Sent when bytes a client may already be waiting for have landed in the host's storage. It is
     * an alarm clock like the rest of this channel and carries no data: the device answers it by
     * asking for that blob again over HTTP.
     *
     * It exists because of the ordinary case where a record is published ahead of its file. The
     * document naming the blob reaches the other devices immediately; without this they would
     * discover the bytes whenever their own backoff next happened to fire, which is minutes of a
     * photograph sitting ready in a bucket. A client that misses the event still gets there, which
     * is why the event may be lost like every other one here.
     *
     * @property collection Collection the blob belongs to.
     * @property blobId Blob that became usable.
     */
    @Serializable
    public data class BlobReady(
        public val collection: CollectionId,
        public val blobId: BlobId,
    ) : SyncEvent()

    /**
     * Access to the scope has been revoked.
     *
     * The client stops its workers and wipes the scope's data; pending local changes are lost by
     * definition, because there is nobody left to accept them.
     */
    @Serializable
    public data object Revoked : SyncEvent()

    /**
     * Event whose type this client does not understand.
     *
     * Treated as an invalidation of every collection of the scope: an unknown event may well be a
     * newer way of saying "there is something to pull", and an extra pull is harmless while a
     * missed one is not.
     *
     * @property type Event type as it arrived on the wire.
     * @property raw Complete event object as it arrived, for logs and diagnostics.
     */
    public data class Unknown(
        public val type: String,
        public val raw: JsonObject,
    ) : SyncEvent()
}

/**
 * Serializer of [SyncEvent] that turns an unrecognised event type into [SyncEvent.Unknown].
 *
 * The channel must survive a server that learned a new event type: failing to parse would drop the
 * socket, and a reconnect loop is a worse outcome than an extra pull.
 */
public object SyncEventSerializer : KSerializer<SyncEvent> {
    private const val FIELD_TYPE = "type"
    private const val TYPE_INVALIDATE = "invalidate"
    private const val TYPE_RESYNC = "resync"
    private const val TYPE_REVOKED = "revoked"
    private const val TYPE_BLOB_READY = "blobReady"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SyncEvent")

    /**
     * Reads one event, dispatching on its `type` field.
     *
     * @param decoder Decoder positioned at the event object.
     * @return Parsed event, or [SyncEvent.Unknown] when the type is not recognised.
     */
    override fun deserialize(decoder: Decoder): SyncEvent {
        val input = requireNotNull(decoder as? JsonDecoder) { "SyncEvent requires a JSON decoder" }
        val element = input.decodeJsonElement().jsonObject
        return when (val type = element[FIELD_TYPE]?.jsonPrimitive?.contentOrNull) {
            TYPE_INVALIDATE -> input.json.decodeFromJsonElement(SyncEvent.Invalidate.serializer(), element)
            TYPE_RESYNC -> input.json.decodeFromJsonElement(SyncEvent.Resync.serializer(), element)
            TYPE_BLOB_READY -> input.json.decodeFromJsonElement(SyncEvent.BlobReady.serializer(), element)
            TYPE_REVOKED -> SyncEvent.Revoked
            else -> SyncEvent.Unknown(type = type.orEmpty(), raw = element)
        }
    }

    /**
     * Writes one event, adding the `type` field the wire format dispatches on.
     *
     * @param encoder Encoder positioned where the event object is expected.
     * @param value Event to write; an unknown one is written back exactly as it arrived.
     */
    override fun serialize(
        encoder: Encoder,
        value: SyncEvent,
    ) {
        val output = requireNotNull(encoder as? JsonEncoder) { "SyncEvent requires a JSON encoder" }
        val element =
            when (value) {
                is SyncEvent.Invalidate -> {
                    output.json.encodeToJsonElement(SyncEvent.Invalidate.serializer(), value).withType(TYPE_INVALIDATE)
                }

                is SyncEvent.Resync -> {
                    output.json.encodeToJsonElement(SyncEvent.Resync.serializer(), value).withType(TYPE_RESYNC)
                }

                is SyncEvent.BlobReady -> {
                    output.json.encodeToJsonElement(SyncEvent.BlobReady.serializer(), value).withType(TYPE_BLOB_READY)
                }

                SyncEvent.Revoked -> {
                    JsonObject(mapOf(FIELD_TYPE to JsonPrimitive(TYPE_REVOKED)))
                }

                is SyncEvent.Unknown -> {
                    value.raw
                }
            }
        output.encodeJsonElement(element)
    }

    private fun JsonElement.withType(type: String): JsonObject =
        JsonObject(jsonObject + (FIELD_TYPE to JsonPrimitive(type)))
}
