package com.ola.keyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Corner radius shared by every row/card in Settings, for one consistent modern look. */
private val SettingsCardShape = RoundedCornerShape(18.dp)

/**
 * Common "grouped list row" background used by every settings row (menu cards,
 * switches, radios, plain preference rows): a rounded gold-bordered card sitting
 * on the matte-black page, so the whole menu/sub-menu reads as one modern system
 * instead of a flat list.
 */
@Composable
private fun Modifier.settingsCard(): Modifier = this
    .padding(horizontal = 16.dp, vertical = 5.dp)
    .clip(SettingsCardShape)
    .background(MaterialTheme.colorScheme.surface)
    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), SettingsCardShape)

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title,
        // Was MaterialTheme.colorScheme.primary, which is a dark navy blue even in
        // dark mode - nearly invisible on the dark background. `primary` is now a
        // lighter tint in dark theme specifically so headers like this stay legible
        // in both themes (see ui/theme/Color.kt - OlivePrimaryDark).
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * A top-level, tappable row used on the Settings home screen - one per section
 * (Languages, Appearance, Typing, Emoji, Clipboard, Dictionary, About). Shows a
 * rounded icon "badge", a title + short summary, and a chevron to signal it opens
 * a sub-screen. This is what turns the settings screen from one long flat list
 * into a modern, organized menu with submenus.
 */
@Composable
fun SettingsMenuCard(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Solid gold badge (not a low-alpha tint) so each section reads clearly
        // against the black card - matches the gold used in the "Ola" wordmark.
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Header shown at the top of a settings sub-screen (e.g. "Appearance"), with a
 * back arrow that returns to the section list. This is a plain row rather than a
 * second Material3 TopAppBar - the screen already has one real app bar ("Ola
 * Keyboard") above it, and stacking a second full-width bar under that looked
 * cluttered. This keeps a single visual app bar while still giving each
 * sub-screen its own clear title + back action.
 */
@Composable
fun SettingsSubScreenHeader(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = title,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.background,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
fun SliderPreference(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
    icon: ImageVector? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
                Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(text = value.toString(), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun RadioOptionPreference(
    title: String,
    summary: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
