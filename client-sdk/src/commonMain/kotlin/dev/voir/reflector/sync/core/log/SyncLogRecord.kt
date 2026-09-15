package dev.voir.reflector.sync.core.log

import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId

/**
 * One thing the library has to say.
 *
 * The shape is deliberately half structured and half prose. [event] and [context] are what a sink
 * filters, counts and routes on; [message] is what a person reads. Neither substitutes for the
 * other: a log of identifiers is unreadable, and a log of sentences cannot be queried.
 *
 * **No record ever carries an entity's document.** The library sends the application's own business
 * data to the server, and a diagnostic channel is the wrong place for it to reappear — a debug
 * build with a remote sink would export it. [context] describes documents by entity type,
 * identifier, version and size instead, and an application that needs the body itself already has
 * it inside its own adapter.
 *
 * @property level Severity, and with it the threshold a sink has to be at to see this at all.
 * @property event What happened, as a stable name.
 * @property message Description for a person, in English, never a user-facing string. It may be
 *   reworded between versions; [event] is the part to match on.
 * @property scope Scope the work belongs to, or `null` for a record from before a scope was opened.
 * @property collection Collection the work belongs to, or `null` when it concerns the whole scope.
 * @property context Values worth extracting, keyed by short stable names — group identifiers,
 *   counts, durations in milliseconds, outcomes. Never a document.
 * @property cause Throwable behind the record, when there is one. Present on every record about
 *   something that threw, which is the only place the stack trace of a failure inside the
 *   application's adapter can be seen.
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

/**
 * Renders a record as one line, for a sink that has nowhere structured to put it.
 *
 * The order is fixed — level, source and event, collection, message, then context — so that lines
 * stay column-comparable when read in bulk. The cause is not included: a sink that can attach a
 * throwable should attach it, and one that cannot decides for itself how much of it to print.
 *
 * @return Single line without a trailing separator.
 */
public fun SyncLogRecord.format(): String =
    buildString {
        append(level.name.padEnd(LEVEL_WIDTH))
        append(" [")
        append(event.source.id)
        append('/')
        append(event.name)
        append(']')
        collection?.let {
            append(' ')
            append(it.value)
        }
        append(": ")
        append(message)
        if (context.isNotEmpty()) {
            append(context.entries.joinToString(prefix = " {", postfix = "}") { "${it.key}=${it.value}" })
        }
    }

/** Width the longest level name needs, so that the rest of a line starts in the same column. */
private const val LEVEL_WIDTH = 5
