package dev.voir.reflector.sync.persistence

import androidx.room3.RoomDatabase
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection

/**
 * [SyncTransactionRunner] over the application's Room database.
 *
 * The transaction is started as `IMMEDIATE` rather than `DEFERRED`: it takes the write lock right
 * away instead of upgrading to it later, so two synchronisation transactions cannot get far enough
 * to collide and have one of them rolled back as a busy loser.
 *
 * @property database Application's database; the library never opens or closes it and never
 *   configures its migrations.
 */
public class RoomSyncTransactionRunner(
    private val database: RoomDatabase,
) : SyncTransactionRunner {
    /**
     * Runs [block] inside a write transaction on the database's writer connection.
     *
     * DAO calls made inside the block reuse the same connection, because Room confines it to the
     * running coroutine — which is also why the block must not move its work to another one.
     *
     * @param block Work to perform inside the transaction.
     * @return Whatever the block returns.
     */
    override suspend fun <R> transaction(block: suspend () -> R): R =
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction { block() }
        }
}
