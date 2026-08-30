package com.ola.keyboard

import android.content.Context
import android.widget.TextView

/**
 * Applies the user's chosen Settings > Emoji Style (System / Custom font) to
 * emoji that show up OUTSIDE the emoji picker grid - the suggestion bar's word
 * chips and the clipboard panel's clip previews. Mirrors [EmojiAdapter].
 *
 * NOTE: The user's custom emoji font (EmojiStyle.CUSTOM) is intentionally
 * NOT applied here for the clipboard or suggestion bar. It is only applied in
 * the emoji picker tabs and the recent-emoji row (see [EmojiAdapter]).
 * The clipboard and suggestion bar always use the bundled app font (AppFont),
 * regardless of which emoji style is active.
 */
class EmojiTextStyler {

    /** Renders [text] into [textView] using [style]. Safe to call repeatedly on
     *  the same TextView (e.g. a suggestion chip being reused, or a
     *  RecyclerView rebind). */
    fun bind(context: Context, textView: TextView, text: CharSequence, style: EmojiStyle) {
        cancel()
        // Every style renders the same way outside the picker grid - only the
        // bundled AppFont is ever used here, regardless of which emoji style
        // is active (see class doc above).
        textView.typeface = AppFont.get(context)
        textView.text = text
    }

    /** No-op now that there's no async image load to cancel; kept so existing
     *  call sites (onViewRecycled/onDetach) don't need to change. */
    fun cancel() {}
}
