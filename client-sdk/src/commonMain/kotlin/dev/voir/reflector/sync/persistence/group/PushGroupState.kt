package dev.voir.reflector.sync.persistence.group

/**
 * State of a push group in the collection's queue.
 *
 * Only one group of a collection is in flight at a time and groups leave strictly in order of their
 * ordinal. Ordering costs the queue its head-of-line: a group that cannot leave blocks the ones
 * behind it. That is deliberate — without knowing the references between entities, the library
 * cannot tell which of the following groups depend on the stuck one.
 */
public enum class PushGroupState {
    /** Waiting for its turn, or for its backoff to expire. */
    PENDING,

    /** Sent, with no answer yet. Never merged with another group: it is already on the wire. */
    IN_FLIGHT,

    /** Refused because at least one of its entities had moved on; waits for the conflicts. */
    CONFLICTED,

    /**
     * Refused permanently.
     *
     * The queue of the collection stays blocked on it until the application changes the data,
     * because retrying it unchanged would produce a message that never leaves.
     */
    FAILED,
}
