package ime.suggest

import android.content.Context
import android.net.Uri
import com.ola.keyboard.ClipboardData
import com.ola.keyboard.ClipItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports the on-device "learned words" data (word frequency +
 * next-word bigram associations) plus clipboard history as a single JSON
 * file the user can save wherever they like and restore later — e.g. after
 * a phone change, or as a manual backup. Nothing here leaves the device on
 * its own: export only happens when the user explicitly picks a save
 * location via the system file picker, same as import.
 *
 * FORMAT_VERSION 2 added the "clipboard" section (every ClipItem field,
 * pinned included). A version-1 backup file simply has no "clipboard" key -
 * optJSONArray returns null for it and import() skips that section
 * gracefully, so old backups still restore the dictionary parts they have.
 */
object UserDataBackup {
    private const val FORMAT_VERSION = 2

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

            // Every field of every clip, pinned included - display position is
            // always re-derived from these fields on read, so backing them up
            // exactly is enough to reproduce identical pinned/unpinned order.
            val clipsArr = JSONArray()
            for (c in ClipboardData.snapshotForBackup(context)) {
                val entry = JSONObject()
                entry.put("id", c.id)
                entry.put("text", c.text)
                entry.put("timestamp", c.timestamp)
                entry.put("pinned", c.pinned)
                entry.put("useCount", c.useCount)
                entry.put("lastUsedTimestamp", c.lastUsedTimestamp)
                clipsArr.put(entry)
            }
            root.put("clipboard", clipsArr)

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
     * whatever's already on this device rather than replacing it, so
     * restoring a backup never discards words or clips learned/copied since.
     * Returns success.
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

            val clipsArr = root.optJSONArray("clipboard")
            if (clipsArr != null) {
                val items = ArrayList<ClipItem>(clipsArr.length())
                for (i in 0 until clipsArr.length()) {
                    val o = clipsArr.optJSONObject(i) ?: continue
                    items.add(
                        ClipItem(
                            id = o.optLong("id"),
                            text = o.optString("text"),
                            timestamp = o.optLong("timestamp"),
                            pinned = o.optBoolean("pinned", false),
                            useCount = o.optInt("useCount", 0),
                            lastUsedTimestamp = o.optLong("lastUsedTimestamp", 0L)
                        )
                    )
                }
                ClipboardData.restoreFromBackup(context, items)
            }

            // A file with none of the known sections is malformed / not one of our backups.
            wordsObj != null || bigramObj != null || customWordsArr != null || clipsArr != null
        } catch (_: Exception) {
            false
        }
    }
}
