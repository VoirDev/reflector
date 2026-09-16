package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobTicket

/**
 * The host's object storage, as the module uses it.
 *
 * The module never holds a blob's bytes, never opens a stream and never learns what S3 is. It knows
 * that an object exists, what was declared about it and whether it may be served; everything
 * physical is behind these five methods. A host over S3, over GCS, over a signed endpoint of its own
 * or over a directory in a test is indistinguishable from the module's side, which is the point.
 *
 * **No method here is called inside one of the module's transactions.** The module arranges its work
 * so that every call to this port happens between transactions, because an implementation is free to
 * reach the network and a database connection held across that is how a backend deadlocks itself.
 * The one thing asked in return is that [keyFor] be a cheap, pure decision — it is called before the
 * module knows whether it will need the answer, and a discarded key must cost nothing.
 */
public interface BlobStorage {
    /**
     * Names the object a blob's bytes will be stored as.
     *
     * Called once per blob, before it is registered, and the answer is kept for the blob's whole
     * life. A host that wants deduplication puts it here: two descriptors with the same checksum may
     * be given the same key, and the module neither notices nor cares.
     *
     * Must be pure and local. The module calls it before it knows whether the blob is new, and
     * discards the answer when it turns out not to be.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob What the client declared about the blob.
     * @return Key the object will be stored under.
     */
    public fun keyFor(
        scope: ScopeId,
        collection: CollectionId,
        blob: BlobDescriptor,
    ): BlobStorageKey

    /**
     * Issues permission to write a blob's object.
     *
     * Called for a blob that has been registered and whose bytes have not been accepted yet,
     * including on a repeat: a device that crashed mid-transfer asks again and gets a fresh ticket
     * for the same object. Keep the ticket short-lived and scoped to that one object — it is a
     * bearer capability, and the module hands it straight to a device.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob Blob whose object is to be written.
     * @return Permission to write, as the device will perform it.
     */
    public fun createUpload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket

    /**
     * Issues permission to read a blob's object.
     *
     * Called only for a blob whose bytes have been accepted.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob Blob whose object is to be read.
     * @return Permission to read, as the device will perform it.
     */
    public fun createDownload(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobTicket

    /**
     * Reports what the storage holds under a blob's key.
     *
     * Usually a `HEAD`. Observe and report; the module compares the answer against what the client
     * declared and decides. Reporting a checksum is optional and never on its own a reason to refuse
     * — see [BlobVerification.Stored].
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob Blob to look for.
     * @return What the storage found.
     */
    public fun verify(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
    ): BlobVerification

    /**
     * Hands over objects nothing references any more.
     *
     * A notification, not an instruction, and the distinction is the whole of this method's
     * contract. The module has already dropped its own rows for these blobs by the time it is
     * called; what it is saying is "nothing points at these any more, here are their keys, they are
     * yours". It forms no opinion about what happens next and asks for nothing back.
     *
     * That is deliberate, because disposal is a policy and the module has no business holding one.
     * Deleting at once, tagging for a lifecycle rule, keeping the object for a retention period a
     * regulator set, or doing nothing because the bucket already has a rule of its own are all
     * correct implementations, and a port that demanded a list of what was deleted would report
     * three of them as a fault.
     *
     * The division of labour is the other way round from what it looks like: deciding **which**
     * objects are garbage is the module's, because only it holds the references and documents are
     * opaque to the host; deciding **what disposal means** is the host's, because only it knows what
     * the storage is for. This method is where the first hands over to the second.
     *
     * Called after the module's rows are durable and never inside a transaction. A failure here is
     * the host's to handle: the module cannot undo an erasure it has already committed, and is not
     * told about the objects again.
     *
     * @param scope Authorised scope the collection belongs to.
     * @param collection Collection the blobs belonged to.
     * @param blobs Blobs nothing references any more, with the keys their objects are stored under.
     */
    public fun onReleased(
        scope: ScopeId,
        collection: CollectionId,
        blobs: List<StoredBlob>,
    )
}
