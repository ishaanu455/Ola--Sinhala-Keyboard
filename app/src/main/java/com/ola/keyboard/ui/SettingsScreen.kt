package com.ola.keyboard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import android.widget.Toast
import ime.suggest.UserDataBackup
import kotlinx.coroutines.launch
import com.ola.keyboard.BundledEmojiFonts
import com.ola.keyboard.BuildConfig
import com.ola.keyboard.CustomFontManager
import com.ola.keyboard.EmojiDownloader
import com.ola.keyboard.EmojiStyle
import com.ola.keyboard.Prefs
import com.ola.keyboard.PredictionManagerActivity
import com.ola.keyboard.UpdateActivity
import com.ola.keyboard.UpdateChecker
import com.ola.keyboard.UpdateCheckOutcome
import com.ola.keyboard.R
import com.ola.keyboard.ui.components.PreferenceItem
import com.ola.keyboard.ui.components.RadioOptionPreference
import com.ola.keyboard.ui.components.SettingsCategory
import com.ola.keyboard.ui.components.SettingsMenuCard
import com.ola.keyboard.ui.components.SettingsSubScreenHeader
import com.ola.keyboard.ui.components.SliderPreference
import com.ola.keyboard.ui.components.SwitchPreference

/** One entry in the Settings home menu. Each maps to its own sub-screen below. */
private enum class SettingsSection(val title: String, val summary: String) {
    LANGUAGES("Languages", "Choose which keyboard layouts are available"),
    APPEARANCE("Appearance", "Theme, dark mode, key borders"),
    TYPING("Typing & Layout", "Size, height, vibration, swipe gestures"),
    EMOJI("Emoji", "Emoji row and emoji style"),
    CLIPBOARD("Clipboard", "Clipboard manager and history"),
    DICTIONARY("Dictionary & Backup", "Suggestion bar, learned words and backups"),
    ABOUT("About", "Source code and version")
}

/**
 * Settings home screen. Shows a modern, icon-led menu of sections instead of one
 * long flat list - tapping a section opens its own sub-screen with a back arrow
 * to return here. All labels are in English throughout, regardless of the
 * device's Sinhala font settings, so the settings UI reads consistently.
 */
@Composable
fun SettingsScreen() {
    // rememberSaveable (not plain remember): survives real configuration changes
    // (rotation, etc.) so the user never gets silently bounced back to the Settings
    // home list out of an open sub-screen.
    var currentSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    // Without this, the device back button isn't consumed by the in-app settings
    // navigation at all, so it falls through to the host Activity and closes the
    // whole app instead of just returning to the Settings home list.
    BackHandler(enabled = currentSection != null) {
        currentSection = null
    }

    AnimatedContent(
        targetState = currentSection,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it } togetherWith slideOutHorizontally { -it })
            } else {
                (slideInHorizontally { -it } togetherWith slideOutHorizontally { it })
            }
        },
        label = "settings_navigation"
    ) { section ->
        if (section == null) {
            SettingsHome(onSectionSelected = { currentSection = it })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsSubScreenHeader(title = section.title, onBack = { currentSection = null })
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (section) {
                        SettingsSection.LANGUAGES -> LanguagesSection()
                        SettingsSection.APPEARANCE -> AppearanceSection()
                        SettingsSection.TYPING -> TypingSection()
                        SettingsSection.EMOJI -> EmojiSection()
                        SettingsSection.CLIPBOARD -> ClipboardSection()
                        SettingsSection.DICTIONARY -> DictionarySection()
                        SettingsSection.ABOUT -> AboutSection()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHome(onSectionSelected: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        SettingsMenuCard(
            icon = Icons.Filled.Translate,
            title = SettingsSection.LANGUAGES.title,
            summary = SettingsSection.LANGUAGES.summary,
            onClick = { onSectionSelected(SettingsSection.LANGUAGES) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.Palette,
            title = SettingsSection.APPEARANCE.title,
            summary = SettingsSection.APPEARANCE.summary,
            onClick = { onSectionSelected(SettingsSection.APPEARANCE) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.FormatSize,
            title = SettingsSection.TYPING.title,
            summary = SettingsSection.TYPING.summary,
            onClick = { onSectionSelected(SettingsSection.TYPING) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.EmojiEmotions,
            title = SettingsSection.EMOJI.title,
            summary = SettingsSection.EMOJI.summary,
            onClick = { onSectionSelected(SettingsSection.EMOJI) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.ContentPaste,
            title = SettingsSection.CLIPBOARD.title,
            summary = SettingsSection.CLIPBOARD.summary,
            onClick = { onSectionSelected(SettingsSection.CLIPBOARD) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.MenuBook,
            title = SettingsSection.DICTIONARY.title,
            summary = SettingsSection.DICTIONARY.summary,
            onClick = { onSectionSelected(SettingsSection.DICTIONARY) }
        )
        SettingsMenuCard(
            icon = Icons.Filled.Info,
            title = SettingsSection.ABOUT.title,
            summary = SettingsSection.ABOUT.summary,
            onClick = { onSectionSelected(SettingsSection.ABOUT) }
        )
    }
}

@Composable
private fun LanguagesSection() {
    val context = LocalContext.current

    val layoutEnglish = rememberBooleanPreference(context, "layout_english", true)
    SwitchPreference(
        title = "English",
        checked = layoutEnglish.value,
        onCheckedChange = { layoutEnglish.value = it }
    )

    val layoutWijesekara = rememberBooleanPreference(context, "layout_wijesekara", true)
    SwitchPreference(
        title = "Wijesekara",
        summary = "Native Sinhala key layout",
        checked = layoutWijesekara.value,
        onCheckedChange = { layoutWijesekara.value = it }
    )

    val layoutSinglish = rememberBooleanPreference(context, "layout_singlish", true)
    SwitchPreference(
        title = "Singlish",
        summary = "Type Sinhala using English letters",
        checked = layoutSinglish.value,
        onCheckedChange = { layoutSinglish.value = it }
    )
}

@Composable
private fun AppearanceSection() {
    val context = LocalContext.current

    val automaticTheme = rememberBooleanPreference(context, "automatic_theme", true)
    SwitchPreference(
        title = "Automatic Theme",
        summary = "Follow the system's light/dark setting",
        icon = Icons.Filled.DarkMode,
        checked = automaticTheme.value,
        onCheckedChange = { automaticTheme.value = it }
    )

    val darkTheme = rememberBooleanPreference(context, "dark_theme", false)
    if (!automaticTheme.value) {
        SwitchPreference(
            title = "Dark Theme",
            checked = darkTheme.value,
            onCheckedChange = { darkTheme.value = it }
        )
    }

    val keyBorders = rememberBooleanPreference(context, "key_borders", true)
    val colorTheme = rememberStringPreference(context, "color_theme", "ola")
    val effectiveDark = if (automaticTheme.value) isSystemInDarkTheme() else darkTheme.value

    SettingsCategory(title = "Preview")
    KeyboardPreview(
        dark = effectiveDark,
        keyBorders = keyBorders.value,
        colorThemeId = colorTheme.value,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )

    SwitchPreference(
        title = "Border",
        summary = "Show an outline around each key",
        checked = keyBorders.value,
        onCheckedChange = { keyBorders.value = it }
    )

    SettingsCategory(title = "Colour Themes")
    ColorThemePicker(
        selected = colorTheme.value,
        onSelect = { colorTheme.value = it },
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/** One entry in the Colour Themes picker - id is what's persisted to
 *  Prefs.colorTheme / SharedPreferences "color_theme", and must match the
 *  `when (colorTheme)` branches in KeyboardView.kt's init{}. Each theme now
 *  retints the whole key bed (not just Enter/Space), so this carries the same
 *  light/dark key-surface palette as the AccentXxxLight/Dark style overlays in
 *  themes.xml + colors.xml - keep the two in sync if either changes. */
private data class KeyboardColorTheme(
    val id: String,
    val label: String,
    val accent: Color,
    val lightBg: Color,
    val lightKey: Color,
    val lightFunc: Color,
    val lightFuncPressed: Color,
    val darkBg: Color,
    val darkKey: Color,
    val darkFunc: Color,
    val darkFuncPressed: Color,
)

private val keyboardColorThemes = listOf(
    KeyboardColorTheme(
        "ola", "Ola", Color(0xFFD4A24C),
        lightBg = Color(0xFFFBF8F2), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFEFE9DC), lightFuncPressed = Color(0xFFE3DBC8),
        darkBg = Color(0xFF1C1B17), darkKey = Color(0xFF26241E), darkFunc = Color(0xFF332F26), darkFuncPressed = Color(0xFF403A2C),
    ),
    KeyboardColorTheme(
        "wine", "Wine", Color(0xFF8C3B4A),
        lightBg = Color(0xFFF8F5F6), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFEBE0E2), lightFuncPressed = Color(0xFFDECDD0),
        darkBg = Color(0xFF1D1618), darkKey = Color(0xFF271D1F), darkFunc = Color(0xFF342528), darkFuncPressed = Color(0xFF402C30),
    ),
    KeyboardColorTheme(
        "slate", "Slate", Color(0xFF46545C),
        lightBg = Color(0xFFF5F7F8), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFE0E7EB), lightFuncPressed = Color(0xFFCDD8DE),
        darkBg = Color(0xFF161A1D), darkKey = Color(0xFF1D2327), darkFunc = Color(0xFF252F34), darkFuncPressed = Color(0xFF2C3940),
    ),
    KeyboardColorTheme(
        "ocean", "Ocean", Color(0xFF34597A),
        lightBg = Color(0xFFF5F7F8), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFE0E6EB), lightFuncPressed = Color(0xFFCDD6DE),
        darkBg = Color(0xFF161A1D), darkKey = Color(0xFF1D2227), darkFunc = Color(0xFF252D34), darkFuncPressed = Color(0xFF2C3740),
    ),
    KeyboardColorTheme(
        "forest", "Forest", Color(0xFF3F6B4A),
        lightBg = Color(0xFFF5F8F6), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFE0EBE3), lightFuncPressed = Color(0xFFCDDED1),
        darkBg = Color(0xFF161D18), darkKey = Color(0xFF1D2720), darkFunc = Color(0xFF253429), darkFuncPressed = Color(0xFF2C4031),
    ),
    KeyboardColorTheme(
        "onyx", "Onyx", Color(0xFF2B2B2E),
        lightBg = Color(0xFFF6F6F7), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFE4E4E7), lightFuncPressed = Color(0xFFD3D3D8),
        darkBg = Color(0xFF19191A), darkKey = Color(0xFF212123), darkFunc = Color(0xFF2B2B2E), darkFuncPressed = Color(0xFF34343A),
    ),
    KeyboardColorTheme(
        "navy", "Navy", Color(0xFF22385C),
        lightBg = Color(0xFFF5F6F8), lightKey = Color(0xFFFFFFFF), lightFunc = Color(0xFFE0E4EB), lightFuncPressed = Color(0xFFCDD3DE),
        darkBg = Color(0xFF16191D), darkKey = Color(0xFF1D2127), darkFunc = Color(0xFF252B34), darkFuncPressed = Color(0xFF2C3440),
    ),
)

@Composable
private fun ColorThemePicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        keyboardColorThemes.forEach { theme ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(theme.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(theme.accent)
                        .then(
                            if (selected == theme.id) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            }
                        )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = theme.label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A Compose mock-up of the real keyboard (KeyboardView.kt / keyboard_layout.xml),
 * built from the exact same colour tokens (see colors.xml + themes.xml) rather
 * than embedding the actual native KeyboardView here - that class is tightly
 * coupled to InputMethodService (key layouts, IME callbacks) and isn't meant to
 * be instantiated standalone inside a Settings screen. Reacts live to dark
 * mode / border / colour-theme selection above, same as the real keyboard does.
 */
@Composable
private fun KeyboardPreview(
    dark: Boolean,
    keyBorders: Boolean,
    colorThemeId: String,
    modifier: Modifier = Modifier
) {
    val theme = keyboardColorThemes.first { it.id == colorThemeId }
    val bg = if (dark) theme.darkBg else theme.lightBg
    val keyColor = if (dark) theme.darkKey else theme.lightKey
    val funcColor = if (dark) theme.darkFunc else theme.lightFunc
    val textColor = if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val borderColor = if (keyBorders) textColor.copy(alpha = 0.18f) else Color.Transparent
    val accent = theme.accent

    fun keyMod(background: Color) = Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(background)
        .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(6.dp)
    ) {
        // Top bar: Ola logo mark + the real toolbar icons, same order as
        // keyboard_layout.xml's top_bar_icon_row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_ola_logo_mark),
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .padding(4.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            listOf(R.drawable.ic_clipboard, R.drawable.ic_emoji, R.drawable.ic_text_select, R.drawable.ic_fonts)
                .forEach { iconRes ->
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(20.dp)
                    )
                }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Number row - shown above the letters, same key styling as the letter
        // row (KeyboardButton has no explicit background override in
        // keyboard_layout.xml's key_row_1, so it inherits the same keyNormal
        // surface as the QWERTY rows on the real keyboard).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            "1234567890".forEach { digit ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(keyColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = digit.toString(), color = textColor, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Letter row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            "QWERTYUIOP".forEach { letter ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(keyColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = letter.toString(), color = textColor, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Shift / letters / backspace row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .then(keyMod(funcColor))
            )
            "ASDFGHJKL".forEach { letter ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(keyColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = letter.toString(), color = textColor, fontSize = 13.sp)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .then(keyMod(funcColor))
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Bottom row: 123 / space (accent) / enter (accent)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .then(keyMod(funcColor)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "?123", color = textColor, fontSize = 11.sp)
            }
            Box(
                modifier = Modifier
                    .weight(4f)
                    .then(keyMod(accent))
            )
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .then(keyMod(accent))
            )
        }
    }
}

@Composable
private fun TypingSection() {
    val context = LocalContext.current

    SettingsCategory(title = "Layout")

    val showNumberRow = rememberBooleanPreference(context, "show_number_row", true)
    SwitchPreference(
        title = "Show Number Row",
        icon = Icons.Filled.Dialpad,
        checked = showNumberRow.value,
        onCheckedChange = { showNumberRow.value = it }
    )

    val heightPercentage = rememberIntPreference(context, "height_percentage", 100)
    SliderPreference(
        title = "Keyboard Height",
        icon = Icons.Filled.Height,
        value = heightPercentage.value,
        range = 70f..190f,
        onValueChange = { heightPercentage.value = it }
    )

    val textSize = rememberIntPreference(context, "text_size", 28)
    SliderPreference(
        title = "Text Size",
        icon = Icons.Filled.FormatSize,
        value = textSize.value,
        range = 20f..40f,
        onValueChange = { textSize.value = it }
    )

    SettingsCategory(title = "Typing behavior")

    // These three were fully wired into the keyboard already (see KeyboardView /
    // InputMethodService, which both read them from Prefs) but had no toggle
    // anywhere in Settings - there was no way to turn them off. Surfacing them
    // here now.
    val vibration = rememberBooleanPreference(context, "vibration", true)
    SwitchPreference(
        title = "Vibrate on Keypress",
        icon = Icons.Filled.Vibration,
        checked = vibration.value,
        onCheckedChange = { vibration.value = it }
    )

    val swipeToErase = rememberBooleanPreference(context, "swipe_to_erase", true)
    SwitchPreference(
        title = "Swipe to Erase",
        summary = "Swipe left on the top keys to delete words",
        icon = Icons.Filled.Backspace,
        checked = swipeToErase.value,
        onCheckedChange = { swipeToErase.value = it }
    )

    val swipeToMoveCursor = rememberBooleanPreference(context, "swipe_to_move_cursor", true)
    SwitchPreference(
        title = "Swipe to Move Cursor",
        summary = "Swipe left/right on the bottom keys to move the cursor",
        icon = Icons.Filled.SwipeLeft,
        checked = swipeToMoveCursor.value,
        onCheckedChange = { swipeToMoveCursor.value = it }
    )
}

@Composable
private fun EmojiSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val bundledFonts = remember { BundledEmojiFonts.list(context) }
    var selectedBundledFont by remember { mutableStateOf(prefs.bundledEmojiFontFile) }
    // Nested one level deeper than the other Settings sub-screens: tapping "Choose
    // Pack" below swaps this whole section's content for a dedicated font-pack list
    // (its own SettingsSubScreenHeader + back arrow), rather than showing all 3
    // packs inline like before - see FontPackPickerScreen. The outer Settings
    // BackHandler (SettingsScreen, currentSection != null) is still enabled the
    // whole time, so a second back-press after this one still exits to Settings home.
    var showFontPackPicker by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showFontPackPicker) {
        showFontPackPicker = false
    }

    fun selectBundledFont(fileName: String) {
        selectedBundledFont = fileName
        prefs.bundledEmojiFontFile = fileName
    }

    if (showFontPackPicker) {
        FontPackPickerScreen(
            fonts = bundledFonts,
            selectedFileName = selectedBundledFont,
            onSelect = { selectBundledFont(it) },
            onBack = { showFontPackPicker = false }
        )
    } else {
        val showRecentEmojiRow = rememberBooleanPreference(context, "show_recent_emoji_row", false)
        SwitchPreference(
            title = "Show Recent Emoji Row",
            checked = showRecentEmojiRow.value,
            onCheckedChange = { showRecentEmojiRow.value = it }
        )

        EmojiStyleSection(
            bundledFonts = bundledFonts,
            selectedBundledFont = selectedBundledFont,
            onSelectBundledFont = { selectBundledFont(it) },
            onOpenFontPackPicker = { showFontPackPicker = true }
        )
    }
}

@Composable
private fun ClipboardSection() {
    val context = LocalContext.current

    val clipboardEnabled = rememberBooleanPreference(context, "clipboard_enabled", true)
    SwitchPreference(
        title = "Clipboard Manager",
        summary = "Show a clipboard icon on the keyboard and keep a history of copied text",
        checked = clipboardEnabled.value,
        onCheckedChange = { clipboardEnabled.value = it }
    )

    PreferenceItem(
        title = "Clips Manager",
        summary = "Browse, pin, add or delete your saved clips",
        icon = Icons.Filled.ContentPaste,
        onClick = {
            context.startActivity(Intent(context, com.ola.keyboard.ClipsManagerActivity::class.java))
        }
    )
}

@Composable
private fun DictionarySection() {
    val context = LocalContext.current

    val showSuggestionBar = rememberBooleanPreference(context, "show_suggestion_bar", true)
    SwitchPreference(
        title = "Show Suggestion Bar",
        summary = "Show word suggestions above the keys while typing. Words are " +
            "still learned in the background either way - this only hides the bar.",
        checked = showSuggestionBar.value,
        onCheckedChange = { showSuggestionBar.value = it }
    )

    PreferenceItem(
        title = "Prediction Manager",
        summary = "Manage words you've typed and added",
        onClick = {
            context.startActivity(Intent(context, PredictionManagerActivity::class.java))
        }
    )

    DictionaryBackupSection()
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }

    PreferenceItem(
        title = "Check for Updates",
        summary = if (checking) "Checking..." else "Version ${BuildConfig.VERSION_NAME}",
        icon = Icons.Filled.Update,
        onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    val outcome = UpdateChecker.checkForUpdate(context, force = true)
                    checking = false
                    // Only jump to UpdateActivity when the check actually completed -
                    // on RateLimited/Failed/Throttled there's nothing new to show there,
                    // and silently opening it anyway (reading whatever stale
                    // Prefs.updateAvailable was left over from a previous check) is what
                    // used to make a failed check look "stuck" and do nothing. Show a
                    // clear Toast instead so the user knows the check itself didn't go
                    // through, rather than mistaking it for "already up to date".
                    when (outcome) {
                        is UpdateCheckOutcome.Completed -> {
                            context.startActivity(Intent(context, UpdateActivity::class.java))
                        }
                        is UpdateCheckOutcome.RateLimited -> {
                            Toast.makeText(
                                context,
                                "GitHub API rate limit hit - please wait a bit and try again",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is UpdateCheckOutcome.Failed -> {
                            Toast.makeText(
                                context,
                                "Couldn't check for updates: ${outcome.reason}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is UpdateCheckOutcome.Throttled -> {
                            // force = true is always passed from this button, so this
                            // branch is unreachable here - kept only for exhaustiveness.
                        }
                    }
                }
            }
        }
    )

    PreferenceItem(
        title = "Source Code",
        summary = "View on GitHub",
        icon = Icons.Filled.Code,
        onClick = {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/ishaanu455/Foxkeyboard-Customized.git")
            )
            context.startActivity(intent)
        }
    )

    PreferenceItem(
        title = "Version",
        summary = BuildConfig.VERSION_NAME
    )
}

/**
 * Lets the user back up their learned-word data (typing frequency + next-word
 * associations) to a JSON file, and restore it later — e.g. after switching
 * phones. Nothing is sent anywhere automatically; export/import only happen
 * when the user explicitly picks a file via the system picker. Import merges
 * additively into whatever's already on the device rather than replacing it.
 */
@Composable
private fun DictionaryBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = UserDataBackup.export(context, uri)
            resultIsError = !ok
            resultMessage = if (ok) "Backup saved successfully" else "Backup failed"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = UserDataBackup.import(context, uri)
            resultIsError = !ok
            resultMessage = if (ok) "Restore successful" else "Couldn't read that file"
        }
    }

    Text(
        text = "The words you type are learned only on this device - they never go " +
            "anywhere else. If you're switching phones or just want a backup, you can " +
            "export/import this data as a JSON file.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        OutlinedButton(onClick = {
            resultMessage = null
            exportLauncher.launch("fox_keyboard_dictionary_backup.json")
        }) {
            Text("Export")
        }
        Spacer(modifier = Modifier.padding(start = 8.dp))
        OutlinedButton(onClick = {
            resultMessage = null
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }) {
            Text("Import")
        }
    }

    resultMessage?.let { message ->
        Text(
            text = message,
            fontSize = 13.sp,
            color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/**
 * Lets the user pick between the phone's built-in ("Mobile") emoji and an optional
 * downloadable, flat/colorful "iOS-style" pack (Twemoji - open-source, CC-BY 4.0).
 * This is NOT Apple's own emoji artwork, which is proprietary and can't legally be
 * bundled or redistributed; Twemoji is the closest legally-usable look-alike.
 */
@Composable
private fun EmojiStyleSection(
    bundledFonts: List<BundledEmojiFonts.BundledFont>,
    selectedBundledFont: String?,
    onSelectBundledFont: (String) -> Unit,
    onOpenFontPackPicker: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var selectedStyle by remember { mutableStateOf(prefs.emojiStyle) }
    var downloaded by remember { mutableStateOf(prefs.twemojiDownloaded) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var downloadFailed by remember { mutableStateOf(false) }
    var hasCustomFont by remember { mutableStateOf(CustomFontManager.hasCustomFont(context)) }
    var customFontImportFailed by remember { mutableStateOf(false) }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = CustomFontManager.importFont(context, uri)
        hasCustomFont = ok
        customFontImportFailed = !ok
        if (ok) {
            selectedStyle = EmojiStyle.CUSTOM
            prefs.emojiStyle = EmojiStyle.CUSTOM
        }
    }

    SettingsCategory(title = "Emoji Style")

    RadioOptionPreference(
        title = EmojiStyle.SYSTEM.displayName,
        summary = "Uses your phone's own emoji - always available, no download",
        selected = selectedStyle == EmojiStyle.SYSTEM,
        onClick = {
            selectedStyle = EmojiStyle.SYSTEM
            prefs.emojiStyle = EmojiStyle.SYSTEM
        }
    )

    RadioOptionPreference(
        title = EmojiStyle.TWEMOJI.displayName,
        summary = when {
            isDownloading -> "Downloading..."
            downloaded -> "Downloaded - flat, colorful look"
            else -> "Free, open-source pack (~3-5 MB, one-time download)"
        },
        selected = selectedStyle == EmojiStyle.TWEMOJI,
        onClick = {
            if (downloaded) {
                selectedStyle = EmojiStyle.TWEMOJI
                prefs.emojiStyle = EmojiStyle.TWEMOJI
            } else if (!isDownloading) {
                downloadFailed = false
                showConfirmDialog = true
            }
        }
    )

    if (isDownloading) {
        val (done, total) = progress
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "$done / $total",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (downloadFailed) {
        Text(
            text = "Download didn't finish - check your internet connection and try again.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    RadioOptionPreference(
        title = EmojiStyle.CUSTOM.displayName,
        summary = if (hasCustomFont) "Using: ${CustomFontManager.fontFile(context).name}" else "Pick a .ttf/.otf font already saved on your device",
        selected = selectedStyle == EmojiStyle.CUSTOM,
        onClick = {
            if (hasCustomFont) {
                selectedStyle = EmojiStyle.CUSTOM
                prefs.emojiStyle = EmojiStyle.CUSTOM
            } else {
                fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "font/collection", "application/x-font-ttf", "*/*"))
            }
        }
    )

    if (selectedStyle == EmojiStyle.CUSTOM || hasCustomFont) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            OutlinedButton(onClick = {
                fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "font/collection", "application/x-font-ttf", "*/*"))
            }) {
                Text(if (hasCustomFont) "Change Font File" else "Choose Font File")
            }
            if (hasCustomFont) {
                Spacer(modifier = Modifier.padding(start = 8.dp))
                TextButton(onClick = {
                    CustomFontManager.removeFont(context)
                    hasCustomFont = false
                    if (selectedStyle == EmojiStyle.CUSTOM) {
                        selectedStyle = EmojiStyle.SYSTEM
                        prefs.emojiStyle = EmojiStyle.SYSTEM
                    }
                }) { Text("Remove") }
            }
        }
        Text(
            text = "Note: fonts extracted from iOS use a color-glyph format Android often can't " +
                "render correctly - you may see blank boxes for some emoji. Works best with " +
                "Android-compatible emoji fonts (COLR/CBDT format).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        if (customFontImportFailed) {
            Text(
                text = "Couldn't read that file as a font. Please pick a valid .ttf/.otf file.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }
    }

    // Font packs bundled inside the app itself (assets/fonts/) - fully offline, no
    // download or file picker needed, unlike TWEMOJI/CUSTOM above. See BundledEmojiFonts
    // for the naming rule: whatever a .ttf is named in that folder is its display name.
    RadioOptionPreference(
        title = EmojiStyle.BUNDLED.displayName,
        summary = if (bundledFonts.isEmpty())
            "No font packs bundled with this app"
        else
            "Choose from ${bundledFonts.size} font pack(s) bundled with the app - fully offline",
        selected = selectedStyle == EmojiStyle.BUNDLED,
        onClick = {
            if (bundledFonts.isNotEmpty()) {
                selectedStyle = EmojiStyle.BUNDLED
                prefs.emojiStyle = EmojiStyle.BUNDLED
                // First time picking this style: default to whatever was saved before,
                // falling back to the first bundled pack if nothing valid was saved yet.
                if (selectedBundledFont == null || bundledFonts.none { it.fileName == selectedBundledFont }) {
                    onSelectBundledFont(bundledFonts.first().fileName)
                }
            }
        }
    )

    // Was an inline list of all 3 packs shown directly here - now a single chevron
    // row showing the current pick, opening a dedicated sub-screen instead (see
    // EmojiSection.showFontPackPicker / FontPackPickerScreen below).
    if (selectedStyle == EmojiStyle.BUNDLED && bundledFonts.isNotEmpty()) {
        val currentPackName = bundledFonts.firstOrNull { it.fileName == selectedBundledFont }?.displayName
            ?: bundledFonts.first().displayName
        PreferenceItem(
            title = "Choose Pack",
            summary = currentPackName,
            onClick = onOpenFontPackPicker
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Download emoji pack?") },
            text = {
                Text(
                    "This downloads Twemoji, a free open-source emoji set (not Apple's own " +
                        "emoji, which can't legally be bundled). It gives a flat, colorful, " +
                        "iOS-like look. Needs an internet connection; downloads once and is " +
                        "then cached for offline use."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    isDownloading = true
                    progress = 0 to 0
                    scope.launch {
                        val success = EmojiDownloader.downloadTwemojiPack(context) { done, total ->
                            progress = done to total
                        }
                        isDownloading = false
                        if (success) {
                            downloaded = true
                            prefs.twemojiDownloaded = true
                            selectedStyle = EmojiStyle.TWEMOJI
                            prefs.emojiStyle = EmojiStyle.TWEMOJI
                        } else {
                            downloadFailed = true
                        }
                    }
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Dedicated sub-screen for picking a bundled emoji font pack, opened from the
 * "Choose Pack" row in EmojiStyleSection (see EmojiSection.showFontPackPicker).
 * Reuses SettingsSubScreenHeader for the same back-arrow + title treatment every
 * other Settings sub-screen uses, and BundledFontOptionPreference for each row
 * (radio + live preview in that pack's own font + name) - identical rows to what
 * used to be shown inline, just on their own screen now.
 */
@Composable
private fun FontPackPickerScreen(
    fonts: List<BundledEmojiFonts.BundledFont>,
    selectedFileName: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSubScreenHeader(title = "Font Packs", onBack = onBack)
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            fonts.forEach { font ->
                BundledFontOptionPreference(
                    font = font,
                    selected = selectedFileName == font.fileName,
                    onClick = { onSelect(font.fileName) }
                )
            }
        }
    }
}

/**
 * One row in the "Emoji Font Packs" radio list (shown once EmojiStyle.BUNDLED is
 * selected) - a RadioButton plus a small live sample rendered in that pack's own
 * font, so the user can see the actual look before picking it, then the file's
 * display name. Mirrors RadioOptionPreference's layout/behavior; a plain View-based
 * TextView is used for the sample (via AndroidView) rather than a Compose Font, since
 * that renders through the exact same Typeface.createFromAsset path the keyboard
 * itself uses (see BundledEmojiFonts), so the preview never disagrees with reality.
 */
@Composable
private fun BundledFontOptionPreference(
    font: BundledEmojiFonts.BundledFont,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        AndroidView(
            factory = { ctx ->
                TextView(ctx).apply {
                    text = "\uD83D\uDE00 \uD83D\uDE0D \uD83C\uDF89" // sample emoji: 😀 😍 🎉
                    textSize = 20f
                }
            },
            update = { tv -> BundledEmojiFonts.loadTypeface(context, font.fileName)?.let { tv.typeface = it } },
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = font.displayName,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun rememberStringPreference(context: Context, key: String, defaultValue: String): MutableState<String> {
    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getString(key, defaultValue) ?: defaultValue) }

    DisposableEffect(key) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key) {
                state.value = sharedPreferences.getString(key, defaultValue) ?: defaultValue
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return remember(state) {
        object : MutableState<String> {
            override var value: String
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit().putString(key, newValue).apply()
                }

            override fun component1() = value
            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberBooleanPreference(context: Context, key: String, defaultValue: Boolean): MutableState<Boolean> {
    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getBoolean(key, defaultValue)) }

    DisposableEffect(key) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key) {
                state.value = sharedPreferences.getBoolean(key, defaultValue)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return remember(state) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit().putBoolean(key, newValue).apply()
                }

            override fun component1() = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberIntPreference(context: Context, key: String, defaultValue: Int): MutableState<Int> {
    val prefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getInt(key, defaultValue)) }

    DisposableEffect(key) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, k ->
            if (k == key) {
                state.value = sharedPreferences.getInt(key, defaultValue)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return remember(state) {
        object : MutableState<Int> {
            override var value: Int
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit().putInt(key, newValue).apply()
                }

            override fun component1() = value
            override fun component2(): (Int) -> Unit = { value = it }
        }
    }
}
