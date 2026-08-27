package ime.suggest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Normalizer

/**
 * The keyboard's own bundled Sinhala word list (assets/sinhala.json) - the same
 * dictionary [SuggestionEngine] loads into its Trie for live suggestions, exposed
 * here as a plain word list so the Prediction Manager's "All Usage" tab can show
 * it alongside words the user has actually typed (see [UserWordFrequency]).
 *
 * Read-only and on-device: this is just the app's shipped asset, not user data,
 * so there's no add/remove here - only [UserWordFrequency] entries are ever
 * deleted from that screen.
 */
object DefaultDictionary {
    private const val SINHALA_FILE = "sinhala.json"

    // Parsed once per process and cached - ~25k words, cheap to hold in memory
    // but not worth re-parsing on every screen open.
    @Volatile
    private var cache: List<String>? = null

    /** All bundled Sinhala words, NFC-normalized and de-duplicated. Order is
     *  whatever the asset file happens to be in - callers that need Sinhala
     *  alphabetical order should sort with [SinhalaCollation]. */
    suspend fun getAll(context: Context): List<String> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val words = LinkedHashSet<String>()
            try {
                context.assets.open(SINHALA_FILE).use { stream ->
                    val jsonText = stream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(jsonText)
                    for (i in 0 until jsonArray.length()) {
                        val raw = jsonArray.optString(i)
                        // Strip a stray leading BOM (U+FEFF) some editors save into
                        // the first array entry - trim() alone doesn't remove it
                        // since it isn't whitespace by Kotlin's definition.
                        val cleaned = raw.trim().trimStart('\uFEFF')
                        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFC)
                        if (normalized.isNotEmpty()) words.add(normalized)
                    }
                }
            } catch (_: Exception) {
                // Missing/corrupt asset - fall back to an empty default list
                // rather than crashing the Prediction Manager screen.
            }
            val result = words.toList()
            cache = result
            result
        }
    }
}
