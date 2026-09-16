package dev.voir.reflector.sync.protocol.blob

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BlobWireFormatTest {
    private val json = SyncProtocolJson.format

    private val blobId = BlobId(Uuid.parse("0199fd1a-0000-7000-8000-0000000000b1"))

    private val info =
        BlobInfo(
            blobId = blobId,
            state = BlobState.PENDING,
            contentType = BlobContentType("image/jpeg"),
            size = 2418123L,
            checksum = BlobChecksum("sha256:9f86d0"),
        )

    @Test
    fun `a registration carries the ticket the bytes have to be sent with`() {
        val registration =
            BlobRegistration(
                blob = info,
                upload =
                    BlobTicket(
                        method = BlobTicketMethod.PUT,
                        url = "https://bucket.example/obj?sig=abc",
                        headers = mapOf("Content-Type" to "image/jpeg"),
                        expiresAt = Instant.parse("2026-09-15T12:34:56Z"),
                    ),
            )

        val expected =
            json.parseToJsonElement(
                """
                {
                  "blob": {
                    "blobId": "0199fd1a-0000-7000-8000-0000000000b1",
                    "state": "pending",
                    "contentType": "image/jpeg",
                    "size": 2418123,
                    "checksum": "sha256:9f86d0"
                  },
                  "upload": {
                    "method": "PUT",
                    "url": "https://bucket.example/obj?sig=abc",
                    "headers": {"Content-Type": "image/jpeg"},
                    "expiresAt": "2026-09-15T12:34:56Z"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(expected, json.encodeToJsonElement(BlobRegistration.serializer(), registration))
    }

    @Test
    fun `registering a blob that is already usable answers without a ticket`() {
        val raw =
            """
            {
              "blob": {
                "blobId": "0199fd1a-0000-7000-8000-0000000000b1",
                "state": "ready",
                "contentType": "image/jpeg",
                "size": 2418123,
                "checksum": null
              },
              "upload": null
            }
            """.trimIndent()

        val registration = json.decodeFromString(BlobRegistration.serializer(), raw)

        // A blob is immutable, so there is nothing left to send and nothing to overwrite.
        assertNull(registration.upload)
        assertEquals(BlobState.READY, registration.blob.state)
        assertNull(registration.blob.checksum)
    }

    @Test
    fun `a blob whose bytes have not landed yet is a state rather than a failure`() {
        val raw =
            """
            {
              "blob": {
                "blobId": "0199fd1a-0000-7000-8000-0000000000b1",
                "state": "pending",
                "contentType": "image/jpeg",
                "size": 2418123,
                "checksum": null
              },
              "download": null
            }
            """.trimIndent()

        val download = json.decodeFromString(BlobDownload.serializer(), raw)

        // The ordinary condition of an attachment whose record arrived ahead of its bytes: the
        // client waits and retries, and nothing about it is reported as an error.
        assertEquals(BlobState.PENDING, download.blob.state)
        assertNull(download.download)
    }

    @Test
    fun `a descriptor without a checksum writes the absence explicitly`() {
        val descriptor =
            BlobDescriptor(
                blobId = blobId,
                contentType = BlobContentType("image/jpeg"),
                size = 2418123L,
            )

        val expected =
            json.parseToJsonElement(
                """
                {
                  "blobId": "0199fd1a-0000-7000-8000-0000000000b1",
                  "contentType": "image/jpeg",
                  "size": 2418123,
                  "checksum": null
                }
                """.trimIndent(),
            )

        assertEquals(expected, json.encodeToJsonElement(BlobDescriptor.serializer(), descriptor))
    }
}
