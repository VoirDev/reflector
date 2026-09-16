package dev.voir.reflector.sample.ledger.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.voir.reflector.sample.ledger.LEDGER
import dev.voir.reflector.sample.ledger.LedgerDatabase
import dev.voir.reflector.sample.ledger.LedgerFiles
import dev.voir.reflector.sample.ledger.ledgerSync
import dev.voir.reflector.sync.core.CollectionHandle
import dev.voir.reflector.sync.core.TokenProvider
import dev.voir.reflector.sync.core.log.AndroidSyncLog
import dev.voir.reflector.sync.core.log.SyncLog
import dev.voir.reflector.sync.core.trigger.ManualTriggerSource
import dev.voir.reflector.sync.core.trigger.PeriodicTriggerSource
import dev.voir.reflector.sync.core.trigger.SyncTrigger
import dev.voir.reflector.sync.network.KtorBlobTransport
import dev.voir.reflector.sync.network.KtorSyncTransport
import dev.voir.reflector.sync.network.SyncHttpTracing
import dev.voir.reflector.sync.network.syncHttpClient
import dev.voir.reflector.sync.protocol.ScopeId
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.files.Path
import java.io.File

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

        // Switched on by the flag the platform already sets, so a release build of this sample gets
        // the discarding sink and pays nothing. Which levels actually reach logcat is then somebody
        // else's decision entirely: `adb shell setprop log.tag.ReflectorSync.push VERBOSE`.
        val log = if (isDebuggable()) AndroidSyncLog() else SyncLog.None

        database =
            Room
                .databaseBuilder<LedgerDatabase>(
                    context = applicationContext,
                    name = applicationContext.getDatabasePath(DATABASE_NAME).absolutePath,
                ).setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        // One client for both transports. The blob transport uses it for the three file endpoints,
        // which carry credentials like any other route, and builds the requests to the host's
        // storage itself — those go to a presigned URL and must carry none.
        val httpClient = syncHttpClient(OkHttp.create(), log, SyncHttpTracing.BASIC)
        val tokens =
            object : TokenProvider {
                // A real application asks its session layer. This host accepts the scope
                // identifier as the token, which is what makes it a reference and not a
                // model of anybody's authentication.
                override suspend fun token(): String = scopeId.value

                override suspend fun refresh(): Boolean = false
            }

        val transport = KtorSyncTransport(httpClient, baseUrl = HOST, tokens = tokens, log = log)

        // Where the bytes of attached photographs live: the application's own private storage, which
        // the library never learns the location of. Under `filesDir` rather than the cache, because
        // a file the system may delete underneath a document that still names it is exactly the
        // failure the library then has to report to the user.
        val files = LedgerFiles(Path(File(filesDir, "ledger-blobs").absolutePath))

        collection =
            ledgerSync(
                database = database,
                transport = transport,
                coroutineScope = workers,
                triggerSources = listOf(triggers, PeriodicTriggerSource()),
                log = log,
                files = files,
                blobTransport = KtorBlobTransport(httpClient, baseUrl = HOST, tokens = tokens, log = log),
            ).scope(scopeId)
                .collection(LEDGER)

        observeForeground()
        observeConnectivity()
        SyncWorker.schedule(this)
    }

    /**
     * Tells whether this build was signed for development.
     *
     * The flag the platform sets from the manifest, rather than `BuildConfig`: it needs no build
     * feature switched on and it is the same question every Android application already asks.
     *
     * @return `true` when the application is debuggable.
     */
    private fun isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

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
