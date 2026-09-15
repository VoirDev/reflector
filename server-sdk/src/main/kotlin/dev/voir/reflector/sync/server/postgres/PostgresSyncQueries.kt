package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.CollectionId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.PageToken
import dev.voir.reflector.sync.protocol.ScopeId
import dev.voir.reflector.sync.server.BlobPage
import dev.voir.reflector.sync.server.DocumentPage
import dev.voir.reflector.sync.server.StoredBlob
import dev.voir.reflector.sync.server.StoredDocument
import dev.voir.reflector.sync.server.SyncConfig
import dev.voir.reflector.sync.server.SyncQueries
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Read access to stored documents for the host's own purposes.
 *
 * Deleted entities are never returned: a tombstone exists for the protocol, not for the host, and
 * an administrative screen showing rows that no client can see would be confusing rather than
 * complete.
 *
 * @property database Database the module's schema lives in.
 * @property config Registered collections.
 * @property clock Source of timestamps.
 */
internal class PostgresSyncQueries(
    private val database: Database,
    private val config: SyncConfig,
    private val clock: Clock,
) : SyncQueries {
    private val collections = CollectionRows(config, clock)
    private val blobs = BlobRows()

    override fun document(
        scope: ScopeId,
        collection: CollectionId,
        type: EntityType,
        id: EntityId,
    ): StoredDocument? =
        transaction(database) {
            val row = collections.find(scope, collection) ?: return@transaction null
            EntitiesTable
                .selectAll()
                .where {
                    (EntitiesTable.collection eq row.id) and
                        (EntitiesTable.entityType eq type.value) and
                        (EntitiesTable.entityId eq id.value) and
                        (EntitiesTable.isDeleted eq false)
                }.singleOrNull()
                ?.let(::toDocument)
        }

    override fun documents(
        scope: ScopeId,
        collection: CollectionId,
        type: EntityType,
        page: PageToken?,
        limit: Int,
    ): DocumentPage =
        transaction(database) {
            val row =
                collections.find(scope, collection)
                    ?: return@transaction DocumentPage(emptyList(), nextPage = null)
            val token = page?.let(SnapshotCursorToken::decode)
            val capped = limit.coerceIn(1, config.maxChangesPageSize)

            val rows =
                EntitiesTable
                    .selectAll()
                    .where {
                        var predicate =
                            (EntitiesTable.collection eq row.id) and
                                (EntitiesTable.entityType eq type.value) and
                                (EntitiesTable.isDeleted eq false)
                        token?.let { predicate = predicate and (EntitiesTable.entityId greater it.entityId.value) }
                        predicate
                    }.orderBy(EntitiesTable.entityId to SortOrder.ASC)
                    .limit(capped + 1)
                    .toList()

            val hasMore = rows.size > capped
            val documents = rows.take(capped).map(::toDocument)
            DocumentPage(
                documents = documents,
                nextPage =
                    documents
                        .lastOrNull()
                        ?.takeIf { hasMore }
                        ?.let { SnapshotCursorToken(row.id, it.entityType, it.entityId, cursor = 0).encode() },
            )
        }

    override fun blob(
        scope: ScopeId,
        collection: CollectionId,
        blobId: BlobId,
    ): StoredBlob? =
        transaction(database) {
            val row = collections.find(scope, collection) ?: return@transaction null
            blobs.find(row.id, blobId)
        }

    override fun blobs(
        scope: ScopeId,
        collection: CollectionId,
        page: PageToken?,
        limit: Int,
    ): BlobPage =
        transaction(database) {
            val row = collections.find(scope, collection) ?: return@transaction BlobPage(emptyList(), nextPage = null)
            // The token is the last identifier and nothing else. A snapshot's token has to carry a
            // cursor as well, because the pages of one snapshot must all describe the same moment;
            // this read makes no such promise — it is an administrative view of a table that is
            // still being written — so the position is the whole of it.
            val after = page?.let { Uuid.parse(it.value) }
            val capped = limit.coerceIn(1, config.maxChangesPageSize)

            val rows =
                BlobsTable
                    .selectAll()
                    .where {
                        var predicate = BlobsTable.collection eq row.id
                        after?.let { predicate = predicate and (BlobsTable.blobId greater it) }
                        predicate
                    }.orderBy(BlobsTable.blobId to SortOrder.ASC)
                    .limit(capped + 1)
                    .toList()

            val hasMore = rows.size > capped
            val found = rows.take(capped).map { it.toStoredBlob() }
            BlobPage(
                blobs = found,
                nextPage = found.lastOrNull()?.takeIf { hasMore }?.let { PageToken(it.blobId.value.toString()) },
            )
        }

    private fun toDocument(row: org.jetbrains.exposed.v1.core.ResultRow): StoredDocument =
        StoredDocument(
            entityType = EntityType(row[EntitiesTable.entityType]),
            entityId = EntityId(row[EntitiesTable.entityId]),
            version = SyncSequences.version(row[EntitiesTable.version]),
            data = checkNotNull(row[EntitiesTable.data]) { "a live entity must have a document" },
            updatedAt = row[EntitiesTable.updatedAt],
        )
}
