package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobContentType
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.server.BlobStorageKey
import dev.voir.reflector.sync.server.StoredBlob
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Reads and writes of the blob row.
 *
 * Every method here runs inside a transaction the caller owns, and none of them calls the host: the
 * blob service arranges its work so that storage is only ever reached between transactions.
 */
internal class BlobRows {
    /**
     * Reads one blob of a collection.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param blobId Blob to look up.
     * @return What the module knows about the blob, or `null` when it knows nothing.
     */
    fun find(
        collectionRowId: Uuid,
        blobId: BlobId,
    ): StoredBlob? =
        BlobsTable
            .selectAll()
            .where { (BlobsTable.collection eq collectionRowId) and (BlobsTable.blobId eq blobId.value) }
            .singleOrNull()
            ?.toStoredBlob()

    /**
     * Writes a blob row unless the identifier is already taken.
     *
     * Two devices — or one device twice — may register the same blob at the same moment, and the
     * unique constraint decides: the loser simply reads what the winner wrote, and the storage key
     * it had computed is discarded without an object ever having been written under it.
     *
     * A blob is unreferenced from the moment it is registered. That is what makes an upload whose
     * document was never pushed collectable rather than immortal.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param descriptor What the client declared.
     * @param storageKey Name the host's storage will know the object by.
     * @param now Moment of registration.
     */
    fun insertIfAbsent(
        collectionRowId: Uuid,
        descriptor: BlobDescriptor,
        storageKey: BlobStorageKey,
        now: Instant,
    ) {
        BlobsTable.insertIgnore { row ->
            row[id] = Uuid.random()
            row[collection] = collectionRowId
            row[blobId] = descriptor.blobId.value
            row[state] = BlobState.PENDING
            row[BlobsTable.storageKey] = storageKey.value
            row[contentType] = descriptor.contentType.value
            row[size] = descriptor.size
            row[checksum] = descriptor.checksum?.value
            row[createdAt] = now
            row[readyAt] = null
            row[unreferencedSince] = now
        }
    }

    /**
     * Replaces what a blob that is not usable yet was declared to be.
     *
     * A client may register the same identifier again after choosing a different file. Nothing has
     * accepted the old declaration — the blob is not usable and nobody can have fetched it — so the
     * new one simply replaces it, and the alternative would be a blob permanently stuck between a
     * declaration nobody meant and bytes that will never match it.
     *
     * The storage key is deliberately **not** changed. The module owns which object a blob lives in
     * for the blob's whole life, and the host's naming is asked once; overwriting the object under
     * the existing key is exactly right, because nothing has accepted what was there.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param descriptor New declaration.
     */
    fun redeclare(
        collectionRowId: Uuid,
        descriptor: BlobDescriptor,
    ) {
        BlobsTable.update(
            where = {
                (BlobsTable.collection eq collectionRowId) and
                    (BlobsTable.blobId eq descriptor.blobId.value) and
                    (BlobsTable.state eq BlobState.PENDING)
            },
        ) { row ->
            row[contentType] = descriptor.contentType.value
            row[size] = descriptor.size
            row[checksum] = descriptor.checksum?.value
        }
    }

    /**
     * Makes a blob usable, if it is not already.
     *
     * The state is part of the condition rather than only of the assignment, which is what makes
     * this safe to race: two paths to acceptance — the device saying so and the host's storage
     * notification — may arrive together, and exactly one of them writes the row.
     *
     * @param collectionRowId Row identifier of the collection.
     * @param blobId Blob to make usable.
     * @param now Moment the bytes were accepted.
     * @return Whether this call is the one that made the blob usable.
     */
    fun promote(
        collectionRowId: Uuid,
        blobId: BlobId,
        now: Instant,
    ): Boolean {
        val updated =
            BlobsTable.update(
                where = {
                    (BlobsTable.collection eq collectionRowId) and
                        (BlobsTable.blobId eq blobId.value) and
                        (BlobsTable.state eq BlobState.PENDING)
                },
            ) { row ->
                row[state] = BlobState.READY
                row[readyAt] = now
            }
        return updated == 1
    }
}

/**
 * Reads a blob row into the model the host sees.
 *
 * @return Blob as the module stores it.
 */
internal fun ResultRow.toStoredBlob(): StoredBlob =
    StoredBlob(
        blobId = BlobId(this[BlobsTable.blobId]),
        state = this[BlobsTable.state],
        storageKey = BlobStorageKey(this[BlobsTable.storageKey]),
        contentType = BlobContentType(this[BlobsTable.contentType]),
        size = this[BlobsTable.size],
        checksum = this[BlobsTable.checksum]?.let(::BlobChecksum),
        createdAt = this[BlobsTable.createdAt],
        readyAt = this[BlobsTable.readyAt],
        unreferencedSince = this[BlobsTable.unreferencedSince],
    )

/**
 * Describes a stored blob in the shape a client reads.
 *
 * @return What the server knows about the blob, without the storage key — which is the host's
 *   business and no client's.
 */
internal fun StoredBlob.toInfo(): BlobInfo =
    BlobInfo(
        blobId = blobId,
        state = state,
        contentType = contentType,
        size = size,
        checksum = checksum,
    )
