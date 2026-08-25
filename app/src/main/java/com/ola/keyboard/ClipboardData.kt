package com.ola.keyboard

import android.content.Context
import android.util.Patterns
import org.json.JSONArray
import org.json.JSONObject

/** One entry in the clipboard history. */
data class ClipItem(
    val id: Long,
    val text: String,
    val timestamp: Long,
    var pinned: Boolean = false,
    /** Bumped every time this clip is pasted (see [ClipboardData.markUsed]) - backs the
     *  "Frequently used" filter. Never touched by a plain copy. */
    val useCount: Int = 0,
    /** Set to the paste time each time this clip is pasted - backs the "Recently used"
     *  filter. 0 means it's been copied but never pasted. */
    val lastUsedTimestamp: Long = 0L
) {
    /** True if [text] is (close to) nothing but a phone number - digits/spacing/punctuation
     *  only, of plausible phone-number length. Deliberately conservative so a sentence that
     *  merely contains a number doesn't get miscategorised. */
    val isMobileNumber: Boolean
        get() {
            val t = text.trim()
            if (t.isEmpty()) return false
            val digitsOnly = t.filter { it.isDigit() }
            if (digitsOnly.length < 7 || digitsOnly.length > 15) return false
            return t.all { it.isDigit() || it in "+-() ." }
        }

    val isEmail: Boolean
        get() = Patterns.EMAIL_ADDRESS.matcher(text.trim()).matches()

    val isLink: Boolean
        get() {
            val t = text.trim()
            if (t.isEmpty()) return false
            return Patterns.WEB_URL.matcher(t).matches()
        }
}

/** Which subset/order of clip history the clipboard panel is currently showing,
 *  chosen from the "Filter clips" dropdown (btn_clip_filter). */
enum class ClipFilter {
    ALL,
    RECENT_COPY,
    RECENT_USED,
    FREQUENTLY_USED,
    MOBILE_NUMBERS,
    EMAILS,
    LINKS
}

/**
 * Stores clipboard history in SharedPreferences as a JSON array.
 *
 * Persistence rule: clips are NEVER removed on a time/duration basis. Pinned
 * clips are kept forever. Unpinned clips are only trimmed once the list grows
 * past [MAX_UNPINNED] items (oldest unpinned dropped first) so storage can't
 * grow without bound — this is a size cap, not an auto-expiry timer.
 */
object ClipboardData {

    private const val PREF_KEY = "clipboard_history"
    private const val MAX_UNPINNED = 100

    private val clips = mutableListOf<ClipItem>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        clips.clear()
        val raw = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                clips.add(
                    ClipItem(
                        id = o.getLong("id"),
                        text = o.getString("text"),
                        timestamp = o.getLong("timestamp"),
                        pinned = o.optBoolean("pinned", false),
                        useCount = o.optInt("useCount", 0),
                        lastUsedTimestamp = o.optLong("lastUsedTimestamp", 0L)
                    )
                )
            }
        } catch (t: Throwable) {
            // corrupt prefs - start clean rather than crash the IME
            clips.clear()
        }
    }

    fun all(): List<ClipItem> = clips.sortedWith(
        compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.timestamp }
    )

    /** Applies a [ClipFilter] on top of the usual pinned-first ordering. Pinned/unpinned
     *  grouping (done by ClipboardAdapter) still applies to whatever this returns - a
     *  filter narrows/reorders the list, it never bypasses pin status. */
    fun filtered(filter: ClipFilter): List<ClipItem> {
        val base = all()
        return when (filter) {
            ClipFilter.ALL -> base
            ClipFilter.RECENT_COPY -> base // `all()` is already newest-copy-first
            ClipFilter.RECENT_USED -> base
                .filter { it.lastUsedTimestamp > 0L }
                .sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.lastUsedTimestamp })
            ClipFilter.FREQUENTLY_USED -> base
                .filter { it.useCount > 0 }
                .sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.useCount })
            ClipFilter.MOBILE_NUMBERS -> base.filter { it.isMobileNumber }
            ClipFilter.EMAILS -> base.filter { it.isEmail }
            ClipFilter.LINKS -> base.filter { it.isLink }
        }
    }

    /** Add newly-copied text to the top of history. Ignores blanks and exact duplicates of the latest clip. */
    fun add(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (clips.isNotEmpty() && clips[0].text == trimmed) return

        // If this exact text already exists further down, move it to front instead of duplicating.
        val existing = clips.find { it.text == trimmed }
        if (existing != null) {
            clips.remove(existing)
            clips.add(0, existing.copy(timestamp = System.currentTimeMillis()))
        } else {
            clips.add(0, ClipItem(id = System.currentTimeMillis(), text = trimmed, timestamp = System.currentTimeMillis()))
        }

        // Size cap on unpinned clips only - pinned clips are never auto-removed.
        val unpinned = clips.filter { !it.pinned }
        if (unpinned.size > MAX_UNPINNED) {
            val toDrop = unpinned.sortedBy { it.timestamp }.take(unpinned.size - MAX_UNPINNED)
            clips.removeAll(toDrop)
        }

        save(context)
    }

    fun setPinned(context: Context, id: Long, pinned: Boolean) {
        val item = clips.find { it.id == id } ?: return
        val idx = clips.indexOf(item)
        clips[idx] = item.copy(pinned = pinned)
        save(context)
    }

    /** Records a paste of this clip - bumps [ClipItem.useCount] and refreshes
     *  [ClipItem.lastUsedTimestamp]. Backs the "Recently used" / "Frequently used"
     *  filters; a plain copy never calls this, only an actual paste does. */
    fun markUsed(context: Context, id: Long) {
        val item = clips.find { it.id == id } ?: return
        val idx = clips.indexOf(item)
        clips[idx] = item.copy(useCount = item.useCount + 1, lastUsedTimestamp = System.currentTimeMillis())
        save(context)
    }

    fun delete(context: Context, id: Long) {
        clips.removeAll { it.id == id }
        save(context)
    }

    /** Deletes several clips at once (used by the clipboard's multi-select delete flow),
     *  saving only once instead of once per item. */
    fun deleteAll(context: Context, ids: Set<Long>) {
        if (ids.isEmpty()) return
        clips.removeAll { it.id in ids }
        save(context)
    }

    /** Clears every unpinned clip, keeping pinned ones. */
    fun clearUnpinned(context: Context) {
        clips.removeAll { !it.pinned }
        save(context)
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (c in clips) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("text", c.text)
            o.put("timestamp", c.timestamp)
            o.put("pinned", c.pinned)
            o.put("useCount", c.useCount)
            o.put("lastUsedTimestamp", c.lastUsedTimestamp)
            arr.put(o)
        }
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, arr.toString()).apply()
    }
}
