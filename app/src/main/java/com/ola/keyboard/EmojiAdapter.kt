package com.ola.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.target.Target

class EmojiAdapter(
    private val context: Context,
    private val clickListener: KeyboardView.ClickListener,
    private val darkTheme: Boolean,
    emojis: List<String>,
    private val textSize: Int,
    private val emojiStyle: EmojiStyle = EmojiStyle.SYSTEM
) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

    companion object {
        // How much smaller than the "requested" size to actually render color-emoji
        // glyphs, to leave headroom for artwork overshoot past its own font-metrics
        // box (see glyphTextSizePx() below). 0.85 = 15% shrink; tune here if some
        // devices' emoji sets still clip at the edges, or come in with visible
        // unused margin once this is confirmed fixed.
        private const val EMOJI_OVERSHOOT_SAFETY = 0.85f
    }

    // SYSTEM mode draws the raw emoji character straight from the device's own font -
    // there's no bundled artwork to fall back on. Some entries in EmojiData (e.g. newer
    // additions like the "smiling face with tear") are only a few Unicode versions old,
    // and plenty of phones still ship an emoji font from before that glyph existed. On
    // those devices the TextView doesn't fail or leave a blank cell - it renders the
    // font's "tofu" placeholder (a small boxed x), which is what showed up as a broken
    // square in the middle of an otherwise normal-looking Smileys page. Paint.hasGlyph
    // (API 23+, and this app's minSdk is already above that) can check per-emoji whether
    // the active font actually has a drawable glyph for it, so unsupported ones are
    // filtered out before they ever reach the adapter's data set - a slightly smaller
    // grid instead of a broken box for whoever's on an older emoji font.
    // hasGlyph needs API 23; this app's minSdk is 21, so guard it - on the handful of
    // API 21/22 devices left, this just no-ops back to the old (unfiltered) behavior
    // instead of crashing.
    private val probePaint: Paint? =
        if (emojiStyle == EmojiStyle.SYSTEM && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            Paint().apply { typeface = Typeface.DEFAULT }
        else null

    private fun supported(emoji: String): Boolean = probePaint?.hasGlyph(emoji) ?: true

    private val items = ArrayList<String>(emojis.filter(::supported))

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /** The emoji grid is a fixed 8-column GridLayoutManager (see KeyboardView), so every
     *  cell is always exactly (grid width) / 8 wide - GridLayoutManager forces that exact
     *  width on its children regardless of what their own layoutParams ask for. Sizing the
     *  glyph in SP used to let it grow past that fixed cell width whenever the device's
     *  system Font size / Display size setting was above 1x (this varies a lot by phone
     *  brand even at their own "default"), which is what clipped the emoji's sides -
     *  differently on different phones, since it depends on a setting this app never reads.
     *  Sizing off density only (like dp(), not scaledDensity) removes that source of
     *  variance entirely, and clamping against the actual computed cell width below is a
     *  second, device-independent safety net so a large "Text Size" preference value can't
     *  overflow the cell either, on any screen width.
     *
     *  That clamp alone still wasn't enough, though: it only protects against the
     *  requested *font-metrics* box being wider than the cell. Colour emoji artwork
     *  routinely paints wider than its own font-metrics box - the same overshoot this
     *  file already compensates for vertically in RECENT_EMOJI_ROW_VERTICAL_PADDING_DP's
     *  comment above - and at the default Text Size the requested box is comfortably
     *  under the cell width, so the minOf() clamp below never even engages; the artwork
     *  still visibly touches/crosses the cell edge on many devices' emoji sets. Scaling
     *  the requested size down by EMOJI_OVERSHOOT_SAFETY first (not just clamping)
     *  reserves that overshoot headroom unconditionally, regardless of which branch of
     *  minOf() ends up chosen.
     *
     *  "Grid width" here isn't the full screen width - emoji_grid itself has
     *  android:padding="4dp" (emoji_layout.xml) on each side, and the keyboard root has
     *  android:paddingStart/End="2dp" (keyboard_layout.xml) outside that, so the real
     *  content width available to the 8 columns is (screen width - 2*(4dp+2dp)) - both are
     *  subtracted below so the clamp isn't a few px too generous and still lets the glyph's
     *  edge touch the cell boundary. */
    private fun glyphTextSizePx(): Float {
        val requestedPx = dp(textSize.toFloat()).toFloat() * EMOJI_OVERSHOOT_SAFETY
        val outerPaddingPx = dp(4f + 2f) * 2 // emoji_grid's 4dp + keyboard root's 2dp, both sides
        val cellWidthPx = (context.resources.displayMetrics.widthPixels - outerPaddingPx) / 8f
        val maxGlyphPx = cellWidthPx - dp(8f) * 2 // leaves room for the pad(8dp) used on each side below
        return if (maxGlyphPx > 0f) minOf(requestedPx, maxGlyphPx) else requestedPx
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        val pad = dp(8f)
        val glyphPx = glyphTextSizePx()

        if (emojiStyle == EmojiStyle.SYSTEM || emojiStyle == EmojiStyle.CUSTOM || emojiStyle == EmojiStyle.BUNDLED) {
            val tv = TextView(context)
            tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tv.gravity = Gravity.CENTER
            tv.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, glyphPx)
            tv.setPadding(pad, pad, pad, pad)
            tv.includeFontPadding = false
            tv.setTextColor(if (darkTheme) Color.WHITE else Color.BLACK)
            if (emojiStyle == EmojiStyle.CUSTOM) {
                // Falls back to the default system typeface (still fine for emoji) if the
                // stored file is missing or Android can't parse its color-glyph table.
                CustomFontManager.loadTypeface(context)?.let { tv.typeface = it }
            } else if (emojiStyle == EmojiStyle.BUNDLED) {
                // Same fallback reasoning, but for one of the app's own bundled packs
                // (assets/fonts/) instead of a file the user picked themselves.
                BundledEmojiFonts.loadSelectedTypeface(context)?.let { tv.typeface = it }
            }
            return EmojiViewHolder(tv, null, null)
        }

        // Twemoji mode: stack a system-emoji TextView (always present, used as an instant
        // fallback) underneath an ImageView that shows the downloaded Twemoji artwork once
        // it's loaded. If the image fails (e.g. no network and not yet cached), the system
        // glyph underneath just stays visible instead of leaving a blank cell.
        val glyphSize = glyphPx.toInt() + dp(4f)
        val cellSize = glyphSize + pad * 2

        val frame = FrameLayout(context)
        frame.layoutParams = RecyclerView.LayoutParams(cellSize, cellSize)
        frame.setPadding(pad, pad, pad, pad)

        val fallbackText = TextView(context)
        fallbackText.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        fallbackText.gravity = Gravity.CENTER
        fallbackText.textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        fallbackText.setTextSize(TypedValue.COMPLEX_UNIT_PX, glyphPx)
        fallbackText.includeFontPadding = false
        fallbackText.setTextColor(if (darkTheme) Color.WHITE else Color.BLACK)
        frame.addView(fallbackText)

        val image = ImageView(context)
        image.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.visibility = View.INVISIBLE
        frame.addView(image)

        return EmojiViewHolder(null, fallbackText, image)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        val emoji = items[position]

        if (emojiStyle == EmojiStyle.SYSTEM || emojiStyle == EmojiStyle.CUSTOM || emojiStyle == EmojiStyle.BUNDLED) {
            holder.textView?.text = emoji
            holder.textView?.setOnClickListener { clickListener.emojiClick(emoji) }
            return
        }

        holder.fallbackText?.text = emoji
        holder.imageView?.visibility = View.INVISIBLE
        holder.imageView?.setImageDrawable(null)
        // Cancel any still-in-flight load from a previous (recycled) binding of this view.
        holder.currentDisposable?.dispose()

        val request = ImageRequest.Builder(context)
            .data(TwemojiUtil.urlFor(emoji))
            .target(object : Target {
                override fun onStart(placeholder: Drawable?) {}

                override fun onSuccess(result: Drawable) {
                    holder.imageView?.setImageDrawable(result)
                    holder.imageView?.visibility = View.VISIBLE
                }

                override fun onError(error: Drawable?) {
                    // Leave the system-glyph fallback (already showing) visible.
                }
            })
            .build()
        holder.currentDisposable = context.imageLoader.enqueue(request)

        holder.itemView.setOnClickListener { clickListener.emojiClick(emoji) }
    }

    override fun onViewRecycled(holder: EmojiViewHolder) {
        super.onViewRecycled(holder)
        holder.currentDisposable?.dispose()
        holder.currentDisposable = null
    }

    override fun getItemCount(): Int = items.size

    fun updateEmojis(newEmojis: List<String>) {
        items.clear()
        items.addAll(newEmojis.filter(::supported))
        notifyDataSetChanged()
    }

    class EmojiViewHolder(
        val textView: TextView?,
        val fallbackText: TextView?,
        val imageView: ImageView?
    ) : RecyclerView.ViewHolder(textView ?: (fallbackText!!.parent as View)) {
        var currentDisposable: Disposable? = null
    }
}
