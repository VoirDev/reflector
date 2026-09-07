package dev.voir.reflector.sample.ledger.server

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sample.ledger.LEDGER
import dev.voir.reflector.sample.ledger.LedgerAdapter
import dev.voir.reflector.sample.ledger.LedgerAdapter.Companion.TRANSACTION
import dev.voir.reflector.sample.ledger.LedgerAdapter.Companion.WALLET
import dev.voir.reflector.sample.ledger.LedgerDatabase
import dev.voir.reflector.sample.ledger.LedgerTransaction
import dev.voir.reflector.sample.ledger.Wallet
import dev.voir.reflector.sample.ledger.ledgerSync
import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.network.KtorSyncTransport
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

/**
 * Two whole clients against one real host, with the interleaving written out by hand.
 *
 * Every other test in the repository drives one side: the coordinators against fakes, the endpoints
 * against a scripted client, the sample against a scripted server. Echo suppression, conflicts and
 * cursor ordering all exist for the case none of them reaches — two devices editing one collection —
 * and until that case is played through, each mechanism is only known to be self-consistent.
 *
 * Both clients run with no trigger sources, so nothing happens except when the test asks for it.
 * What the test then waits for is the outcome and not a phase: a collection is `LIVE` before and
 * after a pull, so waiting on the phase would pass before anything had happened.
 */
class TwoClientConvergenceTest {
    private val scope = ScopeId("user-1")
    private val walletId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000001"))
    private val transactionId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000002"))

    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @BeforeTest
    fun clean() {
        LedgerTestHost.clean()
    }

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
    }

    @Test
    fun `a wallet created on one client appears on the other`() =
        testApplication {
            host()
            val server = probe()
            val a = openClient()
            val b = openClient()

            a.createWallet("Cash", "EUR")
            a.syncUntil("A's wallet never reached the server") { server.holds(WALLET, "title", "Cash") }
            b.syncUntil("the wallet A created never reached B") { b.wallet() != null }

            val onB = assertNotNull(b.wallet())
            assertEquals("Cash", onB.title)
            assertEquals("EUR", onB.currency)
        }

    @Test
    fun `a client does not take the echo of its own push for somebody else's change`() =
        testApplication {
            host()
            val server = probe()
            val a = openClient()

            a.createWallet("Cash", "EUR")
            a.syncUntil("A's wallet never reached the server") { server.holds(WALLET, "title", "Cash") }
            // The batch A has just written is now the next thing in the log A itself reads. Without
            // the origin on it, A would see its own change arriving as a foreign one.
            a.rename("Cash renamed")
            a.syncUntil("A's rename never reached the server") { server.holds(WALLET, "title", "Cash renamed") }
            a.syncUntil("A never read its own change back") { a.collection.state.value.pendingCount == 0 }

            assertEquals("Cash renamed", assertNotNull(a.wallet()).title, "an echo must not overwrite a newer edit")
            assertEquals(0, a.collection.state.value.conflictCount, "a client cannot conflict with itself")
        }

    @Test
    fun `the client that pushes second gets the conflict and both converge on the decision`() =
        testApplication {
            host()
            val server = probe()
            val a = openClient()
            val b = openClient()
            a.createWallet("Cash", "EUR")
            a.syncUntil("A's wallet never reached the server") { server.holds(WALLET, "title", "Cash") }
            b.syncUntil("B never received the wallet") { b.wallet() != null }

            // Both edit the same wallet while neither knows about the other's edit.
            b.goApart()
            b.rename("B's name")
            a.rename("A's name")
            a.syncUntil("A's rename never reached the server") { server.holds(WALLET, "title", "A's name") }
            b.comeBack()

            // B's push carries a base version the server has moved past, so it comes back refused.
            // A wallet's name is the user's choice, so the sample's adapter declines to decide and
            // the conflict reaches the application.
            b.syncUntil("B's losing push never produced a conflict") { b.collection.state.value.conflictCount > 0 }

            val open = b.collection.conflicts.first()
            for (conflict in open) {
                assertEquals("B's name", conflict.local.title())
                assertEquals("A's name", conflict.server.title())
            }
            // One question, although B reaches the same disagreement from two directions: its own
            // push is refused, and the change that refused it then arrives through the log. Which
            // of them lands first depends on whether the group is still serving a backoff when B
            // comes back, and the user is asked once either way.
            assertEquals(1, open.size, "one entity in disagreement is one decision to make")

            // The user picks a third answer, which is the case that has to reach the other device.
            val agreed =
                buildJsonObject {
                    put("title", "Agreed name")
                    put("currency", "EUR")
                }
            b.collection.resolve(open.single().id, Resolution.Merged(agreed))
            b.syncUntil("B's decision never reached the server") { server.holds(WALLET, "title", "Agreed name") }
            a.syncUntil("A never learned how the conflict was decided") { a.wallet()?.title == "Agreed name" }

            assertEquals("Agreed name", assertNotNull(a.wallet()).title)
            assertEquals("Agreed name", assertNotNull(b.wallet()).title)
            assertEquals(0, b.collection.state.value.conflictCount)
        }

    @Test
    fun `a conflict the adapter can decide converges without anybody being asked`() =
        testApplication {
            host()
            val server = probe()
            val a = openClient()
            val b = openClient()
            a.createWallet("Cash", "EUR")
            a.record(500, "Groceries")
            a.syncUntil(
                "A's transaction never reached the server",
            ) { server.holds(TRANSACTION, "comment", "Groceries") }
            b.syncUntil("B never received the transaction") { b.transaction() != null }

            // A movement of money is a recorded fact rather than an opinion, so the sample's adapter
            // answers TakeServer for it without asking anyone.
            b.goApart()
            b.amend(900, "Groceries, mistaken")
            a.amend(700, "Groceries, corrected")
            a.syncUntil("A's correction never reached the server") {
                server.holds(TRANSACTION, "comment", "Groceries, corrected")
            }
            b.comeBack()
            b.syncUntil("B never gave way to the server's version") {
                b.transaction()?.amountMinor == 700L
            }

            assertEquals(0, b.collection.state.value.conflictCount, "the adapter decided, so nobody is asked")
            assertEquals(assertNotNull(a.transaction()).amountMinor, assertNotNull(b.transaction()).amountMinor)
            assertEquals(assertNotNull(a.transaction()).comment, assertNotNull(b.transaction()).comment)
        }

    /**
     * Reads the host's log the way a fresh client would, to settle what actually arrived there.
     *
     * The clients' own `pendingCount` cannot answer that question: it is observed through a Room
     * flow that emits after the transaction it describes, so a test reading it straight after a
     * mutation sees the state from before — and concludes a push finished that had not started.
     * The server is the only place where "this change has landed" is a fact rather than a guess.
     */
    private fun ApplicationTestBuilder.probe(): SyncTransport =
        KtorSyncTransport(
            client = createClient { install(ContentNegotiation) { json(SyncProtocolJson.format) } },
            baseUrl = "/",
            tokens =
                object : TokenProvider {
                    override suspend fun token(): String = scope.value

                    override suspend fun refresh(): Boolean = false
                },
        )

    /**
     * Whether the host's log holds an upsert of [entity] carrying [field] equal to [value].
     *
     * @return `true` once the change is on the server, whoever put it there.
     */
    private suspend fun SyncTransport.holds(
        entity: EntityType,
        field: String,
        value: String,
    ): Boolean =
        changes(scope, LEDGER, cursor = null, limit = 100)
            .batches
            .flatMap { it.ops }
            .filterIsInstance<RemoteOperation.Upsert>()
            .any { it.entity == entity && it.data[field]?.jsonPrimitive?.content == value }

    /** Reads the one field these tests compare conflicting states by. */
    private fun JsonObject?.title(): String? = this?.get("title")?.jsonPrimitive?.content

    private fun ApplicationTestBuilder.host() {
        application {
            syncEndpoints(
                ledgerSyncModule(database, ScopeEvents()),
                TokenIsScopeAuthorizer(),
                ScopeEvents(),
            )
        }
    }

    /**
     * Opens one client: its own database, its own transport, its own installation identity.
     *
     * The identity matters more than the rest — it is generated per database on first access to the
     * scope, and it is what lets each client tell its own changes from the other's.
     */
    private fun ApplicationTestBuilder.openClient(): Client {
        val database =
            Room
                .inMemoryDatabaseBuilder<LedgerDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .build()
        val transport =
            GatedTransport(
                KtorSyncTransport(
                    client = createClient { install(ContentNegotiation) { json(SyncProtocolJson.format) } },
                    baseUrl = "/",
                    tokens =
                        object : TokenProvider {
                            override suspend fun token(): String = scope.value

                            override suspend fun refresh(): Boolean = false
                        },
                ),
            )
        val handle =
            ledgerSync(database, transport, workers, triggerSources = emptyList())
                .scope(scope)
                .collection(LEDGER)
        return Client(database, transport, handle)
    }

    /**
     * A transport with a switch, so that "edited while apart" means it in the test as well.
     *
     * Without it the scenario rests on a client not happening to synchronise, which it is entitled
     * to do at any moment: a request accepted before the test's own edit is still in the worker's
     * channel, and the push it produces would reorder the whole interleaving. Cutting the
     * connection is also what actually happens to the two devices this test is about.
     *
     * @property delegate Real transport to the host.
     */
    private class GatedTransport(
        private val delegate: SyncTransport,
    ) : SyncTransport {
        /** Whether the device can reach the server at all. */
        var online: Boolean = true

        override suspend fun push(
            scope: ScopeId,
            collection: CollectionId,
            request: PushRequest,
        ): PushResponse = gate { delegate.push(scope, collection, request) }

        override suspend fun changes(
            scope: ScopeId,
            collection: CollectionId,
            cursor: Cursor?,
            limit: Int,
        ): ChangesPage = gate { delegate.changes(scope, collection, cursor, limit) }

        override suspend fun snapshot(
            scope: ScopeId,
            collection: CollectionId,
            page: PageToken?,
            limit: Int,
        ): SnapshotPage = gate { delegate.snapshot(scope, collection, page, limit) }

        override suspend fun limits(): SyncLimits = gate { delegate.limits() }

        private suspend fun <R> gate(call: suspend () -> R): R =
            if (online) call() else throw SyncTransportFailure.Unreachable("the device is apart")
    }

    /** One device: the application's rows and the collection handle over them. */
    private inner class Client(
        val database: LedgerDatabase,
        val transport: GatedTransport,
        val collection: CollectionHandle,
    ) {
        /** Cuts this device off, so that what it edits next cannot leave until it is back. */
        fun goApart() {
            transport.online = false
        }

        /** Puts the device back on the network. */
        fun comeBack() {
            transport.online = true
        }

        suspend fun createWallet(
            title: String,
            currency: String,
        ) = collection.mutate {
            database.ledgerDao().upsertWallet(Wallet(walletId.value, title, currency))
            markUpserted(LedgerAdapter.WALLET, walletId)
        }

        suspend fun rename(title: String) =
            collection.mutate {
                val wallet = assertNotNull(database.ledgerDao().wallet(walletId.value))
                database.ledgerDao().upsertWallet(wallet.copy(title = title))
                markUpserted(LedgerAdapter.WALLET, walletId)
            }

        suspend fun record(
            amountMinor: Long,
            comment: String,
        ) = collection.mutate {
            database.ledgerDao().upsertTransaction(
                LedgerTransaction(transactionId.value, walletId.value, amountMinor, comment),
            )
            markUpserted(LedgerAdapter.TRANSACTION, transactionId)
        }

        suspend fun amend(
            amountMinor: Long,
            comment: String,
        ) = record(amountMinor, comment)

        suspend fun wallet(): Wallet? = database.ledgerDao().wallet(walletId.value)

        suspend fun transaction(): LedgerTransaction? = database.ledgerDao().transaction(transactionId.value)

        /**
         * Asks for a cycle until [condition] holds.
         *
         * Repeating the request rather than sending one is deliberate: a single cycle may have been
         * running already when the request arrived, and the conflated channel would fold the two
         * into one — leaving the test asserting on a cycle that started before its own setup.
         */
        suspend fun syncUntil(
            reason: String,
            condition: suspend () -> Boolean,
        ) {
            val reached =
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    while (!condition()) {
                        collection.requestSync()
                        delay(POLL_MILLIS)
                    }
                    true
                }
            assertNotNull(reached, "$reason; stuck at ${collection.state.value}")
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L

        /** How often a client is asked to try again while the test waits for an outcome. */
        const val POLL_MILLIS = 25L

        val database: Database get() = LedgerTestHost.database
    }
}
