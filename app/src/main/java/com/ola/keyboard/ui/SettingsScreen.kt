package com.ola.keyboard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import ime.suggest.UserDataBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ola.keyboard.BundledEmojiFonts
import com.ola.keyboard.BuildConfig
import com.ola.keyboard.CustomBackgroundManager
import com.ola.keyboard.CustomFontManager
import com.ola.keyboard.ImageBlurUtils
import com.ola.keyboard.EmojiStyle
import com.ola.keyboard.Prefs
import com.ola.keyboard.PredictionManagerActivity
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
    DICTIONARY("Dictionary & Backup", "Suggestion bar, learned words and backups")
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
                if (section == SettingsSection.APPEARANCE) {
                    // Manages its own sticky-preview-on-top layout internally (fixed
                    // preview block + a weight(1f) scrollable Column below it) - that
                    // split needs a bounded-height parent, so it can't be dropped into
                    // the shared verticalScroll Column below like the other sections.
                    AppearanceSection()
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        when (section) {
                            SettingsSection.LANGUAGES -> LanguagesSection()
                            SettingsSection.APPEARANCE -> Unit
                            SettingsSection.TYPING -> TypingSection()
                            SettingsSection.EMOJI -> EmojiSection()
                            SettingsSection.CLIPBOARD -> ClipboardSection()
                            SettingsSection.DICTIONARY -> DictionarySection()
                        }
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

        // About used to be its own sub-screen behind a card + chevron, purely for
        // two social links and a version string - not enough content to justify a
        // full navigation hop. It now lives directly on the Settings home screen as
        // a quiet footer instead, so there's one less tap between the user and it.
        SettingsFooter()
    }
}

/**
 * Compact "About" footer shown at the bottom of the Settings home list: source
 * link, community link, and the current version. Kept visually lighter than the
 * SettingsMenuCard rows above it (a thin divider + centered content, no card
 * background/chevron) since it's informational, not a navigable section.
 */
@Composable
private fun SettingsFooter() {
    Spacer(modifier = Modifier.height(12.dp))

    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    )

    Spacer(modifier = Modifier.height(20.dp))

    AboutSection()

    Spacer(modifier = Modifier.height(8.dp))
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

/**
 * Preview stays pinned at the top as its own fixed block (not part of the
 * scrolling content below) so switching Colour/Gradient themes further down the
 * list is visible live without having to scroll back up to see it - the keyboard
 * mock-up is never hidden behind the options that change it.
 */
@Composable
private fun AppearanceSection() {
    val context = LocalContext.current

    val automaticTheme = rememberBooleanPreference(context, "automatic_theme", true)
    val darkTheme = rememberBooleanPreference(context, "dark_theme", false)
    val keyBorders = rememberBooleanPreference(context, "key_borders", true)
    val colorTheme = rememberStringPreference(context, "color_theme", "ola")
    val effectiveDark = if (automaticTheme.value) isSystemInDarkTheme() else darkTheme.value

    val prefs = remember { Prefs(context) }
    var backgroundMode by remember { mutableStateOf(prefs.backgroundMode) }
    // Bumped every time the on-disk image file changes (picked/removed) so the
    // thumbnail AsyncImage-style loader below re-reads it instead of showing a
    // stale bitmap cached under the same file path.
    var customBgVersion by remember { mutableIntStateOf(0) }
    var customBgImportFailed by remember { mutableStateOf(false) }

    // Step 3: full-screen pan/blur/darken adjustment. Opened automatically right
    // after a new photo import succeeds, and also when re-tapping an already-picked
    // photo's card ("tap to change photo" -> re-adjust the existing image without
    // re-opening the system picker). Nothing is written to Prefs until Save inside
    // that screen - Cancel/back here just closes it, discarding any in-progress drag/
    // slider changes.
    var showAdjustScreen by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = CustomBackgroundManager.importImage(context, uri)
        customBgImportFailed = !ok
        if (ok) {
            customBgVersion++
            backgroundMode = "custom_image"
            prefs.backgroundMode = "custom_image"
            showAdjustScreen = true
        }
    }

    BackHandler(enabled = showAdjustScreen) { showAdjustScreen = false }

    if (showAdjustScreen) {
        CustomBackgroundAdjustScreen(
            dark = effectiveDark,
            initialOffsetX = prefs.customBgOffsetX,
            initialOffsetY = prefs.customBgOffsetY,
            initialBlur = prefs.customBgBlur,
            initialDarken = prefs.customBgDarken,
            initialZoom = prefs.customBgZoom,
            onSave = { x, y, blur, darken, zoom ->
                prefs.customBgOffsetX = x
                prefs.customBgOffsetY = y
                prefs.customBgBlur = blur
                prefs.customBgDarken = darken
                prefs.customBgZoom = zoom
                customBgVersion++
                showAdjustScreen = false
            },
            onCancel = { showAdjustScreen = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Fixed top block: never scrolls ---
        SettingsCategory(title = "Preview")
        KeyboardPreview(
            dark = effectiveDark,
            keyBorders = keyBorders.value,
            colorThemeId = colorTheme.value,
            backgroundMode = backgroundMode,
            customBgOffsetX = prefs.customBgOffsetX,
            customBgOffsetY = prefs.customBgOffsetY,
            customBgBlur = prefs.customBgBlur,
            customBgDarken = prefs.customBgDarken,
            customBgZoom = prefs.customBgZoom,
            customBgVersion = customBgVersion,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // --- Scrollable rest ---
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SwitchPreference(
                title = "Automatic Theme",
                summary = "Follow the system's light/dark setting",
                icon = Icons.Filled.DarkMode,
                checked = automaticTheme.value,
                onCheckedChange = { automaticTheme.value = it }
            )

            if (!automaticTheme.value) {
                SwitchPreference(
                    title = "Dark Theme",
                    checked = darkTheme.value,
                    onCheckedChange = { darkTheme.value = it }
                )
            }

            SwitchPreference(
                title = "Border",
                summary = "Show an outline around each key",
                checked = keyBorders.value,
                onCheckedChange = { keyBorders.value = it }
            )

            SettingsCategory(title = "Colour Themes")
            ColorThemePicker(
                themes = keyboardColorThemes,
                selected = colorTheme.value,
                onSelect = {
                    colorTheme.value = it
                    backgroundMode = "theme"
                    prefs.backgroundMode = "theme"
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsCategory(title = "Gradient Color Themes")
            ColorThemePicker(
                themes = gradientColorThemes,
                selected = colorTheme.value,
                onSelect = {
                    colorTheme.value = it
                    backgroundMode = "theme"
                    prefs.backgroundMode = "theme"
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            SettingsCategory(title = "Custom Background")
            // Feature not released yet - shown as a disabled "Coming Soon" row
            // instead of the real picker. Not clickable. Swap back to
            // CustomBackgroundPicker(...) (see git history / previous version)
            // once the feature is ready to ship.
            ComingSoonRow(
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * "Custom Background" row in Appearance - a single card that either shows a
 * "Choose from Gallery" prompt (no image picked yet) or the picked image's
 * thumbnail with a small "Change" / "Remove" action pair once one is selected.
 * Mirrors the rounded, gold-bordered card language every other Settings row in
 * this screen uses (see `settingsCard()` in SettingsComponents.kt), with the
 * same "selected" gold-ring highlight ColorThemePicker's swatches use when this
 * is the active background mode.
 */
@Composable
private fun CustomBackgroundPicker(
    selected: Boolean,
    hasImage: Boolean,
    imageVersion: Int,
    onChooseImage: () -> Unit,
    onAdjust: () -> Unit,
    onRemoveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(18.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(cardShape)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Color(0xFFD4A62A) else MaterialTheme.colorScheme.outlineVariant,
                shape = cardShape
            )
            .background(
                if (selected) Color(0xFFD4A62A).copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface,
                cardShape
            )
            // Already have a photo -> tapping the card re-opens the pan/blur/darken
            // adjustment screen on that same image. No photo yet -> straight to the
            // system picker. Picking a *different* photo goes through the small
            // "Change" text action below instead, so this main tap target never has
            // two different meanings depending on where exactly you touch it.
            .clickable(onClick = if (hasImage) onAdjust else onChooseImage)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 56x56 thumbnail/placeholder box, same corner treatment as the card itself
        // scaled down, so it reads as "one photo, framed" rather than a separate
        // floating element.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (hasImage) {
                val bitmap = remember(imageVersion) {
                    CustomBackgroundManager.loadBitmap(context)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Custom background thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (hasImage) "Photo Background" else "Choose from Gallery",
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasImage) {
                    if (selected) "Active - tap to change photo" else "Tap to use this photo"
                } else {
                    "Use your own photo as the keyboard background"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (hasImage) {
            TextButton(onClick = onChooseImage) {
                Text(text = "Change", fontSize = 13.sp)
            }
            IconButton(onClick = onRemoveImage) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove custom background",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Placeholder shown in place of [CustomBackgroundPicker] while the custom
 * background feature is disabled/unreleased. Mirrors the same card language
 * (rounded corners, border, thumbnail box) but is greyed out, carries a
 * "Coming Soon" badge, and has no click target at all - so it reads as
 * "not available yet" rather than a broken/disabled control.
 */
@Composable
private fun ComingSoonRow(modifier: Modifier = Modifier) {
    val cardShape = RoundedCornerShape(18.dp)
    val mutedContent = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(cardShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = cardShape
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                cardShape
            )
            // Intentionally no .clickable(...) - the row is inert.
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = mutedContent,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Choose from Gallery",
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = mutedContent
            )
            Text(
                text = "Use your own photo as the keyboard background",
                fontSize = 12.sp,
                color = mutedContent
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Coming Soon",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = mutedContent
            )
        }
    }
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
    // Null for the plain "Colour Themes" row - the swatch and the space/enter keys
    // just use [accent] as a flat fill. Non-null for a "Gradient Color Themes" entry:
    // exactly two variants of the SAME main colour (a lighter tint + a darker shade
    // of [accent], see lightenColor/darkenColor below) - never two unrelated hues -
    // so the swatch and space/enter keys render as a 2-stop gradient between them.
    val gradientColors: List<Color>? = null,
)

/** Blends [color] toward white by [amount] (0f = unchanged, 1f = white). */
private fun lightenColor(color: Color, amount: Float): Color {
    return Color(
        red = color.red + (1f - color.red) * amount,
        green = color.green + (1f - color.green) * amount,
        blue = color.blue + (1f - color.blue) * amount,
        alpha = color.alpha
    )
}

/** Blends [color] toward black by [amount] (0f = unchanged, 1f = black). */
private fun darkenColor(color: Color, amount: Float): Color {
    return Color(
        red = color.red * (1f - amount),
        green = color.green * (1f - amount),
        blue = color.blue * (1f - amount),
        alpha = color.alpha
    )
}

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

/** "Gradient Color Themes" row entries - one per existing main colour above, each
 *  reusing that theme's own key-bed tokens untouched and swapping only the flat
 *  [KeyboardColorTheme.accent] for a [gradientColors] pair (a lighter tint + a
 *  darker shade of that SAME accent). Deliberately not a separate hand-picked
 *  palette, per the ask: the gradient stops must be variants of the existing main
 *  colour, not new/unrelated colours. */
private val gradientColorThemes = keyboardColorThemes.map { theme ->
    theme.copy(
        id = "${theme.id}_gradient",
        label = "${theme.label} Gradient",
        gradientColors = listOf(lightenColor(theme.accent, 0.35f), darkenColor(theme.accent, 0.30f))
    )
}

@Composable
private fun ColorThemePicker(
    themes: List<KeyboardColorTheme>,
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
        themes.forEach { theme ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(theme.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .then(
                            if (theme.gradientColors != null) {
                                Modifier.background(Brush.linearGradient(theme.gradientColors))
                            } else {
                                Modifier.background(theme.accent)
                            }
                        )
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
 *
 * Step 5: when [backgroundMode] is "custom_image" and a saved photo actually
 * exists, this swaps the theme-coloured background for that photo - panned/
 * blurred/darkened exactly per the saved [customBgOffsetX]/[customBgOffsetY]/
 * [customBgBlur]/[customBgDarken] values, via the SAME [CustomBackgroundPreviewBox]
 * the adjustment screen (Step 3) uses, just non-draggable here - and every key
 * (letters, function, space/enter alike) switches to a translucent frosted-glass
 * treatment instead of a flat/gradient fill, since a solid theme colour would
 * fight with an arbitrary photo underneath. If the saved file is missing/corrupt
 * (Step 7), [customBitmap] comes back null and this silently falls back to the
 * normal theme-coloured board below - no separate empty/error state needed.
 */
@Composable
private fun KeyboardPreview(
    dark: Boolean,
    keyBorders: Boolean,
    colorThemeId: String,
    backgroundMode: String,
    customBgOffsetX: Float,
    customBgOffsetY: Float,
    customBgBlur: Float,
    customBgDarken: Float,
    customBgZoom: Float,
    customBgVersion: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val theme = (keyboardColorThemes + gradientColorThemes).first { it.id == colorThemeId }
    val bg = if (dark) theme.darkBg else theme.lightBg
    val keyColor = if (dark) theme.darkKey else theme.lightKey
    val funcColor = if (dark) theme.darkFunc else theme.lightFunc

    // customBgVersion is bumped by AppearanceSection on import/adjust-save/remove,
    // so this re-reads the file exactly when it can have actually changed instead
    // of on every recomposition (see CustomBackgroundManager's own in-memory cache
    // for the same reasoning at the disk-read level).
    val customBitmap = if (backgroundMode == "custom_image") {
        remember(customBgVersion) { CustomBackgroundManager.loadBitmap(context)?.asImageBitmap() }
    } else null
    val useGlass = customBitmap != null

    // BUG FIX: this call site used to pass no preBlurredBitmap at all, so on API < 31
    // (no live Modifier.blur - see CustomBackgroundPreviewBox) customBgBlur was silently
    // ignored here no matter what the adjustment screen's slider/Save had set - this is
    // the ONLY thing standing between "sharp photo" and "the blur amount actually saved"
    // on those devices, exactly the same bake CustomBackgroundAdjustScreen already does
    // for its own live preview.
    var bakedBlurredBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val needsBakedBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    LaunchedEffect(needsBakedBlur, customBgBlur, customBitmap) {
        if (!needsBakedBlur || customBitmap == null || customBgBlur <= 0f) {
            bakedBlurredBitmap = null
            return@LaunchedEffect
        }
        val source = CustomBackgroundManager.loadBitmap(context) ?: return@LaunchedEffect
        bakedBlurredBitmap = withContext(Dispatchers.Default) {
            ImageBlurUtils.blur(context, source, customBgBlur).asImageBitmap()
        }
    }

    val textColor = if (useGlass) Color.White else if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val borderColor = if (keyBorders) textColor.copy(alpha = if (useGlass) 0.30f else 0.18f) else Color.Transparent

    // Glass tint switches with light/dark theme, same as every other themed
    // surface here - a white-ish frosted look in light mode, a darker frosted
    // look in dark mode, so the keys still read as "this app's dark mode" and
    // not just "whatever the photo happens to be light/dark at that spot".
    val glassFill = if (dark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.22f)
    // BUG FIX: this used to be a fixed colour, so a glass key always drew a border even
    // with "Border" (keyBorders) switched off in Settings - now it respects that toggle
    // the same way the flat/gradient-theme borderColor above already does.
    val glassBorder = if (!keyBorders) {
        Color.Transparent
    } else if (dark) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val glassShape = RoundedCornerShape(6.dp)

    // Small drop shadow under each key - part of the "glassmorphism ... with
    // small shadow" look asked for; only applied in glass mode, the flat/gradient
    // theme keys never had a shadow and shouldn't suddenly grow one.
    fun glassKeyMod() = Modifier
        .height(40.dp)
        .shadow(elevation = 2.dp, shape = glassShape, clip = false)
        .clip(glassShape)
        .background(glassFill)
        .border(0.8.dp, glassBorder, glassShape)

    fun keyMod(background: Color) = if (useGlass) glassKeyMod() else Modifier
        .height(40.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(background)
        .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))

    // Whole-keyboard fill: for a "*_gradient" theme this is the SAME lightVariant->
    // darkVariant brush the swatch itself uses, painted across the entire preview
    // instead of the flat bg colour, so the preview matches what
    // applyGradientToKeyboard() now does on the real keyboard - gradient behind every
    // key and the top bar. Only used when useGlass is false - the photo itself is the
    // background otherwise (see the Box below).
    val boardBackground = theme.gradientColors
        ?.let { Brush.linearGradient(it) }
        ?: SolidColor(bg)

    Box(
        modifier = modifier.clip(RoundedCornerShape(16.dp))
    ) {
        if (useGlass) {
            CustomBackgroundPreviewBox(
                bitmap = customBitmap,
                offsetX = customBgOffsetX,
                offsetY = customBgOffsetY,
                blurAmount = customBgBlur,
                darkenAmount = customBgDarken,
                dark = dark,
                draggable = false,
                preBlurredBitmap = bakedBlurredBitmap,
                zoom = customBgZoom,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(boardBackground)
            )
        }

        Column(modifier = Modifier.padding(6.dp)) {
            // Top bar: Ola logo mark + the real toolbar icons, same order as
            // keyboard_layout.xml's top_bar_icon_row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // BUG FIX: was .size(30.dp).padding(4.dp) - since .size() constrains the
                // box FIRST and .padding() then eats into that already-fixed box, the
                // logo's actual visible area was only 22dp inside a 38dp-tall row (~58%
                // of the row). The real keyboard's btn_ola_logo is a 44dp box with just
                // 2dp padding - a ~40dp visible logo, ~91% of ITS row - so this mockup's
                // logo read as noticeably smaller/off compared to the real keyboard,
                // not sitting where a user familiar with the real keyboard would expect.
                // Sized/padded here to match that same proportion against this row's
                // own 38dp height instead.
                Image(
                    painter = painterResource(id = R.drawable.ic_ola_logo_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .padding(1.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                // BUG FIX: order/icon-set here used to be
                // [clipboard, emoji, text_select, fonts] - neither the order NOR the
                // set matched the real keyboard's top_bar_icon_row (keyboard_layout.xml):
                // emoji, fonts, clipboard, text_select, settings. Settings (the gear)
                // was missing entirely.
                // BUG FIX: this used to give the circular ?attr/keyFunction badge to only
                // fonts/settings, on the assumption the other three icons have no
                // background at all - but @style/TopBarIcon (styles.xml) sets
                // android:background="@drawable/bg_top_bar_icon_circle" as part of the
                // style itself, and emoji/clipboard/text_select all use that style
                // without overriding background - so on the real keyboard EVERY one of
                // these 5 icons gets the same circular badge, not just 2 of them. This
                // preview now matches: all 5 get circleBadge = true.
                // funcColor (not a glass tint) matches the real keyboard too: even in
                // custom-image/glass mode, applyGlassKeyStyling() only re-skins the
                // letter/number/space/enter keys, never these top-bar badges.
                listOf(
                    R.drawable.ic_emoji to true,
                    R.drawable.ic_fonts to true,
                    R.drawable.ic_clipboard to true,
                    R.drawable.ic_text_select to true,
                    R.drawable.ic_settings to true
                ).forEach { (iconRes, circleBadge) ->
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(26.dp)
                            .then(
                                if (circleBadge) {
                                    Modifier
                                        .clip(CircleShape)
                                        .background(funcColor)
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(textColor),
                            modifier = Modifier.size(if (circleBadge) 15.dp else 20.dp)
                        )
                    }
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
            // BUG FIX: these two side keys used to be empty colour-only boxes with no
            // icon at all - on a flat theme colour that blended in well enough not to
            // notice, but the moment a custom photo background makes every key glassy
            // and translucent, an icon-less box reads as a broken/missing key (exactly
            // the "keys awl wela" report) rather than "this is the shift/backspace key,
            // just simplified". Now uses the SAME ic_shift/ic_backspace drawables the
            // real keyboard (keyboard_layout.xml) draws in this exact spot.
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
                    Image(
                        painter = painterResource(id = R.drawable.ic_shift),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(18.dp)
                    )
                }
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
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_backspace),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Bottom row: 123 / lang / comma / space / dot / enter. Space and Enter now
            // use the SAME funcColor as every other function key (lang/"?123"/","/".")
            // instead of the old accent-brush fill - see key_background_action.xml on
            // the real keyboard side, which got the equivalent recolour so this preview
            // and the real keyboard match (previously both singled out space/enter with
            // the theme's bright accent colour, which read as "randomly coloured
            // differently" rather than intentional).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "?123", color = textColor, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ENG",
                        color = textColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = ",", color = textColor, fontSize = 15.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(3f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_space_bar),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = ".", color = textColor, fontSize = 15.sp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(keyMod(funcColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(textColor),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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

    // GitHub (source code) + Telegram, centered under the list rows above rather
    // than as their own PreferenceItem rows - these are external/social links, not
    // settings, so they get a lighter "icon row" treatment instead of full cards.
    // Telegram has no destination yet (onClick left as a no-op) - link goes in
    // once we have the channel URL.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        SocialIconButton(
            iconRes = R.drawable.ic_github,
            contentDescription = "Source code on GitHub",
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/ishaanu455/Foxkeyboard-Customized.git")
                )
                context.startActivity(intent)
            }
        )
        Spacer(modifier = Modifier.width(24.dp))
        SocialIconButton(
            iconRes = R.drawable.ic_telegram,
            contentDescription = "Telegram",
            onClick = { /* TODO: open Telegram channel once the link is set */ }
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    Text(
        text = "Version ${BuildConfig.VERSION_NAME}",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

/**
 * Small circular icon button for an external/social link (GitHub, Telegram) shown
 * under the About list. Same rounded-card visual language as the rest of Settings
 * (gold border on a slightly-raised surface) but sized and shaped as a compact
 * badge rather than a full-width row, since these are quick link-outs rather than
 * settings a user configures.
 */
@Composable
private fun SocialIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
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
 * a bundled offline font pack (see EmojiStyle.BUNDLED).
 * This is NOT Apple's own emoji artwork, which is proprietary and can't legally be
 * bundled/redistributed, so a look-alike bundled pack is offered instead.
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

    var selectedStyle by remember { mutableStateOf(prefs.emojiStyle) }
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
    // download or file picker needed, unlike CUSTOM above. See BundledEmojiFonts
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
        // Short caption above the list, same pattern as the note shown on the
        // classic keyboard's font_style_layout.xml screen, so this sub-screen
        // doesn't open on a bare list with no context.
        Text(
            text = "Choose how emoji look across the keyboard",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )
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
 * selected) - a RadioButton, a small "preview swatch" chip rendered in that pack's
 * own font, and the file's display name.
 *
 * Rebuilt to sit in the same rounded, gold-bordered card every other Settings row
 * uses (see `settingsCard()` in SettingsComponents.kt) - this row used to be a bare,
 * unbordered list item, the only one in all of Settings that didn't match, which
 * read as unfinished next to RadioOptionPreference etc. The selected pack now also
 * gets its own highlighted card (gold border + warmed background) instead of relying
 * on the radio dot alone, mirroring the "selected" ring treatment used elsewhere in
 * the app (e.g. the emoji-style picker's own selected state).
 *
 * A plain View-based TextView is used for the sample (via AndroidView) rather than a
 * Compose Font, since that renders through the exact same Typeface.createFromAsset
 * path the keyboard itself uses (see BundledEmojiFonts), so the preview never
 * disagrees with reality.
 */
@Composable
private fun BundledFontOptionPreference(
    font: BundledEmojiFonts.BundledFont,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(cardShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = cardShape
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
        Spacer(modifier = Modifier.width(10.dp))
        // Sample rendered inside its own small rounded "swatch" so it reads as a
        // preview chip rather than loose text floating next to the name.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        text = "\uD83D\uDE00 \uD83D\uDE0D \uD83C\uDF89" // sample emoji: 😀 😍 🎉
                        textSize = 18f
                    }
                },
                update = { tv -> BundledEmojiFonts.loadTypeface(context, font.fileName)?.let { tv.typeface = it } }
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = font.displayName,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
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
