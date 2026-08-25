package com.ola.keyboard

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ola.keyboard.BuildConfig
import com.ola.keyboard.ui.MainScreen
import com.ola.keyboard.ui.theme.OlaTheme

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var inputMethodManager: InputMethodManager
    private var isKeyboardEnabled by mutableStateOf(false)
    private var isKeyboardSelected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        applyTheme(prefs)

        super.onCreate(savedInstanceState)

        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        updateUIState()

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

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "automatic_theme" || key == "dark_theme") {
            sharedPreferences?.let { applyTheme(it) }
            recreate()
        }
    }

    private fun applyTheme(prefs: SharedPreferences) {
        val automaticTheme = prefs.getBoolean("automatic_theme", true)
        val darkTheme = prefs.getBoolean("dark_theme", false)

        if (automaticTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            if (darkTheme) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }
}
