package com.ola.keyboard

import android.content.Context
import android.graphics.Typeface

/**
 * Scans the app's own bundled assets/fonts/ folder for .ttf emoji font packs and lets the
 * user pick one from a radio list in Settings (EmojiStyle.BUNDLED) - fully offline, nothing
 * downloaded and no file picker needed, unlike EmojiStyle.TWEMOJI / CUSTOM.
 *
 * Naming rule: whatever the .ttf file is named (minus the extension) IS the display name
 * shown to the user, exactly as-is - so to add/rename a pack, just drop/rename the file in
 * assets/fonts/ and nothing else needs to change. This only works because Android's assets/
 * folder (unlike res/font/) allows spaces, capital letters, and any other characters in a
 * filename - res/font/ resource names are restricted to lowercase letters, digits, and
 * underscores, so a "Bubble Style.ttf" could never live there under that same name.
 */
object BundledEmojiFonts {

    private const val FONTS_DIR = "fonts"

    /** One bundled emoji font pack: its asset filename and the display name shown in the
     *  radio list (the filename with ".ttf" stripped, otherwise untouched). */
    data class BundledFont(val fileName: String, val displayName: String) {
        val assetPath: String get() = "$FONTS_DIR/$fileName"
    }

    private var cachedList: List<BundledFont>? = null
    private val cachedTypefaces = mutableMapOf<String, Typeface?>()

    /** Lists every .ttf pack bundled under assets/fonts/, sorted by display name.
     *  Cached after the first call for the app process's lifetime - the list is baked
     *  into the APK at build time, so it can never change while the app is running. */
    fun list(context: Context): List<BundledFont> {
        cachedList?.let { return it }
        val files = try {
            context.assets.list(FONTS_DIR)?.filter { it.endsWith(".ttf", ignoreCase = true) } ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        return files
            .map { BundledFont(fileName = it, displayName = it.removeSuffix(".ttf").removeSuffix(".TTF")) }
            .sortedBy { it.displayName }
            .also { cachedList = it }
    }

    /** Loads (and caches) the Typeface for [fileName]. Returns null if the file is missing
     *  or Android can't parse it, so callers can fall back to the default typeface instead
     *  of crashing - same defensive pattern as [CustomFontManager.loadTypeface]. */
    fun loadTypeface(context: Context, fileName: String): Typeface? {
        if (cachedTypefaces.containsKey(fileName)) return cachedTypefaces[fileName]
        val typeface = try {
            Typeface.createFromAsset(context.assets, "$FONTS_DIR/$fileName")
        } catch (t: Throwable) {
            null
        }
        cachedTypefaces[fileName] = typeface
        return typeface
    }

    /** Convenience: loads the Typeface for whichever pack is currently selected in
     *  [Prefs.bundledEmojiFontFile], falling back to the first available pack if the saved
     *  one is missing (e.g. removed in an app update), or null if none are bundled at all. */
    fun loadSelectedTypeface(context: Context): Typeface? {
        val packs = list(context)
        if (packs.isEmpty()) return null
        val prefs = Prefs(context)
        val selected = prefs.bundledEmojiFontFile
        val pack = packs.find { it.fileName == selected } ?: packs.first()
        return loadTypeface(context, pack.fileName)
    }
}
