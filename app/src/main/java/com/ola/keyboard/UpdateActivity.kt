package com.ola.keyboard

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ola.keyboard.ui.UpdateScreen
import com.ola.keyboard.ui.UpdateScreenState
import com.ola.keyboard.ui.theme.OlaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts [UpdateScreen] and owns everything DownloadManager/FileProvider related.
 * The composable itself stays a pure function of (state, progress) - all the
 * side-effecty download/install logic lives here, same split as the rest of the
 * app's Activities (e.g. ClipsManagerActivity vs ClipsManagerScreen).
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var downloadId: Long = -1L
    private var receiverRegistered = false

    private var screenState by mutableStateOf(UpdateScreenState.IDLE)
    // Named downloadProgress (not "progress") - a plain "progress" Compose property
    // here generates a synthetic setProgress(Int) setter that collides at the JVM
    // signature level with Activity's own legacy setProgress(int) (old window
    // progress-bar API), which fails the build with an "Accidental override" error.
    private var downloadProgress by mutableIntStateOf(0)
    private var errorMessage by mutableStateOf<String?>(null)

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            if (UpdateDownloader.isDownloadSuccessful(this@UpdateActivity, downloadId)) {
                downloadProgress = 100
                proceedToInstall()
            } else {
                screenState = UpdateScreenState.ERROR
                errorMessage = "Download failed. Check your connection and try again."
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whether or not permission was actually granted only matters once the user
        // taps the button again - re-checking here would just re-show the same
        // system screen if they backed out without granting it.
        if (UpdateDownloader.needsInstallPermission(this)) {
            screenState = UpdateScreenState.ERROR
            errorMessage = "Installing needs \"Allow from this source\" turned on for Ola Keyboard."
        } else {
            proceedToInstall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // registerReceiver's export-flag requirement is meant to be a plain SDK_INT
        // check, but some OEM Android forks (seen on Huawei HarmonyOS devices) enforce
        // it inconsistently with AOSP and throw IllegalArgumentException even when a
        // valid flag is passed. This receiver only exists to auto-continue the install
        // the instant a download finishes - losing it just means the user has to tap
        // the notification/file manually, which is a fine fallback. It's NOT worth
        // crashing this whole screen (and kicking the user back to the home screen)
        // over, so any registration failure here is caught and logged instead.
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED
            else 0
            ContextCompat.registerReceiver(
                this,
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                flags
            )
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.e("UpdateActivity", "Failed to register download-complete receiver", t)
        }

        screenState = if (prefs.updateAvailable) UpdateScreenState.IDLE else UpdateScreenState.UP_TO_DATE

        setContent {
            OlaTheme(darkTheme = true) {
                UpdateScreen(
                    currentVersion = BuildConfig.VERSION_NAME,
                    latestVersion = prefs.latestVersion.ifBlank { BuildConfig.VERSION_NAME },
                    changelog = prefs.latestChangelog,
                    state = screenState,
                    progress = downloadProgress,
                    errorMessage = errorMessage,
                    onBackClick = { onBackPressedDispatcher.onBackPressed() },
                    onPrimaryAction = { onPrimaryAction() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!receiverRegistered) return
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered (e.g. onCreate never finished) - safe to ignore.
        }
    }

    private fun onPrimaryAction() {
        errorMessage = null
        when (screenState) {
            UpdateScreenState.ERROR, UpdateScreenState.IDLE -> startDownload()
            UpdateScreenState.INSTALLING -> proceedToInstall()
            else -> Unit
        }
    }

    private fun startDownload() {
        val apkUrl = prefs.latestApkUrl
        if (apkUrl.isBlank()) {
            screenState = UpdateScreenState.ERROR
            errorMessage = "No download link available yet. Pull to refresh from Settings."
            return
        }

        screenState = UpdateScreenState.DOWNLOADING
        downloadProgress = 0
        downloadId = UpdateDownloader.startDownload(this, apkUrl, prefs.latestVersion)

        // DownloadManager doesn't push progress callbacks - poll it while the
        // download is active. Cheap: one Cursor query per tick, cancelled the
        // moment the screen leaves DOWNLOADING state or the Activity is gone.
        lifecycleScope.launch {
            while (screenState == UpdateScreenState.DOWNLOADING) {
                val pct = withContext(Dispatchers.IO) {
                    UpdateDownloader.queryProgress(this@UpdateActivity, downloadId)
                }
                if (pct != null) downloadProgress = pct
                delay(400)
            }
        }
    }

    private fun proceedToInstall() {
        if (UpdateDownloader.needsInstallPermission(this)) {
            screenState = UpdateScreenState.INSTALLING
            installPermissionLauncher.launch(UpdateDownloader.installPermissionIntent(this))
            return
        }
        screenState = UpdateScreenState.INSTALLING
        UpdateDownloader.installApk(this, prefs.latestVersion)
    }
}
