package com.ola.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
import android.widget.TextView
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.target.Target

/**
 * Applies the user's chosen Settings > Emoji Style (System / Twemoji / Custom
 * font) to emoji that show up OUTSIDE the emoji picker grid - the suggestion
 * bar's word chips and the clipboard panel's clip previews. Without this, both
 * spots always drew emoji with the device's plain system glyphs no matter which
 * style was picked, so a Twemoji or custom-font choice only ever showed up in
 * the emoji grid itself (see [EmojiAdapter], which this mirrors).
 *
 * One instance is meant to live alongside the TextView it styles - a
 * suggestion chip, or a recycled clipboard row - so an in-flight Twemoji image
 * load from a previous bind can be cancelled before starting a new one.
 */
class EmojiTextStyler {

    private val pending = mutableListOf<Disposable>()

    /** Renders [text] into [textView] using [style]. Safe to call repeatedly on
     *  the same TextView (e.g. a suggestion chip being reused, or a
     *  RecyclerView rebind) - always cancels whatever this styler was still
     *  loading before starting the new bind. */
    fun bind(context: Context, textView: TextView, text: CharSequence, style: EmojiStyle) {
        cancel()
        when (style) {
            EmojiStyle.SYSTEM -> {
                textView.typeface = Typeface.DEFAULT
                textView.text = text
            }
            EmojiStyle.CUSTOM -> {
                // Falls back to the default typeface if the stored font file is
                // missing or Android can't parse its color-glyph table - same
                // fallback EmojiAdapter uses for the picker grid.
                textView.typeface = CustomFontManager.loadTypeface(context) ?: Typeface.DEFAULT
                textView.text = text
            }
            EmojiStyle.TWEMOJI -> {
                textView.typeface = Typeface.DEFAULT
                textView.text = buildTwemojiSpans(context, textView, text)
            }
        }
    }

    /** Cancels any in-flight image loads - call from onViewRecycled/onDetach so
     *  a slow load doesn't land on a view that's since been reused elsewhere. */
    fun cancel() {
        pending.forEach { it.dispose() }
        pending.clear()
    }

    private fun buildTwemojiSpans(context: Context, textView: TextView, text: CharSequence): CharSequence {
        val matches = EmojiMatcher.findEmojis(text.toString())
        if (matches.isEmpty()) return text

        val builder = SpannableStringBuilder(text)
        val sizePx = textView.textSize.toInt()
        for (match in matches) {
            val span = TwemojiSpan(sizePx)
            builder.setSpan(span, match.start, match.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val request = ImageRequest.Builder(context)
                .data(TwemojiUtil.urlFor(match.emoji))
                .target(object : Target {
                    override fun onSuccess(result: Drawable) {
                        span.drawable = result.apply { setBounds(0, 0, sizePx, sizePx) }
                        // The span's own getSize() is fixed regardless of whether the
                        // image has loaded yet, so the text never reflows - a plain
                        // redraw is enough once the drawable shows up.
                        textView.invalidate()
                    }
                    override fun onError(error: Drawable?) {
                        // Leave the system-glyph fallback the span already draws.
                    }
                })
                .build()
            pending.add(context.imageLoader.enqueue(request))
        }
        return builder
    }
}

/**
 * A single square image slot inside styled text - draws the loaded Twemoji PNG
 * once available, otherwise draws the plain emoji glyph underneath so the chip
 * never shows a blank gap while the image is still loading.
 */
private class TwemojiSpan(private val sizePx: Int) : ReplacementSpan() {
    var drawable: Drawable? = null

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        fm?.let {
            it.ascent = -sizePx
            it.descent = 0
            it.top = it.ascent
            it.bottom = it.descent
        }
        return sizePx
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val d = drawable
        if (d != null) {
            canvas.save()
            canvas.translate(x, (y - sizePx).toFloat())
            d.draw(canvas)
            canvas.restore()
        } else {
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
        }
    }
}
