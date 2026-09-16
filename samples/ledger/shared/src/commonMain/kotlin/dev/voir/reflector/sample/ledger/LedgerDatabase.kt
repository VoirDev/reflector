package dev.voir.reflector.sample.ledger

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import dev.voir.reflector.sync.persistence.SyncDatabase
import dev.voir.reflector.sync.persistence.blob.SyncBlobEntity
import dev.voir.reflector.sync.persistence.blob.SyncBlobRefEntity
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
 *
 * That is the part worth looking at rather than skipping past. A release of the library that adds
 * a table obliges this application to raise the `version` below and to carry the migration, which
 * Room derives on its own while the change stays additive; one that alters an existing column
 * obliges it to write that migration by hand, against rows this application owns. The single
 * version here is where that history starts rather than the absence of one.
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
        SyncBlobEntity::class,
        SyncBlobRefEntity::class,
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
