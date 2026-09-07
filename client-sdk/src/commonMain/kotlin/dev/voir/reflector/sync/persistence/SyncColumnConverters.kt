package dev.voir.reflector.sync.persistence

import androidx.room3.ColumnTypeConverter
import kotlin.uuid.Uuid

/**
 * Converters for the column types the synchronisation tables use beyond Room's built-ins.
 *
 * Room ships a built-in UUID converter, but only for `java.util.UUID`, which does not exist on the
 * Apple targets. Identifiers here are `kotlin.uuid.Uuid`, so the conversion is written out.
 *
 * Identifiers are stored as 16-byte blobs rather than as text. On a collection of a few hundred
 * thousand records the difference is the size of the table itself, and these columns appear in
 * every primary key of the schema.
 */
public object SyncColumnConverters {
    /**
     * Encodes an identifier for storage.
     *
     * @param value Identifier to encode, or `null` for an absent one.
     * @return 16-byte big-endian representation, or `null` when [value] is `null`.
     */
    @ColumnTypeConverter
    public fun uuidToBytes(value: Uuid?): ByteArray? = value?.toByteArray()

    /**
     * Decodes an identifier read from storage.
     *
     * @param value Stored representation, or `null` for an absent one.
     * @return Decoded identifier, or `null` when [value] is `null`.
     * @throws IllegalArgumentException When the stored value is not exactly 16 bytes, which means
     *   the row was written by something other than this library.
     */
    @ColumnTypeConverter
    public fun bytesToUuid(value: ByteArray?): Uuid? = value?.let { Uuid.fromByteArray(it) }
}
