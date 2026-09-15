package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.protocol.blob.BlobDescriptor
import dev.voir.reflector.sync.protocol.blob.BlobDownload
import dev.voir.reflector.sync.protocol.blob.BlobInfo
import dev.voir.reflector.sync.protocol.blob.BlobRegistration
import dev.voir.reflector.sync.protocol.blob.BlobState
import dev.voir.reflector.sync.server.BlobConfig
import dev.voir.reflector.sync.server.BlobConflictException
import dev.voir.reflector.sync.server.BlobListener
import dev.voir.reflector.sync.server.BlobNotStoredException
import dev.voir.reflector.sync.server.BlobRefusalReason
import dev.voir.reflector.sync.server.BlobStorage
import dev.voir.reflector.sync.server.BlobTooLargeException
import dev.voir.reflector.sync.server.BlobVerification
import dev.voir.reflector.sync.server.StoredBlob
import dev.voir.reflector.sync.server.SyncBlobService
import dev.voir.reflector.sync.server.SyncConfig
import dev.voir.reflector.sync.server.SyncLog
import dev.voir.reflector.sync.server.SyncLogEvent
import dev.voir.reflector.sync.server.SyncLogger
import dev.voir.reflector.sync.server.SyncMetricEvent
import dev.voir.reflector.sync.server.SyncMetrics
import dev.voir.reflector.sync.server.UnknownBlobException
import dev.voir.reflector.sync.server.emit
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Files on PostgreSQL, which is to say: everything about a file except the file.
 *
 * The shape of every operation here is the same, and it is the one rule this class exists to keep:
 * **the host's storage is never called inside a transaction.** Presigning and a `HEAD` are somebody
 * else's code and may reach the network, and a database connection held across that is how a backend
 * deadlocks itself under load. So each operation reads what it needs, lets the transaction go, talks
 * to the host, and opens a second transaction to write the outcome — which is also why every write
 * here is conditional on the state it expects, rather than on what was read a moment ago.
 *
 * @property database Database the module's schema lives in.
 * @property config Registered collections.
 * @property blobConfig Limits on files.
 * @property storage The host's object storage.
 * @property listeners Told after a blob's bytes have been accepted; the host's acknowledgement.
 * @property clock Source of timestamps, injected so that tests are deterministic.
 * @property metrics Sink for what each operation cost, reported after the work is durable.
 * @property log Sink for what each operation decided.
 */
internal class PostgresSyncBlobService(
    private val database: Database,
    private val config: SyncConfig,
    private val blobConfig: BlobConfig,
    private val storage: BlobStorage,
    private val listeners: List<BlobListener>,
    private val clock: Clock,
    private val metrics: SyncMetrics,
    private val log: SyncLog,
) : SyncBlobService {
    private val logger = SyncLogger(log)
    private val collections = CollectionRows(config, clock)
    private val blobs = BlobRows()

    override fun register(
        scope: ScopeId,
        collection: CollectionId,
        descriptor: BlobDescriptor,
    ): BlobRegistration {
        config.require(collection)
        val logger = logger.forCollection(scope, collection)
        if (descriptor.size > blobConfig.maxBlobBytes) {
            logger.warn(
                SyncLogEvent.BLOB_REFUSED,
                context = { mapOf("blob" to descriptor.blobId.value.toString(), "size" to descriptor.size.toString()) },
            ) { "the blob was refused before a ticket was issued: it declares more than the published limit" }
            throw BlobTooLargeException(collection, descriptor.blobId, descriptor.size, blobConfig.maxBlobBytes)
        }

        // Asked before the module knows whether it will need the answer, which is why the port
        // requires this to be a cheap local decision: a discarded key must cost nothing.
        val proposedKey = storage.keyFor(scope, collection, descriptor)
        val registered =
            transaction(database) {
                val row = collections.ensure(scope, collection)
                val existing = blobs.find(row.id, descriptor.blobId)
                when {
                    existing == null -> {
                        blobs.insertIfAbsent(row.id, descriptor, proposedKey, clock.now())
                    }

                    // Nothing has accepted the old declaration, so the new one replaces it. The
                    // alternative is a blob stuck for good between a declaration nobody meant and
                    // bytes that can never match it.
                    existing.state == BlobState.PENDING && existing.declares(descriptor).not() -> {
                        blobs.redeclare(row.id, descriptor)
                    }

                    else -> {
                    }
                }
                val fresh =
                    checkNotNull(blobs.find(row.id, descriptor.blobId)) { "blob row disappeared right after insert" }
                // Decided where it is known rather than inferred afterwards from a timestamp: with a
                // real clock the moment of the insert is not the moment anything later reads.
                Registration(fresh, repeated = existing != null)
            }
        val stored = registered.blob

        if (stored.state == BlobState.READY) {
            // A blob is immutable, so one identifier naming two different files is a client bug
            // rather than a race: accepting either would change what every device already holds.
            if (!stored.declares(descriptor)) {
                throw BlobConflictException(collection, descriptor.blobId)
            }
            logger.debug(SyncLogEvent.BLOB_REPEATED, context = { blobContext(stored) }) {
                "the blob is already usable, so there is nothing left to send"
            }
            metrics.emit(SyncMetricEvent.BlobRegistered(scope, collection, stored.size, repeated = true), logger)
            return BlobRegistration(stored.toInfo(), upload = null)
        }

        val ticket = storage.createUpload(scope, collection, stored)
        logger.info(SyncLogEvent.BLOB_REGISTERED, context = { blobContext(stored) }) {
            "the blob was registered and may now be sent"
        }
        metrics.emit(
            SyncMetricEvent.BlobRegistered(scope, collection, stored.size, repeated = registered.repeated),
            logger,
        )
        return BlobRegistration(stored.toInfo(), ticket)
    }

    override fun markUploaded(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobInfo {
        config.require(collection)
        val logger = logger.forCollection(scope, collection)
        val (collectionRowId, stored) = require(scope, collection, blobId)
        if (stored.state == BlobState.READY) {
            logger.debug(SyncLogEvent.BLOB_REPEATED, context = { blobContext(stored) }) {
                "the blob was already accepted, so nothing was written and nobody was told again"
            }
            return stored.toInfo()
        }

        verify(scope, collection, stored, logger)

        val now = clock.now()
        val promoted = transaction(database) { blobs.promote(collectionRowId, blobId, now) }
        val fresh =
            transaction(database) {
                checkNotNull(blobs.find(collectionRowId, blobId)) { "blob row disappeared right after promotion" }
            }
        if (!promoted) {
            // The other path to acceptance got there first. Both callers are right, and exactly one
            // of them is the one that tells the host.
            logger.debug(SyncLogEvent.BLOB_REPEATED, context = { blobContext(fresh) }) {
                "another path accepted this blob first, so this call wrote nothing"
            }
            return fresh.toInfo()
        }

        logger.info(SyncLogEvent.BLOB_ACCEPTED, context = { blobContext(fresh) }) {
            "the blob's bytes were verified and it can now be fetched"
        }
        metrics.emit(
            SyncMetricEvent.BlobAccepted(scope, collection, fresh.size, transferred = now - fresh.createdAt),
            logger,
        )
        announce(scope, collection, fresh, logger)
        return fresh.toInfo()
    }

    override fun download(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): BlobDownload {
        config.require(collection)
        val (_, stored) = require(scope, collection, blobId)
        // Not a failure and not reported as one: an attachment whose record reached this device
        // ahead of its bytes is the ordinary result of the default binding, and a client answers it
        // by waiting rather than by giving up.
        if (stored.state != BlobState.READY) {
            return BlobDownload(stored.toInfo(), download = null)
        }
        return BlobDownload(stored.toInfo(), storage.createDownload(scope, collection, stored))
    }

    /**
     * Outcome of the transaction that registers a blob.
     *
     * @property blob Blob as it stands after the registration.
     * @property repeated Whether the identifier was already known, which is what a device that
     *   crashed part-way through an earlier attempt produces.
     */
    private data class Registration(
        val blob: StoredBlob,
        val repeated: Boolean,
    )

    /**
     * Tells the host that a blob became usable, once, after the fact is durable.
     *
     * Everything a host does with an uploaded file hangs here, and a failure is logged and dropped
     * for the same reason a commit listener's is: the bytes are in storage and the blob is usable,
     * and no thumbnail is worth undoing that. Nothing asks again, which is why the failure is worth
     * a line — a missing derivative is otherwise indistinguishable from one nobody wanted.
     *
     * @param scope Scope the blob belongs to.
     * @param collection Collection the blob belongs to.
     * @param blob Blob that became usable.
     * @param logger Logger already bound to this collection.
     */
    private fun announce(
        scope: ScopeId,
        collection: CollectionId,
        blob: StoredBlob,
        logger: SyncLogger,
    ) {
        listeners.forEach { listener ->
            runCatching { listener.onBlobReady(scope, collection, blob) }
                .onFailure { failure ->
                    logger.error(SyncLogEvent.BLOB_LISTENER_FAILED, failure, context = { blobContext(blob) }) {
                        "a blob listener threw; the blob stays usable and whatever it meant to do was not done"
                    }
                }
        }
    }

    /**
     * Asks the host what it holds, and refuses anything that is not what was declared.
     *
     * The client's word is never enough on its own — a device that wrote the object and died before
     * saying so, and one that says so without writing, are indistinguishable from here.
     *
     * A checksum the storage does not report is not a disagreement. It means the storage cannot
     * answer, which is true of plenty of them, and the size comparison still applies.
     *
     * @param scope Scope the blob belongs to.
     * @param collection Collection the blob belongs to.
     * @param stored Blob whose object is being checked.
     * @param logger Logger already bound to this collection.
     * @throws BlobNotStoredException When the object is missing or disagrees.
     */
    private fun verify(
        scope: ScopeId,
        collection: CollectionId,
        stored: StoredBlob,
        logger: SyncLogger,
    ) {
        val reason =
            when (val verification = storage.verify(scope, collection, stored)) {
                BlobVerification.Absent -> {
                    BlobRefusalReason.ABSENT to "the storage holds no object for it"
                }

                is BlobVerification.Stored -> {
                    when {
                        verification.size != stored.size -> {
                            BlobRefusalReason.MISMATCHED to
                                "the stored object is ${verification.size} octets, not ${stored.size}"
                        }

                        stored.checksum != null &&
                            verification.checksum != null &&
                            stored.checksum != verification.checksum -> {
                            BlobRefusalReason.MISMATCHED to "the stored object's checksum is not the declared one"
                        }

                        else -> {
                            return
                        }
                    }
                }
            }
        logger.warn(SyncLogEvent.BLOB_REFUSED, context = { blobContext(stored) + ("reason" to reason.second) }) {
            "the blob's bytes were claimed to be in storage and were refused"
        }
        metrics.emit(SyncMetricEvent.BlobRefused(scope, collection, reason.first), logger)
        throw BlobNotStoredException(collection, stored.blobId, reason.second)
    }

    /**
     * Reads a blob that has to exist.
     *
     * @param scope Scope the collection belongs to.
     * @param collection Collection the blob belongs to.
     * @param blobId Blob to read.
     * @return Row identifier of the collection, and the blob.
     * @throws UnknownBlobException When the collection or the blob is unknown here.
     */
    private fun require(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): Pair<Uuid, StoredBlob> {
        val found =
            transaction(database) {
                val row = collections.find(scope, collection) ?: return@transaction null
                blobs.find(row.id, blobId)?.let { row.id to it }
            }
        return found ?: throw UnknownBlobException(collection, blobId)
    }

    /**
     * Whether a stored blob is the same file its declaration now claims.
     *
     * A checksum only takes part when both sides have one: a client that declared none the first
     * time and one that declares none now are both saying nothing, not saying something different.
     *
     * @param descriptor Declaration to compare against.
     * @return Whether the two describe the same bytes as far as either can tell.
     */
    private fun StoredBlob.declares(descriptor: BlobDescriptor): Boolean =
        size == descriptor.size &&
            contentType == descriptor.contentType &&
            (checksum == null || descriptor.checksum == null || checksum == descriptor.checksum)

    /**
     * Describes a blob for a log record.
     *
     * Carries no storage key and no ticket. A key names an object in the host's bucket and a ticket
     * is a bearer capability; server logs are shipped to aggregators, kept for months and read by
     * people who were never granted the scope.
     *
     * @param blob Blob to describe.
     * @return Context map for the record.
     */
    private fun blobContext(blob: StoredBlob): Map<String, String> =
        mapOf(
            "blob" to blob.blobId.value.toString(),
            "size" to blob.size.toString(),
            "state" to blob.state.name,
        )
}
