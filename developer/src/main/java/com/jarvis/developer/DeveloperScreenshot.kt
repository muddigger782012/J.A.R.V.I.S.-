package com.jarvis.developer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * One explicit, in-memory screenshot attachment. Android's document picker
 * grants access only to the selected URI; raw files, location metadata and
 * filesystem paths are never passed to the model or persisted in task history.
 */
internal class DeveloperScreenshot private constructor(val jpegBytes: ByteArray) {
    companion object {
        const val MAX_SOURCE_BYTES = 12_000_000
        const val MAX_JPEG_BYTES = 280_000
        private const val MAX_DIMENSION = 1400

        fun fromUri(resolver: ContentResolver, uri: Uri): DeveloperScreenshot {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
                "Choose an image using Android's document picker."
            }
            val bytes = ByteArrayOutputStream()
            val stream = resolver.openInputStream(uri) ?: error("Unable to read selected image.")
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0 && bytes.size() + count <= MAX_SOURCE_BYTES) {
                        "Screenshot exceeds the 12 MB import limit."
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            val source = bytes.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp") &&
                bounds.outWidth in 1..10_000 && bounds.outHeight in 1..10_000) {
                "Choose a readable PNG, JPEG, or WebP screenshot."
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > MAX_DIMENSION ||
                    bounds.outHeight / inSampleSize > MAX_DIMENSION) inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
                ?: error("Screenshot decoding failed.")
            return try { fromBitmap(bitmap) } finally { bitmap.recycle() }
        }

        internal fun fromBitmap(source: Bitmap): DeveloperScreenshot {
            require(source.width > 0 && source.height > 0)
            val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(source.width, source.height))
            var bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source,
                maxOf(1, (source.width * scale).toInt()),
                maxOf(1, (source.height * scale).toInt()), true) else source
            try {
                repeat(5) { attempt ->
                    val quality = when (attempt) { 0 -> 82; 1 -> 72; 2 -> 60; 3 -> 48; else -> 36 }
                    val bytes = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                    if (bytes.size() <= MAX_JPEG_BYTES) return DeveloperScreenshot(bytes.toByteArray())
                    if (attempt != 4) {
                        val smaller = Bitmap.createScaledBitmap(bitmap,
                            maxOf(1, (bitmap.width * 0.8).toInt()),
                            maxOf(1, (bitmap.height * 0.8).toInt()), true)
                        if (bitmap !== source) bitmap.recycle()
                        bitmap = smaller
                    }
                }
                error("Screenshot cannot be reduced to the safe request limit.")
            } finally {
                if (bitmap !== source) bitmap.recycle()
            }
        }
    }
}
