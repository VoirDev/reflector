package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobChecksum
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobTicket
import dev.voir.reflector.sync.protocol.blob.BlobTicketMethod
import dev.voir.reflector.sync.server.BlobStorage
import dev.voir.reflector.sync.server.BlobStorageKey
import dev.voir.reflector.sync.server.BlobVerification
import dev.voir.reflector.sync.server.StoredBlob
import kotlin.time.Instant

/**
 * A host's object storage, faked.
 *
 * Deliberately a map rather than a temporary directory. The module is never in the data path — it
 * hands out permission to move bytes and is told afterwards what arrived — so writing real files
 * here would exercise the test's own I/O and none of the module's behaviour. What the module
 * actually depends on is the four answers this gives, and those are what a test needs to control:
 * an object that is there, one that is not, and one that is there and wrong.
 *
 * @property tickets Every ticket handed out, so that a test can assert what a client would have
 *   been told to do.
 */
class FakeBlobStorage : BlobStorage {
    /** What the bucket holds, by key. */
    private val objects = mutableMapOf<BlobStorageKey, BlobVerification.Stored>()

    /** Keys this host's policy keeps rather than deletes, so that both policies can be exercised. */
    var retained: Set<BlobStorageKey> = emptySet()

    /** Every ticket handed out, newest last. */
    val tickets: MutableList<BlobTicket> = mutableListOf()

    /** Keys this storage was handed as no longer referenced, in order. */
    val released: MutableList<BlobStorageKey> = mutableListOf()

    /**
     * Pretends a device finished writing an object.
     *
     * @param key Key the object was written under.
     * @param size Length the storage will report.
     * @param checksum Digest the storage will report, or `null` when it computes none.
     */
    fun put(
        key: BlobStorageKey,
        size: Long,
        checksum: BlobChecksum? = null,
    ) {
        objects[key] = BlobVerification.Stored(size, checksum)
    }

    /**
     * Returns the key this storage would choose for a blob.
     *
     * @param scope Scope the blob belongs to.
     * @param collection Collection the blob belongs to.
     * @param blobId Blob to name.
     * @return Key the object would be written under.
     */
    fun keyOf(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobStorageKey = BlobStorageKey("${scope.value}/${collection.value}/${blobId.value}")

    override fun keyFor(
        scope: ScopeId,
        collection: CollectionId,
        blob: BlobDescriptor,
    ): BlobStorageKey = keyOf(scope, collection, blob.blobId)

    override fun createUpload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket = ticket(BlobTicketMethod.PUT, blob.storageKey).also { tickets += it }

    override fun createDownload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket = ticket(BlobTicketMethod.GET, blob.storageKey).also { tickets += it }

    override fun verify(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobVerification = objects[blob.storageKey] ?: BlobVerification.Absent

    override fun onReleased(
        scope: ScopeId,
        collection: CollectionId,
        blobs: List<StoredBlob>,
    ) {
        released += blobs.map { it.storageKey }
        // A host's disposal policy, faked: everything goes except what this one was told to keep,
        // which is what a lifecycle rule or a legal hold looks like from the module's side.
        blobs.filterNot { it.storageKey in retained }.forEach { objects -= it.storageKey }
    }

    private fun ticket(
        method: BlobTicketMethod,
        key: BlobStorageKey,
    ): BlobTicket =
        BlobTicket(
            method = method,
            url = "https://storage.test/${key.value}?sig=${method.name.lowercase()}",
            headers = mapOf("x-test-key" to key.value),
            expiresAt = Instant.fromEpochMilliseconds(TICKET_EXPIRY_MILLIS),
        )

    private companion object {
        /** Far enough in the future that no test has to think about it. */
        const val TICKET_EXPIRY_MILLIS = 4_000_000_000_000
    }
}
