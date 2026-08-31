package ime.suggest

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight offline Suggestion Engine using Trie for fast prefix-based suggestions.
 * Supports English (case-insensitive) and Sinhala (Unicode NFC normalized).
 * Loads dictionaries from assets/english.json and assets/sinhala.json.
 */
class SuggestionEngine(private val context: Context) {

    private val initialized = AtomicBoolean(false)
    private val englishTrie = Trie()
    private val sinhalaTrie = Trie()

    // Static "how common is this word in real English" score (Zipf frequency,
    // roughly 0-8), keyed by lowercase word. Lets a common word a user has
    // never typed before (e.g. "though") still rank above a rare one, instead
    // of relying purely on trie traversal order. No equivalent bundled data
    // exists for Sinhala yet, so sinhalaBaseFreq stays empty and Sinhala
    // ranking falls back to length/alphabetical + on-device learning only.
    private val englishBaseFreq = HashMap<String, Double>()

    // Bundled "previousWord -> common next words" seed data, per language. Only
    // used to fill in when the user hasn't typed enough themselves yet - see
    // suggestNextWord(). Keyed by lowercase/NFC-normalized previous word.
    private val englishNextWordSeed = HashMap<String, List<String>>()
    private val sinhalaNextWordSeed = HashMap<String, List<String>>()

    companion object {
        private const val TAG = "SuggestionEngine"
        private const val ENGLISH_FILE = "english.json"
        private const val SINHALA_FILE = "sinhala.json"
        private const val ENGLISH_FREQ_FILE = "english_freq.json"
        private const val ENGLISH_NEXT_WORDS_FILE = "english_next_words.json"
        private const val SINHALA_NEXT_WORDS_FILE = "sinhala_next_words.json"

        // How much one prior "previousWord -> this word" occurrence is worth,
        // in the same units as UserWordFrequency's recency-decayed score. Tuned
        // so a couple of bigram hits can pull a rarer word above a merely
        // frequent one, without letting a single coincidental pairing dominate.
        private const val BIGRAM_BOOST_WEIGHT = 3.0

        // Flat ranking boost for words the user manually added via the Prediction
        // Manager's "My Prediction" list. Enough to lift a never-typed custom word
        // above plain dictionary matches, without letting it permanently outrank a
        // word the user actually types often.
        private const val CUSTOM_WORD_BOOST = 1.5

        // Weight applied to englishBaseFreq (itself ~0-8). At 1.0 a very common
        // word like "the" (~7.7) easily beats an untyped rare word, but a word
        // the user actually types repeatedly (freqScore compounds with use) can
        // still overtake it over time - cold-start quality now, personalization
        // still wins later.
        private const val BASE_FREQ_WEIGHT = 1.0

        // How many trie matches to pull before ranking. Needs to be large
        // enough that, for short/common prefixes with thousands of matches,
        // the actually-frequent words aren't cut off before frequency ranking
        // ever sees them. Traversal is in-memory and runs off the main thread
        // (debounced), so a generous cap here is cheap.
        private const val MAX_TRIE_CANDIDATES = 4000
    }

    suspend fun initializeIfNeeded() {
        if (initialized.get()) return

        withContext(Dispatchers.IO) {
            var englishLoaded = 0
            var sinhalaLoaded = 0

            try {
                // Load English dictionary
                context.assets.open(ENGLISH_FILE).use { stream ->
                    val jsonText = stream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(jsonText)

                    for (i in 0 until jsonArray.length()) {
                        val rawWord = jsonArray.optString(i) // safer than getString()
                        val word = rawWord.trim().lowercase()
                        if (word.isNotEmpty()) {
                            englishTrie.insert(word)
                            englishLoaded++
                        }
                    }
                }
                Log.d(TAG, "Loaded $englishLoaded English words")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load English dictionary (normal in tests)", e)
            }

            try {
                // Load bundled English frequency scores (word -> Zipf score).
                // Missing/corrupt file just means every word falls back to 0.0,
                // i.e. the old length/alphabetical-only behavior.
                context.assets.open(ENGLISH_FREQ_FILE).use { stream ->
                    val jsonText = stream.bufferedReader().use { it.readText() }
                    val obj = org.json.JSONObject(jsonText)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val word = keys.next()
                        englishBaseFreq[word] = obj.optDouble(word, 0.0)
                    }
                }
                Log.d(TAG, "Loaded ${englishBaseFreq.size} English frequency scores")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load English frequency data (normal in tests)", e)
            }

            try {
                // Load Sinhala dictionary
                context.assets.open(SINHALA_FILE).use { stream ->
                    val jsonText = stream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(jsonText)

                    for (i in 0 until jsonArray.length()) {
                        val rawWord = jsonArray.optString(i)
                        val normalized = Normalizer.normalize(rawWord.trim(), Normalizer.Form.NFC)
                        if (normalized.isNotEmpty()) {
                            sinhalaTrie.insert(normalized)
                            sinhalaLoaded++
                        }
                    }
                }
                Log.d(TAG, "Loaded $sinhalaLoaded Sinhala words")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load Sinhala dictionary (normal in tests)", e)
            }

            loadNextWordSeed(ENGLISH_NEXT_WORDS_FILE, englishNextWordSeed)
            loadNextWordSeed(SINHALA_NEXT_WORDS_FILE, sinhalaNextWordSeed)

            initialized.set(true)
            Log.i(TAG, "SuggestionEngine initialized successfully")
        }
    }

    private fun loadNextWordSeed(fileName: String, target: HashMap<String, List<String>>) {
        try {
            context.assets.open(fileName).use { stream ->
                val jsonText = stream.bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(jsonText)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val prev = keys.next()
                    val arr = obj.optJSONArray(prev) ?: continue
                    val words = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val w = arr.optString(i)
                        if (w.isNotEmpty()) words.add(w)
                    }
                    if (words.isNotEmpty()) target[prev] = words
                }
            }
            Log.d(TAG, "Loaded ${target.size} next-word seed entries from $fileName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load next-word seed data from $fileName (normal in tests)", e)
        }
    }

    suspend fun suggest(prefix: String, limit: Int = 4, previousWord: String? = null): List<String> {
        if (!initialized.get()) initializeIfNeeded()

        val cleanedPrefix = prefix.trim()
        if (cleanedPrefix.isEmpty()) return emptyList()

        val lang = LanguageDetector.detectLanguage(cleanedPrefix)
        val queryPrefix = if (lang == LanguageDetector.Language.SINHALA) cleanedPrefix else cleanedPrefix.lowercase()
        val trie = if (lang == LanguageDetector.Language.SINHALA) sinhalaTrie else englishTrie

        // Normalize the previous word the same way words get normalized when
        // learned, so it actually matches what's stored in the bigram map.
        val normalizedPreviousWord = previousWord?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (lang == LanguageDetector.Language.SINHALA) Normalizer.normalize(it, Normalizer.Form.NFC) else it.lowercase()
        }

        // Gather far more candidates than we'll show, so frequency-based ranking
        // has room to reorder — otherwise a frequent word buried deep in the
        // dictionary's BFS (shortest-first, alphabetical) order for a short,
        // high-fanout prefix would never even make it into the pool to be ranked.
        val dictionaryCandidates = trie.getByPrefix(queryPrefix, MAX_TRIE_CANDIDATES)
        val learnedCandidates = UserWordFrequency.getByPrefix(context, queryPrefix, MAX_TRIE_CANDIDATES)
        val customCandidates = UserDictionary.getByPrefix(context, queryPrefix, MAX_TRIE_CANDIDATES)

        // Custom (manually-added) and learned words go in first so they're never
        // dropped before dictionary candidates when we later cap the pool.
        val merged = LinkedHashSet<String>()
        merged.addAll(customCandidates)
        merged.addAll(learnedCandidates)
        merged.addAll(dictionaryCandidates)
        val customSet = customCandidates.toHashSet()

        // Rank: recency-weighted typing frequency, boosted when this candidate has
        // followed the previous word before (bigram) or was manually added by the
        // user, plus a static real-world commonality score (English only, so a
        // word like "though" outranks a rare word even before it's ever been
        // typed), then shorter words, then alphabetical.
        val ranked = merged.sortedWith(
            compareByDescending<String> {
                val freqScore = UserWordFrequency.getScore(context, it)
                val bigramBoost = UserBigramFrequency.getFollowCount(context, normalizedPreviousWord, it) * BIGRAM_BOOST_WEIGHT
                val customBoost = if (it in customSet) CUSTOM_WORD_BOOST else 0.0
                val baseFreqBoost = if (lang == LanguageDetector.Language.SINHALA) {
                    0.0
                } else {
                    (englishBaseFreq[it] ?: 0.0) * BASE_FREQ_WEIGHT
                }
                freqScore + bigramBoost + customBoost + baseFreqBoost
            }
                .thenBy { it.length }
                .thenBy { it }
        )

        return ranked.take(limit)
    }

    /**
     * Predicts likely next words right after [previousWord] is finished (e.g. the
     * user just pressed space), with no prefix typed yet for the new word. Prioritizes
     * what THIS user has actually typed after this word before (learns from a single
     * occurrence — no minimum-count gate, so it's usable the very next time), then
     * fills any remaining slots from bundled common-pairing seed data so a fresh
     * install still gives useful next-word suggestions from day one.
     */
    suspend fun suggestNextWord(previousWord: String, limit: Int = 4): List<String> {
        if (!initialized.get()) initializeIfNeeded()

        val cleanedPrev = previousWord.trim()
        if (cleanedPrev.isEmpty()) return emptyList()

        val lang = LanguageDetector.detectLanguage(cleanedPrev)
        val normalizedPrev = if (lang == LanguageDetector.Language.SINHALA) {
            Normalizer.normalize(cleanedPrev, Normalizer.Form.NFC)
        } else {
            cleanedPrev.lowercase()
        }

        val learned = UserBigramFrequency.getNextWords(context, normalizedPrev, limit * 3)
        val seed = if (lang == LanguageDetector.Language.SINHALA) {
            sinhalaNextWordSeed[normalizedPrev] ?: emptyList()
        } else {
            englishNextWordSeed[normalizedPrev] ?: emptyList()
        }

        // What the user has actually done after this word themselves always comes
        // first (personal + already proven relevant); bundled seed words only fill
        // in the remaining slots, in their curated order.
        val result = LinkedHashSet<String>()
        result.addAll(learned)
        result.addAll(seed)
        return result.take(limit).toList()
    }

    /**
     * Call when the user accepts a suggestion or finishes typing a word — learns it
     * locally. [previousWord] (the word right before this one, if any) also feeds
     * the bigram model so the next-word prediction has context to work with.
     */
    fun recordAccepted(word: String, lang: LanguageDetector.Language, previousWord: String? = null) {
        val normalized = if (lang == LanguageDetector.Language.SINHALA) {
            Normalizer.normalize(word, Normalizer.Form.NFC)
        } else {
            word.lowercase()
        }
        UserWordFrequency.learn(context, normalized)

        if (!previousWord.isNullOrBlank()) {
            val normalizedPrev = if (lang == LanguageDetector.Language.SINHALA) {
                Normalizer.normalize(previousWord, Normalizer.Form.NFC)
            } else {
                previousWord.lowercase()
            }
            UserBigramFrequency.learn(context, normalizedPrev, normalized)
        }
    }

    /**
     * Efficient Trie with BFS for shortest + alphabetical suggestions
     */
    class Trie {
        private val root = Node()

        private class Node {
            val children: MutableMap<Char, Node> = LinkedHashMap() // preserves insertion order → alphabetical
            var isWord: Boolean = false
        }

        fun insert(word: String) {
            var current = root
            for (char in word) {
                current = current.children.getOrPut(char) { Node() }
            }
            current.isWord = true
        }

        fun getByPrefix(prefix: String, limit: Int): List<String> {
            val results = mutableListOf<String>()
            var current = root

            // Traverse to the prefix end
            for (char in prefix) {
                val node = current.children[char] ?: return results // no matches
                current = node
            }

            // BFS to get shortest words first, alphabetical due to LinkedHashMap
            val queue = ArrayDeque<Pair<Node, String>>()
            queue.add(current to prefix)

            while (queue.isNotEmpty() && results.size < limit) {
                val (node, currentWord) = queue.poll()

                if (node.isWord) {
                    results.add(currentWord)
                }

                // Add children in alphabetical order
                for ((char, childNode) in node.children) {
                    queue.add(childNode to (currentWord + char))
                }
            }

            return results
        }
    }
}