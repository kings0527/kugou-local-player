package com.kugou.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专辑封面加载 + 内存缓存（LruCache 64MB 上限）。
 * MediaStore album art 路径: content://media/external/audio/albumart/{albumId}
 */
object CoverCache {
    private val cache = object : LruCache<Long, Bitmap>(64) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    fun albumArtUri(albumId: Long): Uri =
        Uri.parse("content://media/external/audio/albumart/$albumId")

    /** 同步读取缓存（仅内存命中时返回，避免列表滚动时先字母后图片的闪烁） */
    fun peek(albumId: Long): Bitmap? = if (albumId <= 0) null else cache.get(albumId)

    /** 加载封面，无封面或失败返回 null（调用方显示字母占位） */
    suspend fun load(ctx: Context, albumId: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (albumId <= 0) return@withContext null
        cache.get(albumId)?.let { return@withContext it }
        val bmp = try {
            val uri = albumArtUri(albumId)
            // 先探测尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                BitmapFactory.decodeStream(ins, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Throwable) {
            null
        }
        bmp?.let { cache.put(albumId, it) }
        bmp
    }
}
