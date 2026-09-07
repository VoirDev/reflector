package dev.voir.reflector.sample.ledger.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sample.ledger.LEDGER
import dev.voir.reflector.sample.ledger.LedgerDatabase
import dev.voir.reflector.sample.ledger.ledgerSync
import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.trigger.ManualTriggerSource
import dev.voir.reflector.sync.core.trigger.PeriodicTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTrigger
import dev.voir.reflector.sync.network.KtorSyncTransport
import dev.voir.reflector.sync.network.syncHttpClient
import dev.voir.reflector.sync.protocol.ScopeId
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything the application owns for synchronisation, assembled once for the process.
 *
 * The library is deliberately given things the application already has — a database, a scope, a way
 * to get a token — and nothing else. What cannot be written in the shared module is exactly what is
 * here: the database file, the HTTP engine, and the platform events that mean "now would be a good
 * moment".
 */
class LedgerApplication : Application() {
    /** Scope of the signed-in user. The reference host treats the token as the scope identifier. */
    private val scopeId = ScopeId("user-1")

    /**
     * Scope the synchronisation workers run in.
     *
     * It belongs to the application, so stopping synchronisation is cancelling something it already
     * owns. A process-wide scope is right here because the engine outlives every screen.
     */
    private val workers = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Reasons to synchronise that this application produces; the platform code below fires them. */
    val triggers: ManualTriggerSource = ManualTriggerSource()

    /** The collection the user interface reads and writes. */
    lateinit var collection: CollectionHandle
        private set

    /** The application's own rows, which the library never touches directly. */
    lateinit var database: LedgerDatabase
        private set

    override fun onCreate() {
        super.onCreate()

        database =
            Room
                .databaseBuilder<LedgerDatabase>(
                    context = applicationContext,
                    name = applicationContext.getDatabasePath(DATABASE_NAME).absolutePath,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        val transport =
            KtorSyncTransport(
                client = syncHttpClient(OkHttp.create()),
                baseUrl = HOST,
                tokens =
                    object : TokenProvider {
                        // A real application asks its session layer. This host accepts the scope
                        // identifier as the token, which is what makes it a reference and not a
                        // model of anybody's authentication.
                        override suspend fun token(): String = scopeId.value

                        override suspend fun refresh(): Boolean = false
                    },
            )

        collection =
            ledgerSync(
                database = database,
                transport = transport,
                coroutineScope = workers,
                triggerSources = listOf(triggers, PeriodicTriggerSource()),
            ).scope(scopeId)
                .collection(LEDGER)

        observeForeground()
        observeConnectivity()
        SyncWorker.schedule(this)
    }

    /**
     * Fires [SyncTrigger.FOREGROUND] when the application comes back to the user.
     *
     * Registered on the process rather than on a screen: the question the trigger answers is "is
     * anybody looking", and that outlives any one activity.
     */
    private fun observeForeground() {
        registerActivityLifecycleCallbacks(
            object : SimpleActivityLifecycleCallbacks() {
                private var started = 0

                override fun onActivityStarted(activity: android.app.Activity) {
                    if (started++ == 0) {
                        fire(SyncTrigger.FOREGROUND)
                    }
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    started--
                }
            },
        )
    }

    /**
     * Fires [SyncTrigger.NETWORK] when a network becomes usable again.
     *
     * `fire` never suspends and never fails, which is the property that makes it safe to call from
     * a framework callback: being a little late is better than holding one up.
     */
    private fun observeConnectivity() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    fire(SyncTrigger.NETWORK)
                }
            },
        )
    }

    /**
     * Fires a trigger and says so.
     *
     * A demonstration of platform triggers that gives no sign of firing is not much of one: this is
     * what makes `adb logcat -s Ledger` show the integration working.
     *
     * @param trigger Reason to synchronise.
     */
    fun fire(trigger: SyncTrigger) {
        Log.i(TAG, "trigger: $trigger")
        triggers.fire(trigger)
    }

    private companion object {
        /** Tag the sample logs its triggers under. */
        const val TAG = "Ledger"

        /**
         * The host as seen from the emulator, where `10.0.2.2` is the development machine.
         *
         * On a device this is whatever the backend's address is; nothing in the library depends on
         * the difference.
         */
        const val HOST = "http://10.0.2.2:8080"

        const val DATABASE_NAME = "ledger.db"
    }
}
