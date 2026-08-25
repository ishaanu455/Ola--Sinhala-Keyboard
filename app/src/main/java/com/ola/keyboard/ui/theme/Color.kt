package com.ola.keyboard.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette - "Ola": olive + amber + cream. Chosen deliberately away from the
// blue every other keyboard app (Gboard, SwiftKey, Samsung Keyboard) uses as its
// primary, so Settings/buttons/keys read as our own rather than a Material default.
val Olive = Color(0xFF3A5A40)
// Lighter tint of Olive used only in dark theme, same reasoning as before: on a dark
// background, primary-colored text/icons need to be lighter to stay legible.
val OlivePrimaryDark = Color(0xFF8FB996)
val Amber = Color(0xFFD4A24C)
val AmberDark = Color(0xFFE8BE7A)
val Cream = Color(0xFFF3EFE6)
val Ink1 = Color(0xFF1C1B17)
val Ink2 = Color(0xFF332F26)

val Light1 = Color(0xFFD9D9D9)
val Light2 = Color(0xFFA6A6A6)

// Physical keyboard key palette (warm cream/ink undertone rather than Gboard's
// cool white/grey - see colors.xml for the matching XML tokens used by the
// classic View-based keyboard renderer).
val KeyboardBgLight = Color(0xFFFBF8F2)
val KeyboardKeyLight = Color(0xFFFFFFFF)
val KeyboardFuncLight = Color(0xFFEFE9DC)
val KeyboardFuncPressedLight = Color(0xFFE3DBC8)

val KeyboardBgDark = Color(0xFF1C1B17)
val KeyboardKeyDark = Color(0xFF26241E)
val KeyboardFuncDark = Color(0xFF332F26)
val KeyboardFuncPressedDark = Color(0xFF403A2C)

val AccentAmber = Color(0xFFD4A24C)
val AccentAmberPressed = Color(0xFFB8853A)
