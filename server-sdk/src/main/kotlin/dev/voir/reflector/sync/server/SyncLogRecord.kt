package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * One thing the module has to say.
 *
 * Half structured and half prose, on purpose. [event] and [context] are what a sink filters, counts
 * and routes on; [message] is what a person reads. Neither substitutes for the other: a log of
 * identifiers is unreadable, and a log of sentences cannot be queried.
 *
 * **No record carries a stored document.** The module holds the application's business data as
 * opaque `jsonb` on behalf of its users, and a diagnostic channel is the wrong place for it to
 * reappear — server logs are shipped to aggregators, retained for months and read by people who
 * were never granted that scope. Entities are described by type, identifier, version and size.
 *
 * @property level Severity, and with it the threshold a sink has to be at to see this at all.
 * @property event What happened, as a stable name.
 * @property message Description for a person, in English. It may be reworded between versions;
 *   [event] is the part to match on.
 * @property scope Scope the work belongs to, or `null` for work that is not on behalf of one.
 * @property collection Collection the work belongs to, or `null` when it concerns the module.
 * @property context Values worth extracting, keyed by short stable names — counts, durations in
 *   milliseconds, outcomes, sequences. Never a document.
 * @property cause Throwable behind the record, when there is one.
 */
public data class SyncLogRecord(
    public val level: SyncLogLevel,
    public val event: SyncLogEvent,
    public val message: String,
    public val scope: ScopeId? = null,
    public val collection: CollectionId? = null,
    public val context: Map<String, String> = emptyMap(),
    public val cause: Throwable? = null,
)
