package com.ashairfoil.prism.playback

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ScreenshotCapture — Capture the current video frame as a high-res image.
 *
 * Captures from the currently playing video at the exact playback position.
 * Saves to the device's Pictures/ChloeVR directory.
 *
 * For SBS content, captures only the left eye view (full resolution).
 *
 * Usage:
 *   val capture = ScreenshotCapture(context)
 *   val result = capture.captureFrame(videoFile, positionMs)
 *   // result.file = saved image file
 *   // result.bitmap = the captured bitmap (for preview)
 */
class ScreenshotCapture(private val context: Context) {

    companion object {
        private const val TAG = "Screenshot"
        private const val SAVE_DIR = "ChloeVR"
        private const val QUALITY = 95  // JPEG quality
    }

    data class CaptureResult(
        val success: Boolean,
        val file: File? = null,
        val bitmap: Bitmap? = null,
        val error: String? = null
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Capture the current frame from a video file at the given position.
     * Runs on IO dispatcher.
     */
    suspend fun captureFrame(
        videoFile: File,
        positionMs: Long,
        isSbs: Boolean = false
    ): CaptureResult {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)

                // Seek to exact position
                val bitmap = retriever.getFrameAtTime(
                    positionMs * 1000, // Convert ms to μs
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (bitmap == null) {
                    return@withContext CaptureResult(false, error = "Failed to extract frame")
                }

                // For SBS content, crop to left half
                val finalBitmap = if (isSbs && bitmap.width > bitmap.height * 2.5f) {
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width / 2, bitmap.height).also {
                        if (it !== bitmap) bitmap.recycle()
                    }
                } else {
                    bitmap
                }

                // Save to file
                val savedFile = saveToGallery(finalBitmap, videoFile.nameWithoutExtension, positionMs)

                if (savedFile != null) {
                    CaptureResult(true, file = savedFile, bitmap = finalBitmap)
                } else {
                    CaptureResult(false, bitmap = finalBitmap, error = "Failed to save file")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed: ${e.message}")
                CaptureResult(false, error = e.message)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Save bitmap to the Pictures/ChloeVR gallery directory.
     * Uses MediaStore on Android 10+ for proper gallery integration.
     */
    private fun saveToGallery(bitmap: Bitmap, videoName: String, positionMs: Long): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val posStr = formatPosition(positionMs)
        val fileName = "ChloeVR_${videoName}_${posStr}_$timestamp.jpg"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — use MediaStore
            saveViaMediaStore(bitmap, fileName)
        } else {
            // Older — direct file write
            saveDirectly(bitmap, fileName)
        }
    }

    private fun saveViaMediaStore(bitmap: Bitmap, fileName: String): File? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Screenshot saved via MediaStore: $fileName")

            // Return a File reference (approximate — MediaStore doesn't guarantee path)
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "$SAVE_DIR/$fileName"
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore save failed: ${e.message}")
            resolver.delete(uri, null, null)
            return null
        }
    }

    private fun saveDirectly(bitmap: Bitmap, fileName: String): File? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SAVE_DIR
        )
        dir.mkdirs()
        val file = File(dir, fileName)
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            Log.i(TAG, "Screenshot saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Direct save failed: ${e.message}")
            null
        }
    }

    private fun formatPosition(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%dh%02dm%02ds", h, m, s)
        else String.format("%02dm%02ds", m, s)
    }

    fun release() {
        scope.cancel()
    }
}
