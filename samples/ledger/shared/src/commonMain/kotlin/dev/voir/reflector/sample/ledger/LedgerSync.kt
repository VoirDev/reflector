package dev.voir.reflector.sample.ledger

import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.transport.SyncEventChannel
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.trigger.PeriodicTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTriggerSource
import dev.voir.reflector.sync.engine.SyncEngine
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.protocol.CollectionId
import kotlinx.coroutines.CoroutineScope

/** Collection the demonstration application keeps its data in. */
val LEDGER: CollectionId = CollectionId("ledger")

/**
 * Wires the library into the demonstration application.
 *
 * Everything the library needs is here and nothing else is: the application's database, a way to
 * reach the server, an adapter per collection, and a scope to run the workers in. The scope belongs
 * to the application on purpose — stopping synchronisation is then a matter of cancelling something
 * it already owns, rather than of a lifecycle method the library would have to be trusted with.
 *
 * @param database Application's database, which also carries the library's tables.
 * @param transport Connection to the server.
 * @param coroutineScope Scope the background workers run in.
 * @param eventChannel Optional push channel; without it the client synchronises on its own triggers.
 * @param triggerSources Reasons to synchronise the application produces. A real application adds a
 *   [dev.voir.reflector.sync.core.trigger.ManualTriggerSource] here and fires it from its lifecycle
 *   observer and its connectivity callback; the timer left below is only the floor under those.
 * @return Engine ready to hand out the scope of the signed-in user.
 */
fun ledgerSync(
    database: LedgerDatabase,
    transport: SyncTransport,
    coroutineScope: CoroutineScope,
    eventChannel: SyncEventChannel? = null,
    triggerSources: List<SyncTriggerSource> = listOf(PeriodicTriggerSource()),
): SyncEngine =
    SyncEngine(
        database = database,
        transactions = RoomSyncTransactionRunner(database),
        transport = transport,
        adapters = mapOf(LEDGER to LedgerAdapter(database.ledgerDao())),
        coroutineScope = coroutineScope,
        eventChannel = eventChannel,
        triggerSources = triggerSources,
    )
