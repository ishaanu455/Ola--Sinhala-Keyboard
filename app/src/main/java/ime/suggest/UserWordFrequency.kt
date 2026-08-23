package ime.suggest

import android.content.Context
import org.json.JSONObject

/**
 * Tracks how often the user types each word, and when they last typed it, so
 * frequently- AND recently-used words can be ranked above generic dictionary
 * matches. Stored locally on-device only (SharedPreferences) — never leaves
 * the phone unless the user explicitly exports it (see UserDataBackup).
 *
 * Ranking applies an exponential recency decay on top of the raw count (see
 * [scoreOf]), so a word typed 50 times a while ago doesn't permanently
 * outrank a word typed twice yesterday — it just takes longer for a
 * frequent-but-stale word to fall behind a fresher one.
 */
object UserWordFrequency {
    private const val PREFS_NAME = "user_word_frequency"
    private const val KEY_DATA = "word_counts"
    private const val MAX_WORDS = 2000

    // Half-life of the recency boost, in days. A word not typed again for
    // this many days has its score halved relative to an equally-frequent
    // word typed today. 14 days keeps genuinely regular habits on top while
    // letting yesterday's words beat an old one-off burst of typing.
    private const val RECENCY_HALF_LIFE_DAYS = 14.0
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    private data class WordStat(var count: Int, var lastUsed: Long)

    // In-memory cache — SharedPreferences/JSON is only parsed once per process.
    @Volatile
    private var cache: MutableMap<String, WordStat>? = null

    private fun loadCache(context: Context): MutableMap<String, WordStat> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_DATA, null)
            val map = LinkedHashMap<String, WordStat>()
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    val keys = obj.keys()
                    val now = System.currentTimeMillis()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        when (val value = obj.opt(k)) {
                            // Current format: {"count": N, "lastUsed": epochMs}
                            is JSONObject -> {
                                val count = value.optInt("count", 0)
                                val lastUsed = value.optLong("lastUsed", now)
                                map[k] = WordStat(count, lastUsed)
                            }
                            // Legacy format from before recency tracking: a plain
                            // integer count. Migrate by assuming "last used now" so
                            // existing habits aren't penalized the moment this
                            // upgrade runs.
                            else -> {
                                val count = value?.toString()?.toIntOrNull() ?: 0
                                map[k] = WordStat(count, now)
                            }
                        }
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
        for ((k, v) in map) {
            val entry = JSONObject()
            entry.put("count", v.count)
            entry.put("lastUsed", v.lastUsed)
            obj.put(k, entry)
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DATA, obj.toString())
            .apply() // async write, doesn't block caller
    }

    /**
     * Call this whenever the user finishes typing a word (space/punctuation pressed,
     * or a suggestion tapped). Safe to call from a background thread.
     */
    fun learn(context: Context, word: String) {
        val cleaned = word.trim()
        if (cleaned.length < 2) return // skip single letters / noise

        val map = loadCache(context)
        val existing = map[cleaned]
        map[cleaned] = WordStat((existing?.count ?: 0) + 1, System.currentTimeMillis())

        if (map.size > MAX_WORDS) {
            // Evict by current recency-weighted score, not raw count, so a word
            // that's merely old doesn't survive over one that's both rare and stale.
            val toEvict = map.entries
                .sortedBy { scoreOf(it.value) }
                .take(map.size - MAX_WORDS)
            for (entry in toEvict) map.remove(entry.key)
        }

        persist(context)
    }

    /** Raw typed count, ignoring recency. Kept for callers that just want the tally. */
    fun getFrequency(context: Context, word: String): Int =
        loadCache(context)[word]?.count ?: 0

    /**
     * Ranking score: count decayed by how long it's been since the word was
     * last typed. A word typed once today can outrank one typed many times
     * months ago once it's gone stale enough.
     */
    fun getScore(context: Context, word: String): Double {
        val stat = loadCache(context)[word] ?: return 0.0
        return scoreOf(stat)
    }

    private fun scoreOf(stat: WordStat): Double {
        val daysSince = (System.currentTimeMillis() - stat.lastUsed).coerceAtLeast(0) / MS_PER_DAY.toDouble()
        val decay = Math.pow(0.5, daysSince / RECENCY_HALF_LIFE_DAYS)
        return stat.count * decay
    }

    /** Learned words matching a prefix, highest recency-weighted score first. */
    fun getByPrefix(context: Context, prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val map = loadCache(context)
        return map.entries
            .asSequence()
            .filter { it.key.startsWith(prefix, ignoreCase = true) }
            .sortedByDescending { scoreOf(it.value) }
            .take(limit)
            .map { it.key }
            .toList()
    }

    /** Snapshot for export — word -> (count, lastUsed). Used by UserDataBackup. */
    internal fun snapshot(context: Context): Map<String, Pair<Int, Long>> =
        loadCache(context).mapValues { it.value.count to it.value.lastUsed }

    /**
     * Merges imported data in. Existing counts are added to (not overwritten) so
     * restoring a backup onto a phone that's already been used a bit doesn't
     * discard what it already learned; lastUsed takes the newer of the two.
     */
    internal fun mergeImport(context: Context, data: Map<String, Pair<Int, Long>>) {
        val map = loadCache(context)
        for ((word, pair) in data) {
            val (count, lastUsed) = pair
            val existing = map[word]
            map[word] = if (existing == null) {
                WordStat(count, lastUsed)
            } else {
                WordStat(existing.count + count, maxOf(existing.lastUsed, lastUsed))
            }
        }
        if (map.size > MAX_WORDS) {
            val toEvict = map.entries.sortedBy { scoreOf(it.value) }.take(map.size - MAX_WORDS)
            for (entry in toEvict) map.remove(entry.key)
        }
        persist(context)
    }
}
