package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Position inside a snapshot, encoded for the client as an opaque token.
 *
 * Snapshots page by key rather than by offset: collections can be large, and an offset both
 * degrades and stops being stable as soon as somebody writes during the transfer.
 *
 * The token also carries the cursor fixed before the first page. Recomputing it per page would
 * hand out a newer position each time, and a client that adopted the last one would silently skip
 * everything committed while the snapshot was being transferred.
 *
 * And it carries the incarnation the transfer began under, for the case the epoch exists for: a
 * collection purged halfway through a snapshot would otherwise continue from the key the old one
 * had reached, and the client would be left holding one half of one log and one half of another,
 * each internally consistent.
 *
 * @property collectionRowId Incarnation of the collection the transfer began under.
 * @property entityType Type of the last entity of the previous page.
 * @property entityId Identifier of the last entity of the previous page.
 * @property cursor Position of the log fixed before the first page.
 */
internal data class SnapshotCursorToken(
    val collectionRowId: Uuid,
    val entityType: EntityType,
    val entityId: EntityId,
    val cursor: Long,
) {
    /**
     * Encodes the position for the client.
     *
     * @return Opaque token; clients hand it back unchanged.
     */
    fun encode(): PageToken {
        val payload =
            listOf(collectionRowId.toString(), entityType.value, entityId.value.toString(), cursor.toString())
                .joinToString(SEPARATOR.toString())
        return PageToken(Base64.UrlSafe.encode(payload.encodeToByteArray()))
    }

    companion object {
        private const val SEPARATOR = '\u001f'

        /** Incarnation, type, identifier and cursor. */
        private const val PART_COUNT = 4

        /**
         * Decodes a token produced by [encode].
         *
         * @param token Token as the client sent it back.
         * @return Decoded position.
         * @throws IllegalArgumentException When the token was not produced by this module.
         */
        fun decode(token: PageToken): SnapshotCursorToken {
            val decoded =
                runCatching { Base64.UrlSafe.decode(token.value).decodeToString() }
                    .getOrElse { throw IllegalArgumentException("malformed page token") }
            val parts = decoded.split(SEPARATOR)
            require(parts.size == PART_COUNT) { "malformed page token" }
            return SnapshotCursorToken(
                collectionRowId =
                    runCatching { Uuid.parse(parts[0]) }
                        .getOrElse { throw IllegalArgumentException("malformed page token") },
                entityType = EntityType(parts[1]),
                entityId = EntityId(Uuid.parse(parts[2])),
                cursor = parts[3].toLongOrNull() ?: throw IllegalArgumentException("malformed page token"),
            )
        }
    }
}
