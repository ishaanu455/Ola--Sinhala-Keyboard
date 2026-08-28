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
 * Outcome of a single [UpdateChecker.checkForUpdate] call, so callers (the Settings
 * "Check for Updates" row in particular) can tell a genuine "you're up to date" apart
 * from "the check itself failed" - the two used to look identical (both silently
 * fell through to whatever was already cached in [Prefs.updateAvailable]), which is
 * what made a failed check look like it was "stuck" and did nothing.
 */
sealed class UpdateCheckOutcome {
    /** Check completed; [hasUpdate] reflects the (now up to date) result in Prefs. */
    data class Completed(val hasUpdate: Boolean) : UpdateCheckOutcome()
    /** Check was skipped because it isn't due yet (only possible when force = false). */
    object Throttled : UpdateCheckOutcome()
    /** GitHub API returned 403/429 - almost always the unauthenticated 60-req/hour
     *  rate limit, very easy to hit while repeatedly tapping "Check for Updates". */
    object RateLimited : UpdateCheckOutcome()
    /** Any other failure: no connectivity, timeout, malformed response, latest
     *  release missing a tag or .apk asset, etc. */
    data class Failed(val reason: String) : UpdateCheckOutcome()
}

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
     * returns [UpdateCheckOutcome.Throttled] if a check isn't due yet.
     *
     * Never throws - any network/parsing failure is logged and reported back via the
     * returned [UpdateCheckOutcome] instead, so a failed update check never blocks or
     * crashes the screen that triggered it, but also never silently pretends to have
     * succeeded.
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateCheckOutcome {
        val prefs = Prefs(context)

        if (!force) {
            val elapsed = System.currentTimeMillis() - prefs.lastUpdateCheckMillis
            if (elapsed < CHECK_INTERVAL_MILLIS) return UpdateCheckOutcome.Throttled
        }

        return withContext(Dispatchers.IO) {
            try {
                val fetch = fetchLatestRelease()
                when (fetch) {
                    is FetchResult.RateLimited -> return@withContext UpdateCheckOutcome.RateLimited
                    is FetchResult.HttpError -> return@withContext UpdateCheckOutcome.Failed(
                        "GitHub returned ${fetch.code}"
                    )
                    is FetchResult.Ok -> {
                        val json = fetch.json
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
                            return@withContext UpdateCheckOutcome.Failed(
                                "Latest GitHub release has no .apk attached"
                            )
                        }

                        val isNewer = isVersionNewer(tagName, BuildConfig.VERSION_NAME)

                        prefs.lastUpdateCheckMillis = System.currentTimeMillis()
                        prefs.updateAvailable = isNewer
                        if (isNewer) {
                            prefs.latestVersion = tagName
                            prefs.latestApkUrl = apkUrl
                            prefs.latestChangelog = body
                        }
                        UpdateCheckOutcome.Completed(isNewer)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Update check failed", t)
                UpdateCheckOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private sealed class FetchResult {
        data class Ok(val json: JSONObject) : FetchResult()
        object RateLimited : FetchResult()
        data class HttpError(val code: Int) : FetchResult()
    }

    private fun fetchLatestRelease(): FetchResult {
        val connection = URL(RELEASES_API_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            if (code == 403 || code == 429) {
                Log.w(TAG, "GitHub API rate limited ($code)")
                return FetchResult.RateLimited
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned $code")
                return FetchResult.HttpError(code)
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            FetchResult.Ok(JSONObject(body))
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
