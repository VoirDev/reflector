package dev.voir.reflector.sync.protocol

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Identity of a collection's incarnation on the server.
 *
 * A cursor says *where* in a log a client stands; the epoch says *which log that was*. The
 * difference only becomes visible once a collection can be erased: after a purge the server starts
 * a new log for the same collection identifier, and a position in the old one is not a position in
 * the new one — it is a statement about something that no longer exists.
 *
 * Without this the mistake is silent rather than loud. A client that kept reading would be served
 * the new log from its old offset: nothing would fail, and it would simply never see the batches
 * before that offset. With it the server can refuse, and the client knows what the refusal means —
 * not "you are behind" but "what you were following is gone".
 *
 * The client stores the epoch, sends it back and never interprets it. Equality is the only
 * operation defined on it: there is no ordering, and a newer epoch is not a larger one.
 *
 * @property value Opaque server-defined token.
 */
@Serializable
@JvmInline
public value class CollectionEpoch(
    public val value: String,
)
