package ime.suggest

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer

/**
 * The user's own hand-picked prediction words ("My Prediction" in the Prediction
 * Manager) — added and removed manually, e.g. names, slang, or shortcuts that
 * aren't in the bundled dictionary but the user always wants suggested.
 *
 * Deliberately kept separate from [UserWordFrequency], which only tracks words
 * the user has actually *typed* and how often — that list keeps working exactly
 * as before ("All Usage" in the Prediction Manager). This one is a small,
 * curated set the user maintains by hand. Both feed suggestions, but each has
 * its own storage so neither list's data is ever mixed into or lost from the
 * other. Stored locally on-device only (SharedPreferences) — nothing here
 * leaves the phone.
 */
object UserDictionary {
    private const val PREFS_NAME = "user_dictionary"
    private const val KEY_WORDS = "words"

    // In-memory cache — SharedPreferences/JSON is only parsed once per process.
    @Volatile
    private var cache: LinkedHashSet<String>? = null

    private fun loadCache(context: Context): LinkedHashSet<String> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_WORDS, null)
            val set = LinkedHashSet<String>()
            if (json != null) {
                try {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val w = arr.optString(i).trim()
                        if (w.isNotEmpty()) set.add(w)
                    }
                } catch (_: Exception) {
                    // Corrupt/old data - start fresh rather than crash.
                }
            }
            cache = set
            return set
        }
    }

    private fun persist(context: Context) {
        val set = cache ?: return
        val arr = JSONArray()
        for (w in set) arr.put(w)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORDS, arr.toString())
            .apply() // async write, doesn't block caller
    }

    /**
     * Adds a word to the user's custom prediction list. Returns false (and does
     * nothing) if the word is blank or already present, so callers can tell the
     * user "already added" instead of silently no-op-ing.
     */
    fun add(context: Context, word: String): Boolean {
        val cleaned = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
        if (cleaned.isEmpty()) return false
        val set = loadCache(context)
        val added = set.add(cleaned)
        if (added) persist(context)
        return added
    }

    /** Removes a word from the user's custom prediction list. No-op if not present. */
    fun remove(context: Context, word: String) {
        val set = loadCache(context)
        if (set.remove(word)) persist(context)
    }

    fun contains(context: Context, word: String): Boolean = loadCache(context).contains(word)

    /** All custom words, alphabetically sorted — for the Prediction Manager's "My Prediction" tab. */
    fun getAll(context: Context): List<String> = loadCache(context).sorted()

    /** Custom words matching a prefix — merged into live suggestions alongside learned/dictionary words. */
    fun getByPrefix(context: Context, prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        return loadCache(context)
            .asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    /** Snapshot for export — used by UserDataBackup so custom words survive a phone change too. */
    internal fun snapshot(context: Context): List<String> = loadCache(context).toList()

    /** Merges imported words in additively; existing entries are left as-is. */
    internal fun mergeImport(context: Context, words: List<String>) {
        val set = loadCache(context)
        var changed = false
        for (w in words) {
            val cleaned = Normalizer.normalize(w.trim(), Normalizer.Form.NFC)
            if (cleaned.isNotEmpty() && set.add(cleaned)) changed = true
        }
        if (changed) persist(context)
    }
}
