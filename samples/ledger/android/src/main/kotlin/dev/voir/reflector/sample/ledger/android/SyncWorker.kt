package dev.voir.reflector.sample.ledger.android

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.voir.reflector.sync.core.SyncPhase
import dev.voir.reflector.sync.core.trigger.SyncTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The background half of the trigger story: synchronising while nobody is looking.
 *
 * The worker does not synchronise itself — it fires a trigger and waits for the engine to settle.
 * That split is the point. The engine owns when and in what order the work happens, and the
 * platform owns when the process is allowed to be awake at all; a worker that drove the queue
 * itself would have to duplicate the ordering, the backoff and the conflict handling.
 *
 * @param context Application context WorkManager hands to the worker.
 * @param parameters Parameters of this run.
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as LedgerApplication
        application.fire(SyncTrigger.BACKGROUND)

        // Being cut off mid-cycle costs nothing: the inbox and the queue are durable, and the next
        // run continues from where this one stopped. So the wait has a ceiling and no retry.
        val settled =
            withTimeoutOrNull(SETTLE_TIMEOUT.inWholeMilliseconds) {
                application.collection.state.first { it.phase == SyncPhase.LIVE && it.pendingCount == 0 }
            }
        Log.i("Ledger", "background run finished, settled=${settled != null}")
        return if (settled == null) Result.retry() else Result.success()
    }

    companion object {
        /**
         * Asks WorkManager for a periodic run, replacing whatever was scheduled before.
         *
         * The floor under it is fifteen minutes, which is the platform's decision and not the
         * library's — the engine's own periodic trigger is what covers a foreground session.
         *
         * @param context Context to reach WorkManager with.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(PERIOD.inWholeMinutes, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Name that keeps one schedule rather than one per process start. */
        const val NAME: String = "ledger-sync"

        private val PERIOD = 15.minutes
        private val SETTLE_TIMEOUT = 30.seconds
    }
}
