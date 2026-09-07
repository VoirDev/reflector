package dev.voir.reflector.sync.core.transport

import dev.voir.reflector.sync.protocol.events.SyncEvent

/**
 * What the event channel tells the engine.
 *
 * The channel is only an alarm clock: data always travels over the HTTP pull, which keeps one code
 * path for applying changes and makes falling back to polling trivial.
 */
public sealed class SyncChannelSignal {
    /**
     * The channel has (re)connected.
     *
     * This is not bookkeeping — it is the only thing that closes a real hole. Notifications sent
     * while the socket was down are gone, and nothing in a later notification says "you missed one",
     * so a client that only reacted to events would sit silently out of date until something else
     * happened to wake it. Every connection therefore triggers an unconditional pull.
     */
    public data object Connected : SyncChannelSignal()

    /**
     * The server sent an event.
     *
     * @property event Event as it arrived, including the ones this client does not understand.
     */
    public data class Received(
        public val event: SyncEvent,
    ) : SyncChannelSignal()
}
