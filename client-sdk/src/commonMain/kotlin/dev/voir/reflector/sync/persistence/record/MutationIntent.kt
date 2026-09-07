package dev.voir.reflector.sync.persistence.record

/**
 * What the last local mutation of a record intended.
 *
 * The intent is not the payload: bodies are materialised lazily at push time by asking the
 * application's adapter. It only says which operation to build then, and it is overwritten by every
 * new mutation, because only the latest state is ever sent.
 */
public enum class MutationIntent {
    /** The record was created or changed and its current state has to be sent. */
    UPSERT,

    /**
     * The record was deleted locally.
     *
     * A push still asks the adapter for a snapshot: an entity resurrected before the push left is
     * sent as a write instead, which keeps the queue consistent with what the tables actually hold.
     */
    DELETE,
}
