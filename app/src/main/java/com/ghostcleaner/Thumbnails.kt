package com.ghostcleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Loads small photo/video thumbnails off the main thread, with a memory cache. */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun isPreviewable(f: File): Boolean {
        val kind = StorageBreakdown.kindOf(f)
        return (kind == StorageBreakdown.Kind.IMAGES || kind == StorageBreakdown.Kind.VIDEOS) && !f.isDirectory
    }

    fun load(f: File, into: ImageView, sizePx: Int) {
        val key = f.absolutePath
        into.tag = key
        val cached = cache.get(key)
        if (cached != null) { into.setImageBitmap(cached); return }
        into.setImageDrawable(null)
        pool.execute {
            val bmp = decode(f, sizePx)
            if (bmp != null) cache.put(key, bmp)
            main.post { if (into.tag == key) into.setImageBitmap(bmp) }
        }
    }

    private fun decode(f: File, size: Int): Bitmap? = try {
        if (StorageBreakdown.kindOf(f) == StorageBreakdown.Kind.VIDEOS) {
            if (Build.VERSION.SDK_INT >= 29) {
                ThumbnailUtils.createVideoThumbnail(f, Size(size, size), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Throwable) { null }
}
