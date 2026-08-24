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

    companion object {
        private const val TAG = "SuggestionEngine"
        private const val ENGLISH_FILE = "english.json"
        private const val SINHALA_FILE = "sinhala.json"

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

            initialized.set(true)
            Log.i(TAG, "SuggestionEngine initialized successfully")
        }
    }

    suspend fun suggest(prefix: String, limit: Int = 5, previousWord: String? = null): List<String> {
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

        // Gather more candidates than we'll show, so frequency-based ranking has
        // room to reorder — otherwise a frequent word buried deep in the dictionary
        // BFS order would never surface.
        val candidatePoolSize = limit * 6
        val dictionaryCandidates = trie.getByPrefix(queryPrefix, candidatePoolSize)
        val learnedCandidates = UserWordFrequency.getByPrefix(context, queryPrefix, candidatePoolSize)
        val customCandidates = UserDictionary.getByPrefix(context, queryPrefix, candidatePoolSize)

        // Custom (manually-added) and learned words go in first so they're never
        // dropped before dictionary candidates when we later cap the pool.
        val merged = LinkedHashSet<String>()
        merged.addAll(customCandidates)
        merged.addAll(learnedCandidates)
        merged.addAll(dictionaryCandidates)
        val customSet = customCandidates.toHashSet()

        // Rank: recency-weighted typing frequency, boosted when this candidate has
        // followed the previous word before (bigram) or was manually added by the
        // user, then shorter words, then alphabetical.
        val ranked = merged.sortedWith(
            compareByDescending<String> {
                val freqScore = UserWordFrequency.getScore(context, it)
                val bigramBoost = UserBigramFrequency.getFollowCount(context, normalizedPreviousWord, it) * BIGRAM_BOOST_WEIGHT
                val customBoost = if (it in customSet) CUSTOM_WORD_BOOST else 0.0
                freqScore + bigramBoost + customBoost
            }
                .thenBy { it.length }
                .thenBy { it }
        )

        return ranked.take(limit)
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