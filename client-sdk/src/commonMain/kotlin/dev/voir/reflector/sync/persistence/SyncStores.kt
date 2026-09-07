package dev.voir.reflector.sync.persistence

import dev.voir.reflector.sync.persistence.collection.CollectionStore
import dev.voir.reflector.sync.persistence.conflict.ConflictStore
import dev.voir.reflector.sync.persistence.group.GroupStore
import dev.voir.reflector.sync.persistence.inbox.InboxStore
import dev.voir.reflector.sync.persistence.meta.MetaStore
import dev.voir.reflector.sync.persistence.record.RecordStore

/**
 * The library's storage, assembled from the application's database.
 *
 * Grouping the stores in one holder keeps the engine's constructors readable and, more importantly,
 * makes it obvious that all of them run against a single database: they are only ever correct when
 * used inside one transaction together.
 *
 * @param database Application's database, carrying the library's tables.
 * @property collections Synchronisation state of collections.
 * @property records Per-entity synchronisation metadata.
 * @property groups Push queue.
 * @property conflicts Conflicts waiting for a decision.
 * @property inbox Downloaded batches waiting to be applied.
 * @property meta Per-scope identity of this installation.
 */
internal class SyncStores(
    database: SyncDatabase,
) {
    public val collections: CollectionStore = CollectionStore(database.syncCollectionDao())
    public val records: RecordStore = RecordStore(database.syncRecordDao())
    public val groups: GroupStore = GroupStore(database.syncGroupDao())
    public val conflicts: ConflictStore = ConflictStore(database.syncConflictDao())
    public val inbox: InboxStore = InboxStore(database.syncInboxDao())
    public val meta: MetaStore = MetaStore(database.syncMetaDao())
}
