package com.ola.keyboard

/**
 * Finds occurrences of the app's known emoji glyphs inside arbitrary text (a
 * suggestion chip, a copied clip) so they can be re-rendered with whichever
 * Settings > Emoji Style (Twemoji/Custom) the user picked - the same styling
 * [EmojiAdapter] already applies inside the emoji picker grid itself.
 *
 * There's no reliable general-purpose way to grapheme-split arbitrary Unicode
 * into "is this an emoji" chunks without a full Unicode emoji-segmentation
 * table, so this matches against [EmojiData]'s own curated list instead - the
 * same set the picker grid already draws from - checked longest-sequence-first
 * so a multi-codepoint sequence (flags, ZWJ family groups, skin-tone modifiers)
 * is matched whole rather than split into its component codepoints.
 */
object EmojiMatcher {

    data class Match(val start: Int, val end: Int, val emoji: String)

    // Every emoji the app knows about, excluding "Recent" (just a subset of the
    // others) and "Stylish" (bracket/punctuation glyphs, not pictographic emoji -
    // matching those here would send ordinary punctuation to the Twemoji CDN).
    // Grouped by first codepoint and sorted longest-first within each group so a
    // longer sequence sharing a base codepoint is always tried before a shorter
    // prefix of it.
    private val byFirstCodePoint: Map<Int, List<String>> by lazy {
        EmojiData.emojis
            .filterKeys { it != "Recent" && it != "Stylish" }
            .values
            .flatten()
            .distinct()
            .groupBy { it.codePointAt(0) }
            .mapValues { (_, emojis) -> emojis.sortedByDescending { it.length } }
    }

    /** All known-emoji matches in [text], left to right, non-overlapping. */
    fun findEmojis(text: String): List<Match> {
        if (text.isEmpty()) return emptyList()
        val matches = mutableListOf<Match>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val hit = byFirstCodePoint[cp]?.firstOrNull { text.startsWith(it, i) }
            if (hit != null) {
                matches.add(Match(i, i + hit.length, hit))
                i += hit.length
            } else {
                i += Character.charCount(cp)
            }
        }
        return matches
    }
}
