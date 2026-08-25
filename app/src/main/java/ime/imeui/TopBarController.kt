package ime.imeui

import android.graphics.Color
import android.view.View
import android.widget.TextView

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
    private val olaLogoButton: View? = null
) {

    private fun applyColors(tv: TextView?) {
        if (tv == null) return
        if (darkTheme) {
            tv.setTextColor(Color.WHITE)
        } else {
            tv.setTextColor(Color.BLACK)
        }
    }

    fun showNormal() {
        suggestionContainer?.visibility = View.GONE
        emojiButton?.visibility = View.VISIBLE
        textSelectButton?.visibility = View.VISIBLE
        fontsButton?.visibility = View.VISIBLE
        settingsButton?.visibility = View.VISIBLE
        olaLogoButton?.visibility = View.VISIBLE
        // Restore the clipboard icon only if the feature is actually enabled in
        // Settings - showSuggestions() hides it unconditionally while a suggestion
        // chip is up, so coming back to "normal" must respect the user's toggle
        // rather than always forcing it visible again.
        clipboardButton?.visibility = if (isClipboardEnabled()) View.VISIBLE else View.GONE
    }

    fun showSuggestions(suggestions: List<String>, suggestionTextViews: List<TextView>, onClick: (String) -> Unit) {
        emojiButton?.visibility = View.GONE
        clipboardButton?.visibility = View.GONE
        textSelectButton?.visibility = View.GONE
        fontsButton?.visibility = View.GONE
        settingsButton?.visibility = View.GONE
        olaLogoButton?.visibility = View.GONE
        suggestionContainer?.visibility = View.VISIBLE
        for (i in suggestionTextViews.indices) {
            val tv = suggestionTextViews.getOrNull(i)
            val text = suggestions.getOrNull(i) ?: ""
            if (tv != null) {
                applyColors(tv)
                tv.text = text
                tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                tv.setOnClickListener { onClick(text) }
            }
        }
    }
}
