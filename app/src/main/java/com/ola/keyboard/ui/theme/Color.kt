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

// Matte-black + logo-gold palette. Sampled straight from the "Ola" wordmark
// (cream/ivory letters, gold swoosh + dots on a near-black field), so this is
// what the dark theme now uses everywhere - and what Settings is forced into
// regardless of the user's own light/dark toggle, since that's the screen this
// was specifically built for.
val MattBlack = Color(0xFF151515)                 // page background
val SettingsSurface = Color(0xFF1F1F1D)           // card / row surface, one step up from MattBlack
val SettingsSurfaceElevated = Color(0xFF29281F)   // selected / pressed surface, warmed slightly toward gold
val LogoGold = Color(0xFFFCAD15)                  // primary accent - matches the logo swoosh
val LogoGoldDeep = Color(0xFFC9860B)              // pressed / secondary gold, matches the swoosh's shadow edge
val LogoCream = Color(0xFFF9EED5)                 // primary text/icons on black, matches the logo lettering
val LogoCreamMuted = Color(0xFFA79E8C)            // secondary/summary text on black

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
