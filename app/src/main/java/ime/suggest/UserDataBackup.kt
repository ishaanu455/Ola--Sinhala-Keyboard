package ime.suggest

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports the on-device "learned words" data (word frequency +
 * next-word bigram associations) as a single JSON file the user can save
 * wherever they like and restore later — e.g. after a phone change, or as a
 * manual backup. Nothing here leaves the device on its own: export only
 * happens when the user explicitly picks a save location via the system
 * file picker, same as import.
 */
object UserDataBackup {
    private const val FORMAT_VERSION = 1

    /** Writes a backup of everything learned so far to [uri]. Returns success. */
    fun export(context: Context, uri: Uri): Boolean {
        return try {
            val root = JSONObject()
            root.put("formatVersion", FORMAT_VERSION)
            root.put("exportedAt", System.currentTimeMillis())

            val wordsObj = JSONObject()
            for ((word, stat) in UserWordFrequency.snapshot(context)) {
                val (count, lastUsed) = stat
                val entry = JSONObject()
                entry.put("count", count)
                entry.put("lastUsed", lastUsed)
                wordsObj.put(word, entry)
            }
            root.put("words", wordsObj)

            val bigramObj = JSONObject()
            for ((prev, nextMap) in UserBigramFrequency.snapshot(context)) {
                val nextObj = JSONObject()
                for ((next, count) in nextMap) nextObj.put(next, count)
                bigramObj.put(prev, nextObj)
            }
            root.put("bigrams", bigramObj)

            val customWordsArr = JSONArray()
            for (w in UserDictionary.snapshot(context)) customWordsArr.put(w)
            root.put("customWords", customWordsArr)

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Restores a previously-exported file from [uri]. Merges additively into
     * whatever's already learned on this device rather than replacing it, so
     * restoring a backup never discards words learned since. Returns success.
     */
    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return false

            val root = JSONObject(text)

            val wordsObj = root.optJSONObject("words")
            if (wordsObj != null) {
                val data = LinkedHashMap<String, Pair<Int, Long>>()
                val keys = wordsObj.keys()
                while (keys.hasNext()) {
                    val word = keys.next()
                    val entry = wordsObj.optJSONObject(word) ?: continue
                    data[word] = entry.optInt("count", 0) to entry.optLong("lastUsed", System.currentTimeMillis())
                }
                UserWordFrequency.mergeImport(context, data)
            }

            val bigramObj = root.optJSONObject("bigrams")
            if (bigramObj != null) {
                val data = LinkedHashMap<String, Map<String, Int>>()
                val prevKeys = bigramObj.keys()
                while (prevKeys.hasNext()) {
                    val prev = prevKeys.next()
                    val nextObj = bigramObj.optJSONObject(prev) ?: continue
                    val nextMap = LinkedHashMap<String, Int>()
                    val nextKeys = nextObj.keys()
                    while (nextKeys.hasNext()) {
                        val next = nextKeys.next()
                        nextMap[next] = nextObj.optInt(next, 0)
                    }
                    data[prev] = nextMap
                }
                UserBigramFrequency.mergeImport(context, data)
            }

            val customWordsArr = root.optJSONArray("customWords")
            if (customWordsArr != null) {
                val words = ArrayList<String>(customWordsArr.length())
                for (i in 0 until customWordsArr.length()) {
                    words.add(customWordsArr.optString(i))
                }
                UserDictionary.mergeImport(context, words)
            }

            // A file with none of the known sections is malformed / not one of our backups.
            wordsObj != null || bigramObj != null || customWordsArr != null
        } catch (_: Exception) {
            false
        }
    }
}
