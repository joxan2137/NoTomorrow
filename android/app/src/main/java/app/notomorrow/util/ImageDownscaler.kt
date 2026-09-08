package app.notomorrow.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Shrinks a camera/library photo to something worth uploading: ≤ 1024 px on the
 * long edge, JPEG 0.8, orientation baked in, metadata stripped — re-encoding
 * drops EXIF, including GPS. 1:1 port of `Services/ImageDownscaler.swift`.
 *
 * iOS gets the upright, pixel-sized image for free from `UIImage`; here the
 * EXIF rotation has to be read and applied by hand **before** re-encoding.
 */
object ImageDownscaler {

    const val MAX_LONG_EDGE = 1024

    /** iOS `quality: CGFloat = 0.8`. */
    const val QUALITY = 80

    /**
     * Decodes [uri], rotates it upright, scales it down and returns JPEG bytes.
     * `null` when the image cannot be read or would scale below 1 px.
     */
    fun jpeg(
        context: Context,
        uri: Uri,
        maxLongEdge: Int = MAX_LONG_EDGE,
        quality: Int = QUALITY,
    ): ByteArray? {
        // `decodeStream` returns null by contract with `inJustDecodeBounds`; only the stream
        // itself is the failure signal here, never the decode result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = open(context, uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = open(context, uri)?.use { readOrientation(it) } ?: 0
        val swapped = orientation == 90 || orientation == 270
        val pixelWidth = if (swapped) bounds.outHeight else bounds.outWidth
        val pixelHeight = if (swapped) bounds.outWidth else bounds.outHeight
        val target = targetSize(pixelWidth, pixelHeight, maxLongEdge) ?: return null

        // Decode at the smallest power-of-two step that still covers the target,
        // then do the exact resize — the cheap half of what UIGraphicsImageRenderer does.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(pixelWidth, pixelHeight, target.first, target.second)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return try {
            jpeg(decoded, orientation, maxLongEdge, quality)
        } finally {
            decoded.recycle()
        }
    }

    /**
     * The same pipeline for an already-decoded bitmap (camera capture).
     * [orientationDegrees] is the clockwise rotation still to apply, 0/90/180/270.
     */
    fun jpeg(
        bitmap: Bitmap,
        orientationDegrees: Int = 0,
        maxLongEdge: Int = MAX_LONG_EDGE,
        quality: Int = QUALITY,
    ): ByteArray? {
        val upright = rotate(bitmap, orientationDegrees)
        val target = targetSize(upright.width, upright.height, maxLongEdge) ?: return null
        val scaled =
            if (upright.width == target.first && upright.height == target.second) upright
            else Bitmap.createScaledBitmap(upright, target.first, target.second, true)
        return try {
            ByteArrayOutputStream().use { out ->
                // JPEG has no alpha; the encoder flattens onto opaque, matching `format.opaque = true`.
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
            }
        } finally {
            if (scaled !== upright) scaled.recycle()
            if (upright !== bitmap) upright.recycle()
        }
    }

    /**
     * `factor = maxLongEdge / longEdge` when larger, dimensions **floored**,
     * `null` when either edge would fall below 1 px.
     *
     * Integer maths, not `Double`: `floor(3024 * (1024.0 / 4032.0))` can land on
     * 767 depending on the rounding of the intermediate, and the contract says
     * 4032×3024 → exactly 1024×768.
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val w = (width.toLong() * maxLongEdge / longEdge).toInt()
        val h = (height.toLong() * maxLongEdge / longEdge).toInt()
        if (w < 1 || h < 1) return null
        return w to h
    }

    /** Largest power of two that keeps the decoded bitmap at or above the target. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** EXIF orientation as a clockwise rotation in degrees (mirrored variants are treated as their rotation). */
    fun readOrientation(stream: InputStream): Int = runCatching {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
}
