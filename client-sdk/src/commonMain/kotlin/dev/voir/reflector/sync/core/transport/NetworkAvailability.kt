package dev.voir.reflector.sync.core.transport

/**
 * What the application knows about this device's own connection.
 *
 * The library cannot answer this. All it ever observes is that a request did not reach the server,
 * and that has two causes a person would describe completely differently: the device has no network
 * at all, or the device is fine and the server is not answering. Only the platform can tell them
 * apart — `ConnectivityManager` on Android, `NWPathMonitor` on Apple platforms — so the application
 * supplies the answer and the library asks at the moment it has to classify a failure.
 *
 * Optional. An engine built without one reports [dev.voir.reflector.sync.core.ScopeState.ServerUnreachable]
 * for every unreachable server, because that is the part the library actually witnessed; claiming
 * the device is offline without having looked would be a guess dressed as a fact.
 *
 * Implementations are called from a worker and must not block.
 */
public fun interface NetworkAvailability {
    /**
     * Answers whether the device currently has a usable connection.
     *
     * Asked only after a request has already failed, so a cached answer from a platform monitor is
     * exactly what is wanted; nothing here should perform a probe of its own.
     *
     * @return `true` when the device has a network, `false` when it has none.
     */
    public fun hasNetwork(): Boolean
}
