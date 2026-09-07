package dev.voir.reflector.sample.ledger

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Wire shape of a wallet.
 *
 * The document is deliberately separate from the [Wallet] row: the row is what the application's
 * queries need, the document is what the server merges by key. They agree today and will not
 * forever — a column renamed for the user interface must not silently become a different field on
 * the server.
 *
 * @property title Name the user gave the wallet.
 * @property currency ISO-4217 code of the wallet's currency.
 */
@Serializable
data class WalletDocument(
    val title: String,
    val currency: String,
)

/**
 * Wire shape of a movement of money.
 *
 * @property walletId Wallet the movement belongs to.
 * @property amountMinor Amount in minor currency units.
 * @property comment Note the user left. An explicit `null` clears it on the server: the protocol's
 *   format writes nulls out, and an omitted key would instead mean "leave whatever is stored".
 */
@Serializable
data class TransactionDocument(
    val walletId: Uuid,
    val amountMinor: Long,
    val comment: String?,
)
