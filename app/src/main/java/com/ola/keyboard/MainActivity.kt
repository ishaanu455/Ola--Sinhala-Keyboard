package com.ola.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ola.keyboard.BuildConfig
import com.ola.keyboard.ui.MainScreen
import com.ola.keyboard.ui.theme.OlaTheme

// Deliberately NOT an AppCompatDelegate.setDefaultNightMode() based theme switch, and
// deliberately NOT a SharedPreferences.OnSharedPreferenceChangeListener on
// "automatic_theme"/"dark_theme" anymore. Those prefs only ever needed to affect two
// things - and setDefaultNightMode() helped neither of them:
//  1) The keyboard's own light/dark look - InputMethodService reads Prefs.getDarkTheme()
//     directly off its own SharedPreferences listener and rebuilds itself; it was never
//     wired to this Activity's night mode at all.
//  2) The Appearance screen's live KeyboardPreview - that's plain Compose state
//     (rememberBooleanPreference + isSystemInDarkTheme()), so it already recomposes the
//     instant the switch is tapped, no Activity involvement needed.
// SettingsScaffold (MainScreen.kt) is hardcoded to darkTheme = true regardless of this
// preference, so there was never anything on screen here that setDefaultNightMode()
// was updating. All it did was force AppCompat to recreate() this Activity on every
// toggle - the visible flicker - for zero visual benefit.
class MainActivity : AppCompatActivity() {

    private lateinit var inputMethodManager: InputMethodManager
    private var isKeyboardEnabled by mutableStateOf(false)
    private var isKeyboardSelected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        updateUIState()

        // The keyboard's settings-gear red dot (see KeyboardView.kt) launches us with
        // this extra when an update is pending, so tapping it jumps straight to
        // UpdateActivity instead of landing on the Settings home list first.
        if (intent?.getStringExtra(EXTRA_NAVIGATE_TO) == NAVIGATE_TO_UPDATE) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }

        setContent {
            OlaTheme {
                MainScreen(
                    isKeyboardEnabled = isKeyboardEnabled,
                    isKeyboardSelected = isKeyboardSelected,
                    onEnableKeyboard = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onSelectKeyboard = {
                        inputMethodManager.showInputMethodPicker()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MainActivity is launched with FLAG_ACTIVITY_NEW_TASK from the IME (see
        // KeyboardView.kt) - if it's already on top of its task, onCreate() won't
        // run again for the new tap, only this. Handle the same extra here too so
        // the red-dot tap still jumps to UpdateActivity in that case.
        if (intent.getStringExtra(EXTRA_NAVIGATE_TO) == NAVIGATE_TO_UPDATE) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateUIState()
        }
    }

    private fun updateUIState() {
        isKeyboardEnabled = checkIfKeyboardEnabled()
        isKeyboardSelected = checkIfKeyboardSelected()
    }

    private fun checkIfKeyboardEnabled(): Boolean {
        val packageLocal = BuildConfig.APPLICATION_ID
        for (inputMethodInfo in inputMethodManager.enabledInputMethodList) {
            if (inputMethodInfo.packageName == packageLocal) {
                return true
            }
        }
        return false
    }

    private fun checkIfKeyboardSelected(): Boolean {
        val defaultIME = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return defaultIME != null && defaultIME.contains(BuildConfig.APPLICATION_ID)
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val NAVIGATE_TO_UPDATE = "update"
    }
}

