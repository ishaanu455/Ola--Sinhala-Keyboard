package ime.suggest

import java.text.Normalizer

/**
 * Sorts Sinhala words in traditional hodiya (අ, ආ, ඇ ... ක, ඛ, ග ...) order,
 * instead of plain Unicode code-point order — used by the Prediction Manager's
 * "My Prediction" and "All Usage" lists so words group the way a Sinhala
 * speaker expects (all අ- words together and in the right order, etc.).
 *
 * How it works: every character in the traditional alphabet — independent
 * vowels, then consonants, then the dependent vowel signs/anusvara/visarga/hal
 * kirima that attach to a consonant — gets a rank in that order. Two words are
 * compared character by character using those ranks; a word with no vowel
 * sign after a consonant (the bare "ka" sound) naturally sorts before the same
 * consonant with a vowel sign added, because it simply runs out of characters
 * first — no special-casing needed for that. Characters outside the Sinhala
 * alphabet (digits, Latin letters, punctuation) fall back to code-point order,
 * placed after every Sinhala character so mixed-language lists still group
 * Sinhala words first.
 */
object SinhalaCollation {

    private val HODIYA_ORDER: List<Char> = listOf(
        // Independent vowels
        'අ', 'ආ', 'ඇ', 'ඈ', 'ඉ', 'ඊ', 'උ', 'ඌ', 'ඍ', 'ඎ', 'ඏ', 'ඐ',
        'එ', 'ඒ', 'ඓ', 'ඔ', 'ඕ', 'ඖ',
        // Consonants
        'ක', 'ඛ', 'ග', 'ඝ', 'ඞ', 'ඟ',
        'ච', 'ඡ', 'ජ', 'ඣ', 'ඤ', 'ඥ',
        'ට', 'ඨ', 'ඩ', 'ඪ', 'ණ', 'ඬ',
        'ත', 'ථ', 'ද', 'ධ', 'න', 'ඳ',
        'ප', 'ඵ', 'බ', 'භ', 'ම', 'ඹ',
        'ය', 'ර', 'ල', 'ව',
        'ශ', 'ෂ', 'ස', 'හ', 'ළ', 'ෆ',
        // Dependent vowel signs (pilla) and other combining marks, in the order
        // they'd read after a consonant. The bare inherent-'a' form has no sign
        // at all, so it's handled by the "shorter word wins" rule below rather
        // than appearing in this list.
        'ා', 'ැ', 'ෑ', 'ි', 'ී', 'ු', 'ූ', 'ෘ', 'ෲ', 'ෟ', 'ෳ', 'ෙ', 'ේ', 'ෛ', 'ො', 'ෝ', 'ෞ',
        // Virama (pure consonant, vowel suppressed), then anusvara, visarga
        '්', 'ං', 'ඃ'
    )

    private val RANK: Map<Char, Int> = HODIYA_ORDER.withIndex().associate { (i, c) -> c to i }

    // Anything not in the map (digits, Latin letters, punctuation, etc.) sorts
    // after every mapped Sinhala character, in its own code-point order.
    private const val FALLBACK_BASE = 10_000

    private fun rankOf(c: Char): Int = RANK[c] ?: (FALLBACK_BASE + c.code)

    fun compare(a: String, b: String): Int {
        val an = Normalizer.normalize(a, Normalizer.Form.NFC)
        val bn = Normalizer.normalize(b, Normalizer.Form.NFC)
        val len = minOf(an.length, bn.length)
        for (i in 0 until len) {
            val ca = an[i]
            val cb = bn[i]
            if (ca == cb) continue
            return rankOf(ca).compareTo(rankOf(cb))
        }
        return an.length.compareTo(bn.length)
    }

    /** Comparator form of [compare], for use with sortedWith / Comparator-taking APIs. */
    val comparator: Comparator<String> = Comparator { a, b -> compare(a, b) }
}
