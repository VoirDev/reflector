package dev.voir.reflector.sync.engine.push

import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.core.log.RecordingSyncLog
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogLevel
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.PushMetricOutcome
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.FakeAdapter
import dev.voir.reflector.sync.engine.FakeTransport
import dev.voir.reflector.sync.engine.RecordingMetrics
import dev.voir.reflector.sync.engine.TestClock
import dev.voir.reflector.sync.engine.mutation.MutationCoordinator
import dev.voir.reflector.sync.engine.retry.BackoffPolicy
import dev.voir.reflector.sync.openTestDatabase
import dev.voir.reflector.sync.persistence.RoomSyncTransactionRunner
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.persistence.record.RecordState
import dev.voir.reflector.sync.protocol.BatchSeq
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.push.AppliedVersion
import dev.voir.reflector.sync.protocol.push.ConflictEntry
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.push.RejectCode
import dev.voir.reflector.sync.protocol.push.RejectError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PushCoordinatorTest {
    private val scope = ScopeId("user-1")
    private val collection = CollectionId("ledger")

    /** Sink the coordinator's own account of what it did goes to, so a test can read it back. */
    private val logs = RecordingSyncLog()
    private val log = SyncLogger(logs, scope, collection)
    private val wallet = EntityType("wallet")

    private val database = openTestDatabase()
    private val stores = SyncStores(database)
    private val transactions = RoomSyncTransactionRunner(database)
    private val transport = FakeTransport()
    private val adapter = FakeAdapter()
    private val clock = TestClock()

    private var generated = 0
    private val uuids = { Uuid.parse("00000000-0000-7000-8000-%012d".format(++generated)) }

    private val metrics = RecordingMetrics()

    private val mutations = MutationCoordinator(stores, transactions, log) { GroupId(uuids()) }

    /** Failures the coordinator asked the worker to publish, in order. */
    private val reported = mutableListOf<SyncFailure>()

    private fun coordinator(maxDependencyMerges: Int = 8) =
        PushCoordinator(
            scope = scope,
            collection = collection,
            clientId = ClientId(Uuid.parse("00000000-0000-7000-8000-0000000000c1")),
            stores = stores,
            transactions = transactions,
            transport = transport,
            adapter = adapter,
            limits = transport.limits,
            backoff = BackoffPolicy(random = Random(1)),
            metrics = metrics,
            log = log,
            reportFailure = { reported += it },
            clock = clock,
            newUuid = uuids,
            maxDependencyMerges = maxDependencyMerges,
        )

    private fun entity(index: Int): EntityId = EntityId(Uuid.parse("00000000-0000-7000-8000-1%011d".format(index)))

    private fun body(title: String): JsonObject = buildJsonObject { put("title", title) }

    private suspend fun record(index: Int): RecordState =
        assertNotNull(stores.records.find(scope, collection, wallet, entity(index)))

    private suspend fun groupIdOf(index: Int): GroupId = assertNotNull(record(index).groupId)

    /** Returns the moment a group's backoff expires, so that a test can retry without waiting. */
    private suspend fun retryMoment(groupId: GroupId): Instant =
        Instant.fromEpochMilliseconds(assertNotNull(assertNotNull(stores.groups.find(groupId)).nextRetryAt))

    private fun applied(
        request: PushRequest,
        version: String = "42",
    ): PushResponse {
        val group = request.groups.single()
        return PushResponse(
            results =
                listOf(
                    PushGroupResult.Applied(
                        groupId = group.groupId,
                        versions =
                            group.ops.map { op ->
                                AppliedVersion(op.entity, op.id, EntityVersion(version))
                            },
                    ),
                ),
            latestSeq = BatchSeq(version),
        )
    }

    @Test
    fun `an applied group leaves the queue and keeps the versions it was given`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { applied(it) }

            val outcome = coordinator().pushOnce()

            assertEquals(PushOutcome.Applied, outcome)
            val record = record(1)
            assertEquals(EntityVersion("42"), record.serverVersion)
            assertTrue(!record.isDirty, "an acknowledged record must stop being dirty")
            assertNull(stores.groups.head(scope, collection))
        }

    @Test
    fun `a record edited during the push stays dirty and moves to a new group`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val sentGroup = groupIdOf(1)
            transport.onPush = { request ->
                // The user edits the same wallet while the envelope is on the wire.
                adapter.bodies[wallet to entity(1)] = body("Cash renamed")
                mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
                applied(request)
            }

            coordinator().pushOnce()

            val record = record(1)
            assertTrue(record.isDirty, "an edit during the flight must not be acknowledged")
            assertEquals(EntityVersion("42"), record.serverVersion)
            val group = assertNotNull(record.groupId)
            assertTrue(group != sentGroup, "the leftover belongs to a new group")
            assertNotNull(stores.groups.find(group))
        }

    @Test
    fun `a conflict parks the group and records both sides`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Conflict(
                                groupId = request.groups.single().groupId,
                                conflicts =
                                    listOf(
                                        ConflictEntry(
                                            entity = wallet,
                                            id = entity(1),
                                            serverVersion = EntityVersion("50"),
                                            data = body("Savings"),
                                        ),
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("50"),
                )
            }

            val outcome = coordinator().pushOnce()

            assertEquals(PushOutcome.Blocked, outcome)
            val record = record(1)
            val group = assertNotNull(stores.groups.find(assertNotNull(record.groupId)))
            assertEquals(PushGroupState.CONFLICTED, group.state)
            val conflict = assertNotNull(stores.conflicts.find(assertNotNull(record.conflictId)))
            assertEquals(body("Cash"), conflict.local, "the local side is what was sent")
            assertEquals(body("Savings"), conflict.server)
            assertEquals(EntityVersion("50"), conflict.serverVersion)
        }

    @Test
    fun `a dependency refusal merges with the next group under a new identifier`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            adapter.bodies[wallet to entity(2)] = body("Card")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            val first = groupIdOf(1)
            val second = groupIdOf(2)
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(RejectCode.DEPENDENCY, message = "unknown wallet"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }

            val outcome = coordinator().pushOnce()

            assertEquals(PushOutcome.Applied, outcome)
            val merged = groupIdOf(1)
            val other = groupIdOf(2)
            assertEquals(merged, other, "both changes must now travel together")
            assertTrue(
                merged != first && merged != second,
                "the server already stored an answer for the old identifier",
            )
            assertNull(stores.groups.find(first))
            assertNull(stores.groups.find(second))
            val group = assertNotNull(stores.groups.find(merged))
            assertEquals(1, group.ord, "the merged group keeps the earliest position in the queue")
            assertEquals(1, group.dependencyMerges)
        }

    @Test
    fun `a dependency refusal with nothing to merge waits instead of failing`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val groupId = groupIdOf(1)
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(RejectCode.DEPENDENCY, message = "unknown wallet"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            val group = assertNotNull(stores.groups.find(groupId))
            assertEquals(PushGroupState.PENDING, group.state, "somebody else may close it")
            assertTrue(adapter.rejections.isEmpty(), "waiting is not a refusal to report")
        }

    @Test
    fun `dependency merges stop at the ceiling and reach the application`() =
        runTest {
            for (index in 1..3) {
                adapter.bodies[wallet to entity(index)] = body("Wallet $index")
                mutations.mutate(scope, collection) { markUpserted(wallet, entity(index)) }
            }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(RejectCode.DEPENDENCY, message = "dependent"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }
            val coordinator = coordinator(maxDependencyMerges = 1)

            assertEquals(PushOutcome.Applied, coordinator.pushOnce())
            assertEquals(PushOutcome.Blocked, coordinator.pushOnce())

            val record = record(1)
            val group = assertNotNull(stores.groups.find(assertNotNull(record.groupId)))
            assertEquals(PushGroupState.FAILED, group.state)
            val rejection = assertIs<SyncRejection.Unknown>(adapter.rejections.single().third)
            assertEquals(RejectCode.DEPENDENCY.value, rejection.code)
        }

    @Test
    fun `a business refusal fails the group and reaches the application`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error =
                                    RejectError(
                                        code = RejectCode.VALIDATION,
                                        entity = wallet,
                                        id = entity(1),
                                        message = "currency is required",
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            val record = record(1)
            val group = assertNotNull(stores.groups.find(assertNotNull(record.groupId)))
            assertEquals(PushGroupState.FAILED, group.state, "a retry would block the queue")
            val (type, id, rejection) = adapter.rejections.single()
            assertEquals(wallet, type)
            assertEquals(entity(1), id)
            assertIs<SyncRejection.Validation>(rejection)
        }

    @Test
    fun `a permanent refusal becomes the failure the application sees`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error =
                                    RejectError(
                                        code = RejectCode.VALIDATION,
                                        entity = wallet,
                                        id = entity(1),
                                        message = "currency is required",
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }

            coordinator().pushOnce()

            // The queue stops here until the application rewrites the data, which is the most
            // consequential thing this library does — and until now it reached only the adapter's
            // callback, leaving the published state saying the last attempt had succeeded.
            val failure = assertIs<SyncFailure.Rejected>(reported.single())
            assertEquals(wallet, failure.entityType)
            assertEquals(entity(1), failure.entityId)
            assertIs<SyncRejection.Validation>(failure.rejection)
            assertEquals("currency is required", failure.message)
        }

    @Test
    fun `a permanent refusal is reported with the code and the entity it was about`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error =
                                    RejectError(
                                        code = RejectCode.VALIDATION,
                                        entity = wallet,
                                        id = entity(1),
                                        message = "currency is required",
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }

            coordinator().pushOnce()

            // A refusal is the collection's most consequential silence: the queue stops here until
            // the application rewrites the data, so the record has to say which change and why.
            val rejection = assertNotNull(logs.records.value.singleOrNull { it.event == SyncLogEvent.PUSH_REJECTED })
            assertEquals(SyncLogLevel.ERROR, rejection.level)
            assertEquals(collection, rejection.collection)
            assertEquals(RejectCode.VALIDATION.value, rejection.context["code"])
            assertEquals(wallet.value, rejection.context["entity"])
            assertTrue("currency is required" in rejection.message, "the server's reason has to survive")
        }

    @Test
    fun `nothing reported about a push carries the document being pushed`() =
        runTest {
            // The library sends the application's business data to the server; a log is not a second
            // place for it to leave the device. Entities are described by type, identifier and size.
            val secret = "iban-NL91ABNA0417164300"
            adapter.bodies[wallet to entity(1)] = body(secret)
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Conflict(
                                groupId = request.groups.single().groupId,
                                conflicts =
                                    listOf(
                                        ConflictEntry(
                                            entity = wallet,
                                            id = entity(1),
                                            serverVersion = EntityVersion("7"),
                                            data = body("server-$secret"),
                                        ),
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("7"),
                )
            }

            coordinator().pushOnce()

            assertTrue(logs.records.value.isNotEmpty(), "the attempt has to have been reported at all")
            for (record in logs.records.value) {
                assertTrue(secret !in record.message, "a document reached a message: ${record.message}")
                for ((key, value) in record.context) {
                    assertTrue(secret !in value, "a document reached the context under '$key'")
                }
            }
        }

    @Test
    fun `an unreachable server keeps the group and its identifier for a retry`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val groupId = groupIdOf(1)
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            val group = assertNotNull(stores.groups.find(groupId))
            assertEquals(PushGroupState.PENDING, group.state)
            assertEquals(1, group.attempts)
            assertNotNull(group.nextRetryAt, "a transient failure comes back with a delay")
            assertTrue(record(1).isDirty, "nothing was confirmed, so the change is still pending")
        }

    @Test
    fun `refused credentials stop the push without touching the queue`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val groupId = groupIdOf(1)
            transport.onPush = { throw SyncTransportFailure.Unauthorized("token refused") }

            val outcome = coordinator().pushOnce()

            assertIs<PushOutcome.Interrupted>(outcome)
            val group = assertNotNull(stores.groups.find(groupId))
            assertEquals(PushGroupState.PENDING, group.state)
            assertEquals(0, group.attempts, "authentication is not a failed attempt")
        }

    @Test
    fun `a conflicted group at the head stops the ones behind it`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Conflict(
                                groupId = request.groups.single().groupId,
                                conflicts =
                                    listOf(
                                        ConflictEntry(wallet, entity(1), EntityVersion("50"), body("Savings")),
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("50"),
                )
            }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            // A second, unrelated change queues behind the conflict. It may not overtake it: the
            // library cannot see whether it depends on the entity nobody has decided about yet.
            adapter.bodies[wallet to entity(2)] = body("Card")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            transport.pushes.clear()

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            assertTrue(transport.pushes.isEmpty(), "nothing may go out while the head waits for a decision")
            assertTrue(record(2).isDirty)
        }

    @Test
    fun `a permanently refused group at the head stops the ones behind it`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(RejectCode.VALIDATION, message = "title must not be empty"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())
            val failed = assertNotNull(record(1).groupId)
            assertEquals(PushGroupState.FAILED, assertNotNull(stores.groups.find(failed)).state)

            adapter.bodies[wallet to entity(2)] = body("Card")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            transport.pushes.clear()

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            assertTrue(transport.pushes.isEmpty(), "the queue is blocked until the application reacts")
        }

    @Test
    fun `the queue moves again once the application has corrected the refused data`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Rejected(
                                groupId = request.groups.single().groupId,
                                error = RejectError(RejectCode.VALIDATION, message = "title must not be empty"),
                            ),
                        ),
                    latestSeq = BatchSeq("1"),
                )
            }
            coordinator().pushOnce()
            val failed = assertNotNull(record(1).groupId)

            // Reacting is what the refusal asked for: the corrected entity leaves the failed group
            // for a new one, and what stays behind is a shell with nothing to send.
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { applied(it) }

            assertEquals(PushOutcome.Applied, coordinator().pushOnce())

            assertNull(stores.groups.find(failed), "a group with nothing left in it is not a barrier")
            val sent =
                assertIs<PushOperation.Upsert>(
                    transport.pushes
                        .last()
                        .groups
                        .single()
                        .ops
                        .single(),
                )
            assertEquals(body("Cash"), sent.data, "what goes out is the state the application corrected to")
        }

    @Test
    fun `a group whose answer never arrived goes out again under the same identifier`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val groupId = groupIdOf(1)
            // Anything that is not a transport failure escapes the push and strands the group: the
            // envelope was marked as sent, and nobody is left to read the answer.
            transport.onPush = { error("the process died before the answer arrived") }
            assertFailsWith<IllegalStateException> { coordinator().pushOnce() }
            assertEquals(PushGroupState.IN_FLIGHT, assertNotNull(stores.groups.find(groupId)).state)

            transport.onPush = { applied(it) }

            assertEquals(PushOutcome.Applied, coordinator().pushOnce())

            assertEquals(
                listOf(groupId),
                transport.pushes.map { it.groups.single().groupId }.distinct(),
                "a lost answer is exactly the case the idempotency key exists for",
            )
            assertTrue(!record(1).isDirty)
        }

    @Test
    fun `a group edited while it waited for its retry goes out under a new identifier`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val sent = groupIdOf(1)
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            // The server may well have applied that envelope and lost only the answer. Editing the
            // entity now changes what the group holds, and repeating the key would make the server
            // replay its stored result over content it has never seen.
            adapter.bodies[wallet to entity(1)] = body("Cash renamed")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            clock.instant = retryMoment(sent)
            transport.onPush = { applied(it) }
            transport.pushes.clear()

            assertEquals(PushOutcome.Applied, coordinator().pushOnce())

            val envelope =
                transport.pushes
                    .single()
                    .groups
                    .single()
            assertTrue(envelope.groupId != sent, "the server holds an answer under the identifier that was used")
            assertNull(stores.groups.find(sent))
            assertEquals(body("Cash renamed"), assertIs<PushOperation.Upsert>(envelope.ops.single()).data)
        }

    @Test
    fun `a retry of an envelope nothing has touched keeps its identifier`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            val sent = groupIdOf(1)
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            clock.instant = retryMoment(sent)
            transport.onPush = { applied(it) }
            transport.pushes.clear()

            assertEquals(PushOutcome.Applied, coordinator().pushOnce())

            assertEquals(
                sent,
                transport.pushes
                    .single()
                    .groups
                    .single()
                    .groupId,
                "the same envelope repeated is what the stored answer is for",
            )
        }

    @Test
    fun `a rebuilt group keeps its place in the queue and its merge ceiling`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            adapter.bodies[wallet to entity(2)] = body("Card")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            val sent = groupIdOf(1)
            stores.groups.setDependencyMerges(sent, 3)
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            adapter.bodies[wallet to entity(1)] = body("Cash renamed")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            clock.instant = retryMoment(sent)

            // Still offline, so the rebuilt group stays in the queue where it can be inspected.
            coordinator().pushOnce()

            val rebuilt = assertNotNull(stores.groups.find(groupIdOf(1)))
            assertTrue(rebuilt.groupId != sent)
            assertEquals(1, rebuilt.ord, "a new identifier must not move the changes behind a later group")
            assertEquals(3, rebuilt.dependencyMerges, "a ceiling that resets on a rebuild is not a ceiling")
        }

    @Test
    fun `an attempt is reported with what it carried and how it ended`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            coordinator().pushOnce()

            val failed = assertIs<SyncMetricEvent.PushCompleted>(metrics.events.first())
            assertEquals(1, failed.operations)
            assertEquals(PushMetricOutcome.FAILED, failed.outcome)
            // The wait a failure imposes is reported as well: summed over a period, it is the time
            // the collection spent deliberately doing nothing.
            val scheduled = assertIs<SyncMetricEvent.RetryScheduled>(metrics.events[1])
            assertEquals(1, scheduled.attempts)
            assertEquals(
                retryMoment(groupIdOf(1)) - clock.instant,
                scheduled.delay,
                "the reported wait has to be the one actually written to the group",
            )

            clock.instant = retryMoment(groupIdOf(1))
            transport.onPush = { applied(it) }
            coordinator().pushOnce()

            val applied = assertIs<SyncMetricEvent.PushCompleted>(metrics.events.last())
            assertEquals(PushMetricOutcome.APPLIED, applied.outcome)
        }

    @Test
    fun `a group serving its backoff holds the queue rather than letting the next one past`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Cash")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            transport.onPush = { throw SyncTransportFailure.Unreachable("offline") }
            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())
            val waiting = assertNotNull(stores.groups.find(groupIdOf(1)))
            assertNotNull(waiting.nextRetryAt)

            adapter.bodies[wallet to entity(2)] = body("Card")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(2)) }
            transport.pushes.clear()

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            assertTrue(transport.pushes.isEmpty(), "a backoff is the head's to serve, not the queue's to escape")
        }

    @Test
    fun `a refused push over an entity already in conflict updates it instead of asking twice`() =
        runTest {
            adapter.bodies[wallet to entity(1)] = body("Mine")
            mutations.mutate(scope, collection) { markUpserted(wallet, entity(1)) }
            // The log has already delivered somebody else's change over this edit, so the entity is
            // waiting for a decision before its push ever leaves.
            val first = ConflictId(uuids())
            transactions.transaction {
                stores.conflicts.open(
                    scope = scope,
                    collection = collection,
                    existing = null,
                    conflictId = first,
                    entityType = wallet,
                    entityId = entity(1),
                    origin = ConflictOrigin.PULL,
                    local = body("Mine"),
                    server = body("Theirs"),
                    serverVersion = EntityVersion("50"),
                    detectedAt = 0,
                )
                stores.records.setConflict(scope, collection, wallet, entity(1), first)
            }
            transport.onPush = { request ->
                PushResponse(
                    results =
                        listOf(
                            PushGroupResult.Conflict(
                                groupId = request.groups.single().groupId,
                                conflicts =
                                    listOf(
                                        ConflictEntry(wallet, entity(1), EntityVersion("51"), body("Theirs, later")),
                                    ),
                            ),
                        ),
                    latestSeq = BatchSeq("51"),
                )
            }

            assertEquals(PushOutcome.Blocked, coordinator().pushOnce())

            assertEquals(listOf(first), stores.conflicts.openIds(scope, collection), "one entity, one question")
            val conflict = assertNotNull(stores.conflicts.find(first))
            assertEquals(EntityVersion("51"), conflict.serverVersion, "the decision applies on top of the newer one")
            assertEquals(body("Theirs, later"), conflict.server)
            assertEquals(ConflictOrigin.PULL, conflict.origin, "the disagreement started where it started")
        }
}
