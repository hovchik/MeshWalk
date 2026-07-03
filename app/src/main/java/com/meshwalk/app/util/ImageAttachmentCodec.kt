package com.meshwalk.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.meshwalk.app.domain.model.MessageContent
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares image attachments for the mesh: downscales, JPEG-compresses, and
 * base64-encodes a picked image so it fits within the packet payload budget
 * (the transport layer fragments packets, but bandwidth on BLE/Nearby is
 * still precious).
 */
@Singleton
class ImageAttachmentCodec @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Longest edge of the transmitted image. */
        private const val MAX_DIMENSION = 1024

        /** Target upper bound for encoded JPEG bytes (~2/3 of this after base64 → payload). */
        private const val MAX_JPEG_BYTES = 120_000

        private val QUALITY_STEPS = intArrayOf(75, 60, 45, 30, 20)
    }

    /**
     * Load, downscale, and encode the image at [uri].
     * Returns null when the image can't be read or compressed under the cap.
     */
    fun encodeFromUri(uri: Uri): MessageContent.Image? {
        return try {
            val bitmap = loadDownscaled(uri) ?: return null
            val jpeg = compressUnderCap(bitmap) ?: run {
                Timber.w("Image cannot be compressed under $MAX_JPEG_BYTES bytes")
                return null
            }
            MessageContent.Image(
                base64Jpeg = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                width = bitmap.width,
                height = bitmap.height
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to encode image attachment")
            null
        }
    }

    /** Decode a received image back into a Bitmap for display. Null on corrupt data. */
    fun decodeToBitmap(content: MessageContent.Image): Bitmap? {
        return try {
            val bytes = Base64.decode(content.base64Jpeg, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Timber.w(e, "Failed to decode image attachment")
            null
        }
    }

    private fun loadDownscaled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // First pass: bounds only, to compute the sample size without loading pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_DIMENSION) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // Exact-fit final scale (sampling only gets within 2x).
        val longest = max(sampled.width, sampled.height)
        if (longest <= MAX_DIMENSION) return sampled
        val scale = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            sampled,
            (sampled.width * scale).roundToInt().coerceAtLeast(1),
            (sampled.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun compressUnderCap(bitmap: Bitmap): ByteArray? {
        for (quality in QUALITY_STEPS) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_JPEG_BYTES) return bytes
        }
        return null
    }
}
