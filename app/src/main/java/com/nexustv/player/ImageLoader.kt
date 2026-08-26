package com.nexustv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ImageLoader {
    private val executor = Executors.newFixedThreadPool(4)
    private val memoryCache = object : LruCache<String, Bitmap>(memoryBudget()) {}

    fun load(url: String, target: ImageView, fallback: Int) {
        target.setImageResource(fallback)
        if (url.isBlank()) return
        val key = url.trim()
        target.tag = key
        memoryCache.get(key)?.let { bitmap ->
            target.post { if (target.tag == key) target.setImageBitmap(bitmap) }
            return
        }
        executor.execute {
            val bitmap = download(key)
            if (bitmap != null) memoryCache.put(key, bitmap)
            if (bitmap != null) {
                target.post { if (target.tag == key) target.setImageBitmap(bitmap) }
            }
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            useCaches = true
            setRequestProperty("User-Agent", "OMINUSTV/1.0 AndroidTV")
            setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,*/*")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun shutdown() {
        executor.shutdownNow()
        memoryCache.evictAll()
    }

    private fun memoryBudget(): Int = (Runtime.getRuntime().maxMemory() / 16L).coerceAtMost(24L * 1024L * 1024L).toInt()
}
