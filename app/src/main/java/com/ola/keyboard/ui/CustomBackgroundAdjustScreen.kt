package com.ola.keyboard.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ola.keyboard.CustomBackgroundManager
import com.ola.keyboard.ImageBlurUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Step 3 - full-screen "pan / blur / darken" adjustment screen, opened right
 * after a photo is picked (or when re-opening an already-picked one via
 * "tap to change photo"). Nothing here touches [com.ola.keyboard.Prefs]
 * directly - all edits are local state, only persisted if the user taps Save
 * (see [onSave]); Cancel/back just discards them, so re-opening this screen
 * always starts from whatever was last actually saved.
 */
@Composable
fun CustomBackgroundAdjustScreen(
    dark: Boolean,
    initialOffsetX: Float,
    initialOffsetY: Float,
    initialBlur: Float,
    initialDarken: Float,
    initialZoom: Float = CUSTOM_BG_MIN_ZOOM,
    onSave: (offsetX: Float, offsetY: Float, blur: Float, darken: Float, zoom: Float) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val bitmap = remember { CustomBackgroundManager.loadBitmap(context)?.asImageBitmap() }

    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var blurAmount by remember { mutableFloatStateOf(initialBlur) }
    var darkenAmount by remember { mutableFloatStateOf(initialDarken) }
    var zoomAmount by remember {
        mutableFloatStateOf(initialZoom.coerceIn(CUSTOM_BG_MIN_ZOOM, CUSTOM_BG_MAX_ZOOM))
    }

    // Only meaningful on API < 31, where there's no live Compose blur - see
    // CustomBackgroundPreviewBox's preBlurredBitmap param. Recomputed only
    // when the slider is released (see onValueChangeFinished below), not on
    // every drag tick, since RenderScript's pass isn't free.
    var bakedBlurredBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var blurBakeTrigger by remember { mutableFloatStateOf(initialBlur) }

    val needsBakedBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    LaunchedEffect(needsBakedBlur, blurBakeTrigger, bitmap) {
        if (!needsBakedBlur || bitmap == null) return@LaunchedEffect
        val source = CustomBackgroundManager.loadBitmap(context) ?: return@LaunchedEffect
        bakedBlurredBitmap = if (blurBakeTrigger <= 0f) {
            null
        } else {
            withContext(Dispatchers.Default) {
                ImageBlurUtils.blur(context, source, blurBakeTrigger).asImageBitmap()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Header: Cancel (X) / title / Reset ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Adjust Background",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                offsetX = 0.5f
                offsetY = 0.5f
                blurAmount = 0f
                darkenAmount = 0.25f
                zoomAmount = CUSTOM_BG_MIN_ZOOM
                blurBakeTrigger = 0f
            }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Drag to reposition \u2022 Pinch to zoom",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            CustomBackgroundPreviewBox(
                bitmap = bitmap,
                offsetX = offsetX,
                offsetY = offsetY,
                blurAmount = blurAmount,
                darkenAmount = darkenAmount,
                dark = dark,
                draggable = true,
                preBlurredBitmap = bakedBlurredBitmap,
                zoom = zoomAmount,
                onOffsetChange = { x, y -> offsetX = x; offsetY = y },
                onZoomChange = { zoomAmount = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Range is CUSTOM_BG_MIN_ZOOM..CUSTOM_BG_MAX_ZOOM (100%..300%), not the
            // 0f..1f every other slider here uses - a "Zoom" slider is 100% at rest,
            // not 0%, so it needs its own value range instead of AdjustSlider's.
            ZoomSlider(
                value = zoomAmount,
                onValueChange = { zoomAmount = it }
            )
            AdjustSlider(
                label = "Blur",
                value = blurAmount,
                onValueChange = { blurAmount = it },
                onValueChangeFinished = { blurBakeTrigger = blurAmount }
            )
            AdjustSlider(
                label = "Darken",
                value = darkenAmount,
                onValueChange = { darkenAmount = it },
                onValueChangeFinished = { /* live already - Compose alpha, no bake needed */ }
            )
        }

        // --- Footer: Cancel / Save ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = { onSave(offsetX, offsetY, blurAmount, darkenAmount, zoomAmount) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ZoomSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Zoom", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "${(value * 100).toInt()}%",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = CUSTOM_BG_MIN_ZOOM..CUSTOM_BG_MAX_ZOOM,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "${(value * 100).toInt()}%",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}
