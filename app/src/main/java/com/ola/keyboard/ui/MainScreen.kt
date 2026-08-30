package com.ola.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.ContextCompat.startActivity
import com.ola.keyboard.BuildConfig
import com.ola.keyboard.R
import com.ola.keyboard.ui.theme.OlaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isKeyboardEnabled: Boolean,
    isKeyboardSelected: Boolean,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    if (isKeyboardEnabled && isKeyboardSelected) {
        // Settings now follows the phone's own light/dark setting (OlaTheme's
        // default darkTheme = isSystemInDarkTheme()) via LightColorScheme/
        // DarkColorScheme in ui/theme/Theme.kt, instead of always forcing the
        // matte-black + logo-gold look regardless of the device's theme.
        OlaTheme {
            SettingsScaffold()
        }
    } else {
        Scaffold { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                SetupScreen(
                    isKeyboardEnabled = isKeyboardEnabled,
                    onEnableKeyboard = onEnableKeyboard,
                    onSelectKeyboard = onSelectKeyboard
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ola Keyboard",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            SettingsScreen()
        }
    }
}

@Composable
fun SetupScreen(
    isKeyboardEnabled: Boolean,
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Full app-icon tile (the whole Ola mark on its green background) reads better
        // here than the isolated adaptive-icon foreground layer on its own. Sized down
        // from 120.dp - at that size the mark dominated the whole screen instead of
        // reading as a small brand accent above the copy, which looked oversized next
        // to the 28.sp title. 80.dp keeps it a clear focal point without crowding the
        // text below; corner radius scaled down to match (16.dp is the standard
        // ~1/5-of-size rounding for an 80.dp tile, same ratio the old 24.dp/120.dp used).
        Image(
            painter = painterResource(id = R.drawable.ic_ola_app_icon),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .padding(bottom = 24.dp)
        )

        Text(
            text = stringResource(id = R.string.app_name),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 64.dp)
        )

        Text(
            text = if (!isKeyboardEnabled) 
                "Ola Keyboard is not enabled. Please enable it in settings to continue."
            else 
                "Ola Keyboard is enabled but not selected. Please select it to start typing.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                if (!isKeyboardEnabled) {
                    onEnableKeyboard()
                } else {
                    onSelectKeyboard()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = if (!isKeyboardEnabled) "Enable Keyboard" else "Select Keyboard",
                fontSize = 16.sp
            )
        }
    }
}
