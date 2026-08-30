package com.ola.keyboard.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
 * FIX (was showing solid dark/light fill, a tiny unscaled corner of the photo,
 * or briefly nothing at all): the old implementation measured its own size via
 * BoxWithConstraints's `maxWidth`/`maxHeight` and used those px values directly
 * in the same composition pass to compute the cover-scale AND the pan overflow.
 * On the very first frame(s) - before layout has actually settled (screen
 * enter transition, IME window first inflate, recomposition right after
 * `showAdjustScreen`/`customBgVersion` flips) - that box size can read as 0 or
 * a transient/incorrect value. `scale` had a 0-guard (falls back to 1f), but
 * `overflowX`/`overflowY` did NOT share that guard, so on that same bad frame
 * they were computed from the *unscaled* full image size vs a ~0 box size -
 * a huge bogus "overflow" - and the pan translation (`-(offsetX * overflowX)`)
 * then shoved the image thousands of px outside the (correctly clipped) box,
 * leaving only the flat background fill visible = looked "dark"/blank. Once
 * things settled, a leftover stale small-scale frame could also render as an
 * unscaled little chunk of the photo before catching up.
 *
 * Now: box size is tracked with `onSizeChanged` (reliable actual pixel size,
 * not read mid-measurement) into `remember { mutableStateOf(IntSize.Zero) }`,
 * and nothing is drawn - not even at the wrong scale - until that size is
 * known to be > 0. `clipToBounds()` is also added as a second safety net so
 * even a future math mistake can never paint outside this box.
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

    // Actual measured pixel size of this box, updated by the layout system
    // itself (not derived mid-composition) - see the FIX note above.
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clipToBounds()
            .background(if (dark) Color(0xFF1C1B17) else Color(0xFFFBF8F2))
            .onSizeChanged { boxSizePx = it }
    ) {
        val boxWidthPx = boxSizePx.width.toFloat()
        val boxHeightPx = boxSizePx.height.toFloat()

        // Step 7 fallback (missing/corrupt file), and also the "not measured
        // yet" frame - in both cases the neutral fill above is the whole
        // story; caller's own theme/gradient still applies one layer up
        // since backgroundMode only flips to custom_image once an import
        // actually succeeds.
        if (bitmap == null || boxWidthPx <= 0f || boxHeightPx <= 0f) {
            return@Box
        }

        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()
        if (imgWidth <= 0f || imgHeight <= 0f) {
            return@Box
        }

        // "Cover" scale - same behaviour as ContentScale.Crop, but computed by
        // hand since we need the resulting scaled size below to know how much
        // room there is to pan (the overflow past the box edges).
        val scale = remember(boxWidthPx, boxHeightPx, imgWidth, imgHeight) {
            maxOf(boxWidthPx / imgWidth, boxHeightPx / imgHeight)
        }
        val scaledWidthPx = imgWidth * scale
        val scaledHeightPx = imgHeight * scale
        val overflowX = (scaledWidthPx - boxWidthPx).coerceAtLeast(0f)
        val overflowY = (scaledHeightPx - boxHeightPx).coerceAtLeast(0f)

        val displayBitmap = preBlurredBitmap ?: bitmap
        val useLiveBlur = preBlurredBitmap == null && blurAmount > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val liveBlurRadius = with(density) { (blurAmount * 20.dp.toPx()).toDp() }

        Image(
            bitmap = displayBitmap,
            contentDescription = null,
            // BUG FIX: Image() defaults to ContentScale.Fit when none is given, which
            // itself letterboxes the bitmap to fit inside the fillMaxSize() layout
            // bounds (preserving aspect ratio, padding the rest with empty space)
            // BEFORE our own graphicsLayer scale below ever runs - so the manual
            // "cover" scale was just scaling up an already-letterboxed image, and
            // the empty bars scaled right along with it (never went away). None +
            // TopStart draws the bitmap at its native pixel size anchored at this
            // Image's origin, with zero built-in scaling/centering, which is exactly
            // what the graphicsLayer transform below (scale from origin (0,0), then
            // translate) is written to expect.
            contentScale = ContentScale.None,
            alignment = Alignment.TopStart,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // With contentScale = None above, the Image is drawn at the
                    // bitmap's own NATIVE pixel size (imgWidth x imgHeight), anchored
                    // at this layer's origin - so getting to the "cover" size just
                    // means scaling that native size by `scale` directly. (Scaling by
                    // scaledWidthPx/boxWidthPx here - as if the Image had already been
                    // fit down to the tiny box size first - was the bug: see the note
                    // on contentScale above.)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (darkenAmount * 0.85f).coerceIn(0f, 0.85f)))
            )
        }
    }
}
