package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor

/**
 * Failure the host has to translate into its transport.
 *
 * The set is closed because each case maps to a different answer on the wire, and a host that could
 * not tell them apart would turn a recoverable situation — a stale cursor — into an outage.
 *
 * @param message Description for logs and for the host's own error mapping.
 */
public sealed class SyncServerException(
    message: String,
) : Exception(message) {
    /**
     * The cursor is older than the retained history.
     *
     * The host answers `410`, and the client bootstraps. Serving a partial range instead would leave
     * it silently missing everything between its cursor and the window.
     *
     * @property cursor Position the client asked to continue from.
     */
    public class CursorTooOldException(
        public val cursor: Cursor,
    ) : SyncServerException("cursor ${cursor.value} has fallen out of the retention window")

    /**
     * The collection is not registered in the module's configuration.
     *
     * @property collection Collection the client addressed.
     */
    public class UnknownCollectionException(
        public val collection: CollectionId,
    ) : SyncServerException("collection ${collection.value} is not registered")

    /**
     * The collection the client refers to is not the one that exists now.
     *
     * It was purged and has begun again, so the client's cursor, its versions and its queue all
     * describe a log that no longer exists. The host answers `409`, and the client discards what it
     * has for that collection — pending changes included — and rebuilds from a snapshot.
     *
     * Distinct from [CursorTooOldException], which the client answers with a bootstrap that keeps
     * its local edits. Here there is nothing for those edits to be edits *of*: keeping them would
     * put back, entity by entity, the data the purge was run to remove.
     *
     * @property collection Collection the client addressed.
     * @property epoch Incarnation the client believes it is talking to.
     */
    public class CollectionResetException(
        public val collection: CollectionId,
        public val epoch: CollectionEpoch,
    ) : SyncServerException(
            "collection ${collection.value} is no longer the one epoch ${epoch.value} named; it has been purged",
        )

    /**
     * No blob with that identifier was ever registered in this collection.
     *
     * The host answers `404`. It is also the answer for a blob that was collected as garbage: from
     * the module's side those are the same fact, and telling them apart would mean keeping a
     * tombstone for every file ever deleted in order to say "this used to exist" to a client whose
     * recovery is identical either way.
     *
     * @property collection Collection the client addressed.
     * @property blobId Blob the client asked about.
     */
    public class UnknownBlobException(
        public val collection: CollectionId,
        public val blobId: BlobId,
    ) : SyncServerException("blob ${blobId.value} is not registered in collection ${collection.value}")

    /**
     * The identifier already names a usable blob whose bytes are not the ones now being declared.
     *
     * The host answers `409`. A blob is immutable, so this is a client bug rather than a race: two
     * different files were given one identifier, and accepting either would silently change what
     * every device that already fetched it is holding.
     *
     * @property collection Collection the client addressed.
     * @property blobId Blob the client tried to register again.
     */
    public class BlobConflictException(
        public val collection: CollectionId,
        public val blobId: BlobId,
    ) : SyncServerException(
            "blob ${blobId.value} of collection ${collection.value} already holds different bytes",
        )

    /**
     * The storage holds no such object, or one that disagrees with what was declared.
     *
     * The host answers `409`. The blob stays registered and unusable, which is recoverable: the
     * client transfers again under the same identifier, because nothing was ever accepted under it.
     *
     * @property collection Collection the client addressed.
     * @property blobId Blob whose object was looked for.
     * @property reason What disagreed, for the host's logs.
     */
    public class BlobNotStoredException(
        public val collection: CollectionId,
        public val blobId: BlobId,
        public val reason: String,
    ) : SyncServerException("blob ${blobId.value} of collection ${collection.value} was not accepted: $reason")

    /**
     * The declared size exceeds the limit the deployment publishes.
     *
     * The host answers `413`. Refused at registration, before a ticket exists, because the point of
     * publishing the limit is that nobody pays to transfer a file that was never going to be kept.
     *
     * @property collection Collection the client addressed.
     * @property blobId Blob the client tried to register.
     * @property size Size the client declared.
     * @property limit Largest size this deployment accepts.
     */
    public class BlobTooLargeException(
        public val collection: CollectionId,
        public val blobId: BlobId,
        public val size: Long,
        public val limit: Long,
    ) : SyncServerException(
            "blob ${blobId.value} of collection ${collection.value} declares $size octets, over the limit of $limit",
        )
}

/** Shorthand for [SyncServerException.CursorTooOldException]. */
public typealias CursorTooOldException = SyncServerException.CursorTooOldException

/** Shorthand for [SyncServerException.UnknownCollectionException]. */
public typealias UnknownCollectionException = SyncServerException.UnknownCollectionException

/** Shorthand for [SyncServerException.CollectionResetException]. */
public typealias CollectionResetException = SyncServerException.CollectionResetException

/** Shorthand for [SyncServerException.UnknownBlobException]. */
public typealias UnknownBlobException = SyncServerException.UnknownBlobException

/** Shorthand for [SyncServerException.BlobConflictException]. */
public typealias BlobConflictException = SyncServerException.BlobConflictException

/** Shorthand for [SyncServerException.BlobNotStoredException]. */
public typealias BlobNotStoredException = SyncServerException.BlobNotStoredException

/** Shorthand for [SyncServerException.BlobTooLargeException]. */
public typealias BlobTooLargeException = SyncServerException.BlobTooLargeException
