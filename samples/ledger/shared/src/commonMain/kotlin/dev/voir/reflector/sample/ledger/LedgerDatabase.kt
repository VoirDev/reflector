package dev.voir.reflector.sample.ledger

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import dev.voir.reflector.sync.persistence.SyncDatabase
import dev.voir.reflector.sync.persistence.collection.SyncCollectionEntity
import dev.voir.reflector.sync.persistence.conflict.SyncConflictEntity
import dev.voir.reflector.sync.persistence.group.SyncGroupEntity
import dev.voir.reflector.sync.persistence.inbox.SyncInboxBatchEntity
import dev.voir.reflector.sync.persistence.inbox.SyncInboxOpEntity
import dev.voir.reflector.sync.persistence.meta.SyncMetaEntity
import dev.voir.reflector.sync.persistence.record.SyncRecordEntity

/**
 * Database of the demonstration application, holding both its own rows and the library's metadata.
 *
 * The two live together because applying an incoming batch has to write business rows and advance
 * the cursor in one transaction. The cost is visible here: every version of the library's schema is
 * a version of this database, and migrating it is the application's job.
 */
@Database(
    entities = [
        Wallet::class,
        LedgerTransaction::class,
        SyncCollectionEntity::class,
        SyncRecordEntity::class,
        SyncGroupEntity::class,
        SyncConflictEntity::class,
        SyncInboxBatchEntity::class,
        SyncInboxOpEntity::class,
        SyncMetaEntity::class,
    ],
    version = 1,
)
@ConstructedBy(LedgerDatabaseConstructor::class)
abstract class LedgerDatabase :
    RoomDatabase(),
    SyncDatabase {
    /** Returns access to the application's own rows. */
    abstract fun ledgerDao(): LedgerDao
}

/**
 * Platform constructor of [LedgerDatabase], implemented by Room's code generator.
 *
 * A multiplatform database cannot be instantiated reflectively, so Room generates one actual per
 * target and this declaration is what links them together.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LedgerDatabaseConstructor : RoomDatabaseConstructor<LedgerDatabase> {
    override fun initialize(): LedgerDatabase
}
