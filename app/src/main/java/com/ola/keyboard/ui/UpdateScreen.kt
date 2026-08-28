package com.ola.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ola.keyboard.ui.components.SettingsSubScreenHeader

/** Mirrors [UpdateDownloadState] one level up (UpdateActivity) so this file stays
 *  a pure, stateless UI layer with no DownloadManager/FileProvider knowledge. */
enum class UpdateScreenState { IDLE, CHECKING, DOWNLOADING, INSTALLING, UP_TO_DATE, ERROR }

/**
 * "Update available" screen, reached from the keyboard's settings-gear red dot or
 * Settings > About > Check for updates. Reuses the exact tokens the rest of Settings
 * is built from (SettingsCardShape/gold border via settingsCard-style cards here,
 * MattBlack background, LogoGold accent) so it reads as part of the same system
 * instead of a bolted-on screen - see ui/components/SettingsComponents.kt.
 */
@Composable
fun UpdateScreen(
    currentVersion: String,
    latestVersion: String,
    changelog: String,
    state: UpdateScreenState,
    progress: Int,
    errorMessage: String? = null,
    onBackClick: () -> Unit,
    onPrimaryAction: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsSubScreenHeader(title = "Update", onBack = onBackClick)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Hero icon - solid gold rounded tile, same treatment as the setup
            // screen's app icon (MainScreen.kt SetupScreen) but smaller, since this
            // screen already has its own header above it.
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Ola Keyboard",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (state == UpdateScreenState.UP_TO_DATE)
                    "Version $currentVersion - you're up to date"
                else
                    "Version $currentVersion \u2192 v$latestVersion available",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state != UpdateScreenState.UP_TO_DATE && changelog.isNotBlank()) {
                Spacer(modifier = Modifier.height(24.dp))
                WhatsNewCard(changelog = changelog, shape = cardShape)
            }

            if (state == UpdateScreenState.DOWNLOADING || state == UpdateScreenState.INSTALLING) {
                Spacer(modifier = Modifier.height(16.dp))
                DownloadProgressCard(state = state, progress = progress, shape = cardShape)
            }

            if (state == UpdateScreenState.ERROR && errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (state != UpdateScreenState.UP_TO_DATE) {
                Button(
                    onClick = onPrimaryAction,
                    enabled = state != UpdateScreenState.DOWNLOADING && state != UpdateScreenState.CHECKING,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = primaryButtonLabel(state),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "GitHub Releases \u2022 v$latestVersion",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private fun primaryButtonLabel(state: UpdateScreenState): String = when (state) {
    UpdateScreenState.CHECKING -> "Checking..."
    UpdateScreenState.DOWNLOADING -> "Downloading..."
    UpdateScreenState.INSTALLING -> "Opening installer..."
    else -> "Download update"
}

@Composable
private fun WhatsNewCard(changelog: String, shape: RoundedCornerShape) {
    val lines = changelog
        .lines()
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .filter { it.isNotEmpty() }
        .take(6)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
            .padding(18.dp)
    ) {
        Text(
            text = "What's new",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(
                text = "Bug fixes and stability improvements",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            lines.forEach { line ->
                Row(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = "\u2022  ",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = line,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(state: UpdateScreenState, progress: Int, shape: RoundedCornerShape) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state == UpdateScreenState.INSTALLING) "Download complete" else "Downloading update",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$progress%",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    }
}
