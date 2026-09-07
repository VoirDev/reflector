package dev.voir.reflector.sample.ledger

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import dev.voir.reflector.sync.persistence.SyncColumnConverters
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** Access to the demonstration application's own rows. */
@Dao
@ColumnTypeConverters(SyncColumnConverters::class)
interface LedgerDao {
    /**
     * Reads one wallet.
     *
     * @param id Wallet to read.
     * @return Stored wallet, or `null` when it does not exist locally.
     */
    @Query("SELECT * FROM wallet WHERE id = :id")
    suspend fun wallet(id: Uuid): Wallet?

    /**
     * Observes every wallet, for the user interface.
     *
     * @return Flow emitting the current wallets and every change to them.
     */
    @Query("SELECT * FROM wallet ORDER BY title")
    fun wallets(): Flow<List<Wallet>>

    /**
     * Inserts or replaces a wallet.
     *
     * @param wallet Wallet to store.
     */
    @Upsert
    suspend fun upsertWallet(wallet: Wallet)

    /**
     * Removes a wallet.
     *
     * @param id Wallet to remove.
     */
    @Query("DELETE FROM wallet WHERE id = :id")
    suspend fun deleteWallet(id: Uuid)

    /**
     * Reads one transaction.
     *
     * @param id Transaction to read.
     * @return Stored transaction, or `null` when it does not exist locally.
     */
    @Query("SELECT * FROM ledger_transaction WHERE id = :id")
    suspend fun transaction(id: Uuid): LedgerTransaction?

    /**
     * Inserts or replaces a transaction.
     *
     * @param transaction Transaction to store.
     */
    @Upsert
    suspend fun upsertTransaction(transaction: LedgerTransaction)

    /**
     * Removes a transaction.
     *
     * @param id Transaction to remove.
     */
    @Query("DELETE FROM ledger_transaction WHERE id = :id")
    suspend fun deleteTransaction(id: Uuid)
}
