package dev.voir.reflector.sync.server

/**
 * What a record is about, as a closed set.
 *
 * A name rather than a formatted sentence, because a sentence is for a person reading one server's
 * log and a name is for everything else: filtering a sink, counting occurrences across a fleet, or
 * finding every line about the same decision. The prose lives in [SyncLogRecord.message] and may be
 * reworded; these entries are the part worth matching on and are treated as stable.
 *
 * Each entry carries the [source] it can only come from, which is what lets an adapter route
 * records to a per-area logger without the module naming loggers itself.
 *
 * @property source Part of the module that reports this event.
 */
public enum class SyncLogEvent(
    public val source: SyncLogSource,
) {
    /** The module was assembled, with the collections and limits it serves. */
    MODULE_CREATED(SyncLogSource.MODULE),

    /** The module's schema was brought up to date. */
    MIGRATIONS_APPLIED(SyncLogSource.MODULE),

    /**
     * A host commit listener threw after a batch was committed.
     *
     * The data is durable and stays so — that is what the listener firing after the transaction is
     * for. What is lost is the notification, so clients of that scope learn about the change on
     * their next poll instead of at once. Invisible anywhere else, and slow rather than loud, which
     * is exactly the kind of failure this module has to report rather than swallow.
     */
    COMMIT_LISTENER_FAILED(SyncLogSource.MODULE),

    /** A host metrics sink threw, so that measurement was lost and nothing else was. */
    METRICS_SINK_FAILED(SyncLogSource.MODULE),

    /**
     * A host blob listener threw after a blob was accepted.
     *
     * The bytes are in storage and the blob stays usable. What is lost is whatever the host meant to
     * do with the file — a thumbnail, a scan, an extraction — and nothing will ask again, because
     * the listener fires once per blob. The one line that says a derivative is missing on purpose
     * rather than by accident.
     */
    BLOB_LISTENER_FAILED(SyncLogSource.MODULE),

    /**
     * A client arrived talking about an incarnation of a collection that no longer exists.
     *
     * It survived a purge. Reported from whichever path it reached — a push, a page of the log, a
     * snapshot — which is why it belongs to the module rather than to one of them: what it says is
     * not "this read failed" but "this installation is still carrying data the purge removed", and
     * a count of them is how a host knows the erasure has finished reaching the devices.
     */
    COLLECTION_RESET_REFUSED(SyncLogSource.MODULE),

    /** A group was written and a sequence consumed. */
    GROUP_APPLIED(SyncLogSource.PUSH),

    /** A group was refused because entities in it had moved on; nothing was written. */
    GROUP_CONFLICTED(SyncLogSource.PUSH),

    /** A group was refused for a reason the client cannot retry its way out of. */
    GROUP_REJECTED(SyncLogSource.PUSH),

    /**
     * A group arrived whose answer the module had already stored, and was answered from it.
     *
     * Idempotency working as designed. Worth a line because it is otherwise invisible and is deeply
     * confusing to debug from the client's side: the client believes it is sending new content, and
     * the server is replying about content it sent before.
     */
    GROUP_REPEATED(SyncLogSource.PUSH),

    /**
     * The per-collection counter lock was held longer than a write ought to take.
     *
     * Writers into one collection are serialised by that lock, deliberately, and it is the only
     * thing keeping the change log free of gaps. It is invisible until it is a queue. This is the
     * line that says the day has arrived.
     */
    LOCK_HELD_LONG(SyncLogSource.PUSH),

    /** A blob was registered and an upload ticket handed out. */
    BLOB_REGISTERED(SyncLogSource.BLOBS),

    /** A blob's bytes were verified against what was declared, and the blob became usable. */
    BLOB_ACCEPTED(SyncLogSource.BLOBS),

    /**
     * A blob's bytes were claimed to be in storage and were not, or did not match the declaration.
     *
     * Recoverable and expected at a low rate — a transfer that was cut off says exactly this — but a
     * rate that climbs is the storage refusing writes, which is invisible from the module otherwise.
     */
    BLOB_REFUSED(SyncLogSource.BLOBS),

    /**
     * A blob already usable was registered or confirmed again, and was answered from what is stored.
     *
     * Idempotency working as designed, and worth a line for the same reason [GROUP_REPEATED] is: it
     * is invisible elsewhere and confusing from the device's side, which believes it is uploading.
     */
    BLOB_REPEATED(SyncLogSource.BLOBS),

    /** A page of the change log was served. */
    CHANGES_SERVED(SyncLogSource.CHANGES),

    /**
     * A client's cursor was refused because the history behind it is gone.
     *
     * Each of these is a whole-collection snapshot transfer that is about to happen. A few are a
     * fact of life; many mean the retention window is too short for the population.
     */
    CURSOR_REFUSED(SyncLogSource.CHANGES),

    /** A page of a snapshot was served to a client rebuilding a collection. */
    SNAPSHOT_SERVED(SyncLogSource.SNAPSHOT),

    /** A page token arrived that this module did not produce, or can no longer parse. */
    PAGE_TOKEN_INVALID(SyncLogSource.SNAPSHOT),

    /** Retention trimming finished for one collection. */
    HISTORY_TRIMMED(SyncLogSource.MAINTENANCE),

    /**
     * Files nothing had referenced for longer than the retention window were let go of.
     *
     * Says what the module decided and stops there. What becomes of the objects is the host's, and
     * is recorded where it happens rather than second-hand here.
     */
    BLOBS_COLLECTED(SyncLogSource.MAINTENANCE),

    /**
     * A collection was purged: every row of it was physically deleted.
     *
     * Reported at `INFO`, and the line somebody eventually comes looking for: it is the only
     * removal in the module that no window and no schedule can explain, and the only one a host may
     * have to account for afterwards — "when did this scope's data go, and how much of it was
     * there".
     */
    COLLECTION_PURGED(SyncLogSource.MAINTENANCE),
}
