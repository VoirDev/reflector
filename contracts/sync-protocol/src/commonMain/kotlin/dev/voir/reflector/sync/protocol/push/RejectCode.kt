package dev.voir.reflector.sync.protocol.push

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Machine-readable reason a push group was refused.
 *
 * The set is intentionally open rather than an enum: a server may learn new reasons before the
 * clients do, and an unknown code must arrive as data instead of failing to parse. A client that
 * does not recognise a code treats the group as permanently failed, which is the safe default —
 * it stops the queue on that group instead of retrying it forever.
 *
 * @property value Wire representation of the code.
 */
@Serializable
@JvmInline
public value class RejectCode(
    public val value: String,
) {
    /** Codes both sides of the protocol agree on. */
    public companion object {
        /**
         * The group depends on another group the server has not applied yet.
         *
         * This is the only refusal a retry can fix: the client merges the group with the next
         * pending one and sends it again, up to a bounded number of merges. It must not be used
         * for anything else, since the client answers it with a merge rather than a failure.
         */
        public val DEPENDENCY: RejectCode = RejectCode("DEPENDENCY")

        /** The payload violates a constraint of the host, such as a size or shape it enforces. */
        public val VALIDATION: RejectCode = RejectCode("VALIDATION")

        /** The entity type is not registered for this collection on the server. */
        public val UNKNOWN_ENTITY_TYPE: RejectCode = RejectCode("UNKNOWN_ENTITY_TYPE")

        /** The group or one of its documents exceeds the limits published by the server. */
        public val TOO_LARGE: RejectCode = RejectCode("TOO_LARGE")
    }
}
