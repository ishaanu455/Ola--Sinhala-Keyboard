package com.ola.keyboard.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ola.keyboard.AppFont

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

/** Wraps Material3's default Typography, swapping in the app's bundled font
 *  (res/font/sinhala_sangam_mn.ttf) on every text style so Sinhala/English text
 *  across Settings, Clips manager, Donate, etc. renders with it instead of
 *  whatever font family the device happens to be set to. */
private fun olaTypography(): Typography {
    val font = AppFont.composeFontFamily()
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = font),
        displayMedium = base.displayMedium.copy(fontFamily = font),
        displaySmall = base.displaySmall.copy(fontFamily = font),
        headlineLarge = base.headlineLarge.copy(fontFamily = font),
        headlineMedium = base.headlineMedium.copy(fontFamily = font),
        headlineSmall = base.headlineSmall.copy(fontFamily = font),
        titleLarge = base.titleLarge.copy(fontFamily = font),
        titleMedium = base.titleMedium.copy(fontFamily = font),
        titleSmall = base.titleSmall.copy(fontFamily = font),
        bodyLarge = base.bodyLarge.copy(fontFamily = font),
        bodyMedium = base.bodyMedium.copy(fontFamily = font),
        bodySmall = base.bodySmall.copy(fontFamily = font),
        labelLarge = base.labelLarge.copy(fontFamily = font),
        labelMedium = base.labelMedium.copy(fontFamily = font),
        labelSmall = base.labelSmall.copy(fontFamily = font),
    )
}

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

    val typography = remember { olaTypography() }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
