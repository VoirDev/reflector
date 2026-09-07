package dev.voir.reflector.sync.persistence

import dev.voir.reflector.sync.persistence.collection.SyncCollectionDao
import dev.voir.reflector.sync.persistence.conflict.SyncConflictDao
import dev.voir.reflector.sync.persistence.group.SyncGroupDao
import dev.voir.reflector.sync.persistence.inbox.SyncInboxDao
import dev.voir.reflector.sync.persistence.meta.SyncMetaDao
import dev.voir.reflector.sync.persistence.record.SyncRecordDao

/**
 * Contract the application's Room database fulfils so that the library can reach its own tables.
 *
 * The synchronisation tables live in the **application's** database rather than in one of the
 * library's own. That is not a convenience: applying an incoming batch writes the application's
 * rows and advances the library's cursor, and those two writes have to be one transaction. Two
 * databases cannot give that, and a crash between them leaves a cursor that has passed data which
 * was never written — a loss nothing later detects, because the log is only ever read forwards.
 *
 * The application declares the library's entities in its own `@Database` and implements this
 * interface; Room generates the accessors:
 *
 * ```kotlin
 * @Database(
 *     entities = [
 *         Wallet::class,
 *         Transaction::class,
 *         SyncCollectionEntity::class,
 *         SyncRecordEntity::class,
 *         SyncGroupEntity::class,
 *         SyncConflictEntity::class,
 *         SyncInboxBatchEntity::class,
 *         SyncInboxOpEntity::class,
 *         SyncMetaEntity::class,
 *     ],
 *     version = 1,
 * )
 * @ConstructedBy(AppDatabaseConstructor::class)
 * abstract class AppDatabase : RoomDatabase(), SyncDatabase
 * ```
 *
 * Every change to the library's schema is therefore a version bump of the application's database
 * and needs a migration on its side. The alternative — a separate database with its own version —
 * would trade that visible cost for a silent correctness problem, which is a bad trade.
 */
public interface SyncDatabase {
    /** Returns access to the synchronisation state of collections. */
    public fun syncCollectionDao(): SyncCollectionDao

    /** Returns access to per-entity synchronisation metadata. */
    public fun syncRecordDao(): SyncRecordDao

    /** Returns access to the push queue. */
    public fun syncGroupDao(): SyncGroupDao

    /** Returns access to conflicts waiting for a decision. */
    public fun syncConflictDao(): SyncConflictDao

    /** Returns access to downloaded batches waiting to be applied. */
    public fun syncInboxDao(): SyncInboxDao

    /** Returns access to the per-scope identity of this installation. */
    public fun syncMetaDao(): SyncMetaDao
}
