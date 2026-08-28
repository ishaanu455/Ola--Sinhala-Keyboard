package com.ola.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var layoutEnglish: Boolean
        get() = prefs.getBoolean("layout_english", true)
        set(value) = prefs.edit().putBoolean("layout_english", value).apply()

    var layoutWijesekara: Boolean
        get() = prefs.getBoolean("layout_wijesekara", true)
        set(value) = prefs.edit().putBoolean("layout_wijesekara", value).apply()

    var layoutSinglish: Boolean
        get() = prefs.getBoolean("layout_singlish", true)
        set(value) = prefs.edit().putBoolean("layout_singlish", value).apply()

    var automaticTheme: Boolean
        get() = prefs.getBoolean("automatic_theme", false)
        set(value) = prefs.edit().putBoolean("automatic_theme", value).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", false)
        set(value) = prefs.edit().putBoolean("dark_theme", value).apply()

    var keyBorders: Boolean
        get() = prefs.getBoolean("key_borders", true)
        set(value) = prefs.edit().putBoolean("key_borders", value).apply()

    var colorTheme: String
        get() = prefs.getString("color_theme", "ola") ?: "ola"
        set(value) = prefs.edit().putString("color_theme", value).apply()

    var sinhalaKeyLabels: Boolean
        get() = prefs.getBoolean("sinhala_key_labels", true)
        set(value) = prefs.edit().putBoolean("sinhala_key_labels", value).apply()

    var swipeToErase: Boolean
        get() = prefs.getBoolean("swipe_to_erase", true)
        set(value) = prefs.edit().putBoolean("swipe_to_erase", value).apply()

    var swipeToMoveCursor: Boolean
        get() = prefs.getBoolean("swipe_to_move_cursor", true)
        set(value) = prefs.edit().putBoolean("swipe_to_move_cursor", value).apply()

    var heightPercentage: Int
        get() = prefs.getInt("height_percentage", 100)
        set(value) = prefs.edit().putInt("height_percentage", value).apply()

    var textSize: Int
        // Kept in sync with SettingsScreen.kt's rememberIntPreference(context, "text_size", 28)
        // default - this value is also what governs emoji glyph size (EmojiAdapter), so a
        // mismatched default here made the keyboard's actual first-run size differ from what
        // the Settings slider showed until the user touched it.
        get() = prefs.getInt("text_size", 28)
        set(value) = prefs.edit().putInt("text_size", value).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(value) = prefs.edit().putBoolean("vibration", value).apply()

    var showRecentEmojiRow: Boolean
        get() = prefs.getBoolean("show_recent_emoji_row", false)
        set(value) = prefs.edit().putBoolean("show_recent_emoji_row", value).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean("show_number_row", true)
        set(value) = prefs.edit().putBoolean("show_number_row", value).apply()

    var emojiStyle: EmojiStyle
        get() = EmojiStyle.fromId(prefs.getString("emoji_style", EmojiStyle.SYSTEM.id))
        set(value) = prefs.edit().putString("emoji_style", value.id).apply()

    /** Which bundled emoji font pack (see [BundledEmojiFonts]) is selected for
     *  EmojiStyle.BUNDLED - stores the .ttf filename under assets/fonts/, e.g.
     *  "Bubble Style.ttf". Null means "none picked yet"; callers fall back to the
     *  first available pack in that case (see [BundledEmojiFonts.loadSelectedTypeface]). */
    var bundledEmojiFontFile: String?
        get() = prefs.getString("bundled_emoji_font_file", null)
        set(value) = prefs.edit().putString("bundled_emoji_font_file", value).apply()

    /** Whether the Twemoji image pack has already been fully downloaded/cached. */
    var twemojiDownloaded: Boolean
        get() = prefs.getBoolean("twemoji_downloaded", false)
        set(value) = prefs.edit().putBoolean("twemoji_downloaded", value).apply()

    /** Whether the clipboard manager (auto-capture + panel + icon) is enabled. */
    var clipboardEnabled: Boolean
        get() = prefs.getBoolean("clipboard_enabled", true)
        set(value) = prefs.edit().putBoolean("clipboard_enabled", value).apply()

    /** The "fancy text" style currently applied to freshly-typed Latin text (see
     *  FontStyleData). Persisted so it survives the keyboard closing/reopening,
     *  same as emojiStyle above. */
    var fontStyle: FontStyle
        get() = FontStyle.fromId(prefs.getString("font_style", FontStyle.NONE.id))
        set(value) = prefs.edit().putString("font_style", value.id).apply()

    // --- App update check (see UpdateChecker.kt) ---
    // Kept as flat SharedPreferences values (not a data class) so both the IME's
    // KeyboardView (read-only, just needs the boolean for the red dot) and the
    // Activity side (UpdateChecker/UpdateScreen, full read+write) can access them
    // through the same lightweight Prefs wrapper without sharing a model class
    // across the service/activity boundary.

    /** Whether the last check found a newer release than [BuildConfig.VERSION_NAME]. */
    var updateAvailable: Boolean
        get() = prefs.getBoolean("update_available", false)
        set(value) = prefs.edit().putBoolean("update_available", value).apply()

    /** Latest release tag/version name found on GitHub, e.g. "2.4". */
    var latestVersion: String
        get() = prefs.getString("latest_version", "") ?: ""
        set(value) = prefs.edit().putString("latest_version", value).apply()

    /** Direct download URL of the latest release's APK asset. */
    var latestApkUrl: String
        get() = prefs.getString("latest_apk_url", "") ?: ""
        set(value) = prefs.edit().putString("latest_apk_url", value).apply()

    /** Release notes body for the latest version, shown in the "What's new" card. */
    var latestChangelog: String
        get() = prefs.getString("latest_changelog", "") ?: ""
        set(value) = prefs.edit().putString("latest_changelog", value).apply()

    /** Timestamp of the last successful update check, used to throttle to once/24h. */
    var lastUpdateCheckMillis: Long
        get() = prefs.getLong("last_update_check_millis", 0L)
        set(value) = prefs.edit().putLong("last_update_check_millis", value).apply()

    fun getKeyboardLayout(): KeyboardLayout {
        return when {
            layoutWijesekara -> KeyboardLayout.WIJESEKARA
            layoutSinglish -> KeyboardLayout.SINGLISH
            else -> KeyboardLayout.ENGLISH
        }
    }

    companion object {
        fun getRowHeight(context: Context): Int {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getInt("height_percentage", 100)
        }

        fun getDarkTheme(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("automatic_theme", false)) {
                val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
            return prefs.getBoolean("dark_theme", false)
        }

        fun getAutomaticTheme(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("automatic_theme", false)
        }

        fun getKeyBorders(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("key_borders", true)
        }

        fun getColorTheme(context: Context): String {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getString("color_theme", "ola") ?: "ola"
        }

        fun getSwipeToErase(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("swipe_to_erase", true)
        }

        fun getSwipeToMoveCursor(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("swipe_to_move_cursor", true)
        }

        fun getTextSize(context: Context): Int {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getInt("text_size", 28)
        }

        fun getKeyboardLayout(context: Context): KeyboardLayout {
            // Prefer the first enabled layout to maintain consistent cycling order
            val enabled = getEnabledLayouts(context)
            return enabled.firstOrNull() ?: KeyboardLayout.ENGLISH
        }

        // Persisted selected layout helpers
        fun getSelectedLayout(context: Context): KeyboardLayout {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val selected = prefs.getString("selected_layout", null)
            val enabled = getEnabledLayouts(context)
            if (selected != null) {
                try {
                    val layout = KeyboardLayout.valueOf(selected)
                    if (enabled.contains(layout)) return layout
                } catch (t: Throwable) {
                    // ignore and fall back
                }
            }
            return enabled.firstOrNull() ?: KeyboardLayout.ENGLISH
        }

        fun setSelectedLayout(context: Context, layout: KeyboardLayout) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("selected_layout", layout.name).apply()
        }

        fun getVibration(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("vibration", true)
        }

        fun getShowRecentEmojiRow(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("show_recent_emoji_row", false)
        }

        fun getShowNumberRow(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("show_number_row", true)
        }

        fun getEmojiStyle(context: Context): EmojiStyle {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return EmojiStyle.fromId(prefs.getString("emoji_style", EmojiStyle.SYSTEM.id))
        }

        fun getClipboardEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("clipboard_enabled", true)
        }

        /** Read-only helper for KeyboardView (IME side) - just needs the badge flag
         *  to decide whether to show the red dot on the settings icon, doesn't need
         *  a full Prefs instance held on the service. */
        fun getUpdateAvailable(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("update_available", false)
        }

        /** Whether the suggestion bar (the row of word predictions above the keys)
         *  is shown while typing. Purely a display toggle - turning it off does NOT
         *  stop words from being learned in the background, it only hides the bar
         *  that shows suggestions. */
        fun getShowSuggestionBar(context: Context): Boolean {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("show_suggestion_bar", true)
        }

        fun getFontStyle(context: Context): FontStyle {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            return FontStyle.fromId(prefs.getString("font_style", FontStyle.NONE.id))
        }

        fun setFontStyle(context: Context, style: FontStyle) {
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit().putString("font_style", style.id).apply()
        }

        // New helper: return the list of enabled layouts in priority order
        fun getEnabledLayouts(context: Context): List<KeyboardLayout> {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val enabled = mutableListOf<KeyboardLayout>()
            if (prefs.getBoolean("layout_english", true)) enabled.add(KeyboardLayout.ENGLISH)
            if (prefs.getBoolean("layout_wijesekara", true)) enabled.add(KeyboardLayout.WIJESEKARA)
            if (prefs.getBoolean("layout_singlish", true)) enabled.add(KeyboardLayout.SINGLISH)
            // Ensure at least English is available
            if (enabled.isEmpty()) enabled.add(KeyboardLayout.ENGLISH)
            return enabled
        }
    }
}
