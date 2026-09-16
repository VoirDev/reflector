package dev.voir.reflector.sample.ledger.server

import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.network.KtorSyncTransport
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import dev.voir.reflector.sync.protocol.changes.RemoteOperation
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Drives the reference host the way a client does: over HTTP, with the client's own transport.
 *
 * Testing the module and the client separately proves each of them consistent with its own idea of
 * the protocol. Only a test that puts the real client transport on one end and the real host on the
 * other proves they share one — every mismatch in a path, a status code or a field name shows up
 * here and nowhere else.
 */
class SyncEndpointsTest {
    private val scope = ScopeId("user-1")
    private val wallet = EntityType("wallet")
    private val installation = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1"))
    private val events = ScopeEvents()

    @BeforeTest
    fun clean() {
        LedgerTestHost.clean()
    }

    private fun ApplicationTestBuilder.host(revocations: ScopeRevocations = ScopeRevocations(events)) {
        application {
            syncEndpoints(
                ledgerSyncModule(database, events),
                TokenIsScopeAuthorizer(),
                events,
                revocations,
            )
        }
    }

    private fun ApplicationTestBuilder.transport(token: String?) =
        KtorSyncTransport(
            client =
                createClient {
                    install(ContentNegotiation) { json(SyncProtocolJson.format) }
                },
            baseUrl = "/",
            tokens =
                object : TokenProvider {
                    override suspend fun token(): String? = token

                    override suspend fun refresh(): Boolean = false
                },
        )

    private fun upsert(index: Int) =
        PushOperation.Upsert(
            entity = wallet,
            id = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index))),
            baseVersion = null,
            data = buildJsonObject { put("title", "Wallet $index") },
        )

    @Test
    fun `a change made by the client comes back through the log`() =
        testApplication {
            host()
            val transport = transport(token = scope.value)

            val response =
                transport.push(
                    scope,
                    LEDGER,
                    PushRequest(installation, listOf(PushGroup(GroupId(Uuid.random()), listOf(upsert(1))))),
                )
            assertIs<PushGroupResult.Applied>(response.results.single())

            val page = transport.changes(scope, LEDGER, cursor = null, limit = 10)
            val operation =
                assertIs<RemoteOperation.Upsert>(
                    page.batches
                        .single()
                        .ops
                        .single(),
                )
            assertEquals(installation, page.batches.single().originClientId)
            assertEquals("Wallet 1", operation.data["title"]?.toString()?.trim('"'))

            val snapshot = transport.snapshot(scope, LEDGER, page = null, limit = 10)
            assertEquals(1, snapshot.items.size)
            assertEquals(page.nextCursor, snapshot.cursor)
        }

    @Test
    fun `the limits the host publishes are the ones the module enforces`() =
        testApplication {
            host()

            val limits = transport(token = scope.value).limits()

            assertEquals(500, limits.maxOperationsPerGroup)
            assertTrue(limits.retentionDays > 0)
        }

    @Test
    fun `a request without credentials is refused`() =
        testApplication {
            host()

            val response = client.get("/v1/sync/${scope.value}/${LEDGER.value}/changes")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `a request for somebody else's scope is refused`() =
        testApplication {
            host()

            val response =
                client.get("/v1/sync/${scope.value}/${LEDGER.value}/changes") {
                    header(HttpHeaders.Authorization, "Bearer somebody-else")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `refused credentials reach the client as an authentication failure`() =
        testApplication {
            host()

            assertFailsWith<SyncTransportFailure.Unauthorized> {
                transport(token = null).changes(scope, LEDGER, cursor = null, limit = 10)
            }
        }

    @Test
    fun `a revoked scope is refused in a way the client acts on`() =
        testApplication {
            val revocations = ScopeRevocations(events)
            host(revocations)
            val transport = transport(token = scope.value)
            transport.changes(scope, LEDGER, cursor = null, limit = 10)

            // What a host does when somebody is removed from a shared workspace.
            revocations.revoke(scope)

            // `403` and not `401`: the client wipes the scope instead of keeping its data until
            // somebody signs in again, which for a device nobody opens may be never.
            assertFailsWith<SyncTransportFailure.Revoked> {
                transport.changes(scope, LEDGER, cursor = null, limit = 10)
            }
        }

    @Test
    fun `an unregistered collection answers not found`() =
        testApplication {
            host()

            val response: HttpResponse =
                client.get("/v1/sync/${scope.value}/invoices/changes") {
                    header(HttpHeaders.Authorization, "Bearer ${scope.value}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private companion object {
        val database: Database get() = LedgerTestHost.database
    }
}
