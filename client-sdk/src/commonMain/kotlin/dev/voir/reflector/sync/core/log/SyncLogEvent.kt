package dev.voir.reflector.sync.core.log

/**
 * What a record is about, as a closed set.
 *
 * A name rather than a formatted sentence, because a sentence is for a person reading one device's
 * log and a name is for everything else: filtering a sink, counting occurrences, or finding every
 * line about the same decision across a file. The prose lives in [SyncLogRecord.message] and may be
 * rewritten; these entries are the part worth grepping for and are treated as stable.
 *
 * Each entry carries the [source] it can only come from, which is what lets an adapter route
 * records to a per-area logger without the library naming loggers itself.
 *
 * @property source Part of the library that reports this event.
 */
public enum class SyncLogEvent(
    public val source: SyncLogSource,
) {
    /** An engine was built, with the collections and settings it was given. */
    ENGINE_CREATED(SyncLogSource.ENGINE),

    /** A scope was opened and its workers started. */
    SCOPE_OPENED(SyncLogSource.ENGINE),

    /** The scope moved between online, offline, needing credentials and revoked. */
    SCOPE_STATE_CHANGED(SyncLogSource.ENGINE),

    /** A sign-out stopped the workers and removed the scope's local data. */
    SCOPE_WIPED(SyncLogSource.ENGINE),

    /** A worker began a synchronisation cycle. */
    CYCLE_STARTED(SyncLogSource.ENGINE),

    /** A cycle ran to its end, with what it left behind. */
    CYCLE_FINISHED(SyncLogSource.ENGINE),

    /**
     * A cycle threw.
     *
     * The application's adapter or its database failed inside the library's transaction. Carries
     * the throwable, which is the only place the stack trace of an adapter fault appears.
     */
    CYCLE_FAILED(SyncLogSource.ENGINE),

    /** The adapter declared a different shape than the collection was last synchronised under. */
    SCHEMA_CHANGED(SyncLogSource.ENGINE),

    /** The collection was sent back to a snapshot, with the reason it was. */
    RESYNC_REQUIRED(SyncLogSource.ENGINE),

    /**
     * The server's collection turned out to be a different one, and the local copy was discarded.
     *
     * The loudest thing this library does on its own: rows the user could see are deleted and
     * changes that never reached the server are abandoned, because the collection they belonged to
     * was purged. Reported at `WARN` and with what was given up, since it is the one case where a
     * user can lose work without anybody on the device having asked for it — and the answer to
     * "where did my unsent edits go" has to exist somewhere.
     */
    COLLECTION_RESET(SyncLogSource.ENGINE),

    /**
     * The application asked for the changes this device had not sent to be thrown away.
     *
     * Reported at `WARN` with how many were given up, for the same reason as [COLLECTION_RESET]:
     * it is work the user can no longer get back, and somebody will ask where it went.
     */
    LOCAL_CHANGES_DISCARDED(SyncLogSource.ENGINE),

    /**
     * The queue stopped draining and nothing the library does will restart it.
     *
     * Reported when the collection enters that state, not once per cycle: a line per timer tick
     * about a queue that has been stuck for a week is noise, and the transition is the event.
     */
    QUEUE_BLOCKED(SyncLogSource.ENGINE),

    /** A metrics sink threw, so that measurement was lost and nothing else was. */
    METRICS_SINK_FAILED(SyncLogSource.ENGINE),

    /**
     * Two or more queued groups were merged because they share an entity.
     *
     * The answer to a queue that has become one large group: merging is transitive, so an entity
     * edited in every transaction pulls the whole queue together.
     */
    GROUPS_MERGED(SyncLogSource.MUTATION),

    /**
     * Rows the application already held were taken into synchronisation.
     *
     * Reported at `INFO` with how many were queued and how many were already tracked: a queue that
     * suddenly holds thousands of groups was put there on purpose, and this is where it says so.
     */
    ENTITIES_ADOPTED(SyncLogSource.MUTATION),

    /**
     * A file named by a document was looked into for the first time.
     *
     * Says which way it is about to travel, which is the one question reconciliation answers: bytes
     * on this device are waiting to be sent, and bytes that are not are waiting to be fetched.
     */
    BLOB_ADOPTED(SyncLogSource.BLOBS),

    /** No document names a file any more, so the application was offered it back. */
    BLOB_RELEASED(SyncLogSource.BLOBS),

    /**
     * The application asked for a file this device was not going to fetch on its own.
     *
     * The answer to "why is this downloading now", which under
     * [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] has no other answer: nothing about
     * the log changed and no timer fired — somebody opened a record. Recorded as a warning instead
     * when the file is one no document of the collection names, because then nothing will be
     * fetched and the application has asked for something it has not declared.
     */
    BLOB_REQUESTED(SyncLogSource.BLOBS),

    /**
     * A file's bytes were given up on this device, and the server still has the file.
     *
     * Not the same as [BLOB_RELEASED]: nothing stopped referencing anything, and the file can be
     * fetched again. Recorded as a warning instead when the bytes were the only copy, in which case
     * they stay.
     */
    BLOB_EVICTED(SyncLogSource.BLOBS),

    /**
     * A group was refused because files it names are no longer on the server.
     *
     * Collected as garbage while the group sat blocked for longer than the retention window. Not
     * anybody's mistake and not the application's to correct: the files are sent again and the group
     * follows them.
     */
    PUSH_BLOBS_MISSING(SyncLogSource.PUSH),

    /** A file's bytes reached the server and a record waiting for it can go out. */
    BLOB_UPLOADED(SyncLogSource.BLOBS),

    /** A file's bytes reached this device. */
    BLOB_DOWNLOADED(SyncLogSource.BLOBS),

    /**
     * A file was asked for and the server does not have its bytes yet.
     *
     * Not a failure. It is the ordinary state of an attachment whose record arrived ahead of it,
     * which is what the default binding produces on purpose, and it is answered by waiting.
     */
    BLOB_NOT_READY(SyncLogSource.BLOBS),

    /** A transfer did not complete and will be tried again. */
    BLOB_TRANSFER_FAILED(SyncLogSource.BLOBS),

    /**
     * An upload has failed as many times as a download is allowed to before being given up on, and
     * is still being tried.
     *
     * Recorded once, as a warning, because it is the moment a failure stops looking like a passing
     * network and starts looking like something nobody will notice otherwise: an upload is never
     * given up on while its bytes are here, so without this line a bucket refusing every request
     * would be visible only at debug level.
     */
    BLOB_UPLOAD_STUCK(SyncLogSource.BLOBS),

    /**
     * A file was put back to work: asked to be tried again, or found given up on while its bytes are
     * still on this device.
     */
    BLOB_RETRIED(SyncLogSource.BLOBS),

    /**
     * A file will not arrive, and the application has been told.
     *
     * The end of the line for one file, from either direction: bytes that are gone from this device
     * before they could be sent, or a server that no longer has the ones this device never fetched.
     * The reference is deliberately left alone — dropping it would be the library editing the
     * application's document on a judgement of its own.
     */
    BLOB_UNAVAILABLE(SyncLogSource.BLOBS),

    /**
     * The application threw when told about a file nothing references.
     *
     * The file stays tracked and is offered again on the next cycle, which is the safe direction: an
     * application that failed to hear must not end up with a file neither side is accounting for.
     */
    BLOB_RELEASE_FAILED(SyncLogSource.BLOBS),

    /** The head of the queue could not be sent this cycle, with what it is waiting for. */
    PUSH_WAITING(SyncLogSource.PUSH),

    /** A group was handed to the transport. */
    PUSH_SENT(SyncLogSource.PUSH),

    /** The server applied a group and the local changes in it are confirmed. */
    PUSH_APPLIED(SyncLogSource.PUSH),

    /** The server refused a group because entities in it had moved on. */
    PUSH_CONFLICTED(SyncLogSource.PUSH),

    /** The server refused a group for a reason retrying cannot fix. */
    PUSH_REJECTED(SyncLogSource.PUSH),

    /** A group exceeded the published limits and was failed before it left the device. */
    PUSH_OVERSIZED(SyncLogSource.PUSH),

    /**
     * A group was given a new identifier because its content changed since it was last sent.
     *
     * Worth a line of its own: the identifier in earlier records stops appearing at this point, and
     * without this the group looks like it vanished.
     */
    PUSH_GROUP_REBUILT(SyncLogSource.PUSH),

    /** The server called a group dependent on another, so the two were merged and will go again. */
    DEPENDENCY_MERGED(SyncLogSource.PUSH),

    /** A group was still called dependent after the permitted number of merges and was failed. */
    DEPENDENCY_EXHAUSTED(SyncLogSource.PUSH),

    /** A push failed on the transport and the group was given a delay before the next attempt. */
    RETRY_SCHEDULED(SyncLogSource.PUSH),

    /** A page of the change log arrived. */
    PULL_PAGE_RECEIVED(SyncLogSource.PULL),

    /** One batch from the log was applied to the application's tables and the cursor moved. */
    BATCH_APPLIED(SyncLogSource.PULL),

    /** The log carried an operation code this client does not understand. */
    UNKNOWN_OPERATION(SyncLogSource.PULL),

    /** The server refused the cursor as older than the history it keeps. */
    CURSOR_TOO_OLD(SyncLogSource.PULL),

    /** A bootstrap began, or resumed one that a restart had interrupted. */
    BOOTSTRAP_STARTED(SyncLogSource.BOOTSTRAP),

    /** A page of the snapshot was applied. */
    BOOTSTRAP_PAGE_APPLIED(SyncLogSource.BOOTSTRAP),

    /** Records the snapshot did not mention were deleted from the application's tables. */
    BOOTSTRAP_SWEPT(SyncLogSource.BOOTSTRAP),

    /** A bootstrap finished and the collection follows the log again. */
    BOOTSTRAP_FINISHED(SyncLogSource.BOOTSTRAP),

    /** A disagreement between local and server state was written down. */
    CONFLICT_OPENED(SyncLogSource.CONFLICT),

    /** A conflict was offered to the adapter, which either decided or left it to the user. */
    CONFLICT_OFFERED(SyncLogSource.CONFLICT),

    /** A decision was applied and the conflict is gone. */
    CONFLICT_RESOLVED(SyncLogSource.CONFLICT),

    /** A request to the server did not produce a usable answer. */
    REQUEST_FAILED(SyncLogSource.TRANSPORT),

    /** The server refused the credentials and the application was asked to renew them. */
    TOKEN_REFRESHED(SyncLogSource.TRANSPORT),

    /**
     * A line from the HTTP client's own tracing.
     *
     * Only present when the client was built with tracing switched on, and never carrying a request
     * or response body — see [dev.voir.reflector.sync.network.syncHttpClient].
     */
    HTTP_TRACE(SyncLogSource.TRANSPORT),

    /** The notification socket connected, so anything missed while it was down must be pulled. */
    CHANNEL_CONNECTED(SyncLogSource.CHANNEL),

    /** The notification socket closed or failed to open, and will be retried. */
    CHANNEL_DROPPED(SyncLogSource.CHANNEL),

    /** The server said there is something to pull. */
    CHANNEL_EVENT_RECEIVED(SyncLogSource.CHANNEL),

    /** A frame arrived that this client could not parse; it is skipped. */
    FRAME_UNDECODABLE(SyncLogSource.CHANNEL),
}
