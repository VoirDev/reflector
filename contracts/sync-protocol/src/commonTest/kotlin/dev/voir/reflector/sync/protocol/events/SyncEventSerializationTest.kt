package dev.voir.reflector.sync.protocol.events

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyncEventSerializationTest {
    private val json = SyncProtocolJson.format

    @Test
    fun `invalidate carries the collection and the newest sequence`() {
        val raw = """{"type":"invalidate","collection":"ledger","latestSeq":"1187"}"""

        val event = json.decodeFromString(SyncEventSerializer, raw)

        val invalidate = assertIs<SyncEvent.Invalidate>(event)
        assertEquals(CollectionId("ledger"), invalidate.collection)
        assertEquals(BatchSeq("1187"), invalidate.latestSeq)
    }

    @Test
    fun `unknown event type is decoded as an unknown event instead of failing`() {
        val event = json.decodeFromString(SyncEventSerializer, """{"type":"rebalance","collection":"ledger"}""")

        assertEquals("rebalance", assertIs<SyncEvent.Unknown>(event).type)
    }

    @Test
    fun `revoked event is written as its type alone`() {
        val encoded = json.encodeToJsonElement(SyncEventSerializer, SyncEvent.Revoked).jsonObject

        assertEquals("revoked", encoded["type"]?.jsonPrimitive?.content)
        assertEquals(1, encoded.size)
    }
}
