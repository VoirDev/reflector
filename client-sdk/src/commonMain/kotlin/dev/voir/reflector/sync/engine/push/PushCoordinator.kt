package dev.voir.reflector.sync.engine.push

import dev.voir.reflector.sync.core.SyncFailure
import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.core.conflict.ConflictId
import dev.voir.reflector.sync.core.conflict.ConflictOrigin
import dev.voir.reflector.sync.core.log.SyncLogEvent
import dev.voir.reflector.sync.core.log.SyncLogger
import dev.voir.reflector.sync.core.metrics.PushMetricOutcome
import dev.voir.reflector.sync.core.metrics.SyncMetricEvent
import dev.voir.reflector.sync.core.metrics.SyncMetrics
import dev.voir.reflector.sync.core.metrics.emit
import dev.voir.reflector.sync.core.transport.SyncTransport
import dev.voir.reflector.sync.core.transport.SyncTransportFailure
import dev.voir.reflector.sync.engine.mutation.EntityKey
import dev.voir.reflector.sync.engine.retry.BackoffPolicy
import dev.voir.reflector.sync.persistence.SyncStores
import dev.voir.reflector.sync.persistence.SyncTransactionRunner
import dev.voir.reflector.sync.persistence.group.PendingGroup
import dev.voir.reflector.sync.persistence.group.PushGroupState
import dev.voir.reflector.sync.persistence.record.MutationIntent
import dev.voir.reflector.sync.persistence.record.RecordState
import dev.voir.reflector.sync.protocol.ClientId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.GroupId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.config.SyncLimits
import dev.voir.reflector.sync.protocol.push.PushGroup
import dev.voir.reflector.sync.protocol.push.PushGroupResult
import dev.voir.reflector.sync.protocol.push.PushOperation
import dev.voir.reflector.sync.protocol.push.PushRequest
import dev.voir.reflector.sync.protocol.push.RejectCode
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Sends one group of local changes at a time and applies what the server answered.
 *
 * Two rules shape everything here.
 *
 * The queue is strictly ordered and only one group is in flight. Groups never share an entity, but
 * they can depend on each other in ways the library cannot see — a wallet created in one group and a
 * transaction referring to it in the next — and ordering is the only thing that covers that. The
 * price is head-of-line blocking: a group nobody can resolve stops the ones behind it. So the head
 * of the queue is read whatever state it is in, and a head that cannot be sent stops the collection
 * rather than being looked past — the one thing that would defeat the ordering entirely.
 *
 * The idempotency key is reused **only** when the very same envelope is re-sent. A group whose
 * content changed — merged after a dependency refusal, rebuilt after a conflict was resolved, or
 * simply edited again while it waited for its retry — gets a new identifier, because the server
 * stores the result it produced under the old one and would keep replaying that stored answer
 * instead of looking at the new content.
 *
 * @param scope Scope being synchronised.
 * @param collection Collection being synchronised.
 * @param clientId Identity of this installation, sent so that its own changes come back marked.
 * @param stores Storage of the library.
 * @param transactions Transaction boundary of the application's database.
 * @param transport Connection to the server.
 * @param adapter Application's bridge to its own rows.
 * @param limits Limits published by the server.
 * @param backoff Delay policy for transient failures.
 * @param metrics Sink for what each attempt cost and how it ended.
 * @param log Sink for what the queue did and why, already bound to this collection.
 * @param reportFailure Told when an attempt ended in a failure the application has to see. The
 *   coordinator does not own the collection's published state, and a refusal that only reached the
 *   adapter's callback left that state saying the last attempt had succeeded.
 * @param clock Source of local time; used only for backoff and diagnostics, never for ordering.
 * @param newUuid Source of identifiers; injected so that tests can make them predictable.
 * @param maxDependencyMerges Ceiling on merges caused by dependency refusals.
 */
internal class PushCoordinator(
    private val scope: ScopeId,
    private val collection: CollectionId,
    private val clientId: ClientId,
    private val stores: SyncStores,
    private val transactions: SyncTransactionRunner,
    private val transport: SyncTransport,
    private val adapter: CollectionAdapter,
    private val limits: SyncLimits,
    private val backoff: BackoffPolicy,
    private val metrics: SyncMetrics,
    private val log: SyncLogger,
    private val reportFailure: (SyncFailure) -> Unit = {},
    private val clock: Clock,
    private val newUuid: () -> Uuid,
    private val maxDependencyMerges: Int = DEFAULT_MAX_DEPENDENCY_MERGES,
) {
    /**
     * Sends the oldest group that is ready, if there is one.
     *
     * The network call deliberately happens between two transactions rather than inside one: a
     * database connection held across a request is how a client dead-locks itself, and the write
     * lock would be held for the whole round trip.
     *
     * @return What the worker should do next.
     */
    suspend fun pushOnce(): PushOutcome {
        val startedAt = clock.now()
        val prepared =
            when (val preparation = transactions.transaction { prepare() }) {
                is Preparation.Empty -> {
                    return PushOutcome.Idle
                }

                is Preparation.Waiting -> {
                    // The one line that answers "why is nothing leaving this device": the head of the
                    // queue is read whatever state it is in, and what it is waiting for decides
                    // whether anybody has to do something about it.
                    log.debug(
                        SyncLogEvent.PUSH_WAITING,
                        context = {
                            mapOf(
                                "group" to preparation.groupId.value.toString(),
                                "state" to preparation.state.name,
                                "attempts" to preparation.attempts.toString(),
                            )
                        },
                    ) { "the oldest group cannot be sent yet, so nothing behind it can either" }
                    return PushOutcome.Blocked
                }

                is Preparation.Rejected -> {
                    // Refused before it left the device, which is still an attempt that ended: the
                    // group is failed and the queue is blocked on it exactly as it would be by a
                    // refusal from the server.
                    log.error(
                        SyncLogEvent.PUSH_OVERSIZED,
                        context = {
                            mapOf(
                                "group" to preparation.groupId.value.toString(),
                                "operations" to preparation.operations.toString(),
                            )
                        },
                    ) { "the group exceeds the server's limits and was refused before it left: ${preparation.reason}" }
                    report(preparation.operations, PushMetricOutcome.REJECTED, startedAt)
                    reportFailure(
                        SyncFailure.Rejected(
                            entityType = null,
                            entityId = null,
                            rejection = SyncRejection.TooLarge(preparation.reason),
                            message = preparation.reason,
                        ),
                    )
                    return PushOutcome.Blocked
                }

                is Preparation.Ready -> {
                    preparation
                }
            }
        val operations =
            prepared.request.groups
                .single()
                .ops.size

        log.debug(
            SyncLogEvent.PUSH_SENT,
            context = {
                mapOf(
                    "group" to
                        prepared.group.groupId.value
                            .toString(),
                    "operations" to operations.toString(),
                    "attempts" to prepared.group.attempts.toString(),
                )
            },
        ) { "sending a group to the server" }

        val response =
            try {
                transport.push(scope, collection, prepared.request)
            } catch (failure: SyncTransportFailure) {
                report(operations, PushMetricOutcome.FAILED, startedAt)
                return handleFailure(prepared.group, failure)
            }

        val result =
            response.results.firstOrNull { it.groupId == prepared.group.groupId }
                ?: run {
                    log.error(
                        SyncLogEvent.REQUEST_FAILED,
                        context = {
                            mapOf(
                                "group" to
                                    prepared.group.groupId.value
                                        .toString(),
                            )
                        },
                    ) { "the server answered without a result for the group that was sent" }
                    report(operations, PushMetricOutcome.FAILED, startedAt)
                    return handleFailure(
                        prepared.group,
                        SyncTransportFailure.ServerError(0, "server answered without a result for the sent group"),
                    )
                }

        val outcome =
            transactions.transaction {
                // Recorded with the outcome so that a client which had no incarnation yet — one whose
                // first contact with a collection is a push — is not left claiming none on the next.
                stores.collections.setEpoch(scope, collection, response.epoch)
                apply(prepared, result)
            }
        // Reported after the transaction rather than inside it: a sink is the application's code,
        // and running it while a write lock is held is the one thing this library must never do.
        report(operations, result.toMetricOutcome(), startedAt)
        reportOutcome(prepared, result, operations, startedAt)
        return outcome
    }

    /**
     * Reports one finished attempt.
     *
     * @param operations Operations the attempt carried.
     * @param outcome How it ended.
     * @param startedAt Moment the attempt was picked up.
     */
    private fun report(
        operations: Int,
        outcome: PushMetricOutcome,
        startedAt: Instant,
    ) {
        metrics.emit(
            SyncMetricEvent.PushCompleted(
                scope = scope,
                collection = collection,
                operations = operations,
                outcome = outcome,
                duration = clock.now() - startedAt,
            ),
            log,
        )
    }

    /**
     * Describes how the server answered, in the terms somebody reading a log needs.
     *
     * Separate from [report] because the two audiences want different things: the metric is a count
     * with a duration, and this is the sentence that says which entities the server disagreed about
     * and what the application now has to do. Both run after the transaction that applied the answer.
     *
     * @param prepared Envelope that was sent.
     * @param result Answer the server gave for it.
     * @param operations Operations the envelope carried.
     * @param startedAt Moment the attempt was picked up.
     */
    private fun reportOutcome(
        prepared: Preparation.Ready,
        result: PushGroupResult,
        operations: Int,
        startedAt: Instant,
    ) {
        val group =
            prepared.group.groupId.value
                .toString()
        val tookMs = (clock.now() - startedAt).inWholeMilliseconds.toString()
        when (result) {
            is PushGroupResult.Applied -> {
                log.debug(
                    SyncLogEvent.PUSH_APPLIED,
                    context = { mapOf("group" to group, "operations" to operations.toString(), "tookMs" to tookMs) },
                ) { "the server applied the group; its changes are confirmed" }
            }

            is PushGroupResult.Conflict -> {
                log.warn(
                    SyncLogEvent.PUSH_CONFLICTED,
                    context = {
                        mapOf(
                            "group" to group,
                            "conflicts" to result.conflicts.size.toString(),
                            // Types and identifiers only: a conflict entry carries both sides of a
                            // document, and neither of them belongs in a diagnostic channel.
                            "entities" to result.conflicts.joinToString { "${it.entity.value}/${it.id.value}" },
                            "tookMs" to tookMs,
                        )
                    },
                ) { "the server refused the group because entities in it had moved on; the queue waits for a decision" }
            }

            is PushGroupResult.Rejected -> {
                if (result.error.code == RejectCode.DEPENDENCY) {
                    return
                }
                log.error(
                    SyncLogEvent.PUSH_REJECTED,
                    context = {
                        mapOf(
                            "group" to group,
                            "code" to result.error.code.value,
                            "entity" to
                                result.error.entity
                                    ?.value
                                    .orEmpty(),
                            "id" to
                                result.error.id
                                    ?.value
                                    ?.toString()
                                    .orEmpty(),
                            "tookMs" to tookMs,
                        )
                    },
                ) {
                    "the server refused the group permanently, so the queue is blocked on it until the " +
                        "application corrects the data: ${result.error.message}"
                }
            }
        }
    }

    private fun PushGroupResult.toMetricOutcome(): PushMetricOutcome =
        when (this) {
            is PushGroupResult.Applied -> {
                PushMetricOutcome.APPLIED
            }

            is PushGroupResult.Conflict -> {
                PushMetricOutcome.CONFLICT
            }

            is PushGroupResult.Rejected -> {
                if (error.code == RejectCode.DEPENDENCY) {
                    PushMetricOutcome.DEPENDENCY
                } else {
                    PushMetricOutcome.REJECTED
                }
            }
        }

    private suspend fun prepare(): Preparation {
        val now = clock.now().toEpochMilliseconds()
        val head = headOfQueue() ?: return Preparation.Empty
        when (head.state) {
            PushGroupState.PENDING -> {
                if (head.nextRetryAt != null && head.nextRetryAt > now) {
                    return Preparation.Waiting(head.groupId, head.state, head.attempts)
                }
            }

            // A push whose answer never arrived: the process died between marking the group and
            // reading the response, or the cycle failed on something that is not a transport
            // failure. The envelope goes out again under the same identifier, which is exactly the
            // case the server keeps its result for — unless the group has been edited since, which
            // `rebuiltIfChanged` below catches and gives a new one.
            PushGroupState.IN_FLIGHT -> {
                Unit
            }

            // Waiting for a decision, or refused for good. The rest of the queue waits with it:
            // stepping over the head would send a change that may depend on one the server has not
            // got, which is the single thing the ordering exists to prevent. What unblocks it is
            // the application — resolving the conflict, or correcting the data.
            PushGroupState.CONFLICTED, PushGroupState.FAILED -> {
                return Preparation.Waiting(head.groupId, head.state, head.attempts)
            }
        }

        val group = rebuiltIfChanged(head)
        stores.groups.setState(group.groupId, PushGroupState.IN_FLIGHT)
        stores.records.capturePushingRevisions(group.groupId)
        val records = stores.records.ofGroup(group.groupId).filter { it.isDirty }

        val sent = mutableMapOf<EntityKey, JsonObject?>()
        val ops =
            records.map { record ->
                val payload = if (record.intent == MutationIntent.DELETE) null else snapshotOf(record)
                sent[EntityKey(record.entityType, record.entityId)] = payload
                if (payload == null) {
                    PushOperation.Delete(
                        entity = record.entityType,
                        id = record.entityId,
                        baseVersion = record.serverVersion,
                    )
                } else {
                    PushOperation.Upsert(
                        entity = record.entityType,
                        id = record.entityId,
                        baseVersion = record.serverVersion,
                        data = payload,
                    )
                }
            }

        oversized(ops, sent)?.let { reason ->
            failGroup(group.groupId, reason.message)
            adapter.onRejected(entityType = null, id = null, rejection = reason)
            return Preparation.Rejected(group.groupId, ops.size, reason.message)
        }

        return Preparation.Ready(
            group = group,
            request =
                PushRequest(
                    clientId = clientId,
                    groups = listOf(PushGroup(group.groupId, ops)),
                    // What these changes were made against. The server refuses the request outright
                    // if that collection is gone, which is the only moment at which this queue can
                    // still be stopped from re-creating data somebody erased.
                    epoch = stores.collections.ensure(scope, collection).epoch,
                ),
            sent = sent,
        )
    }

    /**
     * Gives a group a new identifier when what it would send is no longer what it sent.
     *
     * The identifier is the idempotency key and the server keeps the answer it produced under it
     * for any outcome, refusal included. Repeating the key is right for an answer that was lost on
     * the way back and wrong for content that has changed since: the server would replay its stored
     * result, and the client would acknowledge revisions that never left the device — a change
     * marked as confirmed by a server that never saw it.
     *
     * A record whose `local_rev` has moved past the `pushing_rev` its envelope was assembled at is
     * exactly that second case. It only counts for a group that has been sent, which is one that
     * has an attempt behind it or was left in flight; a group on its way out for the first time has
     * no stored answer to collide with.
     *
     * If the server did apply the abandoned envelope, the new one arrives with base versions it has
     * moved past and comes back as a conflict — visible, and offered to the application, which is
     * the whole difference from acknowledging it silently.
     *
     * @param group Group about to be sent.
     * @return Group to send, which is [group] itself when its content is unchanged.
     */
    private suspend fun rebuiltIfChanged(group: PendingGroup): PendingGroup {
        val wasSent = group.attempts > 0 || group.state == PushGroupState.IN_FLIGHT
        if (!wasSent || stores.records.ofGroup(group.groupId).none { it.localRev > it.pushingRev }) {
            return group
        }
        // The ordinal is kept: these are the same changes and more of them, and moving them behind a
        // group queued later would reorder the collection. The dependency-merge count is kept for
        // the reason it is kept on a merge — a ceiling that resets is not a ceiling. The attempts
        // start again, because a backoff describes how one particular envelope was received, and
        // this one has not been sent yet.
        val rebuilt = GroupId(newUuid())
        log.debug(
            SyncLogEvent.PUSH_GROUP_REBUILT,
            context = { mapOf("from" to group.groupId.value.toString(), "to" to rebuilt.value.toString()) },
        ) { "the group was edited since it was last sent, so it goes out under a new idempotency key" }
        stores.groups.create(scope, collection, rebuilt)
        stores.groups.setOrdinal(rebuilt, group.ord)
        stores.groups.setDependencyMerges(rebuilt, group.dependencyMerges)
        stores.records.reassignGroup(group.groupId, rebuilt)
        stores.groups.delete(group.groupId)
        return checkNotNull(stores.groups.find(rebuilt)) { "the group was created in this transaction" }
    }

    /**
     * Returns the group at the head of the queue, discarding the spent ones in front of it.
     *
     * A group with nothing left to send is not a barrier, it is litter: everything in it was
     * acknowledged along some other path, or it was refused and the application has since corrected
     * its records into a later group, or every conflict in it was decided in the server's favour.
     * Since the head is waited on rather than looked past, such a shell left in place would block
     * the collection for good.
     *
     * @return Head of the queue with something to send, or `null` when the queue holds nothing.
     */
    private suspend fun headOfQueue(): PendingGroup? {
        while (true) {
            val group = stores.groups.head(scope, collection) ?: return null
            if (stores.records.ofGroup(group.groupId).any { it.isDirty }) {
                return group
            }
            stores.groups.delete(group.groupId)
        }
    }

    private suspend fun snapshotOf(record: RecordState): JsonObject? =
        adapter.snapshot(record.entityType, record.entityId)

    private fun oversized(
        ops: List<PushOperation>,
        sent: Map<EntityKey, JsonObject?>,
    ): SyncRejection.TooLarge? {
        if (ops.size > limits.maxOperationsPerGroup) {
            return SyncRejection.TooLarge(
                "group holds ${ops.size} operations, the server accepts ${limits.maxOperationsPerGroup}",
            )
        }
        val largest = sent.values.filterNotNull().maxOfOrNull { it.toString().encodeToByteArray().size } ?: 0
        if (largest > limits.maxDocumentBytes) {
            return SyncRejection.TooLarge(
                "document of $largest bytes exceeds the server limit of ${limits.maxDocumentBytes}",
            )
        }
        return null
    }

    private suspend fun apply(
        prepared: Preparation.Ready,
        result: PushGroupResult,
    ): PushOutcome =
        when (result) {
            is PushGroupResult.Applied -> {
                for (version in result.versions) {
                    stores.records.acknowledge(scope, collection, version.entity, version.id, version.version)
                }
                retire(prepared.group.groupId)
                stores.collections.recordPush(scope, collection, clock.now().toEpochMilliseconds())
                PushOutcome.Applied
            }

            is PushGroupResult.Conflict -> {
                stores.groups.setState(prepared.group.groupId, PushGroupState.CONFLICTED)
                for (entry in result.conflicts) {
                    // The entity may already be waiting for a decision — the log can deliver the
                    // same disagreement that refused this push. One entity, one conflict.
                    val existing = stores.records.find(scope, collection, entry.entity, entry.id)?.conflictId
                    val conflictId =
                        stores.conflicts.open(
                            scope = scope,
                            collection = collection,
                            existing = existing,
                            conflictId = ConflictId(newUuid()),
                            entityType = entry.entity,
                            entityId = entry.id,
                            origin = ConflictOrigin.PUSH,
                            local = prepared.sent[EntityKey(entry.entity, entry.id)],
                            server = entry.data,
                            serverVersion = entry.serverVersion,
                            detectedAt = clock.now().toEpochMilliseconds(),
                        )
                    stores.records.setConflict(scope, collection, entry.entity, entry.id, conflictId)
                }
                PushOutcome.Blocked
            }

            is PushGroupResult.Rejected -> {
                if (result.error.code == RejectCode.DEPENDENCY) {
                    mergeAfterDependency(prepared.group, result.error.message)
                } else {
                    failGroup(prepared.group.groupId, result.error.message)
                    adapter.onRejected(result.error.entity, result.error.id, result.error.toRejection())
                    stores.collections.recordFailure(scope, collection, result.error.message)
                    reportFailure(
                        SyncFailure.Rejected(
                            entityType = result.error.entity,
                            entityId = result.error.id,
                            rejection = result.error.toRejection(),
                            message = result.error.message,
                        ),
                    )
                    PushOutcome.Blocked
                }
            }
        }

    /**
     * Retires an applied group and collects whatever stayed dirty into a fresh one.
     *
     * Records edited while the push was in flight are exactly the ones that stayed dirty. They keep
     * their rows — including the versions the server just assigned — and move on as a new group.
     */
    private suspend fun retire(groupId: GroupId) {
        stores.records.releaseCleanRecords(groupId)
        val leftovers = stores.records.ofGroup(groupId)
        if (leftovers.isNotEmpty()) {
            val next = GroupId(newUuid())
            stores.groups.create(scope, collection, next)
            stores.records.reassignGroup(groupId, next)
        }
        stores.groups.delete(groupId)
    }

    private suspend fun mergeAfterDependency(
        group: PendingGroup,
        message: String,
    ): PushOutcome {
        if (group.dependencyMerges >= maxDependencyMerges) {
            log.error(
                SyncLogEvent.DEPENDENCY_EXHAUSTED,
                context = {
                    mapOf("group" to group.groupId.value.toString(), "merges" to group.dependencyMerges.toString())
                },
            ) {
                "the server still calls the group dependent after $maxDependencyMerges merges; it is failed " +
                    "and the queue is blocked on it: $message"
            }
            failGroup(group.groupId, message)
            adapter.onRejected(
                entityType = null,
                id = null,
                rejection =
                    SyncRejection.Unknown(
                        code = RejectCode.DEPENDENCY.value,
                        message = "group still depends on another one after $maxDependencyMerges merges: $message",
                    ),
            )
            stores.collections.recordFailure(scope, collection, message)
            reportFailure(
                SyncFailure.Rejected(
                    entityType = null,
                    entityId = null,
                    rejection =
                        SyncRejection.Unknown(
                            code = RejectCode.DEPENDENCY.value,
                            message = message,
                        ),
                    message = message,
                ),
            )
            return PushOutcome.Blocked
        }

        val next = stores.groups.nextPendingAfter(scope, collection, group.ord)
        if (next == null) {
            // There is nothing to merge with, so the dependency has to be satisfied by somebody
            // else's change. Waiting is the only honest option.
            log.debug(
                SyncLogEvent.PUSH_WAITING,
                context = { mapOf("group" to group.groupId.value.toString(), "state" to "DEPENDENCY") },
            ) { "the group depends on a change this client has not got yet, and there is nothing to merge it with" }
            stores.groups.recordAttempt(
                groupId = group.groupId,
                state = PushGroupState.PENDING,
                nextRetryAt = retryAt(group.attempts + 1),
                error = message,
            )
            return PushOutcome.Blocked
        }

        // The merged group is a new group on purpose: the server has already stored an answer under
        // the old identifier, and re-sending it would replay that answer instead of the new content.
        val merged = GroupId(newUuid())
        log.debug(
            SyncLogEvent.DEPENDENCY_MERGED,
            context = {
                mapOf(
                    "group" to group.groupId.value.toString(),
                    "with" to next.groupId.value.toString(),
                    "into" to merged.value.toString(),
                    "merges" to (group.dependencyMerges + 1).toString(),
                )
            },
        ) { "the server called the group dependent, so it is merged with the next one and will go out again" }
        stores.groups.create(scope, collection, merged)
        stores.groups.setOrdinal(merged, group.ord)
        stores.groups.setDependencyMerges(merged, group.dependencyMerges + 1)
        stores.records.reassignGroup(group.groupId, merged)
        stores.records.reassignGroup(next.groupId, merged)
        stores.groups.delete(group.groupId)
        stores.groups.delete(next.groupId)
        return PushOutcome.Applied
    }

    private suspend fun handleFailure(
        group: PendingGroup,
        failure: SyncTransportFailure,
    ): PushOutcome {
        val attempts = group.attempts + 1
        var scheduled: Duration? = null
        val outcome =
            transactions.transaction {
                when (failure) {
                    is SyncTransportFailure.CollectionReset -> {
                        // No backoff and no new attempt: the group is about to be discarded with the
                        // rest of the collection, and re-sending it is the one thing that must not
                        // happen.
                        PushOutcome.ResetRequired
                    }

                    is SyncTransportFailure.Unauthorized, is SyncTransportFailure.Revoked -> {
                        // The group goes back to the queue untouched: signing in again has to let it
                        // leave, and its identifier stays valid because the envelope never changed.
                        stores.groups.setState(group.groupId, PushGroupState.PENDING)
                        PushOutcome.Interrupted(failure)
                    }

                    else -> {
                        // The server's own instruction wins over the client's guess. The wait is
                        // named here rather than computed inside `recordAttempt`, because it is also
                        // what gets reported once the row is written.
                        val delay =
                            (failure as? SyncTransportFailure.RateLimited)?.retryAfter
                                ?: backoff.nextDelay(attempts)
                        scheduled = delay
                        stores.groups.recordAttempt(
                            groupId = group.groupId,
                            state = PushGroupState.PENDING,
                            nextRetryAt = clock.now().toEpochMilliseconds() + delay.inWholeMilliseconds,
                            error = failure.message,
                        )
                        stores.collections.recordFailure(scope, collection, failure.message.orEmpty())
                        PushOutcome.Blocked
                    }
                }
            }
        // Outside the transaction: a sink is the application's code, and it must never run with a
        // write lock held.
        scheduled?.let { delay ->
            metrics.emit(SyncMetricEvent.RetryScheduled(scope, collection, attempts, delay), log)
            log.debug(
                SyncLogEvent.RETRY_SCHEDULED,
                context = {
                    mapOf(
                        "group" to group.groupId.value.toString(),
                        "attempts" to attempts.toString(),
                        "delayMs" to delay.inWholeMilliseconds.toString(),
                        "serverAsked" to (failure is SyncTransportFailure.RateLimited).toString(),
                    )
                },
            ) { "the push failed on the transport; the group waits before the next attempt" }
        }
        return outcome
    }

    private suspend fun failGroup(
        groupId: GroupId,
        message: String,
    ) {
        stores.groups.recordAttempt(
            groupId = groupId,
            state = PushGroupState.FAILED,
            nextRetryAt = null,
            error = message,
        )
    }

    private fun retryAt(attempts: Int): Long =
        clock.now().toEpochMilliseconds() + backoff.nextDelay(attempts).inWholeMilliseconds

    private sealed class Preparation {
        /** Nothing is ready to send. */
        data object Empty : Preparation()

        /**
         * The group was refused before it left; it is already marked as failed.
         *
         * @property groupId Group that was refused.
         * @property operations Operations the refused group would have carried.
         * @property reason Why the group could not be sent, in the words the application is given.
         */
        data class Rejected(
            val groupId: GroupId,
            val operations: Int,
            val reason: String,
        ) : Preparation()

        /**
         * The head of the queue cannot be sent, so nothing can.
         *
         * It is waiting for a decision, refused permanently, or serving a backoff. They differ in
         * what unblocks them and not in what the queue does meanwhile — but they differ entirely in
         * whether anybody has to act, which is why the state is carried out for the log to name.
         *
         * @property groupId Group at the head of the queue.
         * @property state What it is waiting in.
         * @property attempts Attempts already made on it.
         */
        data class Waiting(
            val groupId: GroupId,
            val state: PushGroupState,
            val attempts: Int,
        ) : Preparation()

        /**
         * An envelope ready to be sent.
         *
         * @property group Group being sent.
         * @property request Envelope as it goes on the wire.
         * @property sent State of every entity as it was sent, kept so that a conflict can show both
         *   sides without asking the application to reproduce a state it has already changed.
         */
        data class Ready(
            val group: PendingGroup,
            val request: PushRequest,
            val sent: Map<EntityKey, JsonObject?>,
        ) : Preparation()
    }

    private companion object {
        /**
         * How many times a group may be merged because the server called it dependent.
         *
         * A ceiling is required: the server decides what a dependency is, and a server that keeps
         * answering the same way would otherwise make the client merge its whole queue into one
         * group and retry forever.
         */
        const val DEFAULT_MAX_DEPENDENCY_MERGES = 8
    }
}
