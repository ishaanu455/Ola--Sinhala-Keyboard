package com.ola.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Handles the "use my own photo as keyboard background" flow: the user picks an image
 * from their device gallery via the system photo picker; we copy those bytes into our
 * own app storage (same reasoning as [CustomFontManager] - keeps working even if the
 * original file/album entry moves or is deleted, and the keyboard service can read it
 * without holding a content:// Uri permission). The app never uploads, bundles, or
 * shares this image with anyone - it's purely a local, on-device copy.
 */
object CustomBackgroundManager {
    private const val TAG = "CustomBackgroundMgr"
    private const val IMAGE_FILE_NAME = "custom_keyboard_background.jpg"

    // Hard cap on the decoded bitmap's longest side. The keyboard is at most a few
    // hundred dp tall, so anything beyond ~2x a large phone's screen width is wasted
    // memory - a 12MP+ camera photo decoded at full resolution can OOM the IME
    // process, which is far more memory-constrained than a normal Activity.
    private const val MAX_DECODED_DIMENSION = 2048

    fun imageFile(context: Context): File = File(context.filesDir, IMAGE_FILE_NAME)

    fun hasCustomBackground(context: Context): Boolean = imageFile(context).exists()

    /**
     * Copies the picked image into app storage, downscaling first if it's larger than
     * [MAX_DECODED_DIMENSION] on its longest side. Returns true on success; on failure,
     * no partial/broken file is left behind.
     */
    fun importImage(context: Context, uri: Uri): Boolean {
        val dest = imageFile(context)
        return try {
            val resolver = context.contentResolver

            // First pass: decode just the bounds (no pixels loaded yet) to compute a
            // downscale factor, same two-pass approach BitmapFactory recommends for
            // avoiding an unnecessary full-resolution decode.
            //
            // NOTE: BitmapFactory.decodeStream() with inJustDecodeBounds=true always
            // returns null by design (it only fills `bounds.outWidth`/`outHeight`,
            // it never allocates a Bitmap) - so the *stream-open* success and the
            // *decode* success have to be checked separately here. Treating the
            // decode call's own (always-null) return value as "did this fail?" was
            // the actual bug: every single import bailed out on this line before
            // ever reaching the real decode below, regardless of the picked photo.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val streamOpened = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
                true
            }
            if (streamOpened != true) {
                Log.e(TAG, "importImage: couldn't open input stream for bounds pass, uri=$uri")
                return false
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "importImage: bounds decode failed (outWidth=${bounds.outWidth}, outHeight=${bounds.outHeight}), uri=$uri")
                return false
            }

            var sampleSize = 1
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > MAX_DECODED_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
            if (bitmap == null) {
                Log.e(TAG, "importImage: full decode returned null, uri=$uri, sampleSize=$sampleSize")
                return false
            }

            dest.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            bitmap.recycle()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "importImage: failed with exception, uri=$uri", t)
            dest.delete()
            false
        }
    }

    fun removeImage(context: Context) {
        imageFile(context).delete()
        clearCache()
    }

    // In-memory cache - disk is only decoded once per app process lifetime, same
    // pattern as CustomFontManager.loadTypeface.
    private var cachedBitmap: Bitmap? = null
    private var cachedPath: String? = null

    fun loadBitmap(context: Context): Bitmap? {
        val file = imageFile(context)
        if (!file.exists()) {
            clearCache()
            return null
        }
        if (cachedBitmap != null && cachedPath == file.absolutePath) {
            return cachedBitmap
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                cachedBitmap = it
                cachedPath = file.absolutePath
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Call after removing the image so the cache is cleared immediately. */
    fun clearCache() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedPath = null
    }
}
