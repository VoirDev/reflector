package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityVersion

/**
 * Conversions between the module's internal ordering and the opaque tokens the protocol uses.
 *
 * Clients treat cursors and versions as strings they only compare for equality, so the encoding is
 * free to be this simple — and keeping it in one place is what makes it safe to change later.
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
     * Encodes a sequence as a cursor.
     *
     * @param seq Sequence the reader has reached.
     * @return Cursor for the wire.
     */
    fun cursor(seq: Long): Cursor = Cursor(seq.toString())

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
     * Reads the sequence out of a cursor.
     *
     * @param cursor Cursor as the client sent it back.
     * @return Sequence the client has reached.
     * @throws IllegalArgumentException When the cursor was not produced by this module.
     */
    fun sequenceOf(cursor: Cursor): Long =
        cursor.value.toLongOrNull() ?: throw IllegalArgumentException("malformed cursor '${cursor.value}'")

    /**
     * Reads the sequence out of a version.
     *
     * @param version Version as the client sent it back.
     * @return Sequence the version stands for, or `null` when it is not one this module produced.
     */
    fun sequenceOf(version: EntityVersion): Long? = version.value.toLongOrNull()
}
