package com.ola.keyboard

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules [UpdateCheckWorker] to run periodically in the background so a new
 * GitHub release gets picked up (and the settings-gear red dot lit up) without the
 * user needing to reopen the app or manually tap "Check for Updates".
 *
 * 6 hours is a compromise: short enough that "how long after I publish a release
 * does the dot show up" is "a few hours, worst case" instead of "whenever the user
 * next opens the app", but long enough (well above WorkManager's 15-minute floor)
 * that it isn't a meaningful battery/data cost - this is one small HTTPS GET, not a
 * sync job. Doze mode / battery optimization can push a real-world run later than
 * exactly 6h; that's expected and fine for a "check for updates" feature.
 */
object UpdateCheckScheduler {

    private const val UNIQUE_WORK_NAME = "periodic_update_check"
    private val REPEAT_INTERVAL = 6L to TimeUnit.HOURS

    /** Call once, e.g. from [OlaKeyboardApp.onCreate]. Cheap and idempotent - KEEP
     *  means an already-scheduled periodic request (same interval) is left alone
     *  rather than restarted (and its progress toward the next run lost) every time
     *  the process is created. */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            REPEAT_INTERVAL.first, REPEAT_INTERVAL.second
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
