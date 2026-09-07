package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The invariant the whole ordering mechanism exists for: a reader must never be able to miss a batch.
 *
 * The failure this guards against is not hypothetical and not detectable in production. Two writers
 * take sequences 5 and 6; the one holding 6 commits first; a reader that reaches 6 stores it as its
 * cursor and never asks for anything below it again. Change 5 is then invisible forever — it shows
 * up weeks later as "a record disappeared for one user" and cannot be reproduced from logs. Holding
 * the counter lock until commit is what makes that impossible, and this test is what proves the lock
 * is still there.
 */
class LogOrderingTest {
    private val module = PostgresFixture.module()
    private val service = module.service
    private val scope = PostgresFixture.scope
    private val ledger = PostgresFixture.ledger
    private val wallet = PostgresFixture.wallet

    @BeforeTest
    fun clean() {
        PostgresFixture.reset()
    }

    @Test
    fun `a reader following the log sees every concurrent push exactly once`() {
        val writers = 6
        val perWriter = 8
        val expected = writers * perWriter

        val pool = Executors.newFixedThreadPool(writers + 1)
        val seen = mutableListOf<Long>()

        // The reader runs while the writers work, exactly as a client would: it advances its cursor
        // as it goes and never looks back.
        val reader =
            pool.submit {
                var cursor: Cursor? = null
                val deadline = System.nanoTime() + READ_TIMEOUT_NANOS
                while (seen.size < expected && System.nanoTime() < deadline) {
                    val page = service.changes(scope, ledger, cursor, limit = 50)
                    page.batches.forEach { seen += it.seq.value.toLong() }
                    cursor = page.nextCursor ?: cursor
                }
            }

        repeat(writers) { writer ->
            pool.submit {
                repeat(perWriter) { index ->
                    service.push(
                        scope,
                        ledger,
                        PushRequest(
                            clientId = ClientId(Uuid.random()),
                            groups =
                                listOf(
                                    PushGroup(
                                        groupId = GroupId(Uuid.random()),
                                        ops =
                                            listOf(
                                                PushOperation.Upsert(
                                                    entity = wallet,
                                                    id = EntityId(Uuid.random()),
                                                    baseVersion = null,
                                                    data = buildJsonObject { put("writer", "$writer-$index") },
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    )
                }
            }
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the writers did not finish in time")
        reader.get()

        assertEquals(expected, seen.size, "the reader missed a batch that was committed while it was reading")
        assertEquals((1L..expected).toList(), seen, "sequences have to arrive in order and without gaps")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val READ_TIMEOUT_NANOS = 30_000_000_000L
    }
}
