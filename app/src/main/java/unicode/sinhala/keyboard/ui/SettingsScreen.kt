package unicode.sinhala.keyboard.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ime.suggest.UserDataBackup
import kotlinx.coroutines.launch
import unicode.sinhala.com.BuildConfig
import unicode.sinhala.keyboard.CustomFontManager
import unicode.sinhala.keyboard.EmojiDownloader
import unicode.sinhala.keyboard.EmojiStyle
import unicode.sinhala.keyboard.Prefs
import unicode.sinhala.keyboard.PredictionManagerActivity
import unicode.sinhala.keyboard.ui.components.PreferenceItem
import unicode.sinhala.keyboard.ui.components.RadioOptionPreference
import unicode.sinhala.keyboard.ui.components.SettingsCategory
import unicode.sinhala.keyboard.ui.components.SettingsMenuCard
import unicode.sinhala.keyboard.ui.components.SettingsSubScreenHeader
import unicode.sinhala.keyboard.ui.components.SliderPreference
import unicode.sinhala.keyboard.ui.components.SwitchPreference

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
    var currentSection by remember { mutableStateOf<SettingsSection?>(null) }

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
    SwitchPreference(
        title = "Key Borders",
        summary = "Show an outline around each key",
        checked = keyBorders.value,
        onCheckedChange = { keyBorders.value = it }
    )
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

    val showRecentEmojiRow = rememberBooleanPreference(context, "show_recent_emoji_row", false)
    SwitchPreference(
        title = "Show Recent Emoji Row",
        checked = showRecentEmojiRow.value,
        onCheckedChange = { showRecentEmojiRow.value = it }
    )

    EmojiStyleSection()
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
private fun EmojiStyleSection() {
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
