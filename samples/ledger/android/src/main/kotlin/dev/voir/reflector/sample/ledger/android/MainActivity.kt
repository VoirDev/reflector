package dev.voir.reflector.sample.ledger.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.voir.reflector.sample.ledger.LedgerAdapter
import dev.voir.reflector.sample.ledger.Wallet
import dev.voir.reflector.sync.core.CollectionSyncState
import dev.voir.reflector.sync.protocol.EntityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * As much user interface as it takes to see the library working, and no more.
 *
 * Two things are worth reading here rather than the layout: a write is a single `mutate` that
 * touches the application's own table and says which entity it touched, and the state the screen
 * shows is the collection's own — the application never tracks synchronisation itself.
 */
class MainActivity : Activity() {
    private val ui = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private lateinit var status: TextView
    private lateinit var wallets: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // From target SDK 35 the window is edge to edge, so a content view laid out from y = 0 sits
        // underneath the status bar and the title. Dropping the title and letting the root take the
        // system insets as padding is the whole of what this sample needs from that.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        val application = application as LedgerApplication

        status = TextView(this)
        wallets = TextView(this)
        val add =
            Button(this).apply {
                text = "Add a wallet"
                setOnClickListener { addWallet() }
            }
        val syncNow =
            Button(this).apply {
                text = "Synchronise now"
                setOnClickListener { ui.launch { application.collection.requestSync() } }
            }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(PADDING, PADDING, PADDING, PADDING)
                fitsSystemWindows = true
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                addView(status)
                addView(add)
                addView(syncNow)
                addView(wallets)
            },
        )

        ui.launch {
            application.collection.state.collectLatest { status.text = it.describe() }
        }
        ui.launch {
            application.database.ledgerDao().wallets().collectLatest { list ->
                wallets.text = list.joinToString("\n") { "${it.title} (${it.currency})" }
            }
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    /**
     * Adds a wallet the way the guide describes: the application's own write, and a mark saying
     * which entity it was, in one transaction the library turns into one push group.
     */
    private fun addWallet() {
        val application = application as LedgerApplication
        val id = EntityId(Uuid.random())
        ui.launch {
            application.collection.mutate {
                application.database.ledgerDao().upsertWallet(
                    Wallet(id.value, "Wallet ${id.value.toString().take(4)}", "EUR"),
                )
                markUpserted(LedgerAdapter.WALLET, id)
            }
        }
    }

    private fun CollectionSyncState.describe(): String =
        "phase=$phase pending=$pendingCount conflicts=$conflictCount" +
            (lastFailure?.let { "\nlast failure: $it" } ?: "")

    private companion object {
        const val PADDING = 32
    }
}
