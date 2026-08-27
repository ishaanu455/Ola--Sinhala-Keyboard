package ime.imeui

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
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
    private val topBarIconRow: View? = null
) {

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

    fun showNormal() {
        setIconRowExpanded(true)
        suggestionContainer?.visibility = View.GONE
        emojiButton?.visibility = View.VISIBLE
        textSelectButton?.visibility = View.VISIBLE
        fontsButton?.visibility = View.VISIBLE
        settingsButton?.visibility = View.VISIBLE
        olaLogoButton?.visibility = View.VISIBLE
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
                tv.text = text
                tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                tv.setOnClickListener { onClick(text) }
            }
        }
    }
}
