package com.ola.keyboard.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Modifier
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The one piece of "custom background" render logic - image cover-scale, pan
 * (offsetX/offsetY), blur and darken - shared by:
 *  - the adjustment screen (Step 3), where [draggable] = true and the sliders
 *    live-update [blurAmount]/[darkenAmount] above this
 *  - the Settings Appearance preview once background mode = custom_image
 *    (Step 5), where [draggable] = false - it just renders the saved values
 *
 * Deliberately does NOT draw the key rows itself (that stays in
 * KeyboardPreview/the glass-key rendering added in Step 5) - this composable
 * is only the background layer underneath them, so both call sites can stack
 * their own foreground content on top of it.
 *
 * @param bitmap the full-resolution image from [com.ola.keyboard.CustomBackgroundManager.loadBitmap].
 *   Null falls back to a flat neutral fill matching the app's own light/dark
 *   background - covers the "file missing/corrupt" case (Step 7) without the
 *   caller needing its own null-check/fallback branch.
 * @param preBlurredBitmap on API < 31 (no live [Modifier.blur] support), the
 *   caller pre-renders a blurred bitmap via [com.ola.keyboard.ImageBlurUtils]
 *   on drag/slider release and passes it here instead; null means "use the
 *   live blur modifier" (API 31+) or "no blur yet requested".
 */
@Composable
fun CustomBackgroundPreviewBox(
    bitmap: ImageBitmap?,
    offsetX: Float,
    offsetY: Float,
    blurAmount: Float,
    darkenAmount: Float,
    dark: Boolean,
    draggable: Boolean,
    preBlurredBitmap: ImageBitmap? = null,
    onOffsetChange: (offsetX: Float, offsetY: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (dark) Color(0xFF1C1B17) else Color(0xFFFBF8F2))
    ) {
        if (bitmap == null) {
            // Step 7 fallback: nothing to show, the neutral background above
            // is the whole story - caller's own theme/gradient still applies
            // one layer up since backgroundMode only flips to custom_image
            // once an import actually succeeds.
            return@BoxWithConstraints
        }

        val boxWidthPx = with(density) { maxWidth.toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }
        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        // "Cover" scale - same behaviour as ContentScale.Crop, but computed by
        // hand since we need the resulting scaled size below to know how much
        // room there is to pan (the overflow past the box edges).
        val scale = remember(boxWidthPx, boxHeightPx, imgWidth, imgHeight) {
            if (imgWidth <= 0f || imgHeight <= 0f || boxWidthPx <= 0f || boxHeightPx <= 0f) 1f
            else maxOf(boxWidthPx / imgWidth, boxHeightPx / imgHeight)
        }
        val scaledWidthPx = imgWidth * scale
        val scaledHeightPx = imgHeight * scale
        val overflowX = (scaledWidthPx - boxWidthPx).coerceAtLeast(0f)
        val overflowY = (scaledHeightPx - boxHeightPx).coerceAtLeast(0f)

        val displayBitmap = preBlurredBitmap ?: bitmap
        val useLiveBlur = preBlurredBitmap == null && blurAmount > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val liveBlurRadius = with(density) { (blurAmount * 20.dp.toPx()).toDp() }

        androidx.compose.foundation.Image(
            bitmap = displayBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(
                    with(density) { scaledWidthPx.toDp() },
                    with(density) { scaledHeightPx.toDp() }
                )
                .graphicsLayer {
                    // offsetX/offsetY are 0f..1f pan fractions across the
                    // available overflow, 0.5f = centered - never lets the
                    // image's edge come in past the box boundary either side.
                    translationX = -(offsetX * overflowX)
                    translationY = -(offsetY * overflowY)
                }
                .then(if (useLiveBlur) Modifier.blur(liveBlurRadius) else Modifier)
                .then(
                    if (draggable) {
                        Modifier.pointerInput(overflowX, overflowY) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newX = if (overflowX > 0f) {
                                    (offsetX - dragAmount.x / overflowX).coerceIn(0f, 1f)
                                } else 0.5f
                                val newY = if (overflowY > 0f) {
                                    (offsetY - dragAmount.y / overflowY).coerceIn(0f, 1f)
                                } else 0.5f
                                onOffsetChange(newX, newY)
                            }
                        }
                    } else Modifier
                )
        )

        if (darkenAmount > 0f) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = (darkenAmount * 0.85f).coerceIn(0f, 0.85f)))
            )
        }
    }
}
