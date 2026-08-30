package ime.suggest

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.ola.keyboard.ClipboardData
import com.ola.keyboard.EmojiData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-device backup: every SharedPreferences file the app writes to, dumped
 * key-by-key with its exact type preserved (String/Boolean/Int/Long/Float/
 * StringSet). This is deliberately generic rather than field-mapped like
 * [UserDataBackup] - it doesn't need to know what a "clipboard" or a
 * "setting" is, so it can never miss a field, and never needs updating when
 * a new setting is added later. Between the four files below this already
 * covers every keyboard setting, the color/background/font choices, recent
 * emoji, clipboard history (pinned included), and the learned-word
 * dictionary - i.e. everything the user can configure or that the keyboard
 * has learned, in one JSON file.
 *
 * Restore is a full replace of every key found in the backup (not a merge
 * like [UserDataBackup]'s dictionary/clipboard import) - "restore everything"
 * is expected to put the device back exactly as the backup describes. Keys
 * NOT present in the backup are left untouched, so restoring an older backup
 * never deletes settings/data added after it was taken.
 */
object FullBackup {
    private const val FORMAT_VERSION = 1

    /** Every SharedPreferences file the app uses. Keep in sync with the
     *  getSharedPreferences(...) calls elsewhere in the codebase - "prefs"
     *  (settings, recent emoji, clipboard) plus the three dictionary files. */
    private val PREF_FILES = listOf(
        "prefs",
        "user_word_frequency",
        "user_bigram_frequency",
        "user_dictionary"
    )

    fun export(context: Context, uri: Uri): Boolean {
        return try {
            val root = JSONObject()
            root.put("formatVersion", FORMAT_VERSION)
            root.put("exportedAt", System.currentTimeMillis())

            val filesObj = JSONObject()
            for (fileName in PREF_FILES) {
                val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                filesObj.put(fileName, dumpPrefs(prefs))
            }
            root.put("prefFiles", filesObj)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return false

            val root = JSONObject(text)
            val filesObj = root.optJSONObject("prefFiles") ?: return false

            var restoredAny = false
            for (fileName in PREF_FILES) {
                val fileObj = filesObj.optJSONObject(fileName) ?: continue
                val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                restorePrefs(prefs, fileObj)
                restoredAny = true
            }

            // "prefs" backs ClipboardData's and EmojiData's own in-memory caches -
            // without this, an old cache loaded earlier in this process would
            // save() itself back over the restore on the next clip/emoji action.
            if (filesObj.has("prefs")) {
                ClipboardData.invalidateCache()
                EmojiData.loadRecentEmojis(context)
            }

            restoredAny
        } catch (_: Exception) {
            false
        }
    }

    /** Dumps every entry of [prefs] as {type, value}, so import can call the
     *  matching putXxx() and reconstruct the exact original type. */
    private fun dumpPrefs(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((key, value) in prefs.all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("type", "boolean"); entry.put("value", value) }
                is Int -> { entry.put("type", "int"); entry.put("value", value) }
                is Long -> { entry.put("type", "long"); entry.put("value", value) }
                is Float -> { entry.put("type", "float"); entry.put("value", value.toDouble()) }
                is String -> { entry.put("type", "string"); entry.put("value", value) }
                is Set<*> -> {
                    entry.put("type", "stringSet")
                    val arr = JSONArray()
                    for (item in value) arr.put(item.toString())
                    entry.put("value", arr)
                }
                else -> continue // unknown/null - skip rather than write something unrestorable
            }
            out.put(key, entry)
        }
        return out
    }

    /** Restores every entry from [fileObj] into [prefs], overwriting existing
     *  values for the keys present but leaving every other key untouched. */
    private fun restorePrefs(prefs: SharedPreferences, fileObj: JSONObject) {
        val editor = prefs.edit()
        val keys = fileObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = fileObj.optJSONObject(key) ?: continue
            when (entry.optString("type")) {
                "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                "int" -> editor.putInt(key, entry.optInt("value"))
                "long" -> editor.putLong(key, entry.optLong("value"))
                "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                "string" -> editor.putString(key, entry.optString("value"))
                "stringSet" -> {
                    val arr = entry.optJSONArray("value") ?: JSONArray()
                    val set = HashSet<String>(arr.length())
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    editor.putStringSet(key, set)
                }
                else -> Unit // unrecognised type tag - skip that one key rather than fail the whole restore
            }
        }
        editor.apply()
    }
}
