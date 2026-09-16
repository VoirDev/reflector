package dev.voir.reflector.sync.protocol.push

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PushWireFormatTest {
    private val json = SyncProtocolJson.format

    @Test
    fun `push request names the incarnation it was made against`() {
        val request =
            PushRequest(
                clientId = ClientId(Uuid.parse("0199fd1a-0000-7000-8000-0000000000c1")),
                epoch = CollectionEpoch("0199fd1a-0000-7000-8000-0000000000a1"),
                groups =
                    listOf(
                        PushGroup(
                            groupId = GroupId(Uuid.parse("0199fd1a-0000-7000-8000-0000000000e1")),
                            ops =
                                listOf(
                                    PushOperation.Upsert(
                                        entity = EntityType("wallet"),
                                        id = EntityId(Uuid.parse("0199fd1a-0000-7000-8000-000000000001")),
                                        baseVersion = EntityVersion("41"),
                                        data = buildJsonObject { put("title", "Cash") },
                                    ),
                                    PushOperation.Delete(
                                        entity = EntityType("transaction"),
                                        id = EntityId(Uuid.parse("0199fd1a-0000-7000-8000-000000000002")),
                                        baseVersion = EntityVersion("7"),
                                    ),
                                ),
                        ),
                    ),
            )

        val expected =
            json.parseToJsonElement(
                """
                {
                  "clientId": "0199fd1a-0000-7000-8000-0000000000c1",
                  "epoch": "0199fd1a-0000-7000-8000-0000000000a1",
                  "groups": [
                    {
                      "groupId": "0199fd1a-0000-7000-8000-0000000000e1",
                      "ops": [
                        {
                          "op": "upsert",
                          "entity": "wallet",
                          "id": "0199fd1a-0000-7000-8000-000000000001",
                          "baseVersion": "41",
                          "data": {"title": "Cash"},
                          "blobs": null
                        },
                        {
                          "op": "delete",
                          "entity": "transaction",
                          "id": "0199fd1a-0000-7000-8000-000000000002",
                          "baseVersion": "7"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(expected, json.encodeToJsonElement(PushRequest.serializer(), request))
    }

    @Test
    fun `absent base version is written explicitly so the server can tell it from an unknown field`() {
        val operation =
            PushOperation.Upsert(
                entity = EntityType("wallet"),
                id = EntityId(Uuid.parse("0199fd1a-0000-7000-8000-000000000001")),
                baseVersion = null,
                data = buildJsonObject { put("title", "Cash") },
            )

        val expected =
            json.parseToJsonElement(
                """
                {
                  "op": "upsert",
                  "entity": "wallet",
                  "id": "0199fd1a-0000-7000-8000-000000000001",
                  "baseVersion": null,
                  "data": {"title": "Cash"},
                  "blobs": null
                }
                """.trimIndent(),
            )

        assertEquals(expected, json.encodeToJsonElement(PushOperation.serializer(), operation))
    }

    @Test
    fun `an empty reference list is kept apart from an absent one on the wire`() {
        val references =
            upsert(blobs = listOf(BlobId(Uuid.parse("0199fd1a-0000-7000-8000-0000000000b1"))))
        val none = upsert(blobs = emptyList())
        val untracked = upsert(blobs = null)

        assertEquals(
            json.parseToJsonElement("""["0199fd1a-0000-7000-8000-0000000000b1"]"""),
            json.encodeToJsonElement(PushOperation.serializer(), references).jsonObject.getValue("blobs"),
        )
        assertEquals(
            json.parseToJsonElement("[]"),
            json.encodeToJsonElement(PushOperation.serializer(), none).jsonObject.getValue("blobs"),
        )
        assertEquals(
            JsonNull,
            json.encodeToJsonElement(PushOperation.serializer(), untracked).jsonObject.getValue("blobs"),
        )
    }

    @Test
    fun `an operation from a client that predates blobs decodes as tracking none`() {
        val raw =
            """
            {
              "op": "upsert",
              "entity": "wallet",
              "id": "0199fd1a-0000-7000-8000-000000000001",
              "baseVersion": "41",
              "data": {"title": "Cash"}
            }
            """.trimIndent()

        val operation = assertIs<PushOperation.Upsert>(json.decodeFromString(PushOperation.serializer(), raw))

        // Absent and explicit null mean the same thing — leave the stored references alone — and
        // that is why the field is nullable rather than a three-state wrapper.
        assertNull(operation.blobs)
    }

    private fun upsert(blobs: List<BlobId>?): PushOperation.Upsert =
        PushOperation.Upsert(
            entity = EntityType("wallet"),
            id = EntityId(Uuid.parse("0199fd1a-0000-7000-8000-000000000001")),
            baseVersion = EntityVersion("41"),
            data = buildJsonObject { put("title", "Cash") },
            blobs = blobs,
        )

    @Test
    fun `every group outcome is decoded into its own case`() {
        val raw =
            """
            {
              "results": [
                {"status":"applied","groupId":"0199fd1a-0000-7000-8000-0000000000e1",
                 "versions":[{"entity":"wallet","id":"0199fd1a-0000-7000-8000-000000000001","version":"42"}]},
                {"status":"conflict","groupId":"0199fd1a-0000-7000-8000-0000000000e2",
                 "conflicts":[{"entity":"wallet","id":"0199fd1a-0000-7000-8000-000000000001",
                               "serverVersion":"50","data":null}]},
                {"status":"rejected","groupId":"0199fd1a-0000-7000-8000-0000000000e3",
                 "error":{"code":"DEPENDENCY","message":"wallet is unknown to the server"}}
              ],
              "latestSeq": "1187",
              "epoch": "0199fd1a-0000-7000-8000-0000000000a1"
            }
            """.trimIndent()

        val response = json.decodeFromString(PushResponse.serializer(), raw)

        val applied = assertIs<PushGroupResult.Applied>(response.results[0])
        val conflict = assertIs<PushGroupResult.Conflict>(response.results[1])
        val rejected = assertIs<PushGroupResult.Rejected>(response.results[2])
        assertEquals(EntityVersion("42"), applied.versions.single().version)
        assertNull(conflict.conflicts.single().data)
        assertEquals(RejectCode.DEPENDENCY, rejected.error.code)
        assertEquals(CollectionEpoch("0199fd1a-0000-7000-8000-0000000000a1"), response.epoch)
    }
}
