package dev.voir.reflector.sync.engine

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.protocol.CollectionEpoch
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.Cursor
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.changes.ChangesPage
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.PushResponse
import dev.voir.reflector.sync.protocol.snapshot.SnapshotPage
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/** Incarnation every fake server in these tests answers from. */
val TEST_EPOCH: CollectionEpoch = CollectionEpoch("0199fd1a-0000-7000-8000-00000000000e")

/**
 * Cursor as the server would issue it: a position inside one incarnation, never a bare sequence.
 *
 * @param seq Sequence of the batch the cursor points at.
 * @return Cursor in the shape the real server produces.
 */
fun testCursor(seq: String): Cursor = Cursor("${TEST_EPOCH.value}.$seq")

/**
 * Server the engine's tests drive by hand.
 *
 * Every answer is scripted, including failures: what these tests are about is the order of pushes,
 * pulls and interruptions, and none of that is reproducible against a real server.
 */
class FakeTransport : SyncTransport {
    /** Envelopes the engine has sent, in order. */
    val pushes: MutableList<PushRequest> = mutableListOf()

    /** Answer to give to the next push; may itself run code, to simulate a concurrent edit. */
    var onPush: suspend (PushRequest) -> PushResponse = { error("no push expected") }

    /** Answer to give to the next change-log read. */
    var onChanges: suspend (Cursor?) -> ChangesPage = {
        ChangesPage(emptyList(), null, hasMore = false, epoch = TEST_EPOCH)
    }

    /** Answer to give to the next snapshot read. */
    var onSnapshot: suspend (PageToken?) -> SnapshotPage = { error("no snapshot expected") }

    /** Limits the fake server publishes. */
    var limits: SyncLimits =
        SyncLimits(
            maxOperationsPerGroup = 500,
            maxDocumentBytes = 256 * 1024,
            maxChangesPageSize = 500,
            retentionDays = 30,
        )

    override suspend fun push(
        scope: ScopeId,
        collection: CollectionId,
        request: PushRequest,
    ): PushResponse {
        pushes += request
        return onPush(request)
    }

    override suspend fun changes(
        scope: ScopeId,
        collection: CollectionId,
        cursor: Cursor?,
        limit: Int,
    ): ChangesPage = onChanges(cursor)

    override suspend fun snapshot(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): SnapshotPage = onSnapshot(page)

    override suspend fun limits(): SyncLimits = limits
}

/**
 * Application the engine's tests stand in for.
 *
 * Bodies live in a map instead of a table, which is exactly the contract the library relies on: it
 * never reads business rows itself and only ever asks for the current state of an entity.
 */
class FakeAdapter : CollectionAdapter {
    /** Current state of every entity the fake application holds. */
    val bodies: MutableMap<Pair<EntityType, EntityId>, JsonObject> = mutableMapOf()

    /** Changes the library asked to apply, in order. */
    val applied: MutableList<List<RemoteOp>> = mutableListOf()

    /** Refusals the library reported. */
    val rejections: MutableList<Triple<EntityType?, EntityId?, SyncRejection>> = mutableListOf()

    /** Conflicts the library asked about. */
    val asked: MutableList<Conflict> = mutableListOf()

    /** Decision to answer with, or `null` to hand the conflict to the user interface. */
    var resolution: Resolution? = null

    /** Shape the fake application declares for its tables, changed by a test to stand in for a migration. */
    override var schema: SchemaFingerprint? = null

    /**
     * Hook run before anything is written, so that a test can cut the process open mid-apply.
     *
     * Throwing from here is how the tests stand in for a crash: the enclosing transaction rolls
     * back, and because the hook runs before the fake touches its own map, what is left behind is
     * exactly what a killed process would leave — the batch still in the inbox and the cursor
     * still behind it.
     */
    var beforeApply: suspend (List<RemoteOp>) -> Unit = { }

    /** What each document is declared to point at, keyed the same way as [bodies]. */
    val references: MutableMap<Pair<EntityType, EntityId>, Set<BlobRef>> = mutableMapOf()

    override fun blobs(
        entityType: EntityType,
        id: EntityId,
        document: JsonObject,
    ): Set<BlobRef> = references[entityType to id].orEmpty()

    override suspend fun snapshot(
        entityType: EntityType,
        id: EntityId,
    ): JsonObject? = bodies[entityType to id]

    override suspend fun applyRemote(ops: List<RemoteOp>) {
        beforeApply(ops)
        applied += ops
        for (op in ops) {
            when (op) {
                is RemoteOp.Upsert -> bodies[op.entityType to op.id] = op.data
                is RemoteOp.Delete -> bodies.remove(op.entityType to op.id)
            }
        }
    }

    override suspend fun onRejected(
        entityType: EntityType?,
        id: EntityId?,
        rejection: SyncRejection,
    ) {
        rejections += Triple(entityType, id, rejection)
    }

    override suspend fun resolve(conflict: Conflict): Resolution? {
        asked += conflict
        return resolution
    }
}

/**
 * Metrics sink that keeps what it was told.
 *
 * The engine reports through a port it cannot see the other side of, so the only way to check that
 * a measurement is taken at all — and taken with the right numbers — is to be that other side.
 */
class RecordingMetrics : SyncMetrics {
    /** Everything reported, in order. */
    val events: MutableList<SyncMetricEvent> = mutableListOf()

    override fun record(event: SyncMetricEvent) {
        events += event
    }
}

/**
 * Clock the tests move by hand.
 *
 * Real time in a test means a flaky test; and the library itself must never derive order from the
 * clock, so pinning it also checks that nothing quietly started to.
 *
 * @property instant Current moment, changed by the test when it wants time to pass.
 */
class TestClock(
    var instant: Instant = Instant.fromEpochMilliseconds(0),
) : Clock {
    override fun now(): Instant = instant
}
