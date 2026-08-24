package unicode.sinhala.keyboard.ui.theme

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
    primary = BluePrimaryDark,
    secondary = Yellow,
    tertiary = Light2,
    background = Night1,
    surface = Night2,
    // onPrimary pairs with `primary` above. Now that primary is the lighter
    // BluePrimaryDark (not the dark Blue), text/icons drawn on top of it need to be
    // dark again to stay readable - Light1 (near-white) would wash out against it.
    onPrimary = Night1,
    onSecondary = Night1,
    onTertiary = Night1,
    onBackground = Light1,
    onSurface = Light1,
)

private val LightColorScheme = lightColorScheme(
    primary = Blue,
    secondary = Yellow,
    tertiary = Light2,
    background = GboardBgLight,
    surface = GboardKeyLight,
    onPrimary = Light1,
    onSecondary = Night1,
    onTertiary = Night1,
    onBackground = Night1,
    onSurface = Night1,
)

@Composable
fun UnicodeSinhalaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
