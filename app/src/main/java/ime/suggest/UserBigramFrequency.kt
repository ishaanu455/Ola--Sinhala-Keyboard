package ime.suggest

import android.content.Context
import org.json.JSONObject

/**
 * Tracks which word tends to follow which — "previousWord -> {nextWord: count}".
 * Lets ranking boost a candidate that's a word the user has typed right after
 * the current previous word before, on top of plain prefix + frequency
 * matching. Stored locally on-device only, same as UserWordFrequency, and
 * only ever leaves the device via an explicit export (see UserDataBackup).
 */
object UserBigramFrequency {
    private const val PREFS_NAME = "user_bigram_frequency"
    private const val KEY_DATA = "bigram_counts"

    // Caps keep this bounded the same way UserWordFrequency is: a limited
    // number of "previous word" contexts, each with a limited number of
    // "what followed it" entries.
    private const val MAX_PREV_WORDS = 800
    private const val MAX_NEXT_PER_WORD = 12

    @Volatile
    private var cache: MutableMap<String, MutableMap<String, Int>>? = null

    private fun loadCache(context: Context): MutableMap<String, MutableMap<String, Int>> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_DATA, null)
            val map = LinkedHashMap<String, MutableMap<String, Int>>()
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val prev = keys.next()
                        val nextObj = obj.optJSONObject(prev) ?: continue
                        val nextMap = LinkedHashMap<String, Int>()
                        val nextKeys = nextObj.keys()
                        while (nextKeys.hasNext()) {
                            val next = nextKeys.next()
                            nextMap[next] = nextObj.optInt(next, 0)
                        }
                        map[prev] = nextMap
                    }
                } catch (_: Exception) {
                    // Corrupt/old data — start fresh rather than crash.
                }
            }
            cache = map
            return map
        }
    }

    private fun persist(context: Context) {
        val map = cache ?: return
        val obj = JSONObject()
        for ((prev, nextMap) in map) {
            val nextObj = JSONObject()
            for ((next, count) in nextMap) nextObj.put(next, count)
            obj.put(prev, nextObj)
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, obj.toString())
            .apply()
    }

    /** Call when the user finishes typing [next] right after [previous]. */
    fun learn(context: Context, previous: String?, next: String) {
        val prev = previous?.trim().orEmpty()
        val cleanedNext = next.trim()
        // No usable context (start of message), or too short to be meaningful.
        if (prev.length < 2 || cleanedNext.length < 2) return

        val map = loadCache(context)
        val nextMap = map.getOrPut(prev) { LinkedHashMap() }
        nextMap[cleanedNext] = (nextMap[cleanedNext] ?: 0) + 1

        if (nextMap.size > MAX_NEXT_PER_WORD) {
            val toEvict = nextMap.entries.sortedBy { it.value }.take(nextMap.size - MAX_NEXT_PER_WORD)
            for (entry in toEvict) nextMap.remove(entry.key)
        }

        if (map.size > MAX_PREV_WORDS) {
            // Evict previous-word contexts whose strongest association is the
            // weakest overall — i.e. the ones that never really "stuck".
            val toEvict = map.entries
                .sortedBy { it.value.values.maxOrNull() ?: 0 }
                .take(map.size - MAX_PREV_WORDS)
            for (entry in toEvict) map.remove(entry.key)
        }

        persist(context)
    }

    /** Words that have followed [previous] before, most-common first. */
    fun getNextWords(context: Context, previous: String, limit: Int): List<String> {
        if (previous.isBlank()) return emptyList()
        val nextMap = loadCache(context)[previous.trim()] ?: return emptyList()
        return nextMap.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    /** How many times [next] has followed [previous] — used to boost ranking. */
    fun getFollowCount(context: Context, previous: String?, next: String): Int {
        val prev = previous?.trim().orEmpty()
        if (prev.isEmpty()) return 0
        return loadCache(context)[prev]?.get(next) ?: 0
    }

    /** Snapshot for export. Used by UserDataBackup. */
    internal fun snapshot(context: Context): Map<String, Map<String, Int>> = loadCache(context)

    /** Merges imported bigram counts additively into what's already learned. */
    internal fun mergeImport(context: Context, data: Map<String, Map<String, Int>>) {
        val map = loadCache(context)
        for ((prev, nextMap) in data) {
            val existing = map.getOrPut(prev) { LinkedHashMap() }
            for ((next, count) in nextMap) {
                existing[next] = (existing[next] ?: 0) + count
            }
            if (existing.size > MAX_NEXT_PER_WORD) {
                val toEvict = existing.entries.sortedBy { it.value }.take(existing.size - MAX_NEXT_PER_WORD)
                for (entry in toEvict) existing.remove(entry.key)
            }
        }
        if (map.size > MAX_PREV_WORDS) {
            val toEvict = map.entries.sortedBy { it.value.values.maxOrNull() ?: 0 }.take(map.size - MAX_PREV_WORDS)
            for (entry in toEvict) map.remove(entry.key)
        }
        persist(context)
    }
}
