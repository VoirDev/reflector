package dev.voir.reflector.sync.server

import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.EntityVersion
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * One entity as the module stores it.
 *
 * @property entityType Type of the entity.
 * @property entityId Identifier of the entity.
 * @property version Version the entity currently has, equal to the sequence of the batch that last
 *   changed it.
 * @property data Stored document. The module treats it as opaque JSON: it stores bodies but models
 *   nothing about them.
 * @property updatedAt When the document was last written.
 * @property blobs Blobs the document currently references, ordered by identifier; empty when it
 *   references none. This is the set the last write that declared one left behind — an upsert that
 *   declared nothing keeps the previous set — because the module never derives references from the
 *   document itself. A host that copies documents elsewhere and later pushes them back has to
 *   declare this set with them: a document re-created without a declaration references nothing,
 *   and the files it names are collected once the retention window passes.
 */
public data class StoredDocument(
    public val entityType: EntityType,
    public val entityId: EntityId,
    public val version: EntityVersion,
    public val data: JsonObject,
    public val updatedAt: Instant,
    public val blobs: List<BlobId> = emptyList(),
)
