package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityType

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
     * The entity type is not accepted by the collection.
     *
     * Reported to the client as a refused group rather than thrown to the host: it is bad data, not
     * a broken request.
     *
     * @property collection Collection the write was addressed to.
     * @property entityType Type the client used.
     */
    public class UnknownEntityTypeException(
        public val collection: CollectionId,
        public val entityType: EntityType,
    ) : SyncServerException("collection ${collection.value} does not accept entity type ${entityType.value}")
}

/** Shorthand for [SyncServerException.CursorTooOldException]. */
public typealias CursorTooOldException = SyncServerException.CursorTooOldException

/** Shorthand for [SyncServerException.UnknownCollectionException]. */
public typealias UnknownCollectionException = SyncServerException.UnknownCollectionException

/** Shorthand for [SyncServerException.UnknownEntityTypeException]. */
public typealias UnknownEntityTypeException = SyncServerException.UnknownEntityTypeException
