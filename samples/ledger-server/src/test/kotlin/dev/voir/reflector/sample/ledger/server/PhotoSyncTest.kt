package dev.voir.reflector.sample.ledger.server

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sample.ledger.LedgerDatabase
import dev.voir.reflector.sample.ledger.LedgerFiles
import dev.voir.reflector.sample.ledger.Wallet
import dev.voir.reflector.sample.ledger.ledgerSync
import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.blob.BlobFetch
import dev.voir.reflector.sync.core.blob.BlobTransferState
import dev.voir.reflector.sync.network.KtorBlobTransport
import dev.voir.reflector.sync.network.KtorSyncTransport
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * A photograph taken on one device and looked at on another, against a real server.
 *
 * This is what the whole file extension is for, and the only test in the repository where every
 * piece is the real one: a PostgreSQL, the module, HTTP, presigned URLs, two separate file stores on
 * disk, and two clients that have never heard of each other. Everything below the endpoints is
 * exercised exactly as an application would exercise it.
 *
 * The scenario is deliberately the one from the design: a wallet created with a photograph, the
 * photograph replaced, and — the case that decides the default binding — a record that must not wait
 * for its file.
 */
class PhotoSyncTest {
    private val scope = ScopeId("user-1")
    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val storageDirectory = Files.createTempDirectory("ledger-objects").toFile()
    private val storage =
        DirectoryBlobStorage(
            directory = storageDirectory,
            baseUrl = "",
            secret = "test-secret".encodeToByteArray(),
            ticketLife = 15.minutes,
        )

    @BeforeTest
    fun clean() {
        LedgerTestHost.clean()
    }

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
        storageDirectory.deleteRecursively()
    }

    @Test
    fun `a photograph attached on one device is readable on another`() =
        testApplication {
            host()
            val first = openClient("first")
            val second = openClient("second")
            val walletId = Uuid.random()
            val photo = BlobId(Uuid.random())

            // The bytes are written to this application's own store before the mutation that names
            // them: a document may not point at a file whose bytes are not there yet.
            first.files.put(photo, PHOTO)
            first.handle.mutate {
                first.database.ledgerDao().upsertWallet(Wallet(walletId, "Holiday", "EUR", photo.value))
                markUpserted(
                    dev.voir.reflector.sample.ledger.LedgerAdapter.WALLET,
                    dev.voir.reflector.sync.protocol
                        .EntityId(walletId),
                )
            }

            await("the wallet reaching the second device", first, second) {
                second.database.ledgerDao().wallet(walletId) != null
            }
            // The record arrives before the bytes do, which is the default binding working. A screen
            // on this device would be drawing a placeholder at this moment.
            await("the photograph reaching the second device", first, second) {
                second.files.contentsOf(photo) != null
            }
            assertContentEquals(PHOTO, second.files.contentsOf(photo))
            assertEquals(
                photo.value,
                second.database
                    .ledgerDao()
                    .wallet(walletId)
                    ?.photoBlobId,
            )
        }

    @Test
    fun `replacing the photograph moves the reference and lets the old file go`() =
        testApplication {
            host()
            val first = openClient("first")
            val second = openClient("second")
            val walletId = Uuid.random()
            val original = BlobId(Uuid.random())
            val replacement = BlobId(Uuid.random())

            first.files.put(original, PHOTO)
            first.handle.mutate {
                first.database.ledgerDao().upsertWallet(Wallet(walletId, "Holiday", "EUR", original.value))
                markUpserted(
                    dev.voir.reflector.sample.ledger.LedgerAdapter.WALLET,
                    dev.voir.reflector.sync.protocol
                        .EntityId(walletId),
                )
            }
            await("the first photograph arriving", first, second) { second.files.contentsOf(original) != null }

            first.files.put(replacement, OTHER_PHOTO)
            first.handle.mutate {
                first.database.ledgerDao().upsertWallet(Wallet(walletId, "Holiday", "EUR", replacement.value))
                markUpserted(
                    dev.voir.reflector.sample.ledger.LedgerAdapter.WALLET,
                    dev.voir.reflector.sync.protocol
                        .EntityId(walletId),
                )
            }

            await("the replacement arriving", first, second) { second.files.contentsOf(replacement) != null }
            assertContentEquals(OTHER_PHOTO, second.files.contentsOf(replacement))
            // Nothing names the original any more, so both devices were offered it back — and this
            // application, which has no undo, took the offer.
            await("both devices letting the original go", first, second) {
                first.files.released.contains(original) && second.files.released.contains(original)
            }
            assertNull(second.files.contentsOf(original))
        }

    @Test
    fun `the wallet does not wait for its photograph`() =
        testApplication {
            host()
            val first = openClient("first")
            val second = openClient("second")
            val walletId = Uuid.random()
            val photo = BlobId(Uuid.random())

            first.files.put(photo, PHOTO)
            first.handle.mutate {
                first.database.ledgerDao().upsertWallet(Wallet(walletId, "Holiday", "EUR", photo.value))
                markUpserted(
                    dev.voir.reflector.sample.ledger.LedgerAdapter.WALLET,
                    dev.voir.reflector.sync.protocol
                        .EntityId(walletId),
                )
            }

            // The case that decides the default. The record is what the user is waiting for, and it
            // must not be held back by bytes: on a poor connection those are the difference between
            // a wallet appearing in a second and appearing in a minute.
            await("the wallet arriving", first, second) { second.database.ledgerDao().wallet(walletId) != null }
            val state = assertNotNull(second.handle.blob(photo).first())
            assertTrue(
                state.state == BlobTransferState.REMOTE ||
                    state.state == BlobTransferState.DOWNLOADING ||
                    state.state == BlobTransferState.READY,
                "the file is on its own path and says so: ${state.state}",
            )
        }

    /**
     * Waits for something to happen, nudging the devices while it waits.
     *
     * The nudging is not impatience. These devices are built with no timer and no socket, so nothing
     * wakes them on their own — a real application supplies both, and a test that relied on either
     * would be waiting on a schedule instead of on the thing it is about. Asking repeatedly also
     * survives the conflated request channel folding a request into a cycle that was already running.
     *
     * @param description What is being waited for, for the failure message.
     * @param clients Devices to keep asking.
     * @param condition What has to become true.
     */
    @Test
    fun `a device that fetches on demand takes the wallet and leaves the photograph`() =
        testApplication {
            host()
            val first = openClient("first")
            val second = openClient("second", blobFetch = BlobFetch.ON_DEMAND)
            val walletId = Uuid.random()
            val photo = BlobId(Uuid.random())

            first.files.put(photo, PHOTO)
            first.handle.mutate {
                first.database.ledgerDao().upsertWallet(Wallet(walletId, "Holiday", "EUR", photo.value))
                markUpserted(
                    dev.voir.reflector.sample.ledger.LedgerAdapter.WALLET,
                    dev.voir.reflector.sync.protocol
                        .EntityId(walletId),
                )
            }

            await("the wallet reaching the second device", first, second) {
                second.database.ledgerDao().wallet(walletId) != null
            }
            // The whole of what the policy buys: the record is here, the reference is here, and the
            // four kilobytes have stayed on the server. A screen drawing this wallet offers a
            // download rather than a placeholder that fills itself in.
            repeat(SYNCS_WITHOUT_A_REQUEST) {
                second.handle.requestSync()
                delay(SETTLE)
            }
            assertNull(second.files.contentsOf(photo), "nothing asked for the photograph")
            val published = assertNotNull(second.handle.blob(photo).first())
            assertEquals(BlobTransferState.REMOTE, published.state)
            assertFalse(published.wanted)

            second.handle.fetch(photo)

            // And once somebody opens the wallet, the same worker that would have fetched it
            // eagerly fetches it now — through a real ticket, against the host's own storage.
            await("the photograph arriving once asked for", first, second) {
                second.files.contentsOf(photo) != null
            }
            assertContentEquals(PHOTO, second.files.contentsOf(photo))
        }

    private suspend fun await(
        description: String,
        vararg clients: Client,
        condition: suspend () -> Boolean,
    ) {
        val reached =
            withTimeoutOrNull(AWAIT_TIMEOUT) {
                while (!condition()) {
                    clients.forEach { it.handle.requestSync() }
                    delay(POLL)
                }
                true
            }
        assertNotNull(reached, "never reached $description")
    }

    private fun ApplicationTestBuilder.host() {
        val events = ScopeEvents()
        application {
            syncEndpoints(
                ledgerSyncModule(LedgerTestHost.database, events, storage = storage),
                TokenIsScopeAuthorizer(),
                events,
                storage = storage,
            )
        }
    }

    /**
     * Opens one device: its own database, its own file store on disk, its own installation identity.
     *
     * @param name Directory the device keeps its files in, so that two devices cannot see each
     *   other's — which is the whole point of the files having to travel.
     * @param blobFetch Whether this device fetches a photograph as soon as its wallet arrives, or
     *   waits to be asked for it.
     */
    private fun ApplicationTestBuilder.openClient(
        name: String,
        blobFetch: BlobFetch = BlobFetch.EAGER,
    ): Client {
        val database =
            Room
                .inMemoryDatabaseBuilder<LedgerDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .build()
        val httpClient = createClient { install(ContentNegotiation) { json(SyncProtocolJson.format) } }
        val tokens =
            object : TokenProvider {
                override suspend fun token(): String = scope.value

                override suspend fun refresh(): Boolean = false
            }
        val files = LedgerFiles(Path(Files.createTempDirectory(name).toString()))
        val handle =
            ledgerSync(
                database = database,
                transport = KtorSyncTransport(httpClient, baseUrl = "/", tokens = tokens),
                coroutineScope = workers,
                triggerSources = emptyList(),
                files = files,
                blobTransport = KtorBlobTransport(httpClient, baseUrl = "/", tokens = tokens),
                blobFetch = blobFetch,
            ).scope(scope).collection(dev.voir.reflector.sample.ledger.LEDGER)
        return Client(database, files, handle)
    }

    private class Client(
        val database: LedgerDatabase,
        val files: LedgerFiles,
        val handle: CollectionHandle,
    )

    private companion object {
        val PHOTO = ByteArray(4096) { (it % 251).toByte() }
        val OTHER_PHOTO = ByteArray(2048) { (it % 199).toByte() }
        const val AWAIT_TIMEOUT = 30_000L
        const val POLL = 25L

        /** Long enough for a transfer nobody asked for to have happened, if one was going to. */
        const val SETTLE = 200L

        /** Cycles run before concluding that a file nobody asked for is staying where it is. */
        const val SYNCS_WITHOUT_A_REQUEST = 3
    }
}
