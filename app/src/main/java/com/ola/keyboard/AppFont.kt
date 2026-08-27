package com.ola.keyboard

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * The keyboard's own bundled font (res/font/sinhala_sangam_mn.ttf) - used for every
 * Sinhala/English text surface in the app: the keys themselves, the suggestion bar,
 * the clipboard panel, and every Settings screen. This makes the keyboard look the
 * same on every phone regardless of what font the device's own system is set to.
 *
 * This is intentionally separate from [CustomFontManager], which is the *user's own*
 * opt-in "pick a local .ttf for Emoji Style > Custom" feature - that flow is untouched
 * and still only affects emoji rendering. Nothing here overrides it: wherever the app
 * already asks CustomFontManager for a typeface, it keeps doing exactly that.
 */
object AppFont {

    private var cachedTypeface: Typeface? = null
    private var cachedComposeFontFamily: FontFamily? = null

    /** The bundled typeface for classic (View-based) UI - keyboard keys, suggestion
     *  bar, clipboard rows, etc. Falls back to the system default if the bundled font
     *  can't be loaded for some reason, so the keyboard never breaks over this. */
    fun get(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val loaded = try {
            ResourcesCompat.getFont(context.applicationContext, R.font.sinhala_sangam_mn)
        } catch (t: Throwable) {
            null
        }
        return (loaded ?: Typeface.DEFAULT).also { cachedTypeface = it }
    }

    /** Same font, wrapped for Jetpack Compose screens (Settings, Clips manager, etc). */
    fun composeFontFamily(): FontFamily {
        cachedComposeFontFamily?.let { return it }
        return FontFamily(Font(R.font.sinhala_sangam_mn)).also { cachedComposeFontFamily = it }
    }

    /**
     * Walks [root] and every descendant, setting the bundled typeface on each TextView
     * (which also covers EditText, Button, and our own KeyboardButton, since all of
     * them extend TextView). Safe to call on a view tree more than once - e.g. after
     * re-inflating the keyboard when the user changes theme/size settings.
     *
     * Call this once on a whole screen/panel right after it's inflated. Views that are
     * created later and added dynamically (RecyclerView rows, popups) need their own
     * call at creation time - this only walks what already exists in the tree.
     */
    fun applyRecursively(root: View, context: Context = root.context) {
        val typeface = get(context)
        fun visit(view: View) {
            if (view is TextView) view.typeface = typeface
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
    }
}
