package com.ashairfoil.prism.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * ThumbnailCache — Video thumbnail generation and caching for the file picker.
 *
 * Generates thumbnails from video files using MediaMetadataRetriever,
 * caches them in both memory (LruCache) and disk (app cache dir).
 *
 * The file picker calls `getThumbnail()` which returns immediately
 * if cached, or triggers async generation and calls back when ready.
 *
 * Thumbnail size: 320x180 (16:9) — small enough for fast loading,
 * large enough for the file picker grid.
 */
class ThumbnailCache(private val context: Context) {

    companion object {
        private const val TAG = "ThumbnailCache"
        private const val THUMB_WIDTH = 320
        private const val THUMB_HEIGHT = 180
        private const val DISK_CACHE_DIR = "thumbs"
        private const val MAX_MEMORY_ENTRIES = 100
    }

    // Memory cache (LRU, ~100 entries × ~170KB ≈ 17MB max)
    private val memoryCache = LruCache<String, Bitmap>(MAX_MEMORY_ENTRIES)

    // Disk cache directory
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, DISK_CACHE_DIR).also { it.mkdirs() }
    }

    // Background scope for thumbnail generation
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track in-flight generation to avoid duplicates (key -> start timestamp)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Get thumbnail for a video file.
     * Returns immediately from cache, or null + triggers async generation.
     *
     * @param file The video file
     * @param callback Called when thumbnail is ready (may be called immediately if cached)
     * @return Cached bitmap or null (callback will fire when ready)
     */
    fun getThumbnail(file: File, callback: (Bitmap?) -> Unit): Bitmap? {
        val key = cacheKeyFor(file)

        // Check memory cache first
        memoryCache.get(key)?.let {
            callback(it)
            return it
        }

        // Check disk cache
        val diskFile = File(diskCacheDir, "$key.jpg")
        if (diskFile.exists()) {
            scope.launch {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    withContext(Dispatchers.Main) { callback(bitmap) }
                } else {
                    // Corrupt cache file — regenerate
                    diskFile.delete()
                    generateAndCache(file, key, callback)
                }
            }
            return null
        }

        // Generate in background
        generateAndCache(file, key, callback)
        return null
    }

    private fun generateAndCache(file: File, key: String, callback: (Bitmap?) -> Unit) {
        // Check if already in-flight, evict stale entries (>30s) to prevent leaks
        val now = System.currentTimeMillis()
        val existing = inFlight[key]
        if (existing != null) {
            if (now - existing > 30_000) {
                inFlight.remove(key)
            } else {
                return // Already generating
            }
        }
        if (inFlight.putIfAbsent(key, now) != null) return // Race-safe guard

        scope.launch {
            try {
                val bitmap = generateThumbnail(file)
                if (bitmap != null) {
                    // Save to memory cache
                    memoryCache.put(key, bitmap)

                    // Save to disk cache
                    try {
                        val diskFile = File(diskCacheDir, "$key.jpg")
                        FileOutputStream(diskFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to write disk cache for $key: ${e.message}")
                    }

                    withContext(Dispatchers.Main) { callback(bitmap) }
                } else {
                    withContext(Dispatchers.Main) { callback(null) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail generation failed for ${file.name}: ${e.message}")
                withContext(Dispatchers.Main) { callback(null) }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun generateThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            // Get frame at 10% into the video (skip black intros)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0
            val seekTimeUs = (durationMs * 100).coerceAtLeast(1000000) // 10% or 1 second

            val frame = retriever.getFrameAtTime(
                seekTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0) // Fallback to first frame

            frame?.let { bitmap ->
                // For SBS content, take the left half only
                val width = bitmap.width
                val height = bitmap.height
                val isWideSbs = width > height * 3 // Likely SBS if very wide

                val cropped = if (isWideSbs) {
                    Bitmap.createBitmap(bitmap, 0, 0, width / 2, height)
                } else {
                    bitmap
                }

                // Scale to thumbnail size
                val scaled = Bitmap.createScaledBitmap(cropped, THUMB_WIDTH, THUMB_HEIGHT, true)
                // Recycle original if we created a crop (crop is a different object)
                if (cropped !== bitmap) bitmap.recycle()
                // Recycle cropped if scaling created a new bitmap
                if (scaled !== cropped) cropped.recycle()
                scaled
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for ${file.name}: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Clear the entire cache (memory + disk).
     */
    fun clearCache() {
        memoryCache.evictAll()
        diskCacheDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Get disk cache size in bytes.
     */
    fun diskCacheSize(): Long {
        return diskCacheDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun release() {
        scope.cancel()
        memoryCache.evictAll()
    }

    private fun cacheKeyFor(file: File): String {
        // Hash of path + last modified for cache busting on file changes
        val input = "${file.absolutePath}:${file.lastModified()}"
        val md5 = MessageDigest.getInstance("MD5")
        return md5.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
