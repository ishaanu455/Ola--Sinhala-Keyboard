package com.ola.keyboard.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = LogoGold,
    secondary = LogoGoldDeep,
    tertiary = LogoCreamMuted,
    background = MattBlack,
    surface = SettingsSurface,
    primaryContainer = SettingsSurfaceElevated,
    outline = LogoGoldDeep,
    // onPrimary pairs with `primary` above - gold needs dark text/icons on top of
    // it to stay readable, Cream would wash out against it.
    onPrimary = Ink1,
    onSecondary = Ink1,
    onTertiary = Ink1,
    onPrimaryContainer = LogoGold,
    onBackground = LogoCream,
    onSurface = LogoCream,
    onSurfaceVariant = LogoCreamMuted,
)

private val LightColorScheme = lightColorScheme(
    primary = Olive,
    secondary = Amber,
    tertiary = Light2,
    background = Cream,
    surface = KeyboardKeyLight,
    onPrimary = Cream,
    onSecondary = Ink1,
    onTertiary = Ink1,
    onBackground = Ink1,
    onSurface = Ink1,
)

@Composable
fun OlaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic (Material You) color is available on Android 12+, but it derives colors
    // from the phone's wallpaper - which would silently override our own brand palette
    // on most modern devices. Defaulting this off keeps the Ola olive/amber identity
    // consistent everywhere instead of only showing up on older phones.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
