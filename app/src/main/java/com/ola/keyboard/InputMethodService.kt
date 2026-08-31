package com.ola.keyboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.ola.keyboard.R
import com.ola.keyboard.Maps.keyLabelsLettersEnglish
import com.ola.keyboard.Maps.keyLabelsLettersEnglishShifted
import com.ola.keyboard.Maps.keyLabelsNumbers
import com.ola.keyboard.Maps.keyLabelsSpecialEnglish
import com.ola.keyboard.Maps.keyLabelsLettersWijesekara
import com.ola.keyboard.Maps.keyLabelsLettersWijesekaraShifted
import com.ola.keyboard.Maps.keyLabelsNumbersWijesekara
import com.ola.keyboard.Maps.keyLabelsSpecialWijesekaraSinhala
import com.ola.keyboard.Maps.keyLabelsSpecialWijesekaraSinhalaShifted
import com.ola.keyboard.Maps.singlishMap
import com.ola.keyboard.swaraSignMap
import com.ola.keyboard.Maps.symbolsMap
import com.ola.keyboard.Maps.symbolsMapShifted
import ime.suggest.SuggestionEngine
import ime.suggest.LanguageDetector
import ime.imeui.DebouncedInputHandler
import ime.imeui.TopBarController
import android.widget.TextView
import androidx.compose.ui.semantics.text
import java.text.Normalizer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

class InputMethodService : android.inputmethodservice.InputMethodService(),
    KeyboardView.ClickListener, KeyboardView.SwipeListener, LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private lateinit var keyboardView: KeyboardView
    private lateinit var keyboardLayout: KeyboardLayout

    private var caps = false
    private var shift = false


    private var keyboardSymbolsActive = false

    private var mComposing = ""
    private var tComposing = ""


    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)


    // --- Backspace history, for step-by-step Sinhala revert (e.g. තෝ -> තො -> ත් -> "") ---
    // Unicode has NO nested relationship between - and ‍- "ො" (short o) and "ෝ" (long o)
    // are two entirely separate codepoints, not one built on top of the other. So a plain
    // "delete 1 codepoint" can never walk through the intermediate steps a user expects;
    // the only way is to remember what each keystroke actually did and reverse it.
    private data class InputStep(
        val myOutput: String,          // exactly what this keystroke placed on screen
        val myWasComposable: Boolean,  // true if myOutput was left as an open composing
                                        // region (setComposingText) rather than committed
        val restoreText: String,       // what to put back in myOutput's place on undo
        val restoreLastChar: CHAR?,
        val restoreLastLetter: CHAR?,
        val restorePendingGaettaBase: CHAR?
    )

    private val inputHistory = ArrayDeque<InputStep>()

    /**
     * Deletes one grapheme cluster backwards from the cursor, without any step-by-step
     * history. Used when there's no usable [inputHistory] entry to revert to: plain
     * English text, the cursor moved, a field/app switch, or a conjunct wide enough
     * (e.g. the 4-unit gaetta-pilla cluster) that singlishInput() didn't try to make it
     * individually revertible.
     */
    private fun performRawBackspaceDelete(ic: android.view.inputmethod.InputConnection) {
        try {
            // Always finalize any open composing region first.
            // If we don't, deleteSurroundingText can interact badly
            // with the composing span and erase the wrong characters
            // (e.g. kombuwa, ispilla, papilla disappearing instead of
            // the character before them).
            ic.finishComposingText()

            // Read up to 3 chars before cursor so we can detect
            // Sinhala grapheme clusters that span multiple code units.
            // We avoid getSelectedText() here (IPC call on every
            // backspace tick) — instead we check whether there IS a
            // selection by comparing the extraction cursor positions,
            // which the framework already has cached locally.
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                // Selection present — delete the whole selection at once.
                ic.commitText("", 1)
            } else {
                // No selection — delete one grapheme cluster backwards.
                // Read enough chars to cover a kombuwa cluster:
                //   kombuwa (ෙ U+0DD9) sits BEFORE the consonant visually
                //   but AFTER it in the string — so "මෙ" in memory is
                //   ම (U+0DB8) + ෙ (U+0DD9). One codepoint = one delete.
                //   BUT rakaransaya / hal-kirima conjuncts can be
                //   consonant + ZWJ + RAYANNA + al-lakuna = 4 units.
                //   We walk back looking for a ZWJ right before the
                //   cluster and delete the whole thing if found.
                val before = ic.getTextBeforeCursor(4, 0)?.toString() ?: ""
                when {
                    // Rakaransaya / yansaya cluster: ends with al-lakuna
                    // preceded by ZWJ — delete 4 units at once so the
                    // whole conjunct disappears in one backspace.
                    before.length >= 4 &&
                    before[before.length - 4] == CHAR.ZERO_WIDTH_JOINER.text[0] -> {
                        ic.deleteSurroundingText(4, 0)
                    }
                    // ZWJ right before cursor (e.g. partial conjunct)
                    before.length >= 1 &&
                    before[before.length - 1] == CHAR.ZERO_WIDTH_JOINER.text[0] -> {
                        ic.deleteSurroundingText(1, 0)
                    }
                    else -> {
                        // Default: delete one codepoint (handles surrogate
                        // pairs = emoji correctly too).
                        ic.deleteSurroundingTextInCodePoints(1, 0)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("IME", "BACKSPACE operation failed", t)
        }
    }

    private var userInvokedInputMethodPicker = false

    // Cached once instead of calling getSystemService() on every single key press /
    // backspace-repeat tick — that lookup + Binder round trip was adding overhead to
    // every keystroke and was especially costly during fast backspace auto-repeat.
    private val vibratorService: Vibrator? by lazy {
        getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    private var suggestionEngine: SuggestionEngine? = null
    private var debouncer: DebouncedInputHandler? = null
    // Tracks the in-flight suggestion search+display coroutine so it can be
    // cancelled explicitly (fixes suggestions "sticking" after send/space/enter).
    private var suggestionJob: Job? = null
    private var topBarController: TopBarController? = null
    private var suggestionTextViews: List<TextView> = emptyList()
    // Purely a display toggle for the suggestion bar - see requestSuggestionsForToken(),
    // which is the only place this gates anything. Word LEARNING (learnLastTypedWord /
    // learnPendingWordIfFieldWasCleared) never checks this, by design: turning
    // suggestions off should only hide the bar, not stop the dictionary from improving
    // in the background. Re-read from Settings each time the keyboard is shown (see
    // onStartInputView), same pattern as every other toggle here.
    private var suggestionsEnabled = true

    // Lifecycle and SavedStateRegistry support
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        Log.d("IME", "onCreate called")
        suggestionEngine = SuggestionEngine(this)
        // Initialize engine asynchronously
        serviceScope.launch {
            suggestionEngine?.initializeIfNeeded()
        }
        // No artificial wait before the suggestion search starts - the search itself
        // still runs off the main thread (Dispatchers.Default in
        // requestSuggestionsForToken) and any in-flight search is cancelled the
        // moment a newer keystroke comes in, so firing immediately doesn't block
        // typing; it only makes the suggestion chip appear without a delay.
        debouncer = DebouncedInputHandler(serviceScope, 0L)

        EmojiData.loadRecentEmojis(this)
        ClipboardData.load(this)
        registerClipboardListener()
        getSharedPreferences("prefs", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(appearancePrefsListener)
    }

    // --- System clipboard auto-capture ---
    // Registers with the platform ClipboardManager so any text the user copies anywhere
    // on the device (not just inside this IME) is captured into clip history automatically.
    private val systemClipboardManager: android.content.ClipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    // Set right before we ourselves commit a clip via paste, so the resulting primary-clip
    // change (some apps re-broadcast the committed text as the new clip) isn't re-captured.
    private var suppressNextClipCapture = false

    // Set right before we ourselves commit text while the emoji panel is open (i.e. an
    // emoji tap), so the resulting cursor-position change doesn't trip the "user touched
    // the text field" auto-close in onUpdateSelection - otherwise typing several emojis
    // in a row kept getting kicked back to the normal keyboard after every single one.
    private var suppressNextSelectionAutoClose = false

    private val clipChangedListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressNextClipCapture) {
            suppressNextClipCapture = false
            return@OnPrimaryClipChangedListener
        }
        if (!Prefs.getClipboardEnabled(this)) return@OnPrimaryClipChangedListener
        try {
            val clip = systemClipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return@OnPrimaryClipChangedListener
            ClipboardData.add(this, text)
            if (::keyboardView.isInitialized) keyboardView.refreshClipboardList()
        } catch (t: Throwable) {
            Log.e("IME", "clipboard capture failed", t)
        }
    }

    private fun registerClipboardListener() {
        try {
            systemClipboardManager.addPrimaryClipChangedListener(clipChangedListener)
        } catch (t: Throwable) {
            Log.e("IME", "failed to register clipboard listener", t)
        }
    }


    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        try {
            systemClipboardManager.removePrimaryClipChangedListener(clipChangedListener)
        } catch (t: Throwable) {
            Log.e("IME", "failed to unregister clipboard listener", t)
        }
        try {
            getSharedPreferences("prefs", MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(appearancePrefsListener)
        } catch (t: Throwable) {
            Log.e("IME", "failed to unregister appearance prefs listener", t)
        }
        super.onDestroy()
        serviceJob.cancel()
        Log.d("IME", "onDestroy called")
    }

    private fun commitWijesekaraChar(char: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""

        val composed = when (before + char) {
            "අැ" -> "ඇ"
            "අා" -> "ආ"
            "එ්" -> "ඒ"
            "එෙ" -> "ඓ"
            "ෙඑ" -> "ඓ"
            "ඔ්" -> "ඕ"
            "උ්" -> "ඌ"


            // Consonant + 'e' sign combinations


            else -> null
        }

        if (composed != null) {
            // If we have a composition, delete the previous character and commit the new one.
            ic.deleteSurroundingText(1, 0)
            ic.commitText(composed, 1)
        } else {
            // Otherwise, just commit the character the user typed.
            ic.commitText(char, 1)
        }
    }



    // Settings that require a full KeyboardView rebuild to take effect, since they
    // drive the inflate-time theme/style or per-button text size and have no
    // cheap hot-update path (unlike number row / recent-emoji row, which update in
    // place). We snapshot what's currently applied and rebuild whenever Settings
    // has changed one of these since the keyboard was last shown.
    // height_percentage is included here too now - it does have its own lighter
    // hot-update path (KeyboardView.updateRowHeight, still called unconditionally
    // below in onStartInputView), but routing it through the same full-rebuild
    // path as text_size/theme as well is what actually guarantees it takes effect
    // on next open on every device, rather than depending on that lighter path
    // alone.
    private var appliedDarkTheme = false
    private var appliedKeyBorders = true
    private var appliedTextSize = -1
    private var appliedHeightPercentage = -1
    private var appliedEmojiStyle = EmojiStyle.SYSTEM
    private var appliedColorTheme = "ola"

    // Step 6: same full-rebuild reasoning as appliedColorTheme above - the
    // baked custom-image background + glass key drawables are painted once
    // in KeyboardView's init{} (applyCustomImageToKeyboard/applyGlassKeyStyling),
    // not hot-swappable in place, so a change to any of these needs a fresh
    // KeyboardView the same way a colour-theme change does.
    private var appliedBackgroundMode = "theme"
    private var appliedCustomBgOffsetX = 0.5f
    private var appliedCustomBgOffsetY = 0.5f
    private var appliedCustomBgBlur = 0f
    private var appliedCustomBgDarken = 0.25f
    private var appliedCustomBgZoom = 1f

    private fun rememberAppliedAppearancePrefs() {
        val prefs = Prefs(this)
        appliedDarkTheme = Prefs.getDarkTheme(this)
        appliedKeyBorders = Prefs.getKeyBorders(this)
        appliedTextSize = Prefs.getTextSize(this)
        appliedHeightPercentage = Prefs.getRowHeight(this)
        appliedEmojiStyle = Prefs.getEmojiStyle(this)
        appliedColorTheme = Prefs.getColorTheme(this)
        appliedBackgroundMode = prefs.backgroundMode
        appliedCustomBgOffsetX = prefs.customBgOffsetX
        appliedCustomBgOffsetY = prefs.customBgOffsetY
        appliedCustomBgBlur = prefs.customBgBlur
        appliedCustomBgDarken = prefs.customBgDarken
        appliedCustomBgZoom = prefs.customBgZoom
    }

    private fun appearancePrefsRequireRebuild(): Boolean {
        val prefs = Prefs(this)
        return appliedDarkTheme != Prefs.getDarkTheme(this) ||
            appliedKeyBorders != Prefs.getKeyBorders(this) ||
            appliedTextSize != Prefs.getTextSize(this) ||
            appliedHeightPercentage != Prefs.getRowHeight(this) ||
            appliedEmojiStyle != Prefs.getEmojiStyle(this) ||
            appliedColorTheme != Prefs.getColorTheme(this) ||
            appliedBackgroundMode != prefs.backgroundMode ||
            appliedCustomBgOffsetX != prefs.customBgOffsetX ||
            appliedCustomBgOffsetY != prefs.customBgOffsetY ||
            appliedCustomBgBlur != prefs.customBgBlur ||
            appliedCustomBgDarken != prefs.customBgDarken ||
            appliedCustomBgZoom != prefs.customBgZoom
    }

    /** Shared by onStartInputView's rebuild-on-reopen path and the live
     *  SharedPreferences listener below: tears down and re-inflates the
     *  KeyboardView against a fresh themed context so an appearance change
     *  (border / colour theme / dark theme / text size / emoji style) that
     *  has no cheap hot-update path actually takes effect. */
    private fun rebuildKeyboardViewForAppearanceChange() {
        if (!::keyboardView.isInitialized) return
        try {
            keyboardView = buildKeyboardView()
            rememberAppliedAppearancePrefs()
            setInputView(keyboardView)
            topBarController = TopBarController(
                keyboardView.suggestionContainerView,
                keyboardView.emojiButtonView,
                Prefs.getDarkTheme(this),
                keyboardView.clipboardButtonView,
                { Prefs.getClipboardEnabled(this) },
                keyboardView.textSelectButtonView,
                keyboardView.fontsButtonView,
                keyboardView.settingsButtonView,
                keyboardView.olaLogoButtonView,
                keyboardView.logoSpacerView,
                keyboardView.topBarIconRowView,
                Prefs.getEmojiStyle(this)
            )
            suggestionTextViews = keyboardView.getSuggestionTextViews()
            keyboardLayout = Prefs.getSelectedLayout(this)
            setKeyboardLayout(keyboardLayout)
        } catch (t: Throwable) {
            Log.e("IME", "Failed to rebuild keyboard view for changed appearance settings", t)
        }
    }

    // Fires the moment an appearance-affecting pref changes in Settings, so
    // Border / Colour Theme / Dark Theme / Text Size / Keyboard Height take effect
    // immediately - even if the keyboard is currently showing behind the Settings
    // screen and never gets a fresh onStartInputView call before the user switches
    // back to it. The onStartInputView-driven rebuild above still runs too
    // (harmless no-op if this listener already caught the change), so either path
    // alone is enough.
    private val appearancePrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "key_borders", "color_theme", "dark_theme", "automatic_theme",
                "text_size", "height_percentage", "emoji_style",
                // Step 6: same rebuild trigger as color_theme above - see
                // appliedBackgroundMode etc.
                "background_mode", "custom_bg_offset_x", "custom_bg_offset_y",
                "custom_bg_blur", "custom_bg_darken", "custom_bg_zoom" ->
                    rebuildKeyboardViewForAppearanceChange()
            }
        }

    private fun buildKeyboardView(): KeyboardView {
        val prefs = Prefs(this)
        return KeyboardView(
            this,
            this,
            this,
            Prefs.getRowHeight(this),
            Prefs.getDarkTheme(this),
            Prefs.getKeyBorders(this),
            Prefs.getSwipeToErase(this),
            Prefs.getSwipeToMoveCursor(this),
            Prefs.getTextSize(this),
            Prefs.getShowRecentEmojiRow(this),
            Prefs.getShowNumberRow(this),
            Prefs.getEmojiStyle(this),
            Prefs.getClipboardEnabled(this),
            Prefs.getFontStyle(this),
            Prefs.getColorTheme(this),
            prefs.backgroundMode,
            prefs.customBgOffsetX,
            prefs.customBgOffsetY,
            prefs.customBgBlur,
            prefs.customBgDarken,
            prefs.customBgZoom
        )
    }

    /**
     * Every freshly-typed-text commit routes through here instead of calling
     * ic.commitText() directly, so the active "fancy text" style (see FontStyleData,
     * KeyboardView.currentFontStyle) is applied consistently in one place rather than
     * at each of the ~9 scattered call sites. Deliberately NOT used for: emoji commits
     * (a style shouldn't touch an emoji/symbol tag), clipboard pastes (pasting existing
     * text should preserve it exactly, not silently restyle it), the empty-string
     * selection-delete, or backspace's history-based restore (that replays exactly what
     * was committed before, which was already styled going in - restyling it again would
     * double-apply combining marks like underline/strikethrough).
     */
    private fun commitStyled(ic: android.view.inputmethod.InputConnection, text: String) {
        val style = if (::keyboardView.isInitialized) keyboardView.currentFontStyle() else FontStyle.NONE
        ic.commitText(if (style != FontStyle.NONE) FontStyleData.convert(text, style) else text, 1)
    }

    override fun fontStyleSelected(style: FontStyle) {
        Prefs.setFontStyle(this, style)
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (::keyboardView.isInitialized) return keyboardView

        try {
            keyboardView = buildKeyboardView()
            rememberAppliedAppearancePrefs()

            keyboardLayout = Prefs.getSelectedLayout(this)
            setKeyboardLayout(keyboardLayout)

            // Setup top bar controller with views from keyboardView and pass dark theme preference
            topBarController = TopBarController(
                keyboardView.suggestionContainerView,
                keyboardView.emojiButtonView,
                Prefs.getDarkTheme(this),
                keyboardView.clipboardButtonView,
                { Prefs.getClipboardEnabled(this) },
                keyboardView.textSelectButtonView,
                keyboardView.fontsButtonView,
                keyboardView.settingsButtonView,
                keyboardView.olaLogoButtonView,
                keyboardView.logoSpacerView,
                keyboardView.topBarIconRowView,
                Prefs.getEmojiStyle(this)
            )
            suggestionTextViews = keyboardView.getSuggestionTextViews()

            return keyboardView
        } catch (t: Throwable) {
            Log.e("IME", "Keyboard view creation failed, providing safe fallback view", t)

            // Provide a safe, minimal fallback view so IME does not crash.
            val fallback = View(this)
            try {
                // Attempt to set a sensible background color from theme attr if available, else default to white/black depending on night mode
                val typedValue = android.util.TypedValue()
                val theme = theme
                // First try app-specific fox_background (safe, non-colliding), then fall back to platform background attr
                var got = theme.resolveAttribute(R.attr.fox_background, typedValue, true)
                if (!got) {
                    try {
                        got = theme.resolveAttribute(android.R.attr.background, typedValue, true)
                    } catch (_: Exception) {
                        // some devices/themes might not expose android attr; ignore and fallback below
                        got = false
                    }
                }
                if (got) {
                    if (typedValue.resourceId != 0) {
                        fallback.setBackgroundResource(typedValue.resourceId)
                    } else {
                        try {
                            fallback.setBackgroundColor(typedValue.data)
                        } catch (e: Exception) {
                            fallback.setBackgroundColor(if (Prefs.getDarkTheme(this)) 0xFF263238.toInt() else 0xFFECEFF1.toInt())
                        }
                    }
                } else {
                    fallback.setBackgroundColor(if (Prefs.getDarkTheme(this)) 0xFF263238.toInt() else 0xFFECEFF1.toInt())
                }
            } catch (inner: Throwable) {
                Log.e("IME", "Failed to set fallback background", inner)
            }

            // Return fallback view to avoid crashing the IME.
            return fallback
        }
    }

     override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
         super.onStartInputView(info, restarting)
         lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
         Log.d("IME", "onStartInputView called restarting=$restarting info=")

         // New field/session starting - clear the last-learned-word guard so a
         // coincidental text match with whatever was in the previous field can't
         // suppress a legitimate learn in this one.
         lastLearnedSnapshot = null

         // Reset to the default key screen (lowercase, no clipboard/emoji panel) every
         // time the keyboard is (re)shown - whether it was fully closed and reopened, or
         // the user just switched focus to a different field while it stayed up.
         resetKeyboardState()

         val desired = Prefs.getSelectedLayout(this)
         if (!::keyboardView.isInitialized) {

             onCreateInputView()
         } else if (appearancePrefsRequireRebuild()) {
             // Dark theme / key borders / text size drive the inflate-time style and
             // per-button text size — no cheap hot-update path, so rebuild the view
             // when Settings has changed one of these since the keyboard was last shown.
             // (Usually already caught live by appearancePrefsListener below - this is
             // just a safety net for whatever edge case reaches here first.)
             rebuildKeyboardViewForAppearanceChange()
         }

        // Re-apply latest toggle/value settings each time the keyboard is shown,
        // so Settings changes take effect without restarting the app.
        keyboardView.setShowRecentEmojiRow(Prefs.getShowRecentEmojiRow(this))
        keyboardView.setShowNumberRow(Prefs.getShowNumberRow(this))
        keyboardView.updateRowHeight(Prefs.getRowHeight(this))
        keyboardView.setClipboardEnabled(Prefs.getClipboardEnabled(this))
        suggestionsEnabled = Prefs.getShowSuggestionBar(this)
        if (!suggestionsEnabled) {
            // Off right now - drop anything left showing from the previous session
            // immediately rather than waiting for the next keystroke.
            topBarController?.showNormal()
        }
        // Pick up any emoji usage from the previous session — kept out of the live
        // typing session (see emojiClick) so the row doesn't reorder under the user's
        // finger while they're using it.
        keyboardView.refreshRecentEmojiRow()

        if (userInvokedInputMethodPicker) {

            userInvokedInputMethodPicker = false
            Log.d("IME", "Skipping automatic keyboard layout change after input method picker")
        } else {
            setKeyboardLayout(desired)
        }


        try {
            updateKeyboard()
        } catch (t: Throwable) {
            Log.e("IME", "updateKeyboard failed in onStartInputView", t)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d("IME", "onStartInput called restarting=$restarting")

        if (currentInputConnection == null && restarting) {
            resetKeyboardState()
        }


        try {
            updateKeyboard()
        } catch (t: Throwable) {
            Log.e("IME", "updateKeyboard failed in onStartInput", t)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        Log.d("IME", "onFinishInputView called finishingInput=$finishingInput")

        // Catches the one remaining gap: the user finishes a word then switches
        // apps, taps into a different field, or dismisses the keyboard entirely
        // without ever pressing space/punctuation/Enter. Without this, that last
        // word would never get learned.
        learnLastTypedWord(currentInputConnection)

        if (finishingInput) {
            resetKeyboardState()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // The field's text/cursor can change for reasons that never go through our own
        // key handlers - e.g. the host app clears the box after its own Send button is
        // tapped, or the cursor is moved by tapping elsewhere in the text. Without this,
        // whatever suggestion was showing beforehand stays stuck on screen indefinitely.
        // Re-derive the token at the new cursor position and refresh/hide the suggestion
        // bar to match what's actually there now.
        //
        // Skipped entirely while any panel (emoji/clipboard/text-select) is open:
        // topBarController.showNormal() unconditionally sets btnEmoji/btnClipboard back
        // to VISIBLE, which would undo the panel-specific icon hiding in KeyboardView
        // every time a cursor-moving button (or an emoji tap) fires this callback.
        if (!(::keyboardView.isInitialized && keyboardView.isAnyPanelOpen)) {
            try {
                val textBefore = currentInputConnection
                    ?.getTextBeforeCursor(50, 0)
                    ?.toString() ?: ""
                val token = textBefore.takeLastWhile { !it.isWhitespace() }
                if (token.isEmpty()) {
                    // No characters typed yet for the next word - if there's a
                    // previous word right before the cursor, offer next-word
                    // predictions for it instead of just clearing the bar (mirrors
                    // the space/punctuation handling in specialClick()). If the
                    // field is genuinely empty (nothing before the cursor at all),
                    // fall back to the old clear-the-bar behavior.
                    val previousWord = textBefore.trimEnd().takeLastWhile { !it.isWhitespace() }
                    if (previousWord.isBlank()) {
                        topBarController?.showNormal()
                    } else {
                        requestSuggestionsForToken("", previousWord)
                    }
                    // Might be a normal space/newline (already learned via the
                    // space/Enter handler) or the host app's own Send button
                    // clearing the whole field out from under us - the function
                    // itself tells those apart and only acts on the latter.
                    learnPendingWordIfFieldWasCleared(currentInputConnection)
                } else {
                    val previousWord = textBefore.dropLast(token.length).trimEnd().takeLastWhile { !it.isWhitespace() }
                    requestSuggestionsForToken(token, previousWord)
                    if (!isInPasswordField()) {
                        // Keep the in-progress word cached in case the field gets
                        // cleared before any of our own handlers see it - see
                        // learnPendingWordIfFieldWasCleared().
                        pendingWordCache = token
                        pendingWordPreviousCache = previousWord
                    }
                }
            } catch (_: Throwable) {}
        }

        // The cursor/selection can only change like this while a panel is open if the
        // user tapped directly in the app's text field (our own key clicks don't move
        // the cursor via touch) - EXCEPT for an emoji tap, which also commits text while
        // the emoji panel is open but should keep the panel open so several emojis can
        // be typed in a row. That case sets suppressNextSelectionAutoClose beforehand.
        if (suppressNextSelectionAutoClose) {
            suppressNextSelectionAutoClose = false
        } else if (::keyboardView.isInitialized) {
            keyboardView.closeClipboardPanel()
            keyboardView.closeEmojiPanel()
            keyboardView.closeTextSelectPanel()
            keyboardView.closeFontStylePanel()
        }
    }


    private fun resetKeyboardState() {
        // Finish any dangling open composing region before switching fields/apps
        // or hiding the keyboard, so it doesn't leak into whatever comes next.
        try {
            currentInputConnection?.finishComposingText()
        } catch (_: Throwable) {}
        mComposing = ""
        tComposing = ""
        // Always come back to the plain key screen in lowercase - whether the keyboard
        // is being (re)shown after being fully closed, or the user has just switched to
        // a different text field - regardless of which panel (clipboard/emoji) or shift
        // state it was left in, and regardless of language layout.
        caps = false
        shift = false
        if (::keyboardView.isInitialized) {
            keyboardView.closeClipboardPanel()
            keyboardView.closeEmojiPanel()
            keyboardView.closeTextSelectPanel()
            keyboardView.closeFontStylePanel()
            keyboardView.resetEmojiPanelState()
        }
        // Also drop any leftover suggestion bar/state from the previous field or app -
        // otherwise a suggestion chip computed for the old text stays on screen after
        // switching to a new app/field, since nothing else here re-derives it.
        debouncer?.cancel()
        suggestionJob?.cancel()
        topBarController?.showNormal()
        // A cached in-progress word belongs to whatever field/app we were just in -
        // carrying it over to a new field would risk learning it under the wrong
        // context (or not at all, since it's stale) if that new field happens to
        // go empty for some unrelated reason.
        pendingWordCache = ""
        pendingWordPreviousCache = ""
    }

    override fun onEvaluateFullscreenMode(): Boolean {

        return false
    }

    // Helper to request suggestions for a token. previousWord is the word right
    // before the one being typed (if any) — feeds the bigram next-word boost.
    private fun requestSuggestionsForToken(token: String, previousWord: String = "") {
        if (!suggestionsEnabled || isInPasswordField()) {
            topBarController?.showNormal()
            return
        }
        // Debounced (short delay) then computed off the main thread. The previous
        // in-flight search is explicitly cancelled before starting a new one —
        // otherwise a slow/stale search can finish after the user has already
        // moved on (sent the message, pressed space) and re-show a stale suggestion.
        debouncer?.onTyping(token, onTypingImmediate = { t ->
            suggestionJob?.cancel()
            suggestionJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    // Empty token means a word was just finished (space/punctuation
                    // just committed) and nothing's been typed for the next one yet -
                    // predict likely next words from the previous word instead of a
                    // prefix match.
                    val sList = if (t.isEmpty()) {
                        if (previousWord.isBlank()) emptyList() else {
                            suggestionEngine?.suggestNextWord(previousWord, 4) ?: emptyList()
                        }
                    } else {
                        suggestionEngine?.suggest(Normalizer.normalize(t, Normalizer.Form.NFC), 4, previousWord)
                            ?: emptyList()
                    }
                    // isActive check: if cancelled while suggest() was running, don't
                    // push a now-stale result to the UI.
                    if (!isActive) return@launch
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (sList.isNotEmpty()) {
                            // Re-read the live pref here instead of trusting whatever
                            // EmojiStyle topBarController was constructed with. Unlike
                            // ClipboardAdapter (which calls Prefs.getEmojiStyle(context)
                            // fresh on every single bind), topBarController's emojiStyle
                            // is a snapshot taken once - at onCreateInputView(), or at the
                            // last full appearance rebuild - so if the user opens Settings
                            // and picks/changes their custom font while this same keyboard
                            // session is still running, that snapshot only refreshes if the
                            // rebuild path happens to catch it. Clipboard previews always
                            // reflected the change immediately for exactly this reason;
                            // suggestion chips didn't. Setting it here, right before every
                            // render, closes that gap the same way.
                            topBarController?.setEmojiStyle(Prefs.getEmojiStyle(this@InputMethodService))
                            topBarController?.showSuggestions(sList, suggestionTextViews) { suggestion ->
                                onSuggestionClicked(suggestion)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        topBarController?.showNormal()
                    }
                }
            }
        }, onIdle = null)
    }


    override fun letterOrSymbolClick(tag: String) {
        when {
            keyboardLayout == KeyboardLayout.SINGLISH && !keyboardSymbolsActive -> {
                singlishInput(tag)
            }

            keyboardLayout == KeyboardLayout.WIJESEKARA && !keyboardSymbolsActive -> {
                commitWijesekaraChar(tag)
            }

            else -> {
                // Finalize any open Singlish composing region before committing
                // plain text (e.g. switched to English mid-open-region) - commitText()
                // replaces an open composing span rather than appending after it.
                currentInputConnection?.finishComposingText()
                currentInputConnection?.let { commitStyled(it, tag) }
            }
        }

        // This same handler also fires for the symbols panel (?, !, @, /, etc. -
        // the letter keys get remapped to show symbols while it's open), which
        // ends the word before it exactly like space/comma/dot do on the main
        // keyboard. Gate on "not a letter" so normal Singlish/Wijesekara/English
        // letter taps (which are still composing a word) never trigger this -
        // only an actual symbol commit does. Without this, a message finished
        // with "?" or a symbols-panel character never had its last word learned.
        val isWordBoundary = tag.isNotEmpty() && tag.none { it.isLetter() }
        if (isWordBoundary) {
            learnLastTypedWord(currentInputConnection)
        }

        // The character is already committed above, so the keypress itself is done -
        // everything below (haptics + fetching text from the host app to compute
        // suggestions) is bookkeeping, not part of what the user is waiting to see.
        // Posting it to run right after this touch event finishes lets the touch
        // dispatcher move on to the next key immediately instead of blocking on a
        // cross-process getTextBeforeCursor() call before it can accept the next tap -
        // this is what caused typing to feel laggy on fast/back-to-back key presses.
        serviceScope.launch {
            vibrate()

            try {
                val textBefore = currentInputConnection
                    ?.getTextBeforeCursor(50, 0)
                    ?.toString() ?: ""
                val token = textBefore.takeLastWhile { !it.isWhitespace() }
                val previousWord = textBefore.dropLast(token.length).trimEnd().takeLastWhile { !it.isWhitespace() }
                requestSuggestionsForToken(token, previousWord)
            } catch (_: Throwable) {}
        }

        checkAutoUnshift()
    }

    private fun checkAutoUnshift() {
        if (caps && !shift) {
            caps = false
            updateKeyboard()
        }
    }

    private fun isInPasswordField(): Boolean {
        val t = currentInputEditorInfo ?: return false
        // Compare against the full variation field (not just AND-with-itself against
        // one constant) so this correctly catches ALL password-style variations, not
        // just the plain one - web login forms and "show password" fields use a
        // different variation value that the old check silently missed.
        val variation = t.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun onSuggestionClicked(suggestion: String) {
        val ic = currentInputConnection ?: return

        // finishComposingText() FIRST, before we read the surrounding text or try
        // to delete anything. The current token is very often still an open
        // composing region (e.g. mid-way through the Singlish transliteration for
        // a matra like ඇ) - deleteSurroundingTextInCodePoints() on some apps'
        // input connections (e.g. Telegram) does not reliably remove text that's
        // still part of an open composing span, since the composing span is
        // handled as a special, not-yet-final edit. Committing it first turns it
        // into plain text, so the delete below actually removes it instead of
        // leaving it in place and appending the suggestion right after it.
        ic.finishComposingText()

        // Replace current token with suggestion
        val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(100, 0)?.toString() ?: ""
        val tokenStart = before.lastIndexOfAny(charArrayOf(' ', '\n', '\t')).let { if (it < 0) 0 else it + 1 }
        val token = before.substring(tokenStart)
        // Word before the one being replaced — feeds the bigram model below.
        val previousWordForBigram = before.substring(0, tokenStart).trimEnd().takeLastWhile { !it.isWhitespace() }
        // delete token
        for (i in 0 until token.codePointCount(0, token.length)) {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
        // commit suggestion, followed by a single space so the user can keep typing the next word
        commitStyled(ic, "$suggestion ")

        // Mirror the normal space-bar bookkeeping, since we just committed a space too.
        lastChar = null
        lastLetter = null
        positionFlag = ""
        mComposing = ""
        tComposing = ""
        inputHistory.clear()

        // record acceptance
        serviceScope.launch {
            val lang = LanguageDetector.detectLanguage(suggestion)
            suggestionEngine?.recordAccepted(suggestion, lang, previousWordForBigram)
        }

        // Hide suggestions now that the word is complete (word + space), same as pressing space.
        topBarController?.showNormal()
        debouncer?.cancel()
        suggestionJob?.cancel()
    }

    private var lastChar: CHAR? = null
    private var lastLetter: CHAR? = null
    private var positionFlag = ""

    // Holds the base consonant when "r" forms a rakaransaya right after a consonant+al-lakuna.
    // If the very next key is "u", we retro-convert that rakar into a gaetta pilla (vocalic-r
    // matra) instead, so "kru"/"shru"/etc. produce කෘ/ශෘ style output instead of ක්‍ර/ශ්‍ර.
    private var pendingGaettaPillaBase: CHAR? = null

    private fun hasPositionChanged(): Boolean =
        currentInputConnection.getTextBeforeCursor(5, 0)?.toString() != positionFlag

    // Vowel signs/letters that can still be "doubled" into a longer form by a
    // matching second keystroke (e.g. short-o ො + o -> long-o ෝ). When the output
    // this keystroke produces is one of these, we commit it as *composing* text
    // instead of final text - so if the next key really is the matching doubler,
    // we can swap the composing region's content directly (one InputConnection
    // call) instead of erase-then-commit (two calls), which is what caused the
    // visible "jump"/flicker when finishing characters like මෝ.
    private val doublableVowelCodes = setOf(
        CHAR.KETTI_AEDA_PILLA.code, CHAR.KETTI_IS_PILLA.code, CHAR.KETTI_PAA_PILLA.code,
        CHAR.KOMBUVA.code, CHAR.KOMBUVA_HAA_AELA_PILLA.code,
        CHAR.AYANNA.code, CHAR.AEYANNA.code, CHAR.IYANNA.code, CHAR.UYANNA.code,
        CHAR.EYANNA.code, CHAR.OYANNA.code
    )

    // Consonants whose bare (al-lakuna) form can still be swapped for a different
    // consonant by a following "h" (retroflex/dental/plain-vs-aspirated pairs, e.g.
    // ට් + h -> ත්, බ් + h -> භ්). Same flicker as the vowels above, and same fix:
    // keep the freshly-typed consonant+al-lakuna as an open composing region so the
    // "h" can swap it directly instead of erase(2)+commit.
    private val hConvertibleConsonantCodes = setOf(
        CHAR.ALPAPRAANA_TTAYANNA.code, CHAR.ALPAPRAANA_BAYANNA.code, CHAR.DANTAJA_SAYANNA.code
    )

    private fun singlishInput(input: String) {
        var output = ""
        var erasePreviousChars = 0
        var mLastChar: CHAR? = null
        var mLastLetter: CHAR? = null
        var tLastChar: CHAR? = null
        var tLastLetter: CHAR? = null
        // Set to true by the branches below when this keystroke's output is either
        // (a) a short vowel that might still be doubled, or (b) the doubled result
        // itself replacing an already-open composing region.
        var composable = false

        // True only when `composable` above was just opened FRESH by this keystroke
        // (newLetter()'s h-convertible-consonant case, or a bare vowel at word start) -
        // as opposed to a doubling keystroke that's replacing the content of an
        // ALREADY-open region from the previous keystroke (e.g. o -> oo). A fresh
        // region can land right after a DIFFERENT region that the previous keystroke
        // left open (e.g. "දු" is still open as ු when "ba" starts a new ට/බ-style
        // composing region) - if we call setComposingText() there without finalizing
        // first, Android silently REPLACES the still-open ු instead of keeping it and
        // appending after it, so it vanishes (e.g. "දුබ" -> "දබ"). finishComposingText()
        // right before setComposingText() closes that gap; it's a safe no-op when
        // nothing was actually left open.
        var freshComposable = false

        if (!hasPositionChanged()) {
            mLastChar = lastChar
            mLastLetter = lastLetter
        }

        if (mLastChar == null && mLastLetter == null) {
            // Cursor moved, or this is the very first character of a fresh word -
            // any history from before belongs to a different position and would be
            // wrong to revert into now.
            inputHistory.clear()
        }

        lastChar = null
        lastLetter = null

        // Snapshot and clear; only reused this call if the "ru" pattern below actually matches.
        val pendingGaettaBase = pendingGaettaPillaBase
        pendingGaettaPillaBase = null

        var singlishChar: CHAR = getSinglishChars(input) ?: CHAR.EMPTY

        fun newLetter() {
            output = singlishChar.text
            if (singlishChar.type == CharType.WYANJANA) {
                output += CHAR.SIGN_AL_LAKUNA.text
                tLastChar = CHAR.SIGN_AL_LAKUNA
                // Keep it open in case the next key is "h" and converts this into a
                // different consonant (e.g. ට් -> ත්) - see hConvertibleConsonantCodes.
                if (singlishChar.code in hConvertibleConsonantCodes) {
                    composable = true
                    freshComposable = true
                }
            }
        }

        if (mLastChar == null || mLastChar == CHAR.EMPTY) {
            if (input == "z" || input == "Z") tLastChar = CHAR.MARK_SANYAKA
            else {
                newLetter()
                // Fresh word starting with a bare vowel letter (e.g. "e" -> එ) -
                // keep it open in case the next key doubles it (e.g. "ee" -> ඒ).
                if (singlishChar.code in doublableVowelCodes) {
                    composable = true
                    freshComposable = true
                }
            }
        } else {
            when {
                input == "z" || input == "Z" -> tLastChar = CHAR.MARK_SANYAKA

                pendingGaettaBase != null && mLastChar.code == CHAR.SIGN_AL_LAKUNA.code && singlishChar.code == CHAR.UYANNA.code -> {
                    // "r" just added a rakar (ZWJ + RAYANNA + al-lakuna) on top of the base
                    // consonant's al-lakuna. Erase all 4 of those units and drop in the
                    // gaetta pilla instead, turning e.g. "k" + "r" + "u" into කෘ.
                    output = CHAR.GAETTA_PILLA.text
                    erasePreviousChars = 4
                    tLastLetter = pendingGaettaBase
                    tLastChar = CHAR.GAETTA_PILLA
                }

                mLastChar.type == CharType.WYANJANA ->
                    when (singlishChar.code) {
                        CHAR.AYANNA.code -> output = CHAR.AELA_PILLA.text
                        CHAR.IYANNA.code -> output = CHAR.KOMBU_DEKA.text
                        CHAR.UYANNA.code -> output = CHAR.KOMBUVA_HAA_GAYANUKITTA.text
                        else -> newLetter()
                    }

                mLastChar.code == CHAR.SIGN_AL_LAKUNA.code -> {
                    when (singlishChar.code) {
                        CHAR.AYANNA.code -> {
                            erasePreviousChars = 1
                            tLastChar = mLastLetter
                        }

                        CHAR.RAYANNA.code -> {
                            output =
                                CHAR.ZERO_WIDTH_JOINER.text + CHAR.RAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                            tLastChar = CHAR.SIGN_AL_LAKUNA
                            // Remember the base consonant in case the next key is "u",
                            // which should convert this rakar into a gaetta pilla (see above).
                            pendingGaettaPillaBase = mLastLetter
                        }

                        CHAR.YAYANNA.code -> {
                            output =
                                CHAR.ZERO_WIDTH_JOINER.text + CHAR.YAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                            tLastChar = CHAR.SIGN_AL_LAKUNA
                        }

                        CHAR.HAYANNA.code -> {
                            if (mLastLetter != null) {
                                when (mLastLetter.code) {
                                    CHAR.ALPAPRAANA_TTAYANNA.code -> {
                                        // Composing region already holds ට් (2 units) -
                                        // swap it directly for ත්, no erase needed.
                                        output =
                                            CHAR.ALPAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        tLastLetter = CHAR.ALPAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                        composable = true
                                    }

                                    CHAR.MAHAAPRAANA_TTAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_DDAYANNA.code -> {
                                        output =
                                            CHAR.ALPAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.ALPAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.MAHAAPRAANA_DDAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_KAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_KAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_KAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_GAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_GAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_GAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_CAYANNA.code -> {
                                        // "ch" should stay as ච් (al-lakuna form), not upgrade
                                        // to the rare aspirated ඡ - unlike t/d/s, ච has no
                                        // separate retroflex/dental/plain-sh pair, so "h" here
                                        // is just absorbed: no visible change, state unchanged,
                                        // so a following "a" still reveals plain ච (not ඡ).
                                        output = ""
                                        erasePreviousChars = 0
                                        tLastLetter = mLastLetter
                                        tLastChar = mLastChar
                                    }

                                    CHAR.ALPAPRAANA_JAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_JAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_JAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_TAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_TAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_TAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_DAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_PAYANNA.code -> {
                                        output =
                                            CHAR.MAHAAPRAANA_PAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MAHAAPRAANA_PAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.ALPAPRAANA_BAYANNA.code -> {
                                        // Composing region already holds බ් (2 units) -
                                        // swap it directly for භ්, no erase needed.
                                        output =
                                            CHAR.MAHAAPRAANA_BAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        tLastLetter = CHAR.MAHAAPRAANA_BAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                        composable = true
                                    }

                                    CHAR.DANTAJA_SAYANNA.code -> {
                                        // Composing region already holds ස් (2 units, now that
                                        // DANTAJA_SAYANNA is in hConvertibleConsonantCodes) -
                                        // swap it directly for ශ්, no erase needed. Same fix as
                                        // ට්->ත් and බ්->භ් above.
                                        output =
                                            CHAR.TAALUJA_SAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        tLastLetter = CHAR.TAALUJA_SAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                        composable = true
                                    }

                                    CHAR.SANYAKA_DDAYANNA.code -> {
                                        output =
                                            CHAR.SANYAKA_DAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.SANYAKA_DAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    CHAR.MUURDHAJA_SAYANNA.code -> {
                                        output =
                                            CHAR.MUURDHAJA_SAYANNA.text + CHAR.SIGN_AL_LAKUNA.text
                                        erasePreviousChars = 2
                                        tLastLetter = CHAR.MUURDHAJA_SAYANNA
                                        tLastChar = CHAR.SIGN_AL_LAKUNA
                                    }

                                    else -> newLetter()
                                }
                            } else newLetter()
                        }

                        else -> {
                            when (singlishChar.type) {
                                CharType.SWARA -> {
                                    if (mLastLetter != null) {
                                        if (singlishChar.code == CHAR.AYANNA.code)
                                            erasePreviousChars = 1
                                        else {
                                            swaraSignMap[singlishChar.code].let { sign ->
                                                if (sign != null) {
                                                    output += sign.text
                                                    tLastChar = sign
                                                    erasePreviousChars = 1
                                                    // Short matra just attached to the consonant
                                                    // (e.g. ම් -> මො) - keep it open in case the
                                                    // next key doubles it (e.g. -> මෝ).
                                                    if (sign.code in doublableVowelCodes) composable = true
                                                } else output = singlishChar.text
                                            }
                                        }
                                    } else output = singlishChar.text
                                }

                                else -> newLetter()
                            }
                        }
                    }
                }

                mLastChar.type == CharType.PILI -> {
                    when {
                        mLastChar.code == CHAR.KETTI_AEDA_PILLA.code && singlishChar.code == CHAR.AYANNA.code -> {
                            // Composing region already holds the short matra (KETTI_AEDA_PILLA) -
                            // just swap its content, no erase needed (avoids the double
                            // reorder/flicker that a separate erase+commit would cause).
                            output = CHAR.DIGA_AEDA_PILLA.text
                            tLastChar = CHAR.DIGA_AEDA_PILLA
                            composable = true
                        }

                        mLastChar.code == CHAR.KETTI_IS_PILLA.code && singlishChar.code == CHAR.IYANNA.code -> {
                            output = CHAR.DIGA_IS_PILLA.text
                            tLastChar = CHAR.DIGA_IS_PILLA
                            composable = true
                        }

                        mLastChar.code == CHAR.KETTI_PAA_PILLA.code && singlishChar.code == CHAR.UYANNA.code -> {
                            output = CHAR.DIGA_PAA_PILLA.text
                            tLastChar = CHAR.DIGA_PAA_PILLA
                            composable = true
                        }

                        mLastChar.code == CHAR.GAETTA_PILLA.code && singlishChar.code == CHAR.IYANNA.code -> {
                            // Vocalic-r lengthening (කෘ -> කෲ) is out of scope for the
                            // composing-text fix for now - keeps the original erase+commit path.
                            output = CHAR.DIGA_GAETTA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_GAETTA_PILLA
                        }

                        mLastChar.code == CHAR.KOMBUVA.code && singlishChar.code == CHAR.EYANNA.code -> {
                            output = CHAR.DIGA_KOMBUVA.text
                            tLastChar = CHAR.DIGA_KOMBUVA
                            composable = true
                        }

                        mLastChar.code == CHAR.KOMBUVA_HAA_AELA_PILLA.code && singlishChar.code == CHAR.OYANNA.code -> {
                            output = CHAR.KOMBUVA_HAA_DIGA_AELA_PILLA.text
                            tLastChar = CHAR.KOMBUVA_HAA_DIGA_AELA_PILLA
                            composable = true
                        }

                        mLastChar.code == CHAR.GAETTA_PILLA.code && singlishChar.code == CHAR.UYANNA.code -> {
                            // Second "u" lengthens the gaetta pilla, e.g. "kru" + "u" -> කෲ.
                            output = CHAR.DIGA_GAETTA_PILLA.text
                            erasePreviousChars = 1
                            tLastChar = CHAR.DIGA_GAETTA_PILLA
                        }

                        else -> newLetter()
                    }
                }

                mLastChar.code == CHAR.MARK_SANYAKA.code -> {
                    when (singlishChar.code) {
                        CHAR.ALPAPRAANA_KAYANNA.code -> singlishChar = CHAR.TAALUJA_NAASIKYAYA
                        CHAR.ALPAPRAANA_GAYANNA.code -> singlishChar = CHAR.SANYAKA_GAYANNA
                        CHAR.ALPAPRAANA_JAYANNA.code -> singlishChar = CHAR.SANYAKA_JAYANNA
                        CHAR.ALPAPRAANA_DDAYANNA.code -> singlishChar = CHAR.SANYAKA_DDAYANNA
                        CHAR.ALPAPRAANA_DAYANNA.code -> singlishChar = CHAR.SANYAKA_DAYANNA
                        CHAR.ALPAPRAANA_BAYANNA.code -> singlishChar = CHAR.AMBA_BAYANNA
                        CHAR.HAYANNA.code -> singlishChar = CHAR.TAALUJA_SANYOOGA_NAAKSIKYAYA
                    }
                    newLetter()
                }

                else -> {
                    if (mLastLetter != null) {
                        when (mLastLetter) {
                            CHAR.AYANNA -> {
                                when (singlishChar.code) {
                                    CHAR.AYANNA.code -> {
                                        output = CHAR.AAYANNA.text
                                        tLastLetter = CHAR.AAYANNA
                                        composable = true
                                    }

                                    CHAR.IYANNA.code -> {
                                        output = CHAR.AIYANNA.text
                                        tLastLetter = CHAR.AIYANNA
                                        composable = true
                                    }

                                    CHAR.UYANNA.code -> {
                                        output = CHAR.AUYANNA.text
                                        tLastLetter = CHAR.AUYANNA
                                        composable = true
                                    }

                                    else -> newLetter()
                                }
                            }

                            CHAR.AEYANNA -> {
                                if (singlishChar.code == CHAR.AYANNA.code) {
                                    output = CHAR.AEEYANNA.text
                                    tLastLetter = CHAR.AEEYANNA
                                    composable = true
                                } else newLetter()
                            }

                            CHAR.IYANNA -> {
                                if (singlishChar.code == CHAR.IYANNA.code) {
                                    output = CHAR.IIYANNA.text
                                    tLastLetter = CHAR.IIYANNA
                                    composable = true
                                } else newLetter()
                            }

                            CHAR.UYANNA -> {
                                if (singlishChar.code == CHAR.UYANNA.code) {
                                    output = CHAR.UUYANNA.text
                                    tLastLetter = CHAR.UUYANNA
                                    composable = true
                                } else newLetter()
                            }

                            CHAR.IRUYANNA -> {
                                // Vocalic-r lengthening is out of scope for the composing-text
                                // fix for now - keeps the original erase+commit path.
                                if (singlishChar.code == CHAR.IYANNA.code) {
                                    output = CHAR.IRUUYANNA.text
                                    erasePreviousChars = 1
                                    tLastLetter = CHAR.IRUUYANNA
                                } else newLetter()
                            }

                            CHAR.EYANNA -> {
                                if (singlishChar.code == CHAR.EYANNA.code) {
                                    output = CHAR.EEYANNA.text
                                    tLastLetter = CHAR.EEYANNA
                                    composable = true
                                } else newLetter()
                            }

                            CHAR.OYANNA -> {
                                if (singlishChar.code == CHAR.OYANNA.code) {
                                    output = CHAR.OOYANNA.text
                                    tLastLetter = CHAR.OOYANNA
                                    composable = true
                                } else newLetter()
                            }

                            else -> newLetter()
                        }
                    } else newLetter()
                }
            }
        }

        // Sinhala conjuncts (gaetta pilla, rakaransaya, etc.) need an erase THEN a
        // commit - two separate InputConnection calls where a plain English key only
        // ever needs one. Wrapping them in begin/endBatchEdit tells the host app to
        // treat both as a single edit and redraw once, instead of once per call -
        // this is the extra overhead that's unique to Sinhala composition and the
        // main remaining gap versus a plain-Latin keyboard.
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            try {
                // If a previous keystroke left an open composing region (setComposingText)
                // and this keystroke needs to erase characters before committing,
                // we must finalize the composing region first. Otherwise, Android's
                // deleteSurroundingText() interacts badly with the open composing span
                // and can erase the wrong characters (e.g. kombuwa/matra disappearing).
                // finishComposingText() is a safe no-op when no region is open.
                if (erasePreviousChars > 0) {
                    ic.finishComposingText()
                    erasePrevious(erasePreviousChars)
                }
                if (composable) {
                    // If this keystroke is opening a BRAND NEW composing region
                    // (freshComposable) rather than replacing the content of one that
                    // was already open, finalize whatever's currently open first.
                    // Otherwise setComposingText() below would silently replace it
                    // instead of leaving it in place - e.g. "දු" (ු still open) + "b"
                    // opening a fresh ට/බ-style region would erase the ු instead of
                    // keeping it and appending "බ්" after it. Safe no-op if nothing
                    // was actually left open.
                    if (freshComposable) ic.finishComposingText()
                    // Leave this as an open composing region instead of finalizing it -
                    // if the next key doubles the vowel, we just swap this region's
                    // content directly (see the composable branches above) instead of
                    // erasing and re-committing, which removes the visible flicker.
                    // Committing anything else afterwards (a different letter, space,
                    // punctuation, etc.) auto-finishes this region per the
                    // InputConnection.commitText() contract, so no extra cleanup needed.
                    ic.setComposingText(output, 1)
                } else {
                    // Finalize any open composing region left by the PREVIOUS keystroke
                    // before this one's commit - unconditionally, regardless of whether
                    // THIS keystroke's own output is empty. Guarding this on
                    // output.isNotEmpty() was the bug: "z" (see MARK_SANYAKA above, the
                    // nasalized-consonant prefix - z+g -> ඟ, z+j etc.) deliberately
                    // produces no visible output on its own first press, since it just
                    // remembers "nasalize whatever consonant comes next". That empty
                    // output meant finishComposingText() got skipped here entirely, so
                    // commitStyled(ic, "") ran directly against whatever the PREVIOUS
                    // keystroke had left open in an active composing region -
                    // InputConnection.commitText() replaces an open region's content
                    // with the given text, so committing "" silently erased it: a bare
                    // vowel like අ vanished outright, and a still-open vowel sign like
                    // පෝ's ෝ or ජෝ's ෝ got wiped back down to the bare consonant. Since
                    // finishComposingText() only LOCKS IN whatever's already visible and
                    // never deletes anything, calling it first - every time, not just
                    // when output is non-empty - is always safe and fixes this for any
                    // keystroke that legitimately has nothing new to show yet.
                    ic.finishComposingText()
                    if (output.isNotEmpty()) commitStyled(ic, output)
                }

                // Record how to undo this exact keystroke, so BACKSPACE can walk back
                // through it step by step instead of just deleting raw codepoints.
                val previousTop = inputHistory.lastOrNull()
                val historyEntry: InputStep? = when {
                    erasePreviousChars == 0 && composable && !freshComposable && previousTop != null && previousTop.myWasComposable ->
                        // This step replaced the still-open region from the previous
                        // keystroke wholesale (e.g. o -> oo) with no erase at all - it must
                        // ALSO be composable itself (i.e. this step used setComposingText to
                        // do the replacing, not commitText appending after finalizing the
                        // old region) or this classification is wrong. That mismatch was
                        // the bug: "koo" + a third "o" finalizes the long-o region as-is and
                        // appends a fresh ඔ after it (no further doubling exists past diga
                        // aela-pilla) - erasePreviousChars is 0 there too, but nothing got
                        // replaced, so treating it as a region-swap made backspace put an
                        // extra character back INTO the field instead of just deleting the
                        // fresh ඔ (කෝඔ -> කෝෝ instead of the correct කෝ).
                        // !freshComposable excludes the sibling bug this fixed: a fresh
                        // region (e.g. a new ට/බ-style open after a DIFFERENT region like
                        // ු was just finalized) isn't a replace either - it's an append
                        // after already-finalized text, same as the plain branch below.
                        InputStep(output, composable, previousTop.myOutput, mLastChar, mLastLetter, pendingGaettaBase)

                    erasePreviousChars == 0 ->
                        // Fresh append with nothing replaced - either after an already-
                        // finalized glyph, the very first character, or (as above) a step
                        // that just finalized whatever open region existed and appended
                        // after it. Either way undo just deletes this step's own output.
                        InputStep(output, composable, "", mLastChar, mLastLetter, pendingGaettaBase)

                    previousTop != null && erasePreviousChars <= previousTop.myOutput.length ->
                        // This step erased a suffix of the previous keystroke's output
                        // (e.g. swapping out just the al-lakuna) - undo restores that
                        // exact suffix, leaving whatever came before it untouched.
                        InputStep(
                            output,
                            composable,
                            previousTop.myOutput.substring(previousTop.myOutput.length - erasePreviousChars),
                            mLastChar,
                            mLastLetter,
                            pendingGaettaBase
                        )

                    else ->
                        // Erase reaches further back than one tracked keystroke can
                        // account for (e.g. the 4-unit gaetta-pilla conjunct, which
                        // spans two earlier keystrokes) - don't guess; let backspace
                        // fall back to the existing whole-cluster delete here.
                        null
                }
                if (historyEntry != null) {
                    inputHistory.addLast(historyEntry)
                    if (inputHistory.size > 64) inputHistory.removeFirst()
                } else {
                    inputHistory.clear()
                }
            } catch (t: Throwable) {
                Log.e("IME", "singlishInput commit failed", t)
            } finally {
                ic.endBatchEdit()
            }
        } else {
            Log.w("IME", "currentInputConnection is null in singlishInput")
        }

        lastChar = tLastChar ?: tLastLetter ?: singlishChar
        lastLetter = tLastLetter ?: singlishChar
        positionFlag = currentInputConnection.getTextBeforeCursor(5, 0)?.toString() ?: ""

        // Suggestion refresh is handled once already, by letterOrSymbolClick's deferred
        // post-commit block right after this function returns. Doing it again here too
        // meant every single Sinhala keystroke fetched text from the host app and ran
        // the suggestion search TWICE - once synchronously on the main thread right
        // here, blocking the very next keystroke, and once more right after. This is
        // why Sinhala typing felt laggier than English/Wijesekara, which only ever hit
        // that path once. Removed the duplicate.
    }

    private fun getSinglishChars(input: String): CHAR? = singlishMap[input]

    override fun emojiClick(tag: String) {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                suppressNextSelectionAutoClose = true
                // finishComposingText() first - see BACKSPACE/space fix notes: commitText()
                // replaces an open composing region instead of appending after it.
                ic.finishComposingText()
                ic.commitText(tag, 1)
                // Update the recency data + persist it now, but do NOT refresh the
                // on-screen recent-emoji row here — reordering it under the user's
                // finger mid-session is jarring. The row picks up the new order the
                // next time the keyboard is shown (see onStartInputView).
                EmojiData.addRecentEmoji(this, tag)
            } catch (t: Throwable) {
                Log.e("IME", "emoji commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in emojiClick")
        }
        vibrate()
        checkAutoUnshift()
    }

    // --- Clipboard manager ---

    override fun clipboardPasteClick(text: String) {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                // Mark the next primary-clip change as self-triggered so pasting a clip
                // doesn't get re-captured as a "new" copy by the system clipboard listener.
                suppressNextClipCapture = true
                // finishComposingText() first - commitText() replaces an open
                // composing region instead of appending after it.
                ic.finishComposingText()
                ic.commitText(text, 1)
            } catch (t: Throwable) {
                Log.e("IME", "clipboard paste failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in clipboardPasteClick")
        }
        vibrate()
        if (::keyboardView.isInitialized) keyboardView.closeClipboardPanel()
    }

    override fun clipboardPinClick(item: ClipItem) {
        ClipboardData.setPinned(this, item.id, !item.pinned)
        if (::keyboardView.isInitialized) keyboardView.refreshClipboardList()
    }

    override fun clipboardShareClick(item: ClipItem) {
        try {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, item.text)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = android.content.Intent.createChooser(shareIntent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (t: Throwable) {
            Log.e("IME", "clipboard share failed", t)
        }
    }

    override fun clipboardDeleteClick(item: ClipItem) {
        ClipboardData.delete(this, item.id)
        if (::keyboardView.isInitialized) keyboardView.refreshClipboardList()
    }

    override fun clipboardDeleteSelectedClick(ids: Set<Long>) {
        ClipboardData.deleteAll(this, ids)
        if (::keyboardView.isInitialized) keyboardView.refreshClipboardList()
    }

    override fun numberClick(tag: String) {
        val ic = currentInputConnection
        if (ic != null) {
            try {

                val toCommit = when (keyboardLayout) {
                    KeyboardLayout.WIJESEKARA -> Maps.keyLabelsNumbersWijesekara[tag] ?: tag
                    else -> tag
                }
                // finishComposingText() first - commitText() replaces an open
                // composing region instead of appending after it.
                ic.finishComposingText()
                commitStyled(ic, toCommit)
            } catch (t: Throwable) {
                Log.e("IME", "number commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in numberClick")
        }
        vibrate()
        checkAutoUnshift()
    }


    override fun functionClick(type: Function) {
        val ic = currentInputConnection
        when (type) {
            Function.ACTION -> {
                // Learn the word that's about to be sent BEFORE performEditorAction() -
                // some host apps clear the input field synchronously as part of handling
                // the action, which would leave nothing left to read afterwards.
                learnLastTypedWord(ic)

                if (ic != null) {
                    try {
                        val editorInfo = currentInputEditorInfo
                        val actionId = editorInfo?.actionId ?: 0
                        if (actionId != 0) ic.performEditorAction(actionId)
                        else ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    } catch (t: Throwable) {
                        Log.e("IME", "performEditorAction/sendKeyEvent failed", t)
                    }
                } else {
                    Log.w("IME", "currentInputConnection is null in ACTION")
                }
                // Hide suggestions explicitly when user presses action
                topBarController?.showNormal()
                debouncer?.cancel()
                suggestionJob?.cancel()
            }

            Function.SHIFT -> {
                if (!caps) {
                    caps = true
                    shift = false
                } else if (!shift) {
                    shift = true
                } else {
                    caps = false
                    shift = false
                }
                updateKeyboard()
            }

            Function.LANG -> {
                try {
                    // Finish any open composing region before switching layouts -
                    // otherwise the first commit in the new language wipes it out.
                    ic?.finishComposingText()
                    val enabled = Prefs.getEnabledLayouts(this)
                    val currentIndex = enabled.indexOf(keyboardLayout).let { if (it < 0) 0 else it }
                    val next = enabled[(currentIndex + 1) % enabled.size]
                    setKeyboardLayout(next)


                    mComposing = ""
                } catch (t: Throwable) {
                    Log.e("IME", "language switch failed", t)

                    setKeyboardLayout(if (keyboardLayout == KeyboardLayout.ENGLISH) Prefs.getKeyboardLayout(this) else KeyboardLayout.ENGLISH)
                }
            }

            Function.IME -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                if (imm != null) {
                    try {

                        userInvokedInputMethodPicker = true
                        imm.showInputMethodPicker()
                    } catch (t: Throwable) {
                        Log.e("IME", "showInputMethodPicker failed", t)
                        userInvokedInputMethodPicker = false
                    }
                } else {
                    Log.w("IME", "InputMethodManager is null in Function.IME")
                }
            }

            Function.BACKSPACE -> {
                if (ic != null) {
                    val topStep = if (!hasPositionChanged()) inputHistory.removeLastOrNull() else null
                    if (topStep != null) {
                        try {
                            // Always finalize any open composing region first - this is a
                            // safe no-op if the region was already closed. We can't trust
                            // topStep.myWasComposable to mean the region is STILL open here:
                            // an arbitrary amount of time (and host-app behaviour, e.g. its
                            // own autocorrect/spellcheck finalizing spans) can happen between
                            // the keystroke that opened it and this backspace press. Assuming
                            // it was still open and calling setComposingText() directly was
                            // the bug - when the region had already been closed by the host
                            // app, that call INSERTED text at the cursor instead of replacing
                            // anything, which is why backspace was making text longer
                            // (ඌ -> ඌඋ -> ඌඌ) instead of shorter. Explicit delete-then-insert
                            // has no such ambiguity regardless of region state, so it's used
                            // for every revert now, batched so it's still a single atomic
                            // edit from the host app's point of view.
                            ic.beginBatchEdit()
                            try {
                                ic.finishComposingText()
                                ic.deleteSurroundingText(topStep.myOutput.length, 0)
                                if (topStep.restoreText.isNotEmpty()) ic.commitText(topStep.restoreText, 1)
                            } finally {
                                ic.endBatchEdit()
                            }
                            lastChar = topStep.restoreLastChar
                            lastLetter = topStep.restoreLastLetter
                            pendingGaettaPillaBase = topStep.restorePendingGaettaBase
                            positionFlag = ic.getTextBeforeCursor(5, 0)?.toString() ?: ""
                        } catch (t: Throwable) {
                            Log.e("IME", "history-based BACKSPACE revert failed, falling back", t)
                            performRawBackspaceDelete(ic)
                            lastChar = null
                            lastLetter = null
                            positionFlag = ""
                            mComposing = ""
                            inputHistory.clear()
                        }
                    } else {
                        // No usable history for this position (plain English text,
                        // cursor moved, app/field switch, or a conjunct that's too
                        // complex to revert step-by-step) - same raw delete as before.
                        performRawBackspaceDelete(ic)
                        lastChar = null
                        lastLetter = null
                        positionFlag = ""
                        mComposing = ""
                    }
                } else {
                    Log.w("IME", "currentInputConnection is null in BACKSPACE")
                }
            }
            Function.PANEL -> {

                keyboardSymbolsActive = !keyboardSymbolsActive
                updateKeyboard()
            }
        }
        vibrate()
    }

    override fun specialClick(tag: String) {
        val ic = currentInputConnection
        var toCommit = tag
        if (ic != null) {
            try {

                toCommit = if (tag.isNotEmpty() && tag.all { it.isDigit() }) {
                    try {
                        val code = tag.toInt()
                        when (code) {
                            32 -> " "
                            else -> code.toChar().toString()
                        }
                    } catch (t: Throwable) {
                        tag
                    }
                } else tag

                // finishComposingText() first - this is THE fix for space/punctuation
                // swallowing the vowel sign (e.g. "තෝ" + space -> "ත "). commitText()
                // replaces an open composing region instead of appending after it, so
                // without this, pressing space right after a long-vowel keystroke wipes
                // out the composing character instead of finalizing + adding the space.
                ic.finishComposingText()
                commitStyled(ic, toCommit)

            } catch (t: Throwable) {
                Log.e("IME", "specialClick commit failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in specialClick")
        }
        vibrate()

        // Any word-boundary character (space, comma, dot, etc. - anything that isn't
        // a letter) ends the word the user was typing, same as pressing space.
        val isWordBoundary = toCommit.isNotEmpty() && toCommit.none { it.isLetter() }
        if (isWordBoundary) {

            // Learn the word the user just finished typing — this is how most words
            // get learned, since users usually type-and-space rather than tapping
            // the suggestion chip. Also returns that word so it can feed next-word
            // predictions below.
            val justTypedWord = learnLastTypedWord(ic)

            lastChar = null
            lastLetter = null
            positionFlag = ""
            inputHistory.clear()
            // A word just finished with nothing typed for the next one yet - ask
            // for next-word predictions (based on justTypedWord) instead of
            // unconditionally hiding the bar. requestSuggestionsForToken() itself
            // falls back to showNormal() when suggestions are off/password field/no
            // predictions found, so this covers those cases too. If there was no
            // usable previous word at all (e.g. password field, or too short to
            // learn), just clear the bar like before.
            if (justTypedWord.isNullOrBlank()) {
                topBarController?.showNormal()
                debouncer?.cancel()
                suggestionJob?.cancel()
            } else {
                requestSuggestionsForToken("", justTypedWord)
            }
        }
        checkAutoUnshift()
    }

    /**
     * Learns the word immediately before the cursor into UserWordFrequency, so it can
     * be ranked above generic dictionary matches next time. Called from every action
     * that ends a word: space, punctuation, and the Enter/Send/Go editor action - not
     * just space, since users frequently finish a word by hitting Send directly (e.g.
     * in a DM) without ever typing a space or tapping a suggestion chip first.
     */
    // The exact trailing text (see `trimmedEnd` below) that was learned last time
    // learnLastTypedWord() ran. Guards against double-counting: this function fires
    // from several independent triggers (space/punctuation, Enter/Send, symbols
    // panel, and onFinishInputView when the keyboard/app closes) and simply learns
    // whatever word currently sits before the cursor. If two of those triggers fire
    // back-to-back with no new character typed in between - e.g. the user hits
    // space and then immediately switches apps, or hits Send and the host app
    // doesn't clear the field before onFinishInputView also runs - the same word
    // would otherwise get learned twice for a single typing action.
    private var lastLearnedSnapshot: String? = null

    // Cache of the word currently being typed (and the word before it, for the
    // bigram model), refreshed on every onUpdateSelection call while the field
    // still has content. Exists for ONE reason: apps like WhatsApp/Messenger have
    // their own in-app Send button (not the keyboard's Enter/Send action key) that
    // clears the whole text field but leaves the keyboard open. That field-clear
    // never runs through any of our own key handlers, so by the time
    // onUpdateSelection notices the change, the word is already gone from the
    // field - reading it from the InputConnection at that point gets nothing.
    // Keeping a running cache means we still have the word to learn from even
    // though the field itself is already empty.
    private var pendingWordCache: String = ""
    private var pendingWordPreviousCache: String = ""

    private fun learnLastTypedWord(ic: android.view.inputmethod.InputConnection?): String? {
        // Never learn anything typed in a password field - a single guard here
        // covers all 4 call sites (space/punctuation, Enter/Send action, symbols
        // panel, keyboard close) so the password itself can never end up as a
        // suggestion later in a different field.
        if (isInPasswordField()) return null
        // Whichever trigger got us here, the in-progress word it was tracking is
        // now resolved one way or another - drop the cache so a later field-clear
        // doesn't try to re-learn a word that's already been handled.
        pendingWordCache = ""
        pendingWordPreviousCache = ""
        try {
            val textBefore = ic?.getTextBeforeCursor(60, 0)?.toString() ?: ""
            val trimmedEnd = textBefore.trimEnd()
            val rawToken = trimmedEnd.takeLastWhile { !it.isWhitespace() }
            // This function runs AFTER the boundary character (comma/dot/?/! from the
            // bottom row or the symbols panel) has already been committed, so rawToken
            // still has that punctuation stuck on the end - e.g. "hello," or "hello?".
            // Learning it as-is would fragment the same word into several distinct
            // dictionary entries ("hello", "hello,", "hello?"), splitting its count
            // instead of accumulating it. Strip trailing punctuation/symbols before
            // learning, so only the pure word gets recorded.
            val justTypedWord = stripTrailingPunctuation(rawToken)
            if (justTypedWord.length >= 2) {
                if (trimmedEnd == lastLearnedSnapshot) {
                    // Same trailing text as the last successful learn, with nothing new
                    // typed in between - a duplicate trigger for the word we already
                    // learned, not a new word. Skip re-learning it, but still hand the
                    // word back to the caller - next-word suggestions should still be
                    // requested even when the learn step itself is a no-op this time.
                    return justTypedWord
                }
                lastLearnedSnapshot = trimmedEnd
                val lang = LanguageDetector.detectLanguage(justTypedWord)
                // Word before this one — feeds the bigram next-word model.
                val previousWord = trimmedEnd.dropLast(rawToken.length).trimEnd().takeLastWhile { !it.isWhitespace() }
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    suggestionEngine?.recordAccepted(justTypedWord, lang, previousWord)
                }
                return justTypedWord
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Handles the one gap [learnLastTypedWord] can't: a host app's OWN Send button
     * (not the keyboard's Enter/Send action key) that clears the whole text field
     * but leaves the keyboard open (WhatsApp/Messenger/Telegram all do this). That
     * clear never goes through any of our key handlers, so by the time
     * onUpdateSelection notices the field is empty, there's nothing left to read
     * from the InputConnection - only [pendingWordCache], kept fresh on every
     * onUpdateSelection call while the field had content, still has it.
     *
     * Called from onUpdateSelection whenever the token-at-cursor comes back empty;
     * only actually learns anything if the field is FULLY empty (not just "cursor
     * sits right after a space/newline", which is the normal, already-handled case
     * every space/punctuation keystroke produces).
     */
    private fun learnPendingWordIfFieldWasCleared(ic: android.view.inputmethod.InputConnection?) {
        if (pendingWordCache.isEmpty()) return
        if (isInPasswordField()) {
            pendingWordCache = ""
            pendingWordPreviousCache = ""
            return
        }
        try {
            val fieldIsFullyEmpty = (ic?.getTextBeforeCursor(1, 0)?.toString() ?: "").isEmpty() &&
                    (ic?.getTextAfterCursor(1, 0)?.toString() ?: "").isEmpty()
            if (!fieldIsFullyEmpty) return

            val justTypedWord = stripTrailingPunctuation(pendingWordCache)
            val previousWord = pendingWordPreviousCache
            pendingWordCache = ""
            pendingWordPreviousCache = ""

            if (justTypedWord.length >= 2) {
                lastLearnedSnapshot = null
                val lang = LanguageDetector.detectLanguage(justTypedWord)
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    suggestionEngine?.recordAccepted(justTypedWord, lang, previousWord)
                }
            }

            // The field just vanished out from under the normal typing state (not
            // through our own space/Enter/symbol handling) - clear the same state
            // those handlers reset, so the next word starts clean instead of
            // possibly reverting into a position that no longer exists.
            lastChar = null
            lastLetter = null
            positionFlag = ""
            inputHistory.clear()
        } catch (_: Throwable) {}
    }

    /**
     * Strips trailing punctuation/symbol characters (., ",", ?, !, @, closing
     * brackets/quotes, etc.) from a token so only the pure word remains.
     *
     * Deliberately keeps anything in the Sinhala Unicode block (U+0D80-U+0DFF),
     * not just Character.isLetterOrDigit(): Sinhala vowel signs, the virama
     * (al-lakuna), and anusvara/visarga are combining marks - not letters by
     * Unicode's own category - but they're a legitimate, non-optional part of a
     * Sinhala word (e.g. the trailing "්" in a conjunct), so they must never be
     * stripped even though a plain isLetterOrDigit() check would treat them the
     * same as a stray punctuation mark.
     */
    private fun stripTrailingPunctuation(word: String): String {
        var end = word.length
        while (end > 0) {
            val ch = word[end - 1]
            val isSinhalaMark = ch.code in 0x0D80..0x0DFF
            if (ch.isLetterOrDigit() || isSinhalaMark) break
            end--
        }
        return word.substring(0, end)
    }

    override fun longPressSecondaryClick(char: String) {
        val ic = currentInputConnection ?: return
        try {
            // finishComposingText() first - commitText() replaces an open
            // composing region instead of appending after it.
            ic.finishComposingText()
            commitStyled(ic, char)
            vibrate()
        } catch (t: Throwable) {
            Log.e("IME", "longPressSecondaryClick commit failed", t)
        }
    }

    override fun eraseDo() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                // Finalize composing region before swipe-erase — same reason as
                // the BACKSPACE handler: open composing span + delete = wrong char erased.
                ic.finishComposingText()
                ic.deleteSurroundingTextInCodePoints(1, 0)
            } catch (t: Throwable) {
                Log.e("IME", "eraseDo failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in eraseDo")
        }
    }

    override fun eraseUndo() {

    }

    override fun eraseDone() {

    }

    override fun moveRight() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                val newCursorPosition = (ic.getTextBeforeCursor(100, 0)?.length ?: 0) + 1
                ic.setSelection(newCursorPosition, newCursorPosition)
            } catch (t: Throwable) {
                Log.e("IME", "moveRight failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in moveRight")
        }
    }

    override fun moveLeft() {
        val ic = currentInputConnection
        if (ic != null) {
            try {
                val currentCursorPosition = ic.getTextBeforeCursor(100, 0)?.length ?: 0
                if (currentCursorPosition > 0) {
                    ic.setSelection(currentCursorPosition - 1, currentCursorPosition - 1)
                }
            } catch (t: Throwable) {
                Log.e("IME", "moveLeft failed", t)
            }
        } else {
            Log.w("IME", "currentInputConnection is null in moveLeft")
        }
    }

    // --- Text-select panel actions ---
    // Cut/copy/paste/selectAll go through performContextMenuAction, the same
    // mechanism the system's own text-selection handles (and every other
    // keyboard app) use to trigger a target field's own menu action - this
    // works correctly across apps without us needing to read/replace text
    // ourselves. Cursor movement (including word-jump and shift-to-extend)
    // goes through synthetic DPAD key events with the standard SHIFT/CTRL meta
    // flags, exactly like a physical keyboard would send - so it inherits
    // whatever line-wrapping/word-boundary logic the target field already has,
    // instead of us re-implementing it (badly) by hand.
    // Every one of these programmatically moves the cursor/selection (or, for
    // paste, also inserts text), which triggers onUpdateSelection just like the
    // user tapping directly in the host app's field would - and that handler's
    // default behaviour is to treat any such change as "the user left the panel"
    // and auto-close it. Setting suppressNextSelectionAutoClose beforehand (the
    // same mechanism the emoji panel already relies on for emoji taps) tells that
    // one upcoming onUpdateSelection call to leave the text-select panel open, so
    // pressing Cut/Copy/Paste/Select-all/an arrow no longer kicks the user back
    // out to the plain keyboard.
    override fun textSelectCutClick() {
        try {
            suppressNextSelectionAutoClose = true
            currentInputConnection?.performContextMenuAction(android.R.id.cut)
        } catch (t: Throwable) {
            Log.e("IME", "textSelectCutClick failed", t)
        }
    }

    override fun textSelectCopyClick() {
        try {
            suppressNextSelectionAutoClose = true
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
        } catch (t: Throwable) {
            Log.e("IME", "textSelectCopyClick failed", t)
        }
    }

    override fun textSelectPasteClick() {
        try {
            suppressNextSelectionAutoClose = true
            currentInputConnection?.performContextMenuAction(android.R.id.paste)
        } catch (t: Throwable) {
            Log.e("IME", "textSelectPasteClick failed", t)
        }
    }

    override fun textSelectAllClick() {
        try {
            suppressNextSelectionAutoClose = true
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        } catch (t: Throwable) {
            Log.e("IME", "textSelectAllClick failed", t)
        }
    }

    override fun textSelectMove(direction: TextSelectDirection, extend: Boolean, byWord: Boolean) {
        val ic = currentInputConnection ?: return
        val keyCode = when (direction) {
            TextSelectDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            TextSelectDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            TextSelectDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
            TextSelectDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        var metaState = 0
        if (extend) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (byWord) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        try {
            suppressNextSelectionAutoClose = true
            val time = android.os.SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            ic.sendKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        } catch (t: Throwable) {
            Log.e("IME", "textSelectMove failed", t)
        }
    }

    private fun setKeyboardLayout(layout: KeyboardLayout) {
        keyboardLayout = layout


      try {
          Prefs.setSelectedLayout(this, layout)
        } catch (t: Throwable) {
            Log.e("IME", "Failed to persist selected keyboard layout", t)
       }

         when (layout) {
             KeyboardLayout.ENGLISH -> {
                 keyboardView.setLangIndicator("ENG")
                 updateKeyboard()
             }
             KeyboardLayout.WIJESEKARA -> {
                 keyboardView.setLangIndicator("SIN")
                 updateKeyboard()
             }
             KeyboardLayout.SINGLISH -> {
                 keyboardView.setLangIndicator("SIN")
                 updateKeyboard()
             }
         }
     }

    private fun updateKeyboard() {

        if (keyboardSymbolsActive) {
            try {
                val symMap = if (caps) symbolsMapShifted else symbolsMap
                val letters = mutableMapOf<String, String>()
                for (c in 'a'..'z') {
                    val key = c.toString()
                    letters[key] = symMap[key] ?: ""
                }
                keyboardView.setLetterKeys(letters)

                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)
                keyboardView.setSecondaryLabels(null)
                keyboardView.setLongPressChars(null)
                return
            } catch (t: Throwable) {
                Log.e("IME", "failed to render symbols keyboard", t)
            }
        }

        val keySet = if (caps) keyLabelsLettersEnglishShifted else keyLabelsLettersEnglish
        var secondaryLabels: Map<String, String>? = null

        when (keyboardLayout) {
            KeyboardLayout.ENGLISH -> {
                keyboardView.setLetterKeys(keySet)
                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)
                
                // Secondary labels: matches symbol keyboard position exactly
                // Row1: q w e r t y u i o p  -> _ ! | = [ ] < > { }
                // Row2: a s d f g h j k l    -> @ # ^ % & - + ( )
                // Row3: z x c v b n m        -> * " ' : ; \ ?
                val englishSecondary = mapOf(
                    "q" to "_",  "w" to "!", "e" to "|",  "r" to "=",
                    "t" to "[",  "y" to "]", "u" to "<",  "i" to ">",
                    "o" to "{",  "p" to "}",
                    "a" to "@",  "s" to "#", "d" to "^",  "f" to "%",
                    "g" to "&",  "h" to "-", "j" to "+",  "k" to "(",
                    "l" to ")",
                    "z" to "*",  "x" to "\"", "c" to "\'", "v" to ":",
                    "b" to ";",  "n" to "\\", "m" to "?"
                )
                keyboardView.setSecondaryLabels(englishSecondary)
                // English: corner label IS the committed char, no separate override needed
                keyboardView.setLongPressChars(null)
            }

            KeyboardLayout.WIJESEKARA -> {
                val sinhalaKeySet = if (caps) keyLabelsLettersWijesekaraShifted else keyLabelsLettersWijesekara
                keyboardView.setLetterKeys(sinhalaKeySet)
                keyboardView.setNumberKeys(keyLabelsNumbersWijesekara)
                val specialKeys = if (caps) keyLabelsSpecialWijesekaraSinhalaShifted else keyLabelsSpecialWijesekaraSinhala
                keyboardView.setSpecialKeys(specialKeys)
                keyboardView.setSecondaryLabels(null)
                // No corner label shown, but long-press still commits the symbol
                // that sits in this key's position on the symbol keyboard.
                keyboardView.setLongPressChars(symbolsMap)


            }

            KeyboardLayout.SINGLISH -> {
                keyboardView.setLetterKeys(keySet)
                keyboardView.setNumberKeys(keyLabelsNumbers)
                keyboardView.setSpecialKeys(keyLabelsSpecialEnglish)


                val labels = mutableMapOf<String, String>()
                for ((k, v) in keySet) {
                    val key = k.lowercase()

                    val charMap = singlishMap[if (caps) key.uppercase() else key]
                    if (charMap != null && charMap != CHAR.EMPTY) {
                        labels[k] = charMap.text
                    }
                }
                // Corner label stays the Sinhala phonetic char (visual only).
                keyboardView.setSecondaryLabels(labels)
                // But long-press commits the symbol from this key's symbol-keyboard
                // position, not the Sinhala char — fixes "z" long-press committing
                // ඳ instead of typing the symbol at that position.
                keyboardView.setLongPressChars(symbolsMap)
            }
        }

        keyboardView.buttonActionShift.setImageResource(
            if (caps) R.drawable.ic_shift_pressed
            else R.drawable.ic_shift
        )


        val editorInfo = currentInputEditorInfo
        if (editorInfo != null) {
            val imeAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
            val (iconRes, desc) = when (imeAction) {
                EditorInfo.IME_ACTION_GO -> Pair(R.drawable.ic_keyboard_return, "Go")
                EditorInfo.IME_ACTION_SEARCH -> Pair(R.drawable.ic_search, "Search")
                EditorInfo.IME_ACTION_SEND -> Pair(R.drawable.ic_send, "Send")
                EditorInfo.IME_ACTION_NEXT -> Pair(R.drawable.ic_keyboard_arrow_right, "Next")
                EditorInfo.IME_ACTION_DONE -> Pair(R.drawable.ic_check, "Done")
                EditorInfo.IME_ACTION_NONE -> Pair(R.drawable.ic_keyboard_return, "Enter")
                else -> Pair(R.drawable.ic_keyboard_return, "Enter")
            }

            try {
                keyboardView.buttonActionAction.setImageResource(iconRes)
                keyboardView.buttonActionAction.contentDescription = desc
            } catch (t: Throwable) {

                Log.e("IME", "Failed to set action icon resource", t)
                keyboardView.buttonActionAction.setImageResource(R.drawable.ic_keyboard_return)
                keyboardView.buttonActionAction.contentDescription = "Enter"
            }
        } else {

            keyboardView.buttonActionAction.setImageResource(R.drawable.ic_keyboard_return)
            keyboardView.buttonActionAction.contentDescription = "Enter"
        }
    }

    private fun vibrate() {
        if (!Prefs.getVibration(this)) return
        val vibrator = vibratorService
        if (vibrator == null) {
            Log.w("IME", "Vibrator service not available")
            return
        }
        try {
            when {
                Build.VERSION.SDK_INT >= 29 -> {
                    // EFFECT_TICK is the OS's own short, crisp "tap" haptic - tuned
                    // per device by the OEM's haptics engine, so it feels much
                    // snappier/more premium than a raw amplitude pulse (closer to
                    // what Gboard/system keyboards use for keypress feedback).
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
                Build.VERSION.SDK_INT >= 26 -> {
                    // No predefined effects before API 29 - a short 15ms pulse is
                    // the closest raw approximation of the same quick tick.
                    vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(15)
                }
            }
        } catch (t: Throwable) {
            Log.e("IME", "vibrate failed", t)
        }
    }




    private fun erasePrevious(count: Int = 1) {
        val ic = currentInputConnection ?: return


        fun deleteUnits(units: Int) {
            try {
                ic.deleteSurroundingText(units, 0)
            } catch (t: Throwable) {
                Log.e("IME", "deleteSurroundingText failed in erasePrevious", t)
            }
        }

        if (count == 1) {

            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length >= 2) {
                val ch = before[before.length - 2]
                if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
                    deleteUnits(2)
                } else {
                    deleteUnits(1)
                }
            } else {
                deleteUnits(1)
            }
        } else {
            deleteUnits(count)
        }


        try {
            val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (before == CHAR.ZERO_WIDTH_JOINER.text) {

                deleteUnits(1)

                erasePrevious(1)
            }
        } catch (t: Throwable) {
            Log.e("IME", "post-delete ZWJ check failed", t)
        }
    }
}
