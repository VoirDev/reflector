package dev.voir.reflector.sample.ledger

import dev.voir.reflector.sync.core.adapter.CollectionAdapter
import dev.voir.reflector.sync.core.adapter.RemoteOp
import dev.voir.reflector.sync.core.adapter.SchemaFingerprint
import dev.voir.reflector.sync.core.adapter.SyncRejection
import dev.voir.reflector.sync.core.blob.BlobRef
import dev.voir.reflector.sync.core.conflict.Conflict
import dev.voir.reflector.sync.core.conflict.Resolution
import dev.voir.reflector.sync.protocol.BlobId
import dev.voir.reflector.sync.protocol.EntityId
import dev.voir.reflector.sync.protocol.EntityType
import dev.voir.reflector.sync.protocol.SyncProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Bridge between the library and the demonstration application's tables.
 *
 * This is the whole integration contract in one class: the library never touches business rows, so
 * everything it needs to read or write them goes through here. Every method runs inside the
 * library's transaction, together with the metadata it belongs to — which is why none of them may
 * start a transaction of their own or wait on anything outside the database.
 *
 * @property dao Access to the application's own rows.
 */
class LedgerAdapter(
    private val dao: LedgerDao,
) : CollectionAdapter {
    // Bumped in the same change as a migration of the tables below. The library then rebuilds the
    // collection from a snapshot instead of trusting a cursor whose rows were rewritten under it —
    // which is the same thing requestResync() does, minus having to remember to call it.
    override val schema: SchemaFingerprint = SchemaFingerprint("ledger-v1")

    /**
     * Says which files a document points at, which is the whole of this application's file support.
     *
     * The binding is the editorial decision, and here it is the default one. A wallet with a name
     * and a currency is a useful wallet: holding its creation back until the photograph had finished
     * uploading would mean a user who attached a large picture on a poor connection cannot see their
     * own wallet on their other device. The photograph arrives when it arrives.
     *
     * The fetch policy is left unstated for the same kind of reason, which leaves it to whatever
     * `ledgerSync` was given: a wallet photograph is small and is drawn in a list, so an application
     * showing that list wants them all. A reference to something large — the original of a scan, a
     * video — would say [dev.voir.reflector.sync.core.blob.BlobFetch.ON_DEMAND] here and be fetched
     * when a screen opens it.
     */
    override fun blobs(
        entityType: EntityType,
        id: EntityId,
        document: JsonObject,
    ): Set<BlobRef> =
        when (entityType) {
            WALLET -> {
                decode(WalletDocument.serializer(), document)
                    .photoBlobId
                    ?.let { setOf(BlobRef(BlobId(it))) }
                    .orEmpty()
            }

            else -> {
                emptySet()
            }
        }

    override suspend fun snapshot(
        entityType: EntityType,
        id: EntityId,
    ): JsonObject? =
        when (entityType) {
            WALLET -> {
                dao.wallet(id.value)?.let { wallet ->
                    encode(
                        WalletDocument.serializer(),
                        WalletDocument(wallet.title, wallet.currency, wallet.photoBlobId),
                    )
                }
            }

            TRANSACTION -> {
                dao.transaction(id.value)?.let { transaction ->
                    encode(
                        TransactionDocument.serializer(),
                        TransactionDocument(
                            walletId = transaction.walletId,
                            amountMinor = transaction.amountMinor,
                            comment = transaction.comment,
                        ),
                    )
                }
            }

            else -> {
                null
            }
        }

    override suspend fun applyRemote(ops: List<RemoteOp>) {
        for (op in ops) {
            when (op) {
                is RemoteOp.Upsert -> upsert(op)
                is RemoteOp.Delete -> delete(op)
            }
        }
    }

    override suspend fun onRejected(
        entityType: EntityType?,
        id: EntityId?,
        rejection: SyncRejection,
    ) {
        // A real application would surface this: the queue of the collection is blocked until the
        // data changes, and only the user can decide what the corrected data should be.
    }

    override suspend fun resolve(conflict: Conflict): Resolution? =
        when (conflict.entityType) {
            // A movement of money is a recorded fact rather than an opinion: if the server has a
            // different version of it, this device is the one that is behind.
            TRANSACTION -> Resolution.TakeServer

            // A wallet's name and currency are the user's choice, and choosing for them silently is
            // exactly what an offline-first application must not do.
            else -> null
        }

    private suspend fun upsert(op: RemoteOp.Upsert) {
        when (op.entityType) {
            WALLET -> {
                val document = decode(WalletDocument.serializer(), op.data)
                dao.upsertWallet(Wallet(op.id.value, document.title, document.currency, document.photoBlobId))
            }

            TRANSACTION -> {
                val document = decode(TransactionDocument.serializer(), op.data)
                dao.upsertTransaction(
                    LedgerTransaction(
                        id = op.id.value,
                        walletId = document.walletId,
                        amountMinor = document.amountMinor,
                        comment = document.comment,
                    ),
                )
            }

            else -> {
                error(unknownType(op.entityType))
            }
        }
    }

    private suspend fun delete(op: RemoteOp.Delete) {
        when (op.entityType) {
            WALLET -> dao.deleteWallet(op.id.value)
            TRANSACTION -> dao.deleteTransaction(op.id.value)
            else -> error(unknownType(op.entityType))
        }
    }

    /**
     * An entity type this build does not know is a hard failure rather than a skipped row.
     *
     * The server would not send it unless it is registered for the collection, so meeting one means
     * this client is older than the data. Dropping it silently would leave a device that looks
     * synchronised while missing rows nobody ever notices.
     */
    private fun unknownType(entityType: EntityType): String =
        "entity type '${entityType.value}' is unknown to this version of the application"

    private fun <T> encode(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): JsonObject = SyncProtocolJson.format.encodeToJsonElement(serializer, value).jsonObject

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        data: JsonObject,
    ): T = SyncProtocolJson.format.decodeFromJsonElement(serializer, data)

    companion object {
        /** Type of a wallet, as registered on the server. */
        val WALLET: EntityType = EntityType("wallet")

        /** Type of a movement of money, as registered on the server. */
        val TRANSACTION: EntityType = EntityType("transaction")
    }
}
