package unicode.sinhala.keyboard

/**
 * "Fancy text" character-swap styles (Bold, Italic, Circled, etc.) - the same trick sites
 * like fsymbols/lingojam use. This is NOT a font: it maps ordinary A-Z / a-z / 0-9
 * codepoints to lookalike codepoints that already exist in the Unicode standard itself
 * (mainly the Mathematical Alphanumeric Symbols and Enclosed Alphanumerics blocks, e.g.
 * A U+0041 -> 𝐀 U+1D400). No font file is loaded, so this works fully offline with no
 * license concerns - it's Unicode Consortium's own open standard, not bundled artwork.
 *
 * Deliberate limitation: only Latin letters (A-Z, a-z) and digits (0-9) have these
 * lookalike codepoints. Sinhala letters are a conjunct/vowel-sign composing script with
 * no styled Unicode variants, so [convert] leaves every non-Latin, non-digit character
 * untouched - typing Sinhala with a style "active" is always a safe no-op, never mangled
 * output. UI that surfaces these styles should make this English-only scope clear to
 * the user up front.
 */
object FontStyleData {

    /** convert() looks up each character in [map]; anything missing (Sinhala, punctuation,
     *  space, emoji, ...) passes through unchanged. [sample] is what the style picker
     *  shows so the user can see the look before picking it. */
    fun convert(text: String, style: FontStyle): String {
        if (style == FontStyle.NONE) return text
        val map = maps[style] ?: return text
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(map[ch] ?: ch.toString())
        return sb.toString()
    }

    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"

    /** Builds a Char->String map from three parallel strings/char-lists (upper, lower,
     *  digit replacements). A null replacement list means that style has no digit
     *  variants, so digits are simply left out of the map (and pass through as-is). */
    private fun buildMap(upperTo: List<String>, lowerTo: List<String>, digitTo: List<String>?): Map<Char, String> {
        val m = HashMap<Char, String>(72)
        UPPER.forEachIndexed { i, c -> m[c] = upperTo[i] }
        LOWER.forEachIndexed { i, c -> m[c] = lowerTo[i] }
        digitTo?.forEachIndexed { i, s -> m[DIGITS[i]] = s }
        return m
    }

    /** Splits a contiguous run of codepoints (given as a single string, one char/surrogate
     *  pair per source letter) into a 26- or 10-element list of single-character strings. */
    private fun run(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        return out
    }

    private val BOLD = buildMap(
        run("𝐀𝐁𝐂𝐃𝐄𝐅𝐆𝐇𝐈𝐉𝐊𝐋𝐌𝐍𝐎𝐏𝐐𝐑𝐒𝐓𝐔𝐕𝐖𝐗𝐘𝐙"),
        run("𝐚𝐛𝐜𝐝𝐞𝐟𝐠𝐡𝐢𝐣𝐤𝐥𝐦𝐧𝐨𝐩𝐪𝐫𝐬𝐭𝐮𝐯𝐰𝐱𝐲𝐳"),
        run("𝟎𝟏𝟐𝟑𝟒𝟓𝟔𝟕𝟖𝟗")
    )
    private val ITALIC = buildMap(
        run("𝐴𝐵𝐶𝐷𝐸𝐹𝐺𝐻𝐼𝐽𝐾𝐿𝑀𝑁𝑂𝑃𝑄𝑅𝑆𝑇𝑈𝑉𝑊𝑋𝑌𝑍"),
        run("𝑎𝑏𝑐𝑑𝑒𝑓𝑔ℎ𝑖𝑗𝑘𝑙𝑚𝑛𝑜𝑝𝑞𝑟𝑠𝑡𝑢𝑣𝑤𝑥𝑦𝑧"),
        null // Unicode has no dedicated italic-digit block
    )
    private val SCRIPT = buildMap(
        run("𝒜ℬ𝒞𝒟ℰℱ𝒢ℋℐ𝒥𝒦ℒℳ𝒩𝒪𝒫𝒬ℛ𝒮𝒯𝒰𝒱𝒲𝒳𝒴𝒵"),
        run("𝒶𝒷𝒸𝒹ℯ𝒻ℊ𝒽𝒾𝒿𝓀𝓁𝓂𝓃ℴ𝓅𝓆𝓇𝓈𝓉𝓊𝓋𝓌𝓍𝓎𝓏"),
        null
    )
    private val FRAKTUR = buildMap(
        run("𝔄𝔅ℭ𝔇𝔈𝔉𝔊ℌℑ𝔍𝔎𝔏𝔐𝔑𝔒𝔓𝔔ℜ𝔖𝔗𝔘𝔙𝔚𝔛𝔜ℨ"),
        run("𝔞𝔟𝔠𝔡𝔢𝔣𝔤𝔥𝔦𝔧𝔨𝔩𝔪𝔫𝔬𝔭𝔮𝔯𝔰𝔱𝔲𝔳𝔴𝔵𝔶𝔷"),
        null
    )
    private val DOUBLE_STRUCK = buildMap(
        run("𝔸𝔹ℂ𝔻𝔼𝔽𝔾ℍ𝕀𝕁𝕂𝕃𝕄ℕ𝕆ℙℚℝ𝕊𝕋𝕌𝕍𝕎𝕏𝕐ℤ"),
        run("𝕒𝕓𝕔𝕕𝕖𝕗𝕘𝕙𝕚𝕛𝕜𝕝𝕞𝕟𝕠𝕡𝕢𝕣𝕤𝕥𝕦𝕧𝕨𝕩𝕪𝕫"),
        run("𝟘𝟙𝟚𝟛𝟜𝟝𝟞𝟟𝟠𝟡")
    )
    private val MONOSPACE = buildMap(
        run("𝙰𝙱𝙲𝙳𝙴𝙵𝙶𝙷𝙸𝙹𝙺𝙻𝙼𝙽𝙾𝙿𝚀𝚁𝚂𝚃𝚄𝚅𝚆𝚇𝚈𝚉"),
        run("𝚊𝚋𝚌𝚍𝚎𝚏𝚐𝚑𝚒𝚓𝚔𝚕𝚖𝚗𝚘𝚙𝚚𝚛𝚜𝚝𝚞𝚟𝚠𝚡𝚢𝚣"),
        run("𝟶𝟷𝟸𝟹𝟺𝟻𝟼𝟽𝟾𝟿")
    )
    private val CIRCLED = buildMap(
        run("ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏ"),
        run("ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ"),
        // No circled 0; U+24EA is a second circled zero, U+2460.. starts at 1 - use
        // 24EA for 0 and 2460-2468 for 1-9.
        listOf("⓪", "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨")
    )
    private val FULLWIDTH = buildMap(
        run("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        run("abcdefghijklmnopqrstuvwxyz"),
        run("0123456789")
    )
    private val SMALL_CAPS = buildMap(
        // Small-caps has no dedicated block for every letter - a handful (Q, X) don't
        // exist as true small-caps codepoints, so those two fall back to their plain
        // uppercase form rather than a wrong-looking substitute glyph.
        listOf("ᴀ","Ᏸ","ᴄ","ᴅ","ᴇ","ꜰ","ɢ","ʜ","ɪ","ᴊ","ᴋ","ʟ","ᴍ","ɴ","ᴏ","ᴘ","Q","ʀ","s","ᴛ","ᴜ","ᴠ","ᴡ","x","ʏ","ᴢ")
            .mapIndexed { i, s -> if (s.length == 1 && s[0].isUpperCase() && s[0] in "QX") UPPER[i].toString() else s },
        listOf("ᴀ","Ᏸ","ᴄ","ᴅ","ᴇ","ꜰ","ɢ","ʜ","ɪ","ᴊ","ᴋ","ʟ","ᴍ","ɴ","ᴏ","ᴘ","Q","ʀ","s","ᴛ","ᴜ","ᴠ","ᴡ","x","ʏ","ᴢ")
            .mapIndexed { i, s -> if (s.length == 1 && s[0].isUpperCase() && s[0] in "QX") UPPER[i].toString() else s },
        null
    )
    private val SUPERSCRIPT = buildMap(
        listOf("ᴬ","Ᏸ","ᶜ","ᴰ","ᴱ","ᶠ","ᴳ","ᴴ","ᴵ","ᴶ","ᴷ","ᴸ","ᴹ","ᴺ","ᴼ","ᴾ","Q","ᴿ","ˢ","ᵀ","ᵁ","ⱽ","ᵂ","x","ʸ","ᶻ")
            .mapIndexed { i, s -> if (s.length == 1 && s[0].isUpperCase() && s[0] in "QX") UPPER[i].toString() else s },
        listOf("ᵃ","ᵇ","ᶜ","ᵈ","ᵉ","ᶠ","ᵍ","ʰ","ⁱ","ʲ","ᵏ","ˡ","ᵐ","ⁿ","ᵒ","ᵖ","q","ʳ","ˢ","ᵗ","ᵘ","ᵛ","ʷ","ˣ","ʸ","ᶻ"),
        run("⁰¹²³⁴⁵⁶⁷⁸⁹")
    )

    /** Underline/strikethrough have no dedicated per-letter codepoints - each letter gets
     *  a combining mark (U+0332 low line / U+0336 long stroke overlay) appended after it
     *  instead. Renders correctly on most modern apps, but combining-mark rendering is
     *  less universally consistent than the other styles, which are plain single codepoints. */
    private fun combiningMap(mark: Char): Map<Char, String> {
        val m = HashMap<Char, String>(72)
        (UPPER + LOWER).forEach { c -> m[c] = "$c$mark" }
        DIGITS.forEach { c -> m[c] = "$c$mark" }
        return m
    }
    private val UNDERLINE = combiningMap('\u0332')
    private val STRIKETHROUGH = combiningMap('\u0336')

    private val maps: Map<FontStyle, Map<Char, String>> = mapOf(
        FontStyle.BOLD to BOLD,
        FontStyle.ITALIC to ITALIC,
        FontStyle.SCRIPT to SCRIPT,
        FontStyle.FRAKTUR to FRAKTUR,
        FontStyle.DOUBLE_STRUCK to DOUBLE_STRUCK,
        FontStyle.MONOSPACE to MONOSPACE,
        FontStyle.CIRCLED to CIRCLED,
        FontStyle.FULLWIDTH to FULLWIDTH,
        FontStyle.SMALL_CAPS to SMALL_CAPS,
        FontStyle.SUPERSCRIPT to SUPERSCRIPT,
        FontStyle.UNDERLINE to UNDERLINE,
        FontStyle.STRIKETHROUGH to STRIKETHROUGH
    )
}
