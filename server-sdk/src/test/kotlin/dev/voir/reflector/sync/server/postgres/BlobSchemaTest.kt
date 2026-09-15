package dev.voir.reflector.sync.server.postgres

import dev.voir.reflector.sync.protocol.blob.BlobState
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The blob tables as PostgreSQL actually built them from the migration.
 *
 * Against an in-memory database none of this would mean anything: every assertion here is about a
 * constraint, a cascade or a check that only the real schema has. The tables are read and written
 * through the Exposed declarations on purpose, so that a divergence between them and the migration
 * fails here rather than in production.
 */
class BlobSchemaTest {
    private val database = PostgresFixture.database
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @BeforeTest
    fun clean() {
        // Before rather than after: a class that only tidied up behind itself would still be at the
        // mercy of whatever ran before it and left rows of its own.
        PostgresFixture.reset()
    }

    @Test
    fun `a blob round-trips through the migrated schema`() {
        val collection = newCollection("ledger")
        val blobId = Uuid.random()

        transaction(database) {
            insertBlob(collection, blobId, BlobState.READY, readyAt = now)
        }

        val row =
            transaction(database) {
                BlobsTable
                    .selectAll()
                    .where { (BlobsTable.collection eq collection) and (BlobsTable.blobId eq blobId) }
                    .single()
            }
        assertEquals(BlobState.READY, row[BlobsTable.state])
        assertEquals("image/jpeg", row[BlobsTable.contentType])
        assertEquals(2_418_123L, row[BlobsTable.size])
        assertEquals("sha256:9f86d0", row[BlobsTable.checksum])
        assertEquals(now, row[BlobsTable.readyAt])
        // A blob nothing points at yet, which is every blob at the moment it is registered.
        assertEquals(now, row[BlobsTable.unreferencedSince])
    }

    @Test
    fun `one identifier cannot name two blobs inside a collection`() {
        val collection = newCollection("ledger")
        val blobId = Uuid.random()
        transaction(database) { insertBlob(collection, blobId) }

        assertFailsWith<ExposedSQLException> {
            transaction(database) { insertBlob(collection, blobId) }
        }
    }

    @Test
    fun `the same identifier in another collection is another blob`() {
        val ledger = newCollection("ledger")
        val archive = newCollection("archive")
        val blobId = Uuid.random()

        transaction(database) {
            insertBlob(ledger, blobId)
            insertBlob(archive, blobId)
        }

        // A blob is addressed by collection and identifier together, exactly like an entity, so two
        // collections that happen to mint the same identifier are not in conflict.
        assertEquals(2, transaction(database) { BlobsTable.selectAll().where { BlobsTable.blobId eq blobId }.count() })
    }

    @Test
    fun `a ready blob has to carry the moment it became ready`() {
        val collection = newCollection("ledger")

        // The two halves of one fact; letting them drift is how a promotion sweep starts lying about
        // what it promoted and when.
        assertFailsWith<ExposedSQLException> {
            transaction(database) {
                insertBlob(collection, Uuid.random(), BlobState.READY, readyAt = null)
            }
        }
    }

    @Test
    fun `deleting a blob takes every reference to it`() {
        val collection = newCollection("ledger")
        val blobId = Uuid.random()
        val entityId = Uuid.random()
        transaction(database) {
            insertBlob(collection, blobId)
            insertRef(collection, entityId, blobId)
        }

        transaction(database) {
            BlobsTable.deleteWhere { (BlobsTable.collection eq collection) and (BlobsTable.blobId eq blobId) }
        }

        // A reference cannot outlive the blob it names: there would be nothing for it to point at.
        assertEquals(0, transaction(database) { BlobRefsTable.selectAll().count() })
    }

    @Test
    fun `purging a collection takes its blobs and their references with it`() {
        val collection = newCollection("ledger")
        val blobId = Uuid.random()
        transaction(database) {
            insertBlob(collection, blobId)
            insertRef(collection, Uuid.random(), blobId)
        }

        transaction(database) { CollectionsTable.deleteWhere { CollectionsTable.id eq collection } }

        // An erasure that left the metadata behind would still name what was erased, and the rows
        // would outlive the only thing that could explain them.
        assertEquals(0, transaction(database) { BlobsTable.selectAll().count() })
        assertEquals(0, transaction(database) { BlobRefsTable.selectAll().count() })
    }

    @Test
    fun `a blob nothing has dropped yet carries no moment of becoming garbage`() {
        val collection = newCollection("ledger")
        val blobId = Uuid.random()

        transaction(database) {
            BlobsTable.insert {
                it[BlobsTable.collection] = collection
                it[BlobsTable.blobId] = blobId
                it[state] = BlobState.PENDING
                it[storageKey] = "scope/ledger/$blobId"
                it[contentType] = "image/jpeg"
                it[size] = 1L
                it[createdAt] = now
                it[unreferencedSince] = null
            }
        }

        val row = transaction(database) { BlobsTable.selectAll().where { BlobsTable.blobId eq blobId }.single() }
        assertNull(row[BlobsTable.unreferencedSince])
    }

    private fun newCollection(id: String): Uuid =
        transaction(database) {
            CollectionsTable
                .insertAndGetId {
                    it[scopeId] = "user-1"
                    it[collectionId] = id
                    it[nextSeq] = 1L
                    it[retentionFloorSeq] = 0L
                    it[createdAt] = now
                }.value
        }

    private fun insertBlob(
        collection: Uuid,
        blobId: Uuid,
        state: BlobState = BlobState.PENDING,
        readyAt: Instant? = null,
    ) {
        BlobsTable.insert {
            it[BlobsTable.collection] = collection
            it[BlobsTable.blobId] = blobId
            it[BlobsTable.state] = state
            it[storageKey] = "user-1/ledger/$blobId"
            it[contentType] = "image/jpeg"
            it[size] = 2_418_123L
            it[checksum] = "sha256:9f86d0"
            it[createdAt] = now
            it[BlobsTable.readyAt] = readyAt
            it[unreferencedSince] = now
        }
    }

    private fun insertRef(
        collection: Uuid,
        entityId: Uuid,
        blobId: Uuid,
    ) {
        BlobRefsTable.insert {
            it[BlobRefsTable.collection] = collection
            it[entityType] = "wallet"
            it[BlobRefsTable.entityId] = entityId
            it[BlobRefsTable.blobId] = blobId
        }
    }
}
