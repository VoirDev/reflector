package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlin.uuid.Uuid

/**
 * Conversions between the module's internal ordering and the opaque tokens the protocol uses.
 *
 * Clients treat cursors and versions as strings they only compare for equality, so the encoding is
 * free to be this simple — and keeping it in one place is what makes it safe to change later.
 *
 * A cursor carries the collection's incarnation as well as the position, because a position alone
 * stopped being meaningful the moment a collection could be purged: the same number means something
 * different in a log that started again. The two travel together so that the pair cannot be split
 * by a caller who only remembered to carry one of them.
 */
internal object SyncSequences {
    /**
     * Encodes a sequence as a batch token.
     *
     * @param seq Sequence to encode.
     * @return Token for the wire.
     */
    fun batchSeq(seq: Long): BatchSeq = BatchSeq(seq.toString())

    /**
     * Names one incarnation of a collection.
     *
     * The collection row's identifier is used as it stands. It is random per row and a purge
     * deletes the row, so a re-created collection is a different incarnation without the schema
     * having to carry a counter that somebody could forget to bump.
     *
     * @param collectionRowId Identifier of the collection row.
     * @return Epoch for the wire.
     */
    fun epoch(collectionRowId: Uuid): CollectionEpoch = CollectionEpoch(collectionRowId.toString())

    /**
     * Encodes a position in one incarnation of a collection's log.
     *
     * @param collectionRowId Identifier of the collection row the position belongs to.
     * @param seq Sequence the reader has reached.
     * @return Cursor for the wire.
     */
    fun cursor(
        collectionRowId: Uuid,
        seq: Long,
    ): Cursor = Cursor("$collectionRowId$SEPARATOR$seq")

    /**
     * Encodes a sequence as an entity version.
     *
     * The version of an entity is the sequence of the batch that last changed it: a separate counter
     * would carry no extra information, because one batch cannot change an entity twice.
     *
     * @param seq Sequence of that batch.
     * @return Version for the wire.
     */
    fun version(seq: Long): EntityVersion = EntityVersion(seq.toString())

    /**
     * Reads a cursor back.
     *
     * @param cursor Cursor as the client sent it back.
     * @return Incarnation it belongs to and the position inside it.
     * @throws IllegalArgumentException When the cursor was not produced by this module.
     */
    fun positionOf(cursor: Cursor): CursorPosition {
        val parts = cursor.value.split(SEPARATOR)
        require(parts.size == PART_COUNT) { "malformed cursor '${cursor.value}'" }
        val epoch =
            runCatching { Uuid.parse(parts[0]) }
                .getOrElse { throw IllegalArgumentException("malformed cursor '${cursor.value}'") }
        val seq = parts[1].toLongOrNull() ?: throw IllegalArgumentException("malformed cursor '${cursor.value}'")
        return CursorPosition(epoch, seq)
    }

    /**
     * Reads the sequence out of a version.
     *
     * @param version Version as the client sent it back.
     * @return Sequence the version stands for, or `null` when it is not one this module produced.
     */
    fun sequenceOf(version: EntityVersion): Long? = version.value.toLongOrNull()

    /** Separates the incarnation from the position; absent from both a UUID and a number. */
    private const val SEPARATOR = '.'

    /** Incarnation and position. */
    private const val PART_COUNT = 2
}

/**
 * A cursor taken apart.
 *
 * @property collectionRowId Incarnation of the collection the cursor was issued for.
 * @property seq Position inside that incarnation's log.
 */
internal data class CursorPosition(
    val collectionRowId: Uuid,
    val seq: Long,
)
