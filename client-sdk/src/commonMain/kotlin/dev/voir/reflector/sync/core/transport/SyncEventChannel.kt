package dev.voir.reflector.sync.core.transport

import dev.voir.reflector.sync.protocol.ScopeId
import kotlinx.coroutines.flow.Flow

/**
 * Push channel that tells the client when there is something to pull.
 *
 * The channel is optional by construction: an engine without one still synchronises, only later —
 * on its own triggers rather than on the server's. That is also the fallback when the socket cannot
 * be established at all, so the implementation is expected to keep reconnecting on its own rather
 * than to report failures the engine would have to interpret.
 */
public interface SyncEventChannel {
    /**
     * Subscribes to the events of a scope.
     *
     * @param scope Scope to listen to.
     * @return Flow that reconnects on its own and emits [SyncChannelSignal.Connected] every time it
     *   does; cancelling the collection closes the channel.
     */
    public fun signals(scope: ScopeId): Flow<SyncChannelSignal>
}
