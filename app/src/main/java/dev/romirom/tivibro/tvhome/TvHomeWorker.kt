package dev.romirom.tivibro.tvhome

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Publishes the saved [TvHome.Snapshot] again every [TvHome.REFRESH], so the home screen's «on now» follows the
 *  clock while the app is closed. Reads nothing but that file: no guide, no network beyond channel logos. */
class TvHomeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        TvHome.publish(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            if (!TvHome.supported) return
            runCatching {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    "tvhome", ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<TvHomeWorker>(TvHome.REFRESH, TimeUnit.MILLISECONDS).build(),
                )
            }
        }
    }
}
