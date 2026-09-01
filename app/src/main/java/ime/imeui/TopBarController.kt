package ime.imeui

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.ola.keyboard.EmojiStyle
import com.ola.keyboard.EmojiTextStyler

class TopBarController(
    private val suggestionContainer: View?,
    private val emojiButton: View?,
    private val darkTheme: Boolean = false,
    private val clipboardButton: View? = null,
    // Whether the clipboard icon should be shown at all when suggestions AREN'T
    // active - i.e. the user's Settings > clipboard toggle. Read lazily (not once at
    // construction time) since the pref can change while the keyboard stays open.
    private val isClipboardEnabled: () -> Boolean = { true },
    // The text-select ("cursor mode") icon at the far end of the top bar. Like
    // emojiButton, it has to make room for the suggestion chips while they're
    // showing, and come back once they're gone.
    private val textSelectButton: View? = null,
    // The Fonts ("Aa") icon - same deal as emojiButton/textSelectButton: it has to
    // get out of the way while suggestion chips are showing (they used to overlap it),
    // and come back once the chips are gone.
    private val fontsButton: View? = null,
    // The Settings (gear) icon at the far end of the row - same deal as the icons
    // above: needs to hide while suggestion chips are showing and come back after.
    private val settingsButton: View? = null,
    // The Ola brand mark, first icon in the row - same deal: hide while suggestion
    // chips are showing, come back after.
    private val olaLogoButton: View? = null,
    // The flexible spacer that sits right after the Ola logo (logo_spacer in
    // keyboard_layout.xml). It has its own layout_weight="1" independent of
    // olaLogoButton, so hiding the logo alone still left this spacer visible and
    // claiming a share of the top bar's width - starving the suggestion chips of
    // room and pushing them off to one side instead of letting them span the
    // whole bar. Must be hidden/restored in lockstep with olaLogoButton.
    private val logoSpacer: View? = null,
    // top_bar_icon_row itself - normally width=0dp/weight=1 so its icons right-align
    // against the flexible logo_spacer (see keyboard_layout.xml / KeyboardView). While
    // suggestion chips are showing there's nothing left in the row to right-align (every
    // icon above is hidden), so it's collapsed back to wrap_content to give the chips the
    // room instead of leaving it holding a now-pointless equal share of the line.
    private val topBarIconRow: View? = null,
    // Settings > Emoji Style - so a suggestion chip that happens to contain an
    // emoji (e.g. echoing back a word the user typed with one in it) matches the
    // same custom-font look the emoji picker grid uses, instead of
    // always falling back to the device's plain system glyph.
    private var emojiStyle: EmojiStyle = EmojiStyle.SYSTEM
) {

    // One styler per suggestion chip slot, reused across binds so a previous
    // chip's in-flight styling gets cancelled before a new word's
    // does - otherwise a slow-loading image for an old suggestion could land on
    // a chip that's since moved on to a completely different word.
    private val suggestionStylers = mutableListOf<EmojiTextStyler>()

    companion object {
        // Gboard/SwiftKey-style behavior: a long suggestion shrinks to fit its
        // fixed-width chip instead of getting cut off with "...". 18sp matches
        // the chip's original fixed size (short words look exactly as before);
        // 11sp is the smallest we'll go before it stops being comfortably
        // readable - beyond that we'd rather truncate than render illegibly.
        private const val SUGGESTION_MAX_TEXT_SIZE_SP = 18f
        private const val SUGGESTION_MIN_TEXT_SIZE_SP = 11f
        private const val SUGGESTION_TEXT_STEP_SP = 1f
    }

    private fun stylerFor(index: Int): EmojiTextStyler {
        while (suggestionStylers.size <= index) suggestionStylers.add(EmojiTextStyler())
        return suggestionStylers[index]
    }

    /** Call when Settings > Emoji Style changes while the keyboard stays open. */
    fun setEmojiStyle(style: EmojiStyle) {
        emojiStyle = style
    }

    private fun setIconRowExpanded(expanded: Boolean) {
        val row = topBarIconRow ?: return
        val params = row.layoutParams as? LinearLayout.LayoutParams ?: return
        if (expanded) {
            params.width = 0
            params.weight = 1f
        } else {
            params.width = LinearLayout.LayoutParams.WRAP_CONTENT
            params.weight = 0f
        }
        row.layoutParams = params
    }

    private fun applyColors(tv: TextView?) {
        if (tv == null) return
        if (darkTheme) {
            tv.setTextColor(Color.WHITE)
        } else {
            tv.setTextColor(Color.BLACK)
        }
    }

    // isNumericField: true while the focused field is a plain number/phone box
    // (OTP boxes, "Enter your phone number" screens). Those fields have no use
    // for word suggestions, emoji, the text-select ("cursor mode") tool, or
    // font styling, so only the Ola logo/clipboard/settings need to be there.
    // logoSpacer stays VISIBLE in both cases: it's the flex space that pushes
    // the icon group flush to the right edge of the row (see the comment on
    // logoSpacer above) while the logo itself stays pinned flush left, exactly
    // like the full icon set - same size, same positions, nothing shifts.
    fun showNormal(isNumericField: Boolean = false) {
        setIconRowExpanded(true)
        suggestionContainer?.visibility = View.GONE
        emojiButton?.visibility = if (isNumericField) View.GONE else View.VISIBLE
        textSelectButton?.visibility = if (isNumericField) View.GONE else View.VISIBLE
        olaLogoButton?.visibility = View.VISIBLE
        fontsButton?.visibility = if (isNumericField) View.GONE else View.VISIBLE
        settingsButton?.visibility = View.VISIBLE
        logoSpacer?.visibility = View.VISIBLE
        // Restore the clipboard icon only if the feature is actually enabled in
        // Settings - showSuggestions() hides it unconditionally while a suggestion
        // chip is up, so coming back to "normal" must respect the user's toggle
        // rather than always forcing it visible again.
        clipboardButton?.visibility = if (isClipboardEnabled()) View.VISIBLE else View.GONE
    }

    fun showSuggestions(suggestions: List<String>, suggestionTextViews: List<TextView>, onClick: (String) -> Unit) {
        setIconRowExpanded(false)
        emojiButton?.visibility = View.GONE
        clipboardButton?.visibility = View.GONE
        textSelectButton?.visibility = View.GONE
        fontsButton?.visibility = View.GONE
        settingsButton?.visibility = View.GONE
        olaLogoButton?.visibility = View.GONE
        logoSpacer?.visibility = View.GONE
        suggestionContainer?.visibility = View.VISIBLE
        for (i in suggestionTextViews.indices) {
            val tv = suggestionTextViews.getOrNull(i)
            val text = suggestions.getOrNull(i) ?: ""
            if (tv != null) {
                applyColors(tv)
                stylerFor(i).bind(tv.context, tv, text, emojiStyle)
                // Shrink long words to fit their fixed-width chip instead of
                // ellipsizing them - see SUGGESTION_MIN/MAX_TEXT_SIZE_SP above.
                tv.maxLines = 1
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    tv,
                    SUGGESTION_MIN_TEXT_SIZE_SP.toInt(),
                    SUGGESTION_MAX_TEXT_SIZE_SP.toInt(),
                    SUGGESTION_TEXT_STEP_SP.toInt(),
                    TypedValue.COMPLEX_UNIT_SP
                )
                tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                tv.setOnClickListener { onClick(text) }
            }
        }
    }
}
