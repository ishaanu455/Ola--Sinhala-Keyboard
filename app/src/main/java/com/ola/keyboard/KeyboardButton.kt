
package com.ola.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Gravity.CENTER
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import com.ola.keyboard.R
import android.util.TypedValue

class KeyboardButton : AppCompatTextView {
    private var isSpecial = false
    private var secondaryLabel: String? = null
    private var longPressChar: String? = null
    private val secondaryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Long-press popup support
    var longPressListener: ((String) -> Unit)? = null
    private var popup: PopupWindow? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val LONG_PRESS_DELAY_MS = 350L

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(attrs, 0)
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs, defStyleAttr)
    }

    var clickListener: (tag: String) -> Unit = { }

    private fun init(attrs: AttributeSet?, defStyleAttr: Int) {
        gravity = CENTER
        isClickable = true

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.KeyboardButton, defStyleAttr, 0)
            isSpecial = typedArray.getBoolean(R.styleable.KeyboardButton_isSpecial, false)
            typedArray.recycle()
        }

        val typedValue = TypedValue()
        context.theme.resolveAttribute(R.attr.foreground, typedValue, true)
        setTextColor(typedValue.data)

        // Bundled Sinhala/English font - main key label and the small long-press
        // hint drawn in the corner (secondaryLabelPaint is a raw Paint, not a View,
        // so it needs the typeface set directly rather than via a TextView sweep).
        typeface = AppFont.get(context)
        secondaryLabelPaint.typeface = typeface

        setOnTouchListener { view, event ->
            // What actually gets committed on long-press: longPressChar wins when set
            // (e.g. Singlish shows a Sinhala corner label but should commit a symbol),
            // otherwise fall back to the secondary label itself (English/Wijesekara).
            val longPressTarget = longPressChar ?: secondaryLabel
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    longPressTriggered = false
                    // Schedule long press only if there's something to long-press to
                    if (longPressTarget != null && longPressListener != null) {
                        longPressHandler.postDelayed({
                            longPressTriggered = true
                            showPopup(longPressTarget)
                        }, LONG_PRESS_DELAY_MS)
                    } else {
                        // Normal tap — commit immediately on down (existing behaviour)
                        val visible = text?.toString()?.takeIf { it.isNotEmpty() }
                        val rawTag = tag?.toString()?.takeIf { it.isNotEmpty() } ?: ""
                        val tagString = visible ?: convertTagToText(rawTag)
                        clickListener.invoke(tagString)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    view.isPressed = false
                    if (longPressTriggered) {
                        // Finger lifted while popup was showing → commit the long-press char
                        dismissPopup()
                        if (longPressTarget != null) {
                            longPressListener?.invoke(longPressTarget)
                        }
                    } else if (longPressTarget != null && longPressListener != null) {
                        // Short tap (released before long-press threshold) → commit primary
                        val visible = text?.toString()?.takeIf { it.isNotEmpty() }
                        val rawTag = tag?.toString()?.takeIf { it.isNotEmpty() } ?: ""
                        val tagString = visible ?: convertTagToText(rawTag)
                        clickListener.invoke(tagString)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    view.isPressed = false
                    if (longPressTriggered) {
                        dismissPopup()
                    }
                    true
                }
                else -> false
            }
        }

        if (background == null) {
            background = AppCompatResources.getDrawable(context, R.drawable.key_background)
        }

        if (isSpecial) {
            background = AppCompatResources.getDrawable(context, R.drawable.key_background_special)
        }

        secondaryLabelPaint.color = currentTextColor
        secondaryLabelPaint.alpha = 150
        secondaryLabelPaint.textAlign = Paint.Align.RIGHT
        secondaryLabelPaint.textSize = textSize * 0.5f
    }

    private fun showPopup(label: String) {
        dismissPopup() // dismiss any existing popup first

        val density = resources.displayMetrics.density

        // Build popup content view programmatically
        val tv = TextView(context).apply {
            text = label
            textSize = this@KeyboardButton.textSize * 1.4f  // bigger than key text
            typeface = AppFont.get(context)
            gravity = CENTER
            setTextColor(this@KeyboardButton.currentTextColor)
            setPadding(
                (12 * density).toInt(), (6 * density).toInt(),
                (12 * density).toInt(), (6 * density).toInt()
            )
            background = AppCompatResources.getDrawable(
                context, R.drawable.key_background_pressed
            )
        }

        val pw = PopupWindow(
            tv,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        pw.isOutsideTouchable = false
        pw.isTouchable = false  // let touches fall through to the key

        // Measure the popup so we can position it centred above the key
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = tv.measuredWidth
        val popupH = tv.measuredHeight

        val loc = IntArray(2)
        getLocationInWindow(loc)
        val xOff = loc[0] + (width - popupW) / 2
        val yOff = loc[1] - popupH - (4 * density).toInt()

        pw.showAtLocation(this, Gravity.NO_GRAVITY, xOff, yOff)
        popup = pw
    }

    private fun dismissPopup() {
        try { popup?.dismiss() } catch (_: Exception) {}
        popup = null
    }

    private fun convertTagToText(raw: String): String {
        if (raw.isEmpty()) return ""
        val digitsOnly = raw.all { it.isDigit() }
        if (digitsOnly) {
            return try {
                val code = raw.toInt()
                when (code) {
                    32 -> " "
                    else -> code.toChar().toString()
                }
            } catch (t: Throwable) {
                raw
            }
        }
        return raw
    }

    fun setSecondaryLabel(label: String?) {
        secondaryLabel = label
        invalidate()
    }

    // What gets committed on long-press. When null, falls back to secondaryLabel
    // (so English/Wijesekara keep working with just setSecondaryLabel).
    // Set this when the visible corner label should differ from the committed char
    // (e.g. Singlish shows a Sinhala corner label but long-press commits a symbol).
    fun setLongPressChar(char: String?) {
        longPressChar = char
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        secondaryLabel?.let {
            secondaryLabelPaint.color = currentTextColor
            secondaryLabelPaint.alpha = 150
            secondaryLabelPaint.textSize = textSize * 0.5f

            // Horizontal and vertical padding are separate on purpose: bumping
            // paddingX shifts the hint glyph left, away from the key's right
            // edge, without touching how far down from the top it sits
            // (paddingY). Text is right-aligned (Paint.Align.RIGHT, set in
            // init{}), so the x coordinate passed to drawText is where its
            // RIGHT edge lands - width - paddingX - and a bigger paddingX
            // pulls that right edge further from the key's actual right edge,
            // i.e. further left.
            // Proportional to the key's own textSize (not screen density).
            // textSize is already the per-device-correct font size for this
            // key (resolved from sp/dp in the layout), so tying the gap to
            // it keeps the hint's position relative to the glyph identical
            // on every device/density automatically - no density guess
            // needed, and no double-scaling like the old density-multiply
            // approach caused.
            val paddingX = textSize * 0.28f
            val paddingY = textSize * 0.2f
            canvas.drawText(it, width.toFloat() - paddingX, secondaryLabelPaint.textSize + paddingY, secondaryLabelPaint)
        }
    }
}
