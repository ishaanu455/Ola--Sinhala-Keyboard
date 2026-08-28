package com.ola.keyboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer build than the one installed.
 *
 * Deliberately lives outside InputMethodService entirely - this only ever runs from
 * an Activity context (SplashActivity/MainActivity), never from the IME. The keyboard
 * service only ever reads the *result* of the last check via [Prefs.getUpdateAvailable]
 * (a plain SharedPreferences boolean) to decide whether to show the red dot - it never
 * does the network call, JSON parsing, or file I/O itself. That keeps the one thing
 * that must never crash (typing) completely isolated from the one thing that's most
 * likely to fail unpredictably (a network call to a third-party API).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "ishaanu455/Foxkeyboard-Customized"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val CHECK_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)

    /**
     * Hits the GitHub Releases API and updates [Prefs] with the result. Throttled to
     * once per [CHECK_INTERVAL_MILLIS] unless [force] is true (e.g. user taps "Check
     * for updates" manually). Safe to call from onCreate/onResume - does nothing and
     * returns immediately if a check isn't due yet.
     *
     * Never throws - any network/parsing failure is logged and swallowed, since a
     * failed update check should never block or crash the screen that triggered it.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false) {
        val prefs = Prefs(context)

        if (!force) {
            val elapsed = System.currentTimeMillis() - prefs.lastUpdateCheckMillis
            if (elapsed < CHECK_INTERVAL_MILLIS) return
        }

        withContext(Dispatchers.IO) {
            try {
                val json = fetchLatestRelease()
                if (json == null) return@withContext

                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                val body = json.optString("body", "").trim()
                val assets = json.optJSONArray("assets")

                var apkUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (tagName.isEmpty() || apkUrl.isEmpty()) {
                    Log.w(TAG, "Latest release missing a version tag or .apk asset, skipping")
                    return@withContext
                }

                val isNewer = isVersionNewer(tagName, BuildConfig.VERSION_NAME)

                prefs.lastUpdateCheckMillis = System.currentTimeMillis()
                prefs.updateAvailable = isNewer
                if (isNewer) {
                    prefs.latestVersion = tagName
                    prefs.latestApkUrl = apkUrl
                    prefs.latestChangelog = body
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Update check failed", t)
            }
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        val connection = URL(RELEASES_API_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Simple dotted-integer version comparison, e.g. "2.4" > "1.1.116" > "1.1.2". */
    private fun isVersionNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}
