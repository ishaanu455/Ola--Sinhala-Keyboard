package com.ola.keyboard

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs [UpdateChecker.checkForUpdate] on WorkManager's own schedule (see
 * [UpdateCheckScheduler]), independent of the user ever opening the app. This is
 * what lets the keyboard's settings-gear red dot catch up on a freshly-published
 * GitHub release on its own, instead of only refreshing when SplashActivity happens
 * to run (which only happens when the user opens the app) or when the user manually
 * taps "Check for Updates" in Settings.
 *
 * force = true here (not the default throttled path) because WorkManager itself is
 * already the thing controlling how often this runs - letting UpdateChecker's own
 * 24h throttle apply on top would mean the periodic interval no longer means what it
 * says.
 *
 * Always returns Result.success(), even when the check failed (rate limit, no
 * network, etc.) - a background check failing is expected sometimes (e.g. no
 * internet at the time) and isn't worth WorkManager's retry/backoff machinery; the
 * next scheduled run will simply try again.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = UpdateChecker.checkForUpdate(applicationContext, force = true)
        Log.d(TAG, "Background update check finished: $outcome")
        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
    }
}
