package dev.voir.reflector.sync.protocol.changes

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class RemoteOperationSerializationTest {
    private val json = SyncProtocolJson.format

    @Test
    fun `unknown operation code is decoded as an unknown operation`() {
        val raw = """{"entity":"wallet","id":"0199fd1a-0000-7000-8000-000000000001","op":"evict","version":"42"}"""

        val operation = json.decodeFromString(RemoteOperationSerializer, raw)

        val unknown = assertIs<RemoteOperation.Unknown>(operation)
        assertEquals("evict", unknown.op)
        assertEquals("42", unknown.raw["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `upsert keeps its operation code through a round trip`() {
        val operation =
            RemoteOperation.Upsert(
                entity = EntityType("wallet"),
                id = EntityId(Uuid.parse("0199fd1a-0000-7000-8000-000000000001")),
                version = EntityVersion("42"),
                data = buildJsonObject { put("title", "Cash") },
            )

        val encoded = json.encodeToJsonElement(RemoteOperationSerializer, operation).jsonObject
        val decoded = json.decodeFromJsonElement(RemoteOperationSerializer, encoded)

        assertEquals("upsert", encoded["op"]?.jsonPrimitive?.content)
        assertEquals(operation, decoded)
    }

    @Test
    fun `unknown operation is written back exactly as it arrived`() {
        val raw = json.parseToJsonElement("""{"op":"evict","entity":"wallet","note":"kept"}""").jsonObject

        val decoded = json.decodeFromJsonElement(RemoteOperationSerializer, raw)

        assertEquals(raw, json.encodeToJsonElement(RemoteOperationSerializer, decoded).jsonObject)
    }
}
