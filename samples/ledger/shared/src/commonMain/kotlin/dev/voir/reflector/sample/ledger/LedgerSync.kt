package dev.voir.reflector.sample.ledger

import dev.voir.reflector.sync.core.SyncEngine
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.transport.BlobTransport
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
 * @param files Where this application keeps the bytes of attached photographs, or `null` to
 *   synchronise none. Passing one is the whole of opting into files.
 * @param blobTransport How those files reach the server; required alongside [files].
 * @param blobFetch Whether a photograph is downloaded as soon as its wallet arrives, or waits to be
 *   asked for through [dev.voir.reflector.sync.core.CollectionHandle.fetch]. The default holds every
 *   photograph, which is what a wallet list wants; an application whose files are large passes
 *   [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] here and fetches one when a screen opens
 *   it. Either way it can be overridden per reference in
 *   [LedgerAdapter.blobs].
 * @param log Where the library says what it is doing. The default discards everything, which is what
 *   a release build wants; the Android application passes a logcat sink on a debuggable build.
 * @return Engine ready to hand out the scope of the signed-in user.
 */
fun ledgerSync(
    database: LedgerDatabase,
    transport: SyncTransport,
    coroutineScope: CoroutineScope,
    eventChannel: SyncEventChannel? = null,
    triggerSources: List<SyncTriggerSource> = listOf(PeriodicTriggerSource()),
    log: SyncLog = SyncLog.None,
    files: LedgerFiles? = null,
    blobTransport: BlobTransport? = null,
    blobFetch: BlobFetch = BlobFetch.EAGER,
): SyncEngine =
    SyncEngine(
        database = database,
        transactions = RoomSyncTransactionRunner(database),
        transport = transport,
        adapters = mapOf(LEDGER to LedgerAdapter(database.ledgerDao())),
        coroutineScope = coroutineScope,
        // Passing a file store is the whole of opting into files. Leave it out and the adapter is
        // never asked what a document points at, and this application behaves exactly as it did
        // before wallets could carry a photograph.
        blobStore = files,
        blobTransport = blobTransport,
        blobFetch = blobFetch,
        eventChannel = eventChannel,
        triggerSources = triggerSources,
        log = log,
    )
