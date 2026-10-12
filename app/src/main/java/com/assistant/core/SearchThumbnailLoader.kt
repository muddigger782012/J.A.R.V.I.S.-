package com.assistant.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.assistant.core.services.ChatSearchResults
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded thumbnail downloads and bitmap memory; no network or decoding on the UI thread. */
object SearchThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(48))

    fun load(view: ImageView, url: String) {
        if (!ChatSearchResults.safeUrl(url)) return
        view.tag = url
        view.visibility = android.view.View.VISIBLE
        synchronized(cache) { cache.get(url) }?.let { view.setImageBitmap(it); return }
        val reference = WeakReference(view)
        try { workers.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap != null) synchronized(cache) { cache.put(url, bitmap) }
            reference.get()?.post {
                reference.get()?.takeIf { it.tag == url }?.let {
                    if (bitmap == null) it.visibility = android.view.View.GONE else { it.visibility = android.view.View.VISIBLE; it.setImageBitmap(bitmap) }
                }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { view.visibility = android.view.View.GONE }
    }

    private fun download(url: String): Bitmap? {
        var target = url
        repeat(4) {
            require(ChatSearchResults.safeUrl(target))
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "JARVIS/1.0 (https://github.com/muddigger782012/J.A.R.V.I.S.-)")
            connection.setRequestProperty("Accept", "image/*")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                if (connection.responseCode in 300..399) {
                    target = URL(URL(target), connection.getHeaderField("Location") ?: return null).toString()
                } else {
                    if (connection.responseCode != 200 || !connection.contentType.orEmpty().startsWith("image/")) return null
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (output.size() + read > 4 * 1024 * 1024) return null
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..20000) return null
                    val options = BitmapFactory.Options().apply {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                        inSampleSize = sample
                    }
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } finally { connection.disconnect() }
        }
        return null
    }
}
