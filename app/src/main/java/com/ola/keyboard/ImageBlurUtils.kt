package com.ola.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Produces a blurred copy of a bitmap for devices where Compose's live
 * `Modifier.blur()` (RenderEffect-backed) isn't available - API < 31. Only
 * used as a fallback: on API 31+ the adjustment screen and the Settings
 * preview both use the real-time Compose blur modifier directly and never
 * call this.
 *
 * Kept deliberately simple (RenderScript's intrinsic blur, still functional
 * despite being deprecated in API 31 - not removed) rather than a hand-rolled
 * stack-blur, since this only ever runs on "drag/slider release", not on
 * every frame - see CustomBackgroundAdjustScreen's onValueChangeFinished.
 */
object ImageBlurUtils {

    /**
     * @param amount 0f (no blur) .. 1f (max blur). Internally mapped to a
     * downscale factor + RenderScript radius, since RenderScript's own radius
     * caps out at 25px - which alone isn't "strong" enough relative to a
     * keyboard-sized crop. Downscaling first (then blurring, then the caller
     * upscales the resulting drawable back to the box size) is the standard
     * trick to get a visually stronger blur out of a capped-radius blur pass.
     */
    fun blur(context: Context, source: Bitmap, amount: Float): Bitmap {
        val clamped = amount.coerceIn(0f, 1f)
        if (clamped <= 0f) return source

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurWithRenderEffectBitmap(context, source, clamped)
        } else {
            blurWithRenderScript(context, source, clamped)
        }
    }

    // On 31+ this helper is only reached if a caller explicitly wants a baked
    // bitmap rather than the live Compose modifier (e.g. for a thumbnail).
    // Uses the same RenderScript path underneath for simplicity/consistency -
    // real-time blur on 31+ happens via Modifier.blur() in Compose instead.
    @RequiresApi(Build.VERSION_CODES.S)
    private fun blurWithRenderEffectBitmap(context: Context, source: Bitmap, amount: Float): Bitmap =
        blurWithRenderScript(context, source, amount)

    @Suppress("DEPRECATION")
    private fun blurWithRenderScript(context: Context, source: Bitmap, amount: Float): Bitmap {
        // amount 0f..1f -> downscale factor 1f (no scale) .. 0.25f (quarter size),
        // so a "max blur" pass reads as genuinely soft rather than capped at
        // RenderScript's 25px ceiling on a full-resolution image.
        val scale = 1f - (amount * 0.75f)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val radius = (amount * 25f).coerceIn(0.1f, 25f)
        var rs: android.renderscript.RenderScript? = null
        return try {
            rs = android.renderscript.RenderScript.create(context)
            val input = android.renderscript.Allocation.createFromBitmap(rs, scaledBitmap)
            val output = android.renderscript.Allocation.createTyped(rs, input.type)
            val script = android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
            script.setRadius(radius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(scaledBitmap)
            script.destroy()
            input.destroy()
            output.destroy()
            // Scale back up to the original size so downstream layout (which
            // expects the source bitmap's own intrinsic dimensions for its
            // cover-scale/pan math) doesn't need to know this was downscaled.
            if (scale < 1f) {
                Bitmap.createScaledBitmap(scaledBitmap, source.width, source.height, true)
            } else {
                scaledBitmap
            }
        } catch (t: Throwable) {
            // RenderScript can fail on some OEM ROMs/emulators - fail soft to
            // the un-blurred (but still darken-able) image rather than crash.
            source
        } finally {
            rs?.destroy()
        }
    }
}
