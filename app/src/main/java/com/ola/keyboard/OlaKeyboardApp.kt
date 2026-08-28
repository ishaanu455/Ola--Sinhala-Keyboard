package com.ola.keyboard

import android.app.Application

/**
 * Only reason this Application subclass exists: schedule the periodic background
 * update check (see [UpdateCheckScheduler]) exactly once per process, from the
 * earliest point that's reliably called - including "process started to run the
 * keyboard IME, user never opened the app itself". SplashActivity.onCreate is NOT
 * enough on its own for that "background dot catches up on its own" goal, since it
 * only runs when the user actually opens the app.
 */
class OlaKeyboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateCheckScheduler.schedule(this)
    }
}
