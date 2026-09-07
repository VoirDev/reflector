package dev.voir.reflector.sample.ledger

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sync.core.CollectionSyncState
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotItem
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Drives the demonstration application end to end: its own rows, the library, and a scripted server.
 *
 * This is the test that proves the integration contract rather than any single mechanism — that an
 * application which only writes its own tables and marks what it changed ends up with its data on
 * the server, and with the server's data in its tables.
 */
class LedgerSyncTest {
    private val scopeId = ScopeId("user-1")
    private val walletId = EntityId(Uuid.parse("00000000-0000-7000-8000-100000000001"))

    private val database =
        Room
            .inMemoryDatabaseBuilder<LedgerDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val server = ScriptedServer()

    @AfterTest
    fun stopWorkers() {
        workers.cancel()
    }

    @Test
    fun `a wallet created locally reaches the server as a document`() =
        runBlocking<Unit> {
            val collection = ledgerSync(database, server, workers).scope(scopeId).collection(LEDGER)

            collection.mutate {
                database.ledgerDao().upsertWallet(Wallet(walletId.value, "Cash", "EUR"))
                markUpserted(LedgerAdapter.WALLET, walletId)
            }
            collection.state.awaitQueueDrained()

            val operation =
                assertIs<PushOperation.Upsert>(
                    server.pushes
                        .single()
                        .groups
                        .single()
                        .ops
                        .single(),
                )
            assertEquals(LedgerAdapter.WALLET, operation.entity)
            assertEquals(
                buildJsonObject {
                    put("title", "Cash")
                    put("currency", "EUR")
                },
                operation.data,
            )
        }

    @Test
    fun `a wallet from the server lands in the application's own table`() =
        runBlocking<Unit> {
            server.snapshotItems =
                listOf(
                    SnapshotItem(
                        entity = LedgerAdapter.WALLET,
                        id = walletId,
                        version = EntityVersion("7"),
                        data =
                            buildJsonObject {
                                put("title", "Savings")
                                put("currency", "USD")
                            },
                    ),
                )
            val collection = ledgerSync(database, server, workers).scope(scopeId).collection(LEDGER)

            collection.state.awaitQueueDrained()

            val wallet = assertNotNull(database.ledgerDao().wallet(walletId.value))
            assertEquals("Savings", wallet.title)
            assertEquals("USD", wallet.currency)
        }

    @Test
    fun `a wallet the snapshot no longer lists disappears from the table`() =
        runBlocking<Unit> {
            server.snapshotItems =
                listOf(
                    SnapshotItem(
                        LedgerAdapter.WALLET,
                        walletId,
                        EntityVersion("7"),
                        buildJsonObject {
                            put("title", "Savings")
                            put("currency", "USD")
                        },
                    ),
                )
            val engine = ledgerSync(database, server, workers)
            val collection = engine.scope(scopeId).collection(LEDGER)
            collection.state.awaitQueueDrained()
            assertNotNull(database.ledgerDao().wallet(walletId.value))

            // The server no longer has it, and a client that was offline for the deletion learns
            // about it only from the absence.
            server.snapshotItems = emptyList()
            server.snapshotCursor = Cursor("200")
            collection.requestResync()

            // The test waits for the observable outcome rather than for a phase: the collection is
            // LIVE both before the resynchronisation and after it, so watching the phase would pass
            // before anything happened.
            val swept =
                withTimeoutOrNull(TIMEOUT_MILLIS) {
                    while (database.ledgerDao().wallet(walletId.value) != null) {
                        delay(POLL_MILLIS)
                    }
                }
            assertNotNull(swept, "an entity the snapshot no longer lists has to disappear locally")
        }

    private suspend fun StateFlow<CollectionSyncState>.awaitQueueDrained() {
        val reached =
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                first { it.phase == SyncPhase.LIVE && it.pendingCount == 0 }
            }
        assertNotNull(reached, "the collection never settled; stuck at $value")
    }

    private suspend fun StateFlow<CollectionSyncState>.awaitLive() {
        val reached = withTimeoutOrNull(TIMEOUT_MILLIS) { first { it.phase == SyncPhase.LIVE } }
        assertNotNull(reached, "the collection never went live; stuck at $value")
    }

    /** Server the test scripts by hand; it applies every push and serves whatever snapshot is set. */
    private class ScriptedServer : SyncTransport {
        val pushes: MutableList<PushRequest> = mutableListOf()
        var snapshotItems: List<SnapshotItem> = emptyList()
        var snapshotCursor: Cursor = Cursor("100")

        override suspend fun push(
            scope: ScopeId,
            collection: CollectionId,
            request: PushRequest,
        ): PushResponse {
            pushes += request
            val group = request.groups.single()
            return PushResponse(
                results =
                    listOf(
                        PushGroupResult.Applied(
                            groupId = group.groupId,
                            versions = group.ops.map { AppliedVersion(it.entity, it.id, EntityVersion("1")) },
                        ),
                    ),
                latestSeq = BatchSeq("1"),
            )
        }

        override suspend fun changes(
            scope: ScopeId,
            collection: CollectionId,
            cursor: Cursor?,
            limit: Int,
        ): ChangesPage = ChangesPage(emptyList(), nextCursor = null, hasMore = false)

        override suspend fun snapshot(
            scope: ScopeId,
            collection: CollectionId,
            page: PageToken?,
            limit: Int,
        ): SnapshotPage = SnapshotPage(snapshotCursor, snapshotItems, nextPage = null, hasMore = false)

        override suspend fun limits(): SyncLimits =
            SyncLimits(
                maxOperationsPerGroup = 500,
                maxDocumentBytes = 256 * 1024,
                maxChangesPageSize = 500,
                retentionDays = 30,
            )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** How often the test looks at a table it cannot observe as a flow. */
        const val POLL_MILLIS = 20L
    }
}
