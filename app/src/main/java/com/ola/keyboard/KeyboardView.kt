package com.ola.keyboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext
import com.ola.keyboard.R
import com.ola.keyboard.databinding.KeyboardLayoutBinding
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyboardView(
    context: Context,
    private val clickListener: ClickListener,
    private val swipeListener: SwipeListener,
    private val rowHeight: Int,
    private val darkTheme: Boolean,
    private val keyBorders: Boolean,
    private val swipeToErase: Boolean,
    private val swipeToMoveCursor: Boolean,
    private val textSize: Int,
    private var showRecentEmojiRow: Boolean = false,
    private var showNumberRow: Boolean = true,
    private val emojiStyle: EmojiStyle = EmojiStyle.SYSTEM,
    private var clipboardEnabled: Boolean = true,
    private var initialFontStyle: FontStyle = FontStyle.NONE,
    private val colorTheme: String = "ola",
    // --- Custom background image (Step 6 - see CustomBackgroundManager,
    // CustomBackgroundAdjustScreen, and CustomBackgroundPreviewBox, whose
    // exact cover-scale/pan/blur/darken math applyCustomImageToKeyboard()
    // below reproduces for this real, non-Compose keyboard surface). Mirrors
    // the same primitives-in/Prefs-read-by-the-caller pattern [colorTheme]
    // above already uses, rather than this class reading Prefs itself. ---
    private val backgroundMode: String = "theme",
    private val customBgOffsetX: Float = 0.5f,
    private val customBgOffsetY: Float = 0.5f,
    private val customBgBlur: Float = 0f,
    private val customBgDarken: Float = 0.25f,
    private val customBgZoom: Float = 1f
) : LinearLayout(context) {

    companion object {
        // The number row (1-9,0) reads fine a bit shorter than the letter rows -
        // keeping it as a ratio of rowHeight (instead of forcing it identical)
        // means that relationship stays the same no matter what the user's
        // Settings > row height slider is set to.
        private const val NUM_ROW_HEIGHT_RATIO = 0.86f

        // Extra vertical room added on top of the emoji glyph's own font-metrics
        // height, mirroring EmojiAdapter's cell padding (8dp top + 8dp bottom) plus
        // a small buffer for how much color-emoji glyphs can visually overshoot
        // their font metrics box on some devices. Without this buffer the row was
        // sized purely off the sp value and glyphs got clipped top/bottom.
        private const val RECENT_EMOJI_ROW_VERTICAL_PADDING_DP = 24f

        // The Settings > "Keyboard Height" slider (key: "height_percentage", range
        // 70-190, default 100) is a PERCENTAGE, not a dp value - the pref name says
        // so, and the layout XML's own default row height is 48dp. So "100" must
        // mean "100% of 48dp", i.e. 48dp. This is the baseline that percentage is
        // applied against.
        private const val BASE_ROW_HEIGHT_DP = 48f

        // text_select_layout.xml's cursor-cross column uses fixed (not weighted) dp
        // sizes: 56dp title bar + (68dp up-chevron + 24dp margin + 68dp mid row +
        // 24dp margin + 68dp down-chevron = 252dp cross) + 56dp bottom bar = 364dp.
        // Before the row-height fix above, rowHeightPx worked out to roughly double
        // BASE_ROW_HEIGHT_DP even at the slider's default, so the panel height derived
        // from it (see applyPanelHeights) was comfortably over this floor and nobody
        // noticed the panel's own content was fixed-size. Now that the default panel
        // height is correctly smaller, it can fall under what this fixed content
        // actually needs, and a LinearLayout doesn't shrink non-weighted children to
        // fit - it just centers-and-clips them, which is what was chopping the up/down
        // chevrons down to slivers. Flooring the panel height at this fixed minimum
        // keeps the cross fully visible regardless of the height slider. If the fixed
        // dp values in text_select_layout.xml change, update this to match.
        private const val TEXT_SELECT_MIN_CONTENT_HEIGHT_DP = 364f
    }

    /** Height to use for the number row given the current base [rowHeight]. */
    private fun numRowHeight(baseRowHeight: Int): Int = (baseRowHeight * NUM_ROW_HEIGHT_RATIO).toInt()

    /**
     * The recent-emoji quick strip used to be tied to rowHeight, same as the letter
     * rows. That meant the Settings > keyboard-height slider stretched or squeezed
     * the empty space around the emoji glyphs: turned up, a big gap opened between
     * the emoji row and the number row below it; turned down, the row got too short
     * and the emojis got visually clipped/merged into the number row.
     *
     * Instead this is sized off the actual emoji glyph size ([textSize], the same
     * Settings value EmojiAdapter renders emojis at) so it always fits the glyphs
     * fully with consistent breathing room, independent of the keyboard-height
     * slider entirely.
     */
    private fun recentEmojiRowHeightPx(): Int {
        val glyphHeightPx =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat(), context.resources.displayMetrics)
        return (glyphHeightPx + dp(RECENT_EMOJI_ROW_VERTICAL_PADDING_DP)).toInt()
    }

    /** dp -> px, using this view's density (same pattern as EmojiAdapter's helper). */
    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /** [rowHeight] is the Settings > Keyboard Height slider value - a PERCENTAGE
     *  (70-190, default 100) of [BASE_ROW_HEIGHT_DP], not a dp value on its own.
     *  It was previously being passed straight into dp() as if "100" meant "100dp",
     *  so on first install (before anyone touches the slider) every row rendered at
     *  ~100dp instead of the intended 48dp - roughly double height, which is why the
     *  keyboard opened taking up more than half the screen. Scaling the 48dp baseline
     *  by rowHeight/100 first, then converting through density, makes "100" mean
     *  "100% of the normal 48dp row" as the pref name and default imply, and it's
     *  still device-independent since dp() still goes through density last. */
    private var rowHeightPx: Int = dp(BASE_ROW_HEIGHT_DP * rowHeight / 100f)

    interface ClickListener {
        fun letterOrSymbolClick(tag: String)
        fun emojiClick(tag: String)
        fun numberClick(tag: String)
        fun functionClick(type: Function)
        fun specialClick(tag: String)
        fun longPressSecondaryClick(char: String)
        fun clipboardPasteClick(text: String)
        fun clipboardPinClick(item: ClipItem)
        fun clipboardShareClick(item: ClipItem)
        fun clipboardDeleteClick(item: ClipItem)
        fun clipboardDeleteSelectedClick(ids: Set<Long>)
        // New text-select panel actions. Cut/copy/paste/selectAll are dispatched
        // via performContextMenuAction on the target field (the same mechanism the
        // system's own text-selection handles use), and cursor movement goes
        // through textSelectMove so word-jump and shift-to-extend share one path.
        fun textSelectCutClick()
        fun textSelectCopyClick()
        fun textSelectPasteClick()
        fun textSelectAllClick()
        fun textSelectMove(direction: TextSelectDirection, extend: Boolean, byWord: Boolean = false)
        fun fontStyleSelected(style: FontStyle)
    }

    interface SwipeListener {
        fun eraseDo()
        fun eraseUndo()
        fun eraseDone()
        fun moveRight()
        fun moveLeft()
    }

    private var lastBackspaceDownTime = 0L

    var keyboardVisible = false

    // True while the full emoji picker panel (with its own Recent/Smileys/... tabs) is open.
    private var isEmojiPanelOpen = false

    // True while the clipboard history panel is open.
    private var isClipboardPanelOpen = false
    private var closeClipboardPanelFn: (() -> Unit)? = null
    private var closeEmojiPanelFn: (() -> Unit)? = null
    // Resets the emoji panel back to its defaults (Recent tab selected, recent-emoji
    // strip scrolled to the start) - invoked when the keyboard is fully hidden/reopened
    // so the user doesn't land back on whatever category or scroll position they left
    // it on last time. See resetEmojiPanelState().
    private var resetEmojiPanelStateFn: (() -> Unit)? = null
    private var isTextSelectPanelOpen = false
    private var closeTextSelectPanelFn: (() -> Unit)? = null
    // True while the Fonts ("fancy text" style picker) panel is open.
    private var isFontStylePanelOpen = false
    private var closeFontStylePanelFn: (() -> Unit)? = null
    private lateinit var fontStyleAdapter: FontStyleAdapter
    // The "fancy text" style currently applied to freshly-typed Latin text. Read by
    // InputMethodService via [currentFontStyle] so it knows what to pass into
    // FontStyleData.convert() on every commit.
    private var activeFontStyle: FontStyle = FontStyle.NONE
    // Whether the cursor cluster is currently extending a selection (the
    // "Select" toggle in the middle of the cluster) rather than just moving
    // the cursor. Purely UI state - InputMethodService reads it as a param on
    // every move call, it doesn't need to track it itself.
    private var isTextSelecting = false

    private lateinit var binding: KeyboardLayoutBinding

    val viewBlank1: View get() = binding.blank1
    val viewBlank2: View get() = binding.blank2
    val buttonColon: KeyboardButton get() = binding.colonWijesekara
    val buttonActionShift: ImageView get() = binding.shift

    val buttonSpecialComma: KeyboardButton get() = binding.comma
    val buttonSpecialCommaWijesekara: KeyboardButton get() = binding.commaWijesekara
    val buttonActionAction: ImageView get() = binding.action

    private val backspaceRepeater = flow<Unit> {
        while (true) {
            val currentTimeMillis = System.currentTimeMillis()
            val timeSinceLastDown = currentTimeMillis - lastBackspaceDownTime
            delay(
                when {
                    // Repeat speed curve — tuned to feel like Gboard:
                    //   initial delay 300 ms (was 500) so first repeat fires sooner,
                    //   ramps up to max speed after ~2 s of holding.
                    timeSinceLastDown > 5000 -> 20L
                    timeSinceLastDown > 4000 -> 24L
                    timeSinceLastDown > 3000 -> 32L
                    timeSinceLastDown > 2000 -> 48L
                    timeSinceLastDown > 1000 -> 72L
                    timeSinceLastDown > 500  -> 110L
                    timeSinceLastDown > 300  -> 200L
                    else                     -> 300L
                }
            )
            // clickListener.functionClick() touches the InputConnection and the
            // vibrator, both of which must only be driven from the main thread. This
            // coroutine's delay-loop runs on backspaceScope (IO), so the actual click
            // is hopped onto Main here rather than fired straight from the IO thread -
            // that mismatch was the root cause of backspace feeling laggy/unresponsive
            // the longer it was held (deletes silently failing under the hood).
            withContext(Dispatchers.Main) {
                clickListener.functionClick(Function.BACKSPACE)
            }
        }
    }
    private lateinit var backspaceRepeaterJob: Job
    // Single reused scope for backspace repeater — avoids creating a new scope on every press
    private val backspaceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var recentEmojiAdapter: EmojiAdapter
    private lateinit var clipboardAdapter: ClipboardAdapter

    /** Which clip subset btn_clip_filter currently has selected - reset to ALL whenever
     *  the clipboard panel closes so it always reopens unfiltered. */
    private var currentClipFilter: ClipFilter = ClipFilter.ALL
    private var clipFilterPopup: PopupWindow? = null
    /** Set once in init{} - the themed context (light/dark + border variant) every
     *  panel is inflated with. Kept around so views built later, like the clip-filter
     *  dropdown, pick up the same theme attrs (?attr/clipCard etc.) instead of the
     *  IME service's own untouched context. */
    private lateinit var themedContext: Context

    private var swipeStepStartX: Float = 0F
    private val swipeStepDistance: Float = resources.displayMetrics.widthPixels / 15f
    private var startIgnoreSwipe = false
    private var currentSwipeActionType = SwipeActionType.NONE
    // Set on ACTION_DOWN: true if the gesture started inside the recent-emoji strip,
    // so swipe-to-erase/cursor never hijacks scrolling that row.
    private var touchStartedInRecentEmojiRow = false
    private val recentEmojiRowScreenLoc = IntArray(2)

    private enum class SwipeActionType { ERASE, MOVE_CURSOR, NONE }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (swipeToErase || swipeToMoveCursor) {
            if (ev != null && ev.pointerCount > 1) startIgnoreSwipe = true
            when (ev?.action) {
                MotionEvent.ACTION_DOWN -> {
                    currentSwipeActionType = SwipeActionType.NONE
                    swipeStepStartX = ev.x
                    touchStartedInRecentEmojiRow = isTouchInRecentEmojiRow(ev)
                }

                MotionEvent.ACTION_MOVE -> {
                    // Swipe-to-erase/cursor is a typing-row gesture and makes no
                    // sense (and must not compete with a panel's own gestures -
                    // e.g. the emoji grid's left/right category swipe) whenever
                    // keyboardRows is hidden in favour of the emoji/clipboard/
                    // text-select panel. Guarding on keyboardRows' own visibility
                    // (rather than a single row's bounds like touchStartedInRecentEmojiRow)
                    // covers the whole panel area, not just part of it.
                    val panelShowing = ::binding.isInitialized &&
                        binding.keyboardRows.visibility != View.VISIBLE
                    if (!startIgnoreSwipe && !touchStartedInRecentEmojiRow && !panelShowing) {
                        val distanceFromDownX: Float = swipeStepStartX - ev.x

                        if (swipeToErase && ev.y < rowHeightPx * 4 && distanceFromDownX > swipeStepDistance)
                            currentSwipeActionType = SwipeActionType.ERASE
                        else if (swipeToMoveCursor && ev.y >= rowHeightPx * 4 && (distanceFromDownX > swipeStepDistance || distanceFromDownX < -swipeStepDistance))
                            currentSwipeActionType = SwipeActionType.MOVE_CURSOR

                        return currentSwipeActionType != SwipeActionType.NONE
                    }
                }

                MotionEvent.ACTION_UP -> if (ev.pointerCount == 1) {
                    startIgnoreSwipe = false
                    touchStartedInRecentEmojiRow = false
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    /** Uses screen-absolute coordinates so this stays correct regardless of how
     *  deeply recentEmojiRow is nested inside this view's layout hierarchy. */
    private fun isTouchInRecentEmojiRow(ev: MotionEvent): Boolean {
        if (!::binding.isInitialized) return false
        val row = binding.recentEmojiRow
        if (row.visibility != View.VISIBLE) return false
        row.getLocationOnScreen(recentEmojiRowScreenLoc)
        val left = recentEmojiRowScreenLoc[0]
        val top = recentEmojiRowScreenLoc[1]
        val right = left + row.width
        val bottom = top + row.height
        return ev.rawX >= left && ev.rawX <= right && ev.rawY >= top && ev.rawY <= bottom
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_MOVE -> {
                val swipeDistance = swipeStepStartX - event.x
                if (swipeDistance > swipeStepDistance) {
                    when (currentSwipeActionType) {
                        SwipeActionType.ERASE -> swipeListener.eraseDo()
                        SwipeActionType.MOVE_CURSOR -> swipeListener.moveLeft()
                        SwipeActionType.NONE -> {}
                    }
                    swipeStepStartX = event.x
                    return true // Swipe handled
                } else if (swipeDistance < -swipeStepDistance) {
                    when (currentSwipeActionType) {
                        SwipeActionType.ERASE -> swipeListener.eraseUndo()
                        SwipeActionType.MOVE_CURSOR -> swipeListener.moveRight()
                        SwipeActionType.NONE -> {}
                    }
                    swipeStepStartX = event.x
                    return true // Swipe handled
                }

            }

            MotionEvent.ACTION_UP -> {
                if (currentSwipeActionType != SwipeActionType.NONE) {
                    swipeListener.eraseDone()
                    if (event.pointerCount == 1) startIgnoreSwipe = false
                    return true // Consume the ACTION_UP that ends a swipe
                }

            }
        }

        return false
    }

    init {
        val style = when {
            !darkTheme && keyBorders -> R.style.Light
            !darkTheme && !keyBorders -> R.style.LightNoBorder
            darkTheme && !keyBorders -> R.style.NightNoBorder
            else -> R.style.Night
        }

        // "ola" (the brand default) needs no overlay - the base styles above
        // already use accent_amber. Any other colour theme layers a second
        // ContextThemeWrapper on top that retints the whole key bed (background,
        // key surfaces, pressed states, border, accent) to that theme's hue -
        // split into Light/Dark variants since key surface colors differ
        // between the two, matching whichever the current dark-theme setting is.
        // Gradient theme ids (e.g. "wine_gradient", picked from Settings > Appearance's
        // "Gradient Color Themes" row) are a Compose-preview-only visual for now - the
        // native keyboard here still renders a flat accent overlay, not a true gradient
        // drawable. Stripping the suffix means a gradient pick still shows the right
        // *hue* on the actual keyboard (e.g. wine, not the "ola" default) instead of
        // silently falling through to `else -> null` below just because the exact string
        // doesn't match any case.
        val baseColorTheme = colorTheme.removeSuffix("_gradient")
        val accentOverlayStyle = when (baseColorTheme) {
            "wine" -> if (darkTheme) R.style.AccentWineDark else R.style.AccentWineLight
            "slate" -> if (darkTheme) R.style.AccentSlateDark else R.style.AccentSlateLight
            "ocean" -> if (darkTheme) R.style.AccentOceanDark else R.style.AccentOceanLight
            "forest" -> if (darkTheme) R.style.AccentForestDark else R.style.AccentForestLight
            "onyx" -> if (darkTheme) R.style.AccentOnyxDark else R.style.AccentOnyxLight
            "navy" -> if (darkTheme) R.style.AccentNavyDark else R.style.AccentNavyLight
            else -> null
        }

        val baseThemeWrapper = ContextThemeWrapper(context, style)
        val contextThemeWrapper = if (accentOverlayStyle != null) {
            ContextThemeWrapper(baseThemeWrapper, accentOverlayStyle)
        } else {
            baseThemeWrapper
        }
        themedContext = contextThemeWrapper

        try {
            binding =
                KeyboardLayoutBinding.inflate(LayoutInflater.from(contextThemeWrapper), this, true)
        } catch (t: Throwable) {
            Log.e("KeyboardView", "Themed inflation failed, falling back to default inflater", t)
            try {

                val root =
                    LayoutInflater.from(context).inflate(R.layout.keyboard_layout, this, true)
                binding = KeyboardLayoutBinding.bind(root)
            } catch (fallbackT: Throwable) {

                Log.e(
                    "KeyboardView",
                    "FATAL: Default inflation also failed. The layout XML is likely invalid.",
                    fallbackT
                )
                throw RuntimeException("Failed to inflate keyboard layout.", fallbackT)
            }
        }

        // *_gradient theme ids only got a flat accentOverlayStyle above (XML
        // styles/colors can't express a 2-stop gradient) - layer the real gradient
        // onto the Space/Enter keys now that binding exists. Safe to call
        // unconditionally: it's a no-op for every non-gradient colorTheme.
        applyGradientToKeyboard()

        // Step 6: the real-keyboard counterpart of Step 5's Settings preview -
        // no-op whenever backgroundMode isn't "custom_image" (including "no
        // photo ever saved"), same as applyGradientToKeyboard() above being a
        // no-op for non-gradient themes, so safe to call unconditionally too.
        applyCustomImageToKeyboard()

        // Bundled Sinhala/English font, applied everywhere in the keyboard view tree
        // in one sweep - keys, suggestion bar, clipboard/emoji/text-select/font-style
        // panels (all included inside keyboard_layout.xml). Anything added later
        // dynamically (RecyclerView rows, popups) gets the font at creation time
        // instead - see ClipboardAdapter, FontStyleAdapter, and showClipFilterMenu().
        AppFont.applyRecursively(this, context)

        try {
            // Number row: hide entirely when the user has toggled it off in Settings
            binding.keyRow1.visibility = if (showNumberRow) View.VISIBLE else View.GONE

            // Recently-used emoji quick row (horizontal strip above the keys)
            recentEmojiAdapter = EmojiAdapter(
                contextThemeWrapper,
                clickListener,
                darkTheme,
                EmojiData.emojis["Recent"] ?: emptyList(),
                textSize,
                emojiStyle
            )
            binding.recentEmojiRow.layoutManager =
                LinearLayoutManager(contextThemeWrapper, LinearLayoutManager.HORIZONTAL, false)
            binding.recentEmojiRow.adapter = recentEmojiAdapter
            binding.recentEmojiRow.layoutParams.height = recentEmojiRowHeightPx()
            updateRecentEmojiRowVisibility()

            binding.keyRow1.layoutParams.height = numRowHeight(rowHeightPx)
            binding.keyRow2.layoutParams.height = rowHeightPx
            binding.keyRow3.layoutParams.height = rowHeightPx
            binding.keyRow4.layoutParams.height = rowHeightPx
            binding.keyRow5.layoutParams.height = rowHeightPx

            for (row in binding.keyboardRows.children)
                if (row is LinearLayout)
                    for (button in row.children)
                        if (button is KeyboardButton)
                            button.textSize = textSize.toFloat()

            // Calculate padding to ensure icon size scales with text size
            val density = resources.displayMetrics.density
            // Use fitCenter to allow icons to scale UP if padding is small
            binding.emojiView.btnBackspace.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.emojiView.btnAbc.scaleType = ImageView.ScaleType.FIT_CENTER

            // Target icon size based on text size (roughly matching text height)
            val targetIconSize = textSize * density

            val padding = max(0, ((rowHeightPx - targetIconSize) / 2).toInt())

            binding.emojiView.btnBackspace.setPadding(padding, padding, padding, padding)
            binding.emojiView.btnAbc.setPadding(padding, padding, padding, padding)


            binding.n0.clickListener = { clickListener.numberClick(it) }
            binding.n1.clickListener = { clickListener.numberClick(it) }
            binding.n2.clickListener = { clickListener.numberClick(it) }
            binding.n3.clickListener = { clickListener.numberClick(it) }
            binding.n4.clickListener = { clickListener.numberClick(it) }
            binding.n5.clickListener = { clickListener.numberClick(it) }
            binding.n6.clickListener = { clickListener.numberClick(it) }
            binding.n7.clickListener = { clickListener.numberClick(it) }
            binding.n8.clickListener = { clickListener.numberClick(it) }
            binding.n9.clickListener = { clickListener.numberClick(it) }

            binding.lA.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lB.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lC.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lD.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lE.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lF.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lG.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lH.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lI.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lJ.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lK.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lL.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lM.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lN.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lO.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lP.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lQ.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lR.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lS.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lT.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lU.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lV.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lW.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lX.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lY.clickListener = { clickListener.letterOrSymbolClick(it) }
            binding.lZ.clickListener = { clickListener.letterOrSymbolClick(it) }

            // Wire long-press secondary char listeners for all letter keys
            val letterButtons = listOf(
                binding.lA, binding.lB, binding.lC, binding.lD, binding.lE,
                binding.lF, binding.lG, binding.lH, binding.lI, binding.lJ,
                binding.lK, binding.lL, binding.lM, binding.lN, binding.lO,
                binding.lP, binding.lQ, binding.lR, binding.lS, binding.lT,
                binding.lU, binding.lV, binding.lW, binding.lX, binding.lY,
                binding.lZ
            )
            for (btn in letterButtons) {
                btn.longPressListener = { clickListener.longPressSecondaryClick(it) }
            }

            binding.symbol1.clickListener = { clickListener.letterOrSymbolClick(it) }

            binding.colonWijesekara.clickListener = { clickListener.specialClick(it) }

            binding.comma.clickListener = { clickListener.specialClick(it) }
            binding.commaWijesekara.clickListener = { clickListener.specialClick(it) }
            binding.dot.clickListener = { clickListener.specialClick(it) }


            if (binding.space.tag == null || binding.space.tag.toString().isEmpty()) {
                binding.space.tag = " "
            }
            binding.space.setOnClickListener { v ->
                val tagStr = (v.tag as? String)?.takeIf { it.isNotEmpty() } ?: " "
                clickListener.specialClick(tagStr)
            }
            binding.space.setOnLongClickListener {
                clickListener.functionClick(Function.IME)
                true
            }

            binding.lang.setOnClickListener { clickListener.functionClick(Function.LANG) }
            binding.panel.clickListener = { clickListener.functionClick(Function.PANEL) }

            val fastTouchListener = View.OnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        when (v.id) {
                            R.id.action -> clickListener.functionClick(Function.ACTION)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        true
                    }

                    else -> false
                }
            }

            binding.shift.setOnClickListener { clickListener.functionClick(Function.SHIFT) }
            binding.action.setOnTouchListener(fastTouchListener)

            val backspaceTouchListener = View.OnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = AppCompatResources.getDrawable(
                            contextThemeWrapper,
                            R.drawable.key_background_pressed
                        )
                        clickListener.functionClick(Function.BACKSPACE)
                        lastBackspaceDownTime = System.currentTimeMillis()
                        v.performClick()
                        backspaceRepeaterJob =
                            backspaceRepeater.launchIn(backspaceScope)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = AppCompatResources.getDrawable(
                            contextThemeWrapper,
                            R.drawable.key_background
                        )
                        backspaceRepeaterJob.cancel()
                    }
                }
                true
            }

            binding.backspace.setOnTouchListener(backspaceTouchListener)
            binding.emojiView.btnBackspace.setOnTouchListener(backspaceTouchListener)

            // Emoji Logic
            // Match the emoji panel height to however many key rows are actually
            // showing (the number row can be hidden via Settings) instead of a
            // hardcoded 5 rows, otherwise the panel ends up taller than the keyboard.
            // (Final height - including recent-row compensation - is applied below,
            // once the clipboard panel section has also been set up.)
            binding.emojiView.root.layoutParams.height =
                rowHeightPx * 4 + (if (showNumberRow) numRowHeight(rowHeightPx) else 0)

            binding.emojiView.emojiBottomBar.layoutParams.height = rowHeightPx
            // emoji_categories_scroll now lives directly in the top bar (same line
            // as the back arrow) instead of its own row inside emoji_layout.xml, so
            // its height is simply match_parent against the top bar's fixed height -
            // no rowHeight sync needed here any more.

            val emojiCategories = binding.emojiCategories
            val emojiGrid = binding.emojiView.emojiGrid
            val emojiCategoriesScroll = binding.emojiCategoriesScroll

            val emojiAdapter = EmojiAdapter(
                contextThemeWrapper,
                clickListener,
                darkTheme,
                EmojiData.emojis["Recent"] ?: emptyList(),
                textSize,
                emojiStyle
            )
            emojiGrid.layoutManager = GridLayoutManager(context, 8)
            emojiGrid.adapter = emojiAdapter

            val categoryClickListener = View.OnClickListener { v ->
                val category = v.tag as String
                emojiAdapter.updateEmojis(EmojiData.emojis[category] ?: emptyList())
                for (child in emojiCategories.children) {
                    child.background = null
                }
                v.background = AppCompatResources.getDrawable(
                    contextThemeWrapper,
                    R.drawable.key_background_pressed
                )
            }

            // Long-pressing any category icon picks it up; dragging it over its
            // neighbours live-reorders the strip. The back arrow (btn_emoji) lives
            // outside this container entirely, so it's never part of the drag and
            // never moves - only the category icons themselves can be swapped.
            val categoryLongPressListener = View.OnLongClickListener { v ->
                val shadow = View.DragShadowBuilder(v)
                v.startDragAndDrop(ClipData.newPlainText("", ""), shadow, v, 0)
                v.alpha = 0.3f
                true
            }

            emojiCategories.setOnDragListener { container, event ->
                val draggedView = event.localState as? View
                when (event.action) {
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        if (draggedView != null && container is ViewGroup) {
                            val currentIndex = container.indexOfChild(draggedView)
                            var targetIndex = container.childCount - 1
                            var accumulated = 0f
                            for (i in 0 until container.childCount) {
                                accumulated += container.getChildAt(i).width
                                if (event.x < accumulated) {
                                    targetIndex = i
                                    break
                                }
                            }
                            if (targetIndex != currentIndex) {
                                container.removeView(draggedView)
                                container.addView(draggedView, targetIndex)
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        draggedView?.alpha = 1f
                        true
                    }
                    else -> true
                }
            }

            for (category in EmojiData.categories) {
                val categoryView = ImageView(contextThemeWrapper)
                categoryView.setImageResource(EmojiData.categoryIcon(category))
                categoryView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                // Filled category glyphs read fine smaller than the old outline ones did,
                // so shrink the padding to let them render bigger and bolder in the tab strip.
                val iconPadding = (rowHeightPx * 0.20f).toInt()
                categoryView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                categoryView.layoutParams =
                    LinearLayout.LayoutParams(rowHeightPx, LayoutParams.MATCH_PARENT)
                categoryView.tag = category
                categoryView.setOnClickListener(categoryClickListener)
                categoryView.setOnLongClickListener(categoryLongPressListener)
                emojiCategories.addView(categoryView)
            }

            // Click the first category (Recent) to load it by default
            (emojiCategories.getChildAt(0) as? ImageView)?.performClick()

            // Swiping left/right on the emoji grid itself (not just tapping the
            // tab strip) moves to the next/previous category tab. GridLayoutManager
            // only ever scrolls the grid vertically, so a horizontal drag is
            // otherwise unused by the RecyclerView - safe to read it here via
            // onInterceptTouchEvent without ever intercepting (returning true),
            // which means normal vertical scrolling/tapping on emoji cells is
            // completely unaffected.
            fun switchEmojiCategory(direction: Int) {
                val currentIndex = emojiCategories.children.indexOfFirst { it.background != null }
                if (currentIndex == -1) return
                val newIndex = (currentIndex + direction).coerceIn(0, emojiCategories.childCount - 1)
                if (newIndex == currentIndex) return
                val newTab = emojiCategories.getChildAt(newIndex) as? ImageView ?: return
                newTab.performClick()
                // Keep the newly-selected tab visible in the (horizontally
                // scrollable) tab strip in case it scrolled out of view.
                emojiCategoriesScroll.requestChildRectangleOnScreen(
                    newTab,
                    android.graphics.Rect(0, 0, newTab.width, newTab.height),
                    false
                )
            }

            val emojiSwipeDetector = android.view.GestureDetector(
                contextThemeWrapper,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (e1 == null) return false
                        val diffX = e2.x - e1.x
                        val diffY = e2.y - e1.y
                        if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) &&
                            kotlin.math.abs(diffX) > 100 &&
                            kotlin.math.abs(velocityX) > 300
                        ) {
                            switchEmojiCategory(if (diffX < 0) 1 else -1)
                            return true
                        }
                        return false
                    }
                }
            )
            emojiGrid.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    emojiSwipeDetector.onTouchEvent(e)
                    return false
                }
                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
            })

            fun toggleEmojiView(visible: Boolean) {
                binding.keyboardRows.visibility = if (visible) View.GONE else View.VISIBLE
                binding.emojiView.root.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) binding.clipboardView.root.visibility = View.GONE
                if (visible) binding.textSelectView.root.visibility = View.GONE
                if (visible) binding.fontStyleView.root.visibility = View.GONE
                // While the emoji panel is open, its own back arrow is the only exit
                // control needed - the clipboard/text-select toggle icons next to it
                // would just be dead weight, so hide them and bring back a proper
                // full-arrow "back" icon (matching the text-editor panel's own back
                // arrow) instead of the plain chevron.
                binding.btnEmoji.setImageResource(if (visible) R.drawable.ic_arrow_back else R.drawable.ic_emoji)
                binding.btnClipboard.visibility =
                    if (visible) View.GONE else (if (clipboardEnabled) View.VISIBLE else View.GONE)
                binding.btnTextSelect.visibility = if (visible) View.GONE else View.VISIBLE
                binding.btnFonts.visibility = if (visible) View.GONE else View.VISIBLE
                // The category tab strip moves onto this same row, right after the
                // fixed btn_emoji back arrow, and the row itself switches to
                // width=0dp/weight=1 so the strip has the rest of the line to expand
                // into (see setTopBarIconRowExpanded).
                binding.emojiCategoriesScroll.isVisible = visible
                setTopBarIconRowExpanded(visible)
                setLogoVisible(!visible)
                if (isClipboardPanelOpen) {
                    binding.btnClipboard.setImageResource(R.drawable.ic_clipboard)
                    binding.btnClipClear.isVisible = false
                    binding.btnClipFilter.isVisible = false
                    binding.clipboardRowSpacer.isVisible = false
                    // Leaving the clipboard panel this way (switching straight to
                    // emoji) bypasses toggleClipboardView(false), so it needs the
                    // same cleanup - otherwise a clip's pin/share/delete row (or an
                    // in-progress selection) reappears already expanded next time
                    // the clipboard panel is opened.
                    clipboardAdapter.collapse()
                    clipboardAdapter.exitSelectionMode()
                    clipFilterPopup?.dismiss()
                    currentClipFilter = ClipFilter.ALL
                }
                if (visible) {
                    isClipboardPanelOpen = false
                    isTextSelectPanelOpen = false
                    isFontStylePanelOpen = false
                }
                // This was the missing piece causing two bugs at once: the emoji
                // panel's own back arrow (btn_emoji) reads isEmojiPanelOpen to decide
                // whether to open or close, and updateRecentEmojiRowVisibility() reads
                // it to hide the recent-emoji strip while a panel is open. Without this
                // line isEmojiPanelOpen stayed false forever, so the back arrow always
                // reopened the panel instead of closing it, and the recent-emoji strip
                // never hid itself and sat floating above whichever category grid was
                // showing.
                isEmojiPanelOpen = visible
                updateRecentEmojiRowVisibility()

                // If showing emoji view, refresh Recent category as it might have changed
                if (visible) {
                     val firstChild = emojiCategories.getChildAt(0) as? ImageView
                     // Only refresh if the "Recent" tab is currently selected
                     if (firstChild?.background != null) {
                         emojiAdapter.updateEmojis(EmojiData.emojis["Recent"] ?: emptyList())
                     }
                }
            }

            binding.btnEmoji.setOnClickListener { toggleEmojiView(!isEmojiPanelOpen) }

            binding.emojiView.btnAbc.setOnClickListener { toggleEmojiView(false) }

            this.closeEmojiPanelFn = { toggleEmojiView(false) }

            // Resets the emoji panel back to its defaults: the "Recent" tab
            // re-selected (and its data reloaded) and the category tab strip's
            // scroll position reset to the start - so that hiding the keyboard
            // and coming back to the emoji tab later doesn't leave whatever
            // category/scroll-position was last picked. Invoked from
            // InputMethodService.resetKeyboardState() on true keyboard hide,
            // not on every panel open/close within the same session.
            this.resetEmojiPanelStateFn = {
                val firstChild = emojiCategories.getChildAt(0) as? ImageView
                if (firstChild != null) {
                    for (child in emojiCategories.children) child.background = null
                    firstChild.background = AppCompatResources.getDrawable(
                        contextThemeWrapper,
                        R.drawable.key_background_pressed
                    )
                }
                emojiAdapter.updateEmojis(EmojiData.emojis["Recent"] ?: emptyList())
                emojiCategoriesScroll.scrollTo(0, 0)
                // Recent-emoji quick-strip above the keys: also reset to the start,
                // rather than staying scrolled to wherever the user last dragged a
                // swap to (see recentEmojiRow drag/reorder handling above).
                binding.recentEmojiRow.scrollToPosition(0)
            }

            // --- Clipboard panel logic ---
            binding.btnClipboard.isVisible = clipboardEnabled

            // Same row-count fix as the emoji panel above, so the clipboard panel
            // opens at the same height as the normal keyboard, not taller.
            binding.clipboardView.root.layoutParams.height =
                rowHeightPx * 4 + (if (showNumberRow) numRowHeight(rowHeightPx) else 0)
            binding.textSelectView.root.layoutParams.height =
                rowHeightPx * 4 + (if (showNumberRow) numRowHeight(rowHeightPx) else 0)

            // Finalize both panels' heights now that recentEmojiRow is fully configured,
            // adding back the recent-row height if it's currently showing on the plain
            // keyboard - otherwise the keyboard shrinks by that amount the moment either
            // panel opens (since the strip is hidden while a panel is shown).
            applyPanelHeights()

            clipboardAdapter = ClipboardAdapter(object : ClipboardAdapter.Actions {
                override fun onClipTap(item: ClipItem) {
                    ClipboardData.markUsed(context, item.id)
                    clickListener.clipboardPasteClick(item.text)
                }
                override fun onClipPin(item: ClipItem) = clickListener.clipboardPinClick(item)
                override fun onClipShare(item: ClipItem) = clickListener.clipboardShareClick(item)
                override fun onClipDelete(item: ClipItem) = clickListener.clipboardDeleteClick(item)
            })
            binding.clipboardView.clipboardList.layoutManager =
                StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
                    gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                }
            binding.clipboardView.clipboardList.adapter = clipboardAdapter

            // Tapping empty space in the clipboard list (not on any clip card) collapses
            // whichever clip currently has its pin/share/delete row expanded.
            // Plain click listener only fires when the RecyclerView itself gets the
            // click with nothing else consuming the touch first, which isn't
            // reliable with a StaggeredGridLayoutManager - blank space taps could
            // land without ever calling this. Use an item-touch listener instead:
            // on ACTION_UP, if there's no child view under the touch point, the tap
            // landed on blank space, so collapse whichever clip's share/delete/pin
            // row is currently expanded.
            binding.clipboardView.clipboardList.setOnClickListener { clipboardAdapter.collapse() }
            binding.clipboardView.clipboardList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_UP && rv.findChildViewUnder(e.x, e.y) == null) {
                        clipboardAdapter.collapse()
                    }
                    return false
                }
                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
            })

            // Clear-all now lives as a purple circular icon in the keyboard's top_bar
            // (next to btn_clipboard) instead of a second header row inside the panel,
            // so it's only ever visible while the clipboard panel itself is open.
            // It no longer wipes every unpinned clip on a single tap (too easy to hit
            // by accident with nothing to undo) - the first tap now enters a select
            // mode so the user can choose exactly which clips to remove, and a second
            // tap deletes whatever's checked (or just cancels select mode if nothing
            // was checked).
            binding.btnClipClear.setOnClickListener {
                when {
                    !clipboardAdapter.isSelectionMode() -> clipboardAdapter.enterSelectionMode()
                    clipboardAdapter.hasSelection() -> {
                        clickListener.clipboardDeleteSelectedClick(clipboardAdapter.selectedIds())
                        clipboardAdapter.exitSelectionMode()
                    }
                    else -> clipboardAdapter.exitSelectionMode()
                }
            }

            // "Filter clips" dropdown - same purple circular icon as btn_clip_clear,
            // right next to it. Lets the user narrow the panel down to just recently
            // copied / recently used / frequently used clips, or clips that look like
            // a mobile number, email, or link.
            binding.btnClipFilter.setOnClickListener { anchor ->
                showClipFilterMenu(anchor)
            }

            fun toggleClipboardView(visible: Boolean) {
                binding.keyboardRows.visibility = if (visible) View.GONE else View.VISIBLE
                binding.clipboardView.root.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) binding.emojiView.root.visibility = View.GONE
                if (visible) binding.textSelectView.root.visibility = View.GONE
                if (visible) binding.fontStyleView.root.visibility = View.GONE
                // Same idea as the emoji panel above: while the clipboard panel is
                // open, hide the emoji/text-select toggle icons and swap in the same
                // proper full-arrow "back" icon instead of the plain chevron.
                binding.btnClipboard.setImageResource(if (visible) R.drawable.ic_arrow_back else R.drawable.ic_clipboard)
                binding.btnClipClear.isVisible = visible
                binding.btnClipFilter.isVisible = visible
                // The spacer between the fixed back arrow (btn_clipboard) and
                // btn_clip_clear/btn_clip_filter only shows up while the panel is
                // open, and the row itself switches to width=0dp/weight=1 at the
                // same time so the spacer can actually expand and push the trash/
                // filter icons to the row's far end (see setTopBarIconRowExpanded).
                binding.clipboardRowSpacer.isVisible = visible
                setTopBarIconRowExpanded(visible)
                setLogoVisible(!visible)
                binding.btnEmoji.visibility = if (visible) View.GONE else View.VISIBLE
                binding.btnTextSelect.visibility = if (visible) View.GONE else View.VISIBLE
                binding.btnFonts.visibility = if (visible) View.GONE else View.VISIBLE
                if (isEmojiPanelOpen) {
                    binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
                    binding.emojiCategoriesScroll.isVisible = false
                }
                if (isTextSelectPanelOpen) binding.btnTextSelect.isSelected = false

                isClipboardPanelOpen = visible
                if (visible) {
                    isEmojiPanelOpen = false
                    isTextSelectPanelOpen = false
                    isFontStylePanelOpen = false
                }
                updateRecentEmojiRowVisibility()
                if (visible) refreshClipboardList()
                // Don't let a clip's expanded pin/share/delete row, or an in-progress
                // selection, survive a close - the next time the panel opens (even for a
                // different field/session) it should start fresh. Same idea for the
                // filter dropdown: any open menu is dismissed and the filter itself
                // resets to "All" so the panel always reopens unfiltered.
                if (!visible) {
                    clipboardAdapter.collapse()
                    clipboardAdapter.exitSelectionMode()
                    clipFilterPopup?.dismiss()
                    currentClipFilter = ClipFilter.ALL
                }
            }

            binding.btnClipboard.setOnClickListener {
                // While selecting clips to delete, the back arrow cancels the selection
                // first rather than immediately closing the whole panel.
                if (isClipboardPanelOpen && clipboardAdapter.isSelectionMode()) {
                    clipboardAdapter.exitSelectionMode()
                } else {
                    toggleClipboardView(!isClipboardPanelOpen)
                }
            }

            this.closeClipboardPanelFn = { toggleClipboardView(false) }

            // --- Text-select panel logic ---
            // Everything here lives in its own panel (text_select_layout.xml, wired
            // exactly like the emoji/clipboard panels above) so it inherits the same
            // show/hide + height rules automatically.
            fun toggleTextSelectView(visible: Boolean) {
                binding.keyboardRows.visibility = if (visible) View.GONE else View.VISIBLE
                binding.textSelectView.root.visibility = if (visible) View.VISIBLE else View.GONE
                // The text-editor panel takes over the whole keyboard area, including
                // the top bar row (clipboard/emoji/back icons), so it reads as a full
                // dedicated screen instead of a panel squeezed in under those icons.
                binding.topBar.visibility = if (visible) View.GONE else View.VISIBLE
                if (visible) binding.emojiView.root.visibility = View.GONE
                if (visible) binding.clipboardView.root.visibility = View.GONE
                if (visible) binding.fontStyleView.root.visibility = View.GONE
                binding.btnTextSelect.setImageResource(if (visible) R.drawable.ic_keyboard_arrow_left else R.drawable.ic_text_select)
                if (isEmojiPanelOpen) {
                    binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
                    binding.emojiCategoriesScroll.isVisible = false
                }
                if (isClipboardPanelOpen) {
                    binding.btnClipboard.setImageResource(R.drawable.ic_clipboard)
                    binding.btnClipClear.isVisible = false
                    binding.btnClipFilter.isVisible = false
                    binding.clipboardRowSpacer.isVisible = false
                    // Same reasoning as toggleEmojiView above - this path bypasses
                    // toggleClipboardView(false) too.
                    clipboardAdapter.collapse()
                    clipboardAdapter.exitSelectionMode()
                    clipFilterPopup?.dismiss()
                    currentClipFilter = ClipFilter.ALL
                }
                if (visible && (isEmojiPanelOpen || isClipboardPanelOpen)) setTopBarIconRowExpanded(false)

                isTextSelectPanelOpen = visible
                if (visible) {
                    isEmojiPanelOpen = false
                    isClipboardPanelOpen = false
                    isFontStylePanelOpen = false
                } else {
                    // Selection mode shouldn't survive a close - reopening should
                    // always start with a plain, non-extending cursor.
                    isTextSelecting = false
                    binding.textSelectView.btnTsToggleSelect.isSelected = false
                }
                updateRecentEmojiRowVisibility()
            }

            binding.textSelectView.btnTsCut.setOnClickListener { clickListener.textSelectCutClick() }
            binding.textSelectView.btnTsCopy.setOnClickListener { clickListener.textSelectCopyClick() }
            binding.textSelectView.btnTsPaste.setOnClickListener { clickListener.textSelectPasteClick() }
            binding.textSelectView.btnTsSelectAll.setOnClickListener { clickListener.textSelectAllClick() }

            binding.textSelectView.btnTsToggleSelect.setOnClickListener { v ->
                isTextSelecting = !isTextSelecting
                v.isSelected = isTextSelecting
            }

            binding.textSelectView.btnTsLeft.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.LEFT, isTextSelecting)
            }
            binding.textSelectView.btnTsRight.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.RIGHT, isTextSelecting)
            }
            binding.textSelectView.btnTsUp.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.UP, isTextSelecting)
            }
            binding.textSelectView.btnTsDown.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.DOWN, isTextSelecting)
            }
            binding.textSelectView.btnTsWordLeft.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.LEFT, isTextSelecting, byWord = true)
            }
            binding.textSelectView.btnTsWordRight.setOnClickListener {
                clickListener.textSelectMove(TextSelectDirection.RIGHT, isTextSelecting, byWord = true)
            }

            binding.textSelectView.btnTsAbc.setOnClickListener { toggleTextSelectView(false) }
            binding.textSelectView.btnTsBackspace.setOnTouchListener(backspaceTouchListener)

            binding.btnTextSelect.setOnClickListener { toggleTextSelectView(!isTextSelectPanelOpen) }

            this.closeTextSelectPanelFn = { toggleTextSelectView(false) }

            // --- Fonts (fancy-text style picker) panel logic ---
            // Wired exactly like the clipboard panel above. Height is handled by
            // applyPanelHeights() (called again below) rather than set manually here,
            // so it stays in sync with the recent-emoji-row compensation the other
            // panels get - setting it manually here left this panel a bit shorter
            // than the others whenever the recent-emoji strip was showing.
            activeFontStyle = initialFontStyle

            fun toggleFontStyleView(visible: Boolean) {
                binding.keyboardRows.visibility = if (visible) View.GONE else View.VISIBLE
                binding.fontStyleView.root.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) binding.emojiView.root.visibility = View.GONE
                if (visible) binding.clipboardView.root.visibility = View.GONE
                if (visible) binding.textSelectView.root.visibility = View.GONE
                binding.btnFonts.setImageResource(if (visible) R.drawable.ic_arrow_back else R.drawable.ic_fonts)
                binding.btnClipboard.visibility =
                    if (visible) View.GONE else (if (clipboardEnabled) View.VISIBLE else View.GONE)
                binding.btnEmoji.visibility = if (visible) View.GONE else View.VISIBLE
                binding.btnTextSelect.visibility = if (visible) View.GONE else View.VISIBLE
                setTopBarIconRowExpanded(visible)
                setLogoVisible(!visible)
                if (isEmojiPanelOpen) {
                    binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
                    binding.emojiCategoriesScroll.isVisible = false
                }
                if (isClipboardPanelOpen) {
                    binding.btnClipboard.setImageResource(R.drawable.ic_clipboard)
                    binding.btnClipClear.isVisible = false
                    binding.btnClipFilter.isVisible = false
                    binding.clipboardRowSpacer.isVisible = false
                    clipboardAdapter.collapse()
                    clipboardAdapter.exitSelectionMode()
                    clipFilterPopup?.dismiss()
                    currentClipFilter = ClipFilter.ALL
                }
                if (isTextSelectPanelOpen) binding.btnTextSelect.isSelected = false
                isFontStylePanelOpen = visible
                if (visible) {
                    isEmojiPanelOpen = false
                    isClipboardPanelOpen = false
                    isTextSelectPanelOpen = false
                    fontStyleAdapter.setActiveStyle(activeFontStyle)
                }
                updateRecentEmojiRowVisibility()
            }

            fontStyleAdapter = FontStyleAdapter { style ->
                activeFontStyle = style
                clickListener.fontStyleSelected(style)
                updateFontsIconBadge()
                // Auto-back to keyboard once a style (or "None") is picked, same as
                // the plan's UX flow - no separate confirm step needed.
                toggleFontStyleView(false)
            }
            fontStyleAdapter.setActiveStyle(activeFontStyle)
            binding.fontStyleView.fontStyleList.layoutManager =
                StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
                    gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                }
            binding.fontStyleView.fontStyleList.adapter = fontStyleAdapter
            updateFontsIconBadge()

            binding.btnFonts.setOnClickListener { toggleFontStyleView(!isFontStylePanelOpen) }

            this.closeFontStylePanelFn = { toggleFontStyleView(false) }

            // --- Settings icon (fixed, far right of the top bar) ---
            // Doesn't toggle an in-keyboard panel like the others - it jumps
            // straight to the app's own Settings screen (MainActivity shows
            // SettingsScreen once the keyboard is enabled/selected). Needs
            // FLAG_ACTIVITY_NEW_TASK since we're launching an Activity from the
            // IME's service context, not from an Activity.
            binding.btnSettings.setOnClickListener {
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            applyPanelHeights()
        } catch (t: Throwable) {
            Log.e("KeyboardView", "Error during KeyboardView init configuration", t)

        }

        // Programmatic creation of suggestion TextViews removed - relies on XML include.
    }

    /**
     * "*_gradient" theme ids (Settings > Appearance > Gradient Color Themes) only get a
     * flat [accentOverlayStyle] from `init{}` above - XML styles/colors can't express a
     * 2-stop gradient via style attrs alone. This retints EVERY key on the keyboard
     * (not just Space/Enter) the same way a static colour theme retints the whole key
     * bed, except each key's fill is sampled from a position along the SAME
     * lightVariant->darkVariant gradient (top-left to bottom-right, matching the
     * diagonal already used on Space/Enter) instead of one flat colour - so the whole
     * keyboard reads as one continuous gradient sweeping across all the keys, the same
     * way the flat colour themes tint every key. Built from the SAME resolved
     * ?attr/keyAction the rest of the keyboard already uses for this theme (via
     * [themedContext]), so it's automatically correct for whichever colour theme +
     * light/dark combination is active. No-op for every non-gradient colorTheme, so
     * it's safe to call unconditionally from init{}.
     */
    private fun applyGradientToKeyboard() {
        if (!colorTheme.endsWith("_gradient")) return

        val typedValue = TypedValue()
        themedContext.theme.resolveAttribute(R.attr.keyAction, typedValue, true)
        val baseAccent = typedValue.data

        val density = resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()

        // Same two variants (a lighter tint + a darker shade of the SAME accent) as the
        // Settings > Appearance preview, so the real keyboard matches what was picked.
        val lightVariant = lightenColor(baseAccent, 0.35f)
        val darkVariant = darkenColor(baseAccent, 0.30f)

        fun lerp(from: Int, to: Int, t: Float): Int {
            val ct = t.coerceIn(0f, 1f)
            val r = Color.red(from) + (Color.red(to) - Color.red(from)) * ct
            val g = Color.green(from) + (Color.green(to) - Color.green(from)) * ct
            val b = Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ct
            return Color.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }

        // Space/Enter: the wide action-style shape (18dp/14dp radius, taller insets),
        // with the gradient running across the key's own width - unchanged from before.
        fun buildActionKeyDrawable(): android.graphics.drawable.Drawable {
            val normalShape = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(lightVariant, darkVariant)
            ).apply { cornerRadius = dp(18f).toFloat() }
            val pressedShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(darkenColor(baseAccent, 0.45f))
                cornerRadius = dp(14f).toFloat()
            }
            val normalInset = android.graphics.drawable.InsetDrawable(normalShape, dp(2f), dp(3f), dp(2f), dp(3f))
            val pressedInset = android.graphics.drawable.InsetDrawable(pressedShape, dp(3f), dp(4f), dp(3f), dp(4f))
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedInset)
                addState(intArrayOf(), normalInset)
            }
        }

        // Every other key: the regular 5dp-radius shape (same as key_background.xml /
        // key_background_function.xml / key_background_special.xml), just filled with
        // a solid colour sampled from this key's (row, column) position along the
        // gradient instead of the theme's single flat keyNormal/keyFunction colour.
        fun buildFlatKeyDrawable(fillColor: Int): android.graphics.drawable.Drawable {
            val normalShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(fillColor)
                cornerRadius = dp(5f).toFloat()
            }
            val pressedShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(darkenColor(fillColor, 0.15f))
                cornerRadius = dp(5f).toFloat()
            }
            val normalInset = android.graphics.drawable.InsetDrawable(normalShape, dp(2f), dp(3f), dp(2f), dp(3f))
            val pressedInset = android.graphics.drawable.InsetDrawable(pressedShape, dp(2f), dp(3f), dp(2f), dp(3f))
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedInset)
                addState(intArrayOf(), normalInset)
            }
        }

        try {
            // The keyboard's own root + top bar normally paint a FLAT ?attr/fox_background
            // (see keyboard_layout.xml) behind everything - that's what made the gradient
            // read as "only on the keys": the gaps between keys, the row/edge padding, and
            // the whole top bar strip stayed the old flat colour. Painting the SAME
            // lightVariant->darkVariant diagonal there too (root spans the full keyboard
            // height, so this is one continuous sweep, not a second clashing gradient)
            // makes every pixel of the keyboard - not just the key faces - read as part of
            // one gradient, matching the Settings > Appearance preview.
            val wholeKeyboardGradient = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(lightVariant, darkVariant)
            )
            binding.root.background = wholeKeyboardGradient
            // Top bar previously drew its own opaque fox_background on top of the root's
            // background, which would otherwise hide the gradient behind the toolbar row -
            // clear it so the root's gradient shows through instead.
            binding.topBar.background = null

            binding.space.background = buildActionKeyDrawable()
            binding.action.background = buildActionKeyDrawable()

            val rows = binding.keyboardRows.children.filterIsInstance<LinearLayout>().toList()
            val totalRows = rows.size
            rows.forEachIndexed { rowIndex, row ->
                val keys = row.children.toList()
                val totalKeys = keys.size
                keys.forEachIndexed { keyIndex, keyView ->
                    // Space/Enter already got their own dedicated shape above - don't
                    // flatten them to the generic 5dp key shape here.
                    if (keyView === binding.space || keyView === binding.action) return@forEachIndexed
                    if (keyView !is KeyboardButton && keyView !is ImageView) return@forEachIndexed

                    val fx = if (totalKeys > 1) keyIndex / (totalKeys - 1).toFloat() else 0f
                    val fy = if (totalRows > 1) rowIndex / (totalRows - 1).toFloat() else 0f
                    val fraction = (fx + fy) / 2f
                    keyView.background = buildFlatKeyDrawable(lerp(lightVariant, darkVariant, fraction))
                }
            }
        } catch (t: Throwable) {
            Log.e("KeyboardView", "Failed to apply whole-keyboard gradient", t)
        }
    }

    /**
     * Step 6: real-keyboard counterpart of Step 5's Settings > Appearance preview
     * (see KeyboardPreview in SettingsScreen.kt). No-op whenever [backgroundMode]
     * isn't "custom_image" - including "the adjustment screen exists but the user
     * never actually picked a photo", since backgroundMode only ever flips to
     * custom_image once [CustomBackgroundManager.importImage] has actually
     * succeeded (see Prefs.backgroundMode's own doc comment) - so it's safe to
     * call unconditionally from init{} right after [applyGradientToKeyboard],
     * same pattern.
     *
     * Step 7 (missing/corrupt file): if [CustomBackgroundManager.loadBitmap]
     * comes back null, this simply returns - whatever colorTheme/
     * applyGradientToKeyboard already painted above is left alone. No crash,
     * no half-applied custom-image state.
     *
     * The actual cover-scale/pan/blur/darken math is deferred to
     * [doOnLayout] because it needs binding.root's actual laid-out width/
     * height, which aren't known yet at init{} time - measure/layout hasn't
     * run on the IME window on this first call.
     */
    private fun applyCustomImageToKeyboard() {
        if (backgroundMode != "custom_image") return

        val sourceBitmap = try {
            CustomBackgroundManager.loadBitmap(context)
        } catch (t: Throwable) {
            Log.e("KeyboardView", "Failed to load custom background bitmap", t)
            null
        } ?: return

        binding.root.doOnLayout {
            try {
                renderCustomImageBackground(sourceBitmap)
                applyGlassKeyStyling()
            } catch (t: Throwable) {
                Log.e("KeyboardView", "Failed to apply custom image background", t)
            }
        }
    }

    /**
     * Bakes [source] into one bitmap sized to this keyboard's own laid-out
     * bounds, reproducing the EXACT same cover-scale + pan + blur + darken
     * math as [com.ola.keyboard.ui.CustomBackgroundPreviewBox] (the
     * adjustment screen and the Settings preview both use it) so the real
     * keyboard matches what was saved. Baked once rather than drawn live
     * since there's no drag gesture on the real keyboard - just the static
     * values Settings already saved - unlike the RenderEffect-vs-baked-bitmap
     * split ImageBlurUtils documents for API < 31's Compose preview, this
     * always bakes, on every API level, because a native View background
     * drawable has no live-blur equivalent to reach for in the first place.
     *
     * Paints the result onto binding.root - the same spot
     * [applyGradientToKeyboard] paints its gradient - and clears the top
     * bar's own opaque background for the same reason: it would otherwise
     * hide whatever's now behind it.
     */
    private fun renderCustomImageBackground(source: android.graphics.Bitmap) {
        val boxW = binding.root.width
        val boxH = binding.root.height
        if (boxW <= 0 || boxH <= 0) return

        val imgW = source.width.toFloat()
        val imgH = source.height.toFloat()
        if (imgW <= 0f || imgH <= 0f) return

        // Same CUSTOM_BG_MIN_ZOOM/CUSTOM_BG_MAX_ZOOM range as
        // CustomBackgroundPreviewBox - duplicated as literals here rather
        // than importing a Compose-file constant into this plain View class.
        val clampedZoom = customBgZoom.coerceIn(1f, 3f)
        val baseScale = max(boxW / imgW, boxH / imgH)
        val scale = baseScale * clampedZoom
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val overflowX = (scaledW - boxW).coerceAtLeast(0f)
        val overflowY = (scaledH - boxH).coerceAtLeast(0f)

        val translateX = -(customBgOffsetX.coerceIn(0f, 1f) * overflowX)
        val translateY = -(customBgOffsetY.coerceIn(0f, 1f) * overflowY)

        val cropped = android.graphics.Bitmap.createBitmap(
            boxW, boxH, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(cropped)
        val matrix = android.graphics.Matrix().apply {
            setScale(scale, scale)
            postTranslate(translateX, translateY)
        }
        val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
        )
        canvas.drawBitmap(source, matrix, paint)

        // ImageBlurUtils fails soft to the sharp bitmap on its own (OEM
        // RenderScript issues) - the extra try/catch here is only for
        // anything unexpected happening around that call itself.
        val blurred = if (customBgBlur > 0f) {
            try {
                ImageBlurUtils.blur(context, cropped, customBgBlur)
            } catch (t: Throwable) {
                Log.e("KeyboardView", "Custom background blur failed, using sharp image", t)
                cropped
            }
        } else {
            cropped
        }

        if (customBgDarken > 0f) {
            // Same 0.85f alpha cap as CustomBackgroundPreviewBox's darken
            // overlay - "near-black", never a fully opaque black square.
            val alpha = (customBgDarken.coerceIn(0f, 1f) * 0.85f * 255f).toInt().coerceIn(0, 255)
            android.graphics.Canvas(blurred).drawColor(android.graphics.Color.argb(alpha, 0, 0, 0))
        }

        binding.root.background = android.graphics.drawable.BitmapDrawable(resources, blurred)
        binding.topBar.background = null
    }

    /**
     * Step 6: every key - letters, number row, Space/Enter alike - switches
     * from a flat/gradient theme fill to a translucent frosted-glass
     * treatment: semi-transparent fill + border, matching KeyboardPreview's
     * glass styling in SettingsScreen.kt (same fill/border alpha values, and
     * the same "respects the Settings Border toggle instead of always
     * drawing one" fix) so the real keyboard matches the Settings preview. A
     * solid theme colour would otherwise visually fight with an arbitrary
     * photo underneath. Only ever called once [renderCustomImageBackground]
     * has already painted a valid photo behind everything - see
     * [applyCustomImageToKeyboard].
     *
     * BUG FIX (was showing each key's translucent box looking "shifted"/
     * smeared rightward, worse toward the end of a row - most visible on
     * k/l and n/m/backspace): this used to also set `keyView.elevation` on
     * every key for a drop-shadow accent, same as KeyboardPreview's
     * `Modifier.shadow(...)` in the Compose preview. But that Compose
     * preview's keys have real breathing room between them
     * (`Arrangement.spacedBy(3.dp)`), while these real keys sit edge-to-edge
     * with zero margin (see keyboard_layout.xml - no layout_margin anywhere
     * in the letter/number/symbol rows, by design, so the row fills exactly
     * edge-to-edge). Android draws a View's elevation shadow projecting
     * OUTSIDE its own bounds, and sibling views don't clip each other's
     * shadows - so key N's shadow spilled rightward onto key N+1, and
     * because the glass fill is semi-transparent that spillover showed
     * through as a dark smear along each key's left edge, compounding
     * across the row (key N+1's own shadow then spills onto N+2, etc.) -
     * exactly the "shifted box, worse further right" look reported. Simplest
     * correct fix for zero-margin siblings: drop the shadow accent here
     * entirely and keep only fill+border, which was always the load-bearing
     * part of the "glassmorphism" look anyway.
     */
    private fun applyGlassKeyStyling() {
        val density = resources.displayMetrics.density
        fun dp(value: Float) = value * density

        val glassFill = if (darkTheme) {
            android.graphics.Color.argb((0.30f * 255).toInt(), 0, 0, 0)
        } else {
            android.graphics.Color.argb((0.22f * 255).toInt(), 255, 255, 255)
        }
        // Mirrors KeyboardPreview's own bug fix: no border at all when the
        // Settings "Border" toggle is off, instead of always drawing one.
        val glassBorder = if (!keyBorders) {
            android.graphics.Color.TRANSPARENT
        } else if (darkTheme) {
            android.graphics.Color.argb((0.16f * 255).toInt(), 255, 255, 255)
        } else {
            android.graphics.Color.argb((0.55f * 255).toInt(), 255, 255, 255)
        }
        val strokeWidthPx = dp(0.8f).toInt().coerceAtLeast(1)

        fun buildGlassDrawable(
            cornerRadiusDp: Float,
            insetHDp: Float,
            insetVDp: Float
        ): android.graphics.drawable.Drawable {
            val normalShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(glassFill)
                cornerRadius = dp(cornerRadiusDp)
                if (glassBorder != android.graphics.Color.TRANSPARENT) {
                    setStroke(strokeWidthPx, glassBorder)
                }
            }
            // Pressed feedback bumps the fill's own alpha up rather than
            // blending toward black/white (darkenColor()/lightenColor()
            // below both discard alpha via Color.rgb()) - keeps the glass
            // look translucent instead of the pressed key suddenly going
            // opaque.
            val pressedShape = android.graphics.drawable.GradientDrawable().apply {
                setColor(pressedGlassFill(glassFill))
                cornerRadius = dp(cornerRadiusDp)
                if (glassBorder != android.graphics.Color.TRANSPARENT) {
                    setStroke(strokeWidthPx, glassBorder)
                }
            }
            val insetH = dp(insetHDp).toInt()
            val insetV = dp(insetVDp).toInt()
            val normalInset = android.graphics.drawable.InsetDrawable(normalShape, insetH, insetV, insetH, insetV)
            val pressedInset = android.graphics.drawable.InsetDrawable(pressedShape, insetH, insetV, insetH, insetV)
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedInset)
                addState(intArrayOf(), normalInset)
            }
        }

        try {
            // Space/Enter: same wide action-key insets applyGradientToKeyboard
            // uses (18dp radius, 2dp/3dp insets), just glass-filled instead of
            // gradient-filled.
            binding.space.background = buildGlassDrawable(18f, 2f, 3f)
            binding.action.background = buildGlassDrawable(18f, 2f, 3f)

            val rows = binding.keyboardRows.children.filterIsInstance<LinearLayout>().toList()
            rows.forEach { row ->
                row.children.forEach { keyView ->
                    if (keyView === binding.space || keyView === binding.action) return@forEach
                    if (keyView !is KeyboardButton && keyView !is ImageView) return@forEach
                    // Same regular 5dp-radius shape/insets as
                    // applyGradientToKeyboard's buildFlatKeyDrawable, glass-filled.
                    keyView.background = buildGlassDrawable(5f, 2f, 3f)
                }
            }
        } catch (t: Throwable) {
            Log.e("KeyboardView", "Failed to apply glass key styling", t)
        }
    }

    /** Bumps an ARGB [color]'s own alpha channel up by a fixed amount (capped at
     *  fully opaque), keeping its RGB untouched - used for the glass keys' pressed
     *  state instead of [darkenColor], which discards alpha entirely via Color.rgb(). */
    private fun pressedGlassFill(color: Int): Int {
        val alpha = (android.graphics.Color.alpha(color) + (0.20f * 255).toInt()).coerceAtMost(255)
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    /** Blends [color] toward white by [amount] (0f = unchanged, 1f = white). Mirrors
     *  lightenColor() in SettingsScreen.kt's Compose preview, kept as a plain Int/ARGB
     *  version here since the real keyboard has no Compose Color type in scope. */
    private fun lightenColor(color: Int, amount: Float): Int {
        val r = Color.red(color) + ((255 - Color.red(color)) * amount).toInt()
        val g = Color.green(color) + ((255 - Color.green(color)) * amount).toInt()
        val b = Color.blue(color) + ((255 - Color.blue(color)) * amount).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Blends [color] toward black by [amount] (0f = unchanged, 1f = black). Mirrors
     *  darkenColor() in SettingsScreen.kt's Compose preview. */
    private fun darkenColor(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt()
        val g = (Color.green(color) * (1f - amount)).toInt()
        val b = (Color.blue(color) * (1f - amount)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    fun setLetterKeys(keySet: Map<String, String>) {
        binding.lA.text = keySet["a"]
        binding.lB.text = keySet["b"]
        binding.lC.text = keySet["c"]
        binding.lD.text = keySet["d"]
        binding.lE.text = keySet["e"]
        binding.lF.text = keySet["f"]
        binding.lG.text = keySet["g"]
        binding.lH.text = keySet["h"]
        binding.lI.text = keySet["i"]
        binding.lJ.text = keySet["j"]
        binding.lK.text = keySet["k"]
        binding.lL.text = keySet["l"]
        binding.lM.text = keySet["m"]
        binding.lN.text = keySet["n"]
        binding.lO.text = keySet["o"]
        binding.lP.text = keySet["p"]
        binding.lQ.text = keySet["q"]
        binding.lR.text = keySet["r"]
        binding.lS.text = keySet["s"]
        binding.lT.text = keySet["t"]
        binding.lU.text = keySet["u"]
        binding.lV.text = keySet["v"]
        binding.lW.text = keySet["w"]
        binding.lX.text = keySet["x"]
        binding.lY.text = keySet["y"]
        binding.lZ.text = keySet["z"]
    }

    fun setSecondaryLabels(secondaryLabels: Map<String, String>?) {
        binding.lA.setSecondaryLabel(secondaryLabels?.get("a"))
        binding.lB.setSecondaryLabel(secondaryLabels?.get("b"))
        binding.lC.setSecondaryLabel(secondaryLabels?.get("c"))
        binding.lD.setSecondaryLabel(secondaryLabels?.get("d"))
        binding.lE.setSecondaryLabel(secondaryLabels?.get("e"))
        binding.lF.setSecondaryLabel(secondaryLabels?.get("f"))
        binding.lG.setSecondaryLabel(secondaryLabels?.get("g"))
        binding.lH.setSecondaryLabel(secondaryLabels?.get("h"))
        binding.lI.setSecondaryLabel(secondaryLabels?.get("i"))
        binding.lJ.setSecondaryLabel(secondaryLabels?.get("j"))
        binding.lK.setSecondaryLabel(secondaryLabels?.get("k"))
        binding.lL.setSecondaryLabel(secondaryLabels?.get("l"))
        binding.lM.setSecondaryLabel(secondaryLabels?.get("m"))
        binding.lN.setSecondaryLabel(secondaryLabels?.get("n"))
        binding.lO.setSecondaryLabel(secondaryLabels?.get("o"))
        binding.lP.setSecondaryLabel(secondaryLabels?.get("p"))
        binding.lQ.setSecondaryLabel(secondaryLabels?.get("q"))
        binding.lR.setSecondaryLabel(secondaryLabels?.get("r"))
        binding.lS.setSecondaryLabel(secondaryLabels?.get("s"))
        binding.lT.setSecondaryLabel(secondaryLabels?.get("t"))
        binding.lU.setSecondaryLabel(secondaryLabels?.get("u"))
        binding.lV.setSecondaryLabel(secondaryLabels?.get("v"))
        binding.lW.setSecondaryLabel(secondaryLabels?.get("w"))
        binding.lX.setSecondaryLabel(secondaryLabels?.get("x"))
        binding.lY.setSecondaryLabel(secondaryLabels?.get("y"))
        binding.lZ.setSecondaryLabel(secondaryLabels?.get("z"))
    }

    // What each key actually commits on long-press. Pass null to clear (falls back
    // to the visible secondary label per key). Keyed by physical key position
    // (a-z), same as setSecondaryLabels/setLetterKeys.
    fun setLongPressChars(longPressChars: Map<String, String>?) {
        binding.lA.setLongPressChar(longPressChars?.get("a"))
        binding.lB.setLongPressChar(longPressChars?.get("b"))
        binding.lC.setLongPressChar(longPressChars?.get("c"))
        binding.lD.setLongPressChar(longPressChars?.get("d"))
        binding.lE.setLongPressChar(longPressChars?.get("e"))
        binding.lF.setLongPressChar(longPressChars?.get("f"))
        binding.lG.setLongPressChar(longPressChars?.get("g"))
        binding.lH.setLongPressChar(longPressChars?.get("h"))
        binding.lI.setLongPressChar(longPressChars?.get("i"))
        binding.lJ.setLongPressChar(longPressChars?.get("j"))
        binding.lK.setLongPressChar(longPressChars?.get("k"))
        binding.lL.setLongPressChar(longPressChars?.get("l"))
        binding.lM.setLongPressChar(longPressChars?.get("m"))
        binding.lN.setLongPressChar(longPressChars?.get("n"))
        binding.lO.setLongPressChar(longPressChars?.get("o"))
        binding.lP.setLongPressChar(longPressChars?.get("p"))
        binding.lQ.setLongPressChar(longPressChars?.get("q"))
        binding.lR.setLongPressChar(longPressChars?.get("r"))
        binding.lS.setLongPressChar(longPressChars?.get("s"))
        binding.lT.setLongPressChar(longPressChars?.get("t"))
        binding.lU.setLongPressChar(longPressChars?.get("u"))
        binding.lV.setLongPressChar(longPressChars?.get("v"))
        binding.lW.setLongPressChar(longPressChars?.get("w"))
        binding.lX.setLongPressChar(longPressChars?.get("x"))
        binding.lY.setLongPressChar(longPressChars?.get("y"))
        binding.lZ.setLongPressChar(longPressChars?.get("z"))
    }

    fun setNumberKeys(keyLabels: Map<String, String>) {
        binding.n1.text = keyLabels["1"]
        binding.n2.text = keyLabels["2"]
        binding.n3.text = keyLabels["3"]
        binding.n4.text = keyLabels["4"]
        binding.n5.text = keyLabels["5"]
        binding.n6.text = keyLabels["6"]
        binding.n7.text = keyLabels["7"]
        binding.n8.text = keyLabels["8"]
        binding.n9.text = keyLabels["9"]
        binding.n0.text = keyLabels["0"]
    }

    fun setSpecialKeys(keyLabels: Map<String, String>) {
        keyLabels[";"]?.let { binding.colonWijesekara.text = it }
        keyLabels[","]?.let { binding.commaWijesekara.text = it }
        keyLabels["."]?.let { binding.dot.text = it }
    }

    /** Shows the active layout as text on the language-switch key ("ENG" / "SIN")
     *  instead of an icon, since the English/Sinhala icons looked identical and
     *  gave no way to tell which language was actually active at a glance. */
    fun setLangIndicator(text: String) {
        binding.lang.text = text
    }

    // Expose top bar and suggestion views for IME to control
    val topBarView: LinearLayout get() = binding.topBar
    val emojiButtonView: ImageView get() = binding.btnEmoji
    val clipboardButtonView: ImageView get() = binding.btnClipboard
    val textSelectButtonView: ImageView get() = binding.btnTextSelect
    val fontsButtonView: ImageView get() = binding.btnFonts
    val settingsButtonView: ImageView get() = binding.btnSettings
    val olaLogoButtonView: ImageView get() = binding.btnOlaLogo
    val logoSpacerView: View get() = binding.logoSpacer
    val topBarIconRowView: LinearLayout get() = binding.topBarIconRow

    /** True while any of the emoji/clipboard/text-select panels is open. The IME uses
     *  this to skip its own suggestion-bar refresh (which otherwise fights with the
     *  panel-specific icon visibility below by forcing btnEmoji/btnClipboard back on). */
    val isAnyPanelOpen: Boolean get() = isEmojiPanelOpen || isClipboardPanelOpen || isTextSelectPanelOpen || isFontStylePanelOpen

    /** top_bar_icon_row is now always width=0dp/weight=1 (set in the layout XML),
     *  so its icons right-align against the flexible logo_spacer instead of
     *  bunching up at the row's start with dead space after them. That same
     *  expansion is also what lets one of its icons stretch to the far end of the
     *  line while a panel is open (the clip-clear trash icon for clipboard, the
     *  category tab strip for emoji) - so this just re-asserts that expansion
     *  rather than ever collapsing it back to wrap_content; only
     *  TopBarController's showSuggestions()/showNormal() collapse/restore it now,
     *  to give suggestion chips the room they need. */
    private fun setTopBarIconRowExpanded(expanded: Boolean) {
        val params = binding.topBarIconRow.layoutParams as LinearLayout.LayoutParams
        params.width = 0
        params.weight = 1f
        binding.topBarIconRow.layoutParams = params
    }

    /** While any panel (emoji/clipboard/fonts) is open, the Ola brand mark and the
     *  fixed gap after it just push that panel's back arrow away from the row's
     *  true start. Hiding both here - and only here, alongside the other
     *  panel-specific icon visibility toggles in toggleEmojiView/toggleClipboardView/
     *  toggleFontStyleView - lets the back arrow become the first thing in the row,
     *  flush against its left edge, exactly like it already is on the base keyboard
     *  screen when the logo itself is the flush-left icon. */
    private fun setLogoVisible(visible: Boolean) {
        binding.btnOlaLogo.visibility = if (visible) View.VISIBLE else View.GONE
        binding.logoSpacer.visibility = if (visible) View.VISIBLE else View.GONE
    }
    // suggestionContainer in the binding is a generated binding object; use its root view when a View is expected
    val suggestionContainerView: View get() = binding.suggestionContainer.root
    fun getSuggestionTextViews(): List<TextView> {
        val list = ArrayList<TextView>()
        // Use the root view of the suggestion container binding to access children
        val suggestionContainerRoot = binding.suggestionContainer.root

        if (suggestionContainerRoot is ViewGroup) {
            // Iterate children and check tag to ensure correct order (0, 1, 2)
            for (child in suggestionContainerRoot.children) {
                if (child is TextView && child.tag?.toString()?.startsWith("suggest_") == true) {
                    list.add(child)
                }
            }
            // Sort the list based on the numeric part of the tag
            list.sortBy { it.tag.toString().substringAfter("_").toIntOrNull() ?: Int.MAX_VALUE }
        }
        return list
    }

    // --- Recent emoji row + number row toggles ---
    // The quick "Recent" strip above the keys is redundant while a panel (emoji or
    // clipboard) is open, so we hide it while either is true.

    /**
     * The recent-emoji quick strip sits OUTSIDE the emoji/clipboard panel's own
     * FrameLayout - it's a sibling row between the top bar and the panel. It always
     * gets hidden while a panel is open (see below), so if it was showing on the
     * plain keyboard, that height needs to be added back onto the panel or the whole
     * keyboard visibly shrinks the instant a panel opens (and grows again on close).
     */
    private fun recentRowCompensation(): Int {
        val recent = EmojiData.emojis["Recent"] ?: emptyList()
        return if (showRecentEmojiRow && recent.isNotEmpty()) binding.recentEmojiRow.layoutParams.height else 0
    }

    /** Keeps the emoji/clipboard panels exactly as tall as the keyboard they cover. */
    private fun applyPanelHeights() {
        // keyRow2-5 (letter/space rows) are always the 4 "full height" rows; the
        // number row (keyRow1) is intentionally shorter (see NUM_ROW_HEIGHT_RATIO)
        // and only counts when it's actually showing.
        val currentRowHeight = binding.keyRow2.layoutParams.height
        val numRowHeightNow = binding.keyRow1.layoutParams.height
        val panelHeight = currentRowHeight * 4 +
            (if (showNumberRow) numRowHeightNow else 0) +
            recentRowCompensation()
        binding.emojiView.root.layoutParams.height = panelHeight
        binding.clipboardView.root.layoutParams.height = panelHeight
        // The text-select panel additionally swallows the top bar's own row (see
        // toggleTextSelectView), so while it's open it needs that row's height
        // added back on top, or the panel would come up short by exactly that much.
        val textSelectExtra = if (isTextSelectPanelOpen) binding.topBar.layoutParams.height else 0
        binding.textSelectView.root.layoutParams.height =
            max(panelHeight + textSelectExtra, dp(TEXT_SELECT_MIN_CONTENT_HEIGHT_DP))
        binding.fontStyleView.root.layoutParams.height = panelHeight
    }

    private fun updateRecentEmojiRowVisibility() {
        val recent = EmojiData.emojis["Recent"] ?: emptyList()
        binding.recentEmojiRow.visibility =
            if (showRecentEmojiRow && recent.isNotEmpty() && !isEmojiPanelOpen && !isClipboardPanelOpen && !isTextSelectPanelOpen && !isFontStylePanelOpen) View.VISIBLE else View.GONE
        // Whether the strip just appeared or disappeared, re-sync the panel heights
        // so the keyboard's total height never jumps when a panel opens/closes.
        applyPanelHeights()
        requestLayout()
    }

    /** Re-reads clip history from [ClipboardData] and refreshes the open panel's list/empty state. */
    fun refreshClipboardList() {
        if (!::clipboardAdapter.isInitialized) return
        val items = ClipboardData.filtered(currentClipFilter)
        clipboardAdapter.submit(items)
        binding.clipboardView.clipboardEmpty.isVisible = items.isEmpty()
        binding.clipboardView.clipboardList.isVisible = items.isNotEmpty()
        // "No clips yet" only makes sense with zero history overall - once a filter
        // is narrowing things down, an empty result means "nothing matched", not
        // "nothing's been copied".
        if (items.isEmpty()) {
            binding.clipboardView.clipboardEmpty.setText(
                if (currentClipFilter == ClipFilter.ALL) R.string.clipboard_empty
                else R.string.clipboard_empty_filtered
            )
        }
        // Small purple dot badge on top of btn_clip_filter's icon, shown only while a
        // non-default filter is active, so the user can tell at a glance the list is
        // narrowed even after the dropdown itself has closed.
        binding.btnClipFilter.background = AppCompatResources.getDrawable(
            context,
            if (currentClipFilter != ClipFilter.ALL) R.drawable.bg_clip_purple_circle_active
            else R.drawable.bg_clip_purple_circle
        )
    }

    /** Builds and shows the "Filter clips" dropdown anchored under [anchor] (btn_clip_filter). */
    private fun showClipFilterMenu(anchor: View) {
        clipFilterPopup?.dismiss()

        val popupContent = LayoutInflater.from(themedContext)
            .inflate(R.layout.popup_clip_filter, null)
        val optionsContainer = popupContent.findViewById<LinearLayout>(R.id.clip_filter_options)

        val options = listOf(
            ClipFilter.ALL to R.string.clip_filter_all,
            ClipFilter.RECENT_COPY to R.string.clip_filter_recent_copy,
            ClipFilter.RECENT_USED to R.string.clip_filter_recent_used,
            ClipFilter.FREQUENTLY_USED to R.string.clip_filter_frequent,
            ClipFilter.MOBILE_NUMBERS to R.string.clip_filter_mobile,
            ClipFilter.EMAILS to R.string.clip_filter_email,
            ClipFilter.LINKS to R.string.clip_filter_link
        )

        // NOT focusable: a focusable popup opens as its own focused window, and inside
        // an IME that makes Android think the keyboard's own window just lost focus -
        // which fires onFinishInputView() and resets the whole keyboard straight back
        // to the plain letters view the instant this button is tapped. Non-focusable
        // still gets taps fine (isTouchable defaults to true) and still dismisses on
        // an outside tap via isOutsideTouchable below - it just doesn't steal window
        // focus from the keyboard.
        val popup = PopupWindow(
            popupContent,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.isOutsideTouchable = true
        popup.elevation = 8f

        options.forEach { (filter, labelRes) ->
            val row = LayoutInflater.from(themedContext)
                .inflate(R.layout.item_clip_filter_option, optionsContainer, false)
            val label = row.findViewById<TextView>(R.id.clip_filter_option_label)
            val check = row.findViewById<ImageView>(R.id.clip_filter_option_check)

            label.setText(labelRes)
            val isActive = filter == currentClipFilter
            check.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            ImageViewCompat.setImageTintList(
                check,
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, R.color.clip_accent)
                )
            )
            if (isActive) label.setTextColor(
                androidx.core.content.ContextCompat.getColor(context, R.color.clip_accent)
            )

            row.setOnClickListener {
                currentClipFilter = filter
                refreshClipboardList()
                popup.dismiss()
            }
            optionsContainer.addView(row)
        }

        AppFont.applyRecursively(popupContent, context)

        clipFilterPopup = popup

        // Anchor it hanging below-right of the filter icon, matching where a
        // dropdown from a top-bar icon is expected to appear.
        popup.showAsDropDown(anchor, 0, 4, Gravity.END)
    }

    /** Closes the clipboard panel (e.g. right after a paste, or when the input field changes). */
    fun closeClipboardPanel() {
        if (isClipboardPanelOpen) closeClipboardPanelFn?.invoke()
    }

    /** Closes the Fonts panel (mirrors closeClipboardPanel). */
    fun closeFontStylePanel() {
        if (isFontStylePanelOpen) closeFontStylePanelFn?.invoke()
    }

    /** The style InputMethodService should currently apply to freshly-typed Latin
     *  text - read on every commit via FontStyleData.convert(). */
    fun currentFontStyle(): FontStyle = activeFontStyle

    /** Purple-circle badge on btn_fonts (same treatment as the clipboard's
     *  btn_clip_clear) so it's obvious at a glance that a style is active,
     *  without having to open the panel to check. */
    /** Purple-circle badge on btn_fonts (same treatment as the clipboard's
     *  btn_clip_clear) so it's obvious at a glance that a style is active, without
     *  having to open the panel to check. Restores the icon's normal circular
     *  background (from @style/TopBarIcon, same as the clipboard/emoji/text-select
     *  icons) rather than clearing it, so the icon isn't left flat/backgroundless
     *  once a style is picked and then turned back off. */
    private fun updateFontsIconBadge() {
        val active = activeFontStyle != FontStyle.NONE
        // Must resolve against themedContext (the ContextThemeWrapper that has the
        // custom Light/Dark theme applied), not the raw `context` - bg_top_bar_icon_circle
        // references ?attr/keyFunction/?attr/keyFunctionPressed, which are only defined
        // on the custom theme. Resolving them against the raw context silently failed
        // to produce a visible color, which is why btn_fonts's circular background
        // (and its back-arrow state, since it's the same ImageView) never showed up
        // even though the XML style/background were both correct.
        binding.btnFonts.background = AppCompatResources.getDrawable(
            themedContext,
            if (active) R.drawable.bg_clip_purple_circle else R.drawable.bg_top_bar_icon_circle
        )
    }

    /** Closes the emoji panel (e.g. when the input field changes or the keyboard is reopened). */
    fun closeEmojiPanel() {
        if (isEmojiPanelOpen) closeEmojiPanelFn?.invoke()
    }

    /** Resets the emoji panel to its defaults (Recent tab + start scroll position).
     *  Called from InputMethodService.resetKeyboardState() when the keyboard is
     *  fully hidden, so the next time the user opens the emoji tab (or the
     *  recent-emoji strip) it doesn't resume wherever they last left it. */
    fun resetEmojiPanelState() {
        resetEmojiPanelStateFn?.invoke()
    }

    /** Closes the text-select panel (e.g. when the input field changes or the keyboard is reopened). */
    fun closeTextSelectPanel() {
        if (isTextSelectPanelOpen) closeTextSelectPanelFn?.invoke()
    }

    /** Hot-toggle from Settings without recreating the whole KeyboardView. */
    fun setClipboardEnabled(enabled: Boolean) {
        clipboardEnabled = enabled
        binding.btnClipboard.isVisible = enabled
        if (!enabled && isClipboardPanelOpen) closeClipboardPanel()
    }

    /** Call after a new emoji is committed so the quick row reflects the latest "Recent" list. */
    fun refreshRecentEmojiRow() {
        if (!::recentEmojiAdapter.isInitialized) return
        recentEmojiAdapter.updateEmojis(EmojiData.emojis["Recent"] ?: emptyList())
        updateRecentEmojiRowVisibility()
    }

    /** Applies the latest Settings value without recreating the whole keyboard view. */
    fun setShowRecentEmojiRow(enabled: Boolean) {
        showRecentEmojiRow = enabled
        updateRecentEmojiRowVisibility()
    }

    /** Applies the latest Settings value without recreating the whole keyboard view. */
    fun setShowNumberRow(enabled: Boolean) {
        showNumberRow = enabled
        binding.keyRow1.visibility = if (enabled) View.VISIBLE else View.GONE
        // Keep the emoji/clipboard panels the same height as the keyboard whenever
        // the number row is toggled, using whatever row height is currently applied.
        applyPanelHeights()
        requestLayout()
    }

    /** Hot-update all row heights (called when height slider changes without keyboard recreate). */
    fun updateRowHeight(newRowHeight: Int) {
        // newRowHeight is the raw Settings slider value - a percentage of
        // BASE_ROW_HEIGHT_DP (see constructor comment above), not a dp value on its
        // own. Scale first, then convert through density, and keep rowHeightPx in
        // sync so the swipe-gesture Y-thresholds above (which compare against real
        // touch-event pixels) stay correct too.
        rowHeightPx = dp(BASE_ROW_HEIGHT_DP * newRowHeight / 100f)
        // recentEmojiRow is intentionally NOT updated here - its height is sized
        // off the emoji glyph size (recentEmojiRowHeightPx()) set at init, not
        // this keyboard-height slider.
        binding.keyRow1.layoutParams.height = numRowHeight(rowHeightPx)
        binding.keyRow2.layoutParams.height = rowHeightPx
        binding.keyRow3.layoutParams.height = rowHeightPx
        binding.keyRow4.layoutParams.height = rowHeightPx
        binding.keyRow5.layoutParams.height = rowHeightPx
        binding.emojiView.emojiBottomBar.layoutParams.height = rowHeightPx
        // emoji_categories_scroll no longer needs syncing here - it now lives in the
        // top bar with a fixed match_parent height (see keyboard_layout.xml).
        applyPanelHeights()
        requestLayout()
    }
}