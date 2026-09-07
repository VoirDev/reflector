package dev.voir.reflector.sync

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sync.persistence.SyncDatabase
import dev.voir.reflector.sync.persistence.collection.SyncCollectionEntity
import dev.voir.reflector.sync.persistence.conflict.SyncConflictEntity
import dev.voir.reflector.sync.persistence.group.SyncGroupEntity
import dev.voir.reflector.sync.persistence.inbox.SyncInboxBatchEntity
import dev.voir.reflector.sync.persistence.inbox.SyncInboxOpEntity
import dev.voir.reflector.sync.persistence.meta.SyncMetaEntity
import dev.voir.reflector.sync.persistence.record.SyncRecordEntity
import kotlinx.coroutines.Dispatchers

/**
 * Database the library's own tests run against, holding its tables only.
 *
 * The application's own rows are not needed here: the engine's tests use a fake adapter that keeps
 * entity bodies in memory, so what is exercised is exactly the library's own bookkeeping. Declaring
 * this database is also a check in its own right — the library deliberately ships no `@Database`, so
 * the fact that one can be assembled from its entities alone is part of the contract it offers.
 */
@Database(
    entities = [
        SyncCollectionEntity::class,
        SyncRecordEntity::class,
        SyncGroupEntity::class,
        SyncConflictEntity::class,
        SyncInboxBatchEntity::class,
        SyncInboxOpEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TestSyncDatabase :
    RoomDatabase(),
    SyncDatabase

/**
 * Opens a fresh in-memory database for one test.
 *
 * @return Database that disappears with the test, so that no test can depend on another's leftovers.
 */
fun openTestDatabase(): TestSyncDatabase =
    Room
        .inMemoryDatabaseBuilder<TestSyncDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
