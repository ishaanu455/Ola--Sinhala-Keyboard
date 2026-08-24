package unicode.sinhala.keyboard.ui.theme

import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF3956AA)
// Lighter tint of Blue used only in dark theme. Material's own dark-theme guidance is to
// raise a brand color's lightness in dark mode - the plain Blue above is dark enough that,
// used as `primary` on a dark background (Night1), section headers and other primary-colored
// text/icons were rendering with too little contrast to read (the "invisible header" bug).
val BluePrimaryDark = Color(0xFF8FA8E8)
val Light1 = Color(0xFFD9D9D9)
val Light2 = Color(0xFFA6A6A6)
val Night1 = Color(0xFF262626)
val Night2 = Color(0xFF595959)
val Yellow = Color(0xFFDFC540)

val GboardBgLight = Color(0xFFECEFF1)
val GboardKeyLight = Color(0xFFFFFFFF)
val GboardFuncLight = Color(0xFFCFD8DC)
val GboardFuncPressedLight = Color(0xFFB0BEC5)

val GboardBgDark = Color(0xFF263238)
val GboardKeyDark = Color(0xFF455A64)
val GboardFuncDark = Color(0xFF37474F)
val GboardFuncPressedDark = Color(0xFF263238)

val GboardActionBlue = Color(0xFF4285F4)
val GboardActionBluePressed = Color(0xFF3367D6)
