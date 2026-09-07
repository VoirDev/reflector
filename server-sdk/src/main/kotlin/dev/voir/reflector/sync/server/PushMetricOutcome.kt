package dev.voir.reflector.sync.server

/**
 * How the module answered one push group, in the terms worth counting.
 *
 * The same four questions a host asks of its synchronisation traffic: how much work is accepted, how
 * much of it argues with what is already stored, how much is refused outright, and how much of it is
 * a client repeating itself because an answer was lost on the way back.
 */
public enum class PushMetricOutcome {
    /** The group was applied and a batch was committed under a new sequence. */
    APPLIED,

    /**
     * At least one entity in the group had moved past the base version the client sent, so nothing
     * of the group was applied. A rising share means several clients writing the same entities, not
     * a fault in the module.
     */
    CONFLICT,

    /**
     * The group was refused for a reason a retry cannot fix: an unregistered entity type, or a group
     * or document beyond the configured limits.
     */
    REJECTED,

    /**
     * The group had already been answered, and the stored answer was returned without applying
     * anything. This is the count of lost responses — the clients' network, seen from the server.
     */
    REPEATED,
}
