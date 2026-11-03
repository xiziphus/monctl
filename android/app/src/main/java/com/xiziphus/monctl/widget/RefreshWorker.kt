package com.xiziphus.monctl.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiziphus.monctl.data.Settings
import com.xiziphus.monctl.net.MonctlClient
import java.util.concurrent.TimeUnit

/**
 * Keeps the widget roughly current while nobody is tapping it: every 15
 * minutes (WorkManager's floor), only with a network. /api/remote never wakes
 * a sleeping display, so this cannot keep the monitor lit.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cfg = Settings.load(applicationContext)
        if (cfg.isConfigured) WidgetStore.publish(applicationContext, MonctlClient.remote(cfg))
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "monctl-widget-refresh"
        private const val ONCE = "monctl-widget-refresh-now"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
