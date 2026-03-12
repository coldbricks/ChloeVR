package com.ashairfoil.prism.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File

/**
 * VideoInfoOverlay — Displays video technical metadata in a HUD-style overlay.
 *
 * Shows: resolution, codec, bitrate, fps, duration, file size, container format,
 * color space, HDR status, audio codec, audio channels, stereo mode, projection.
 *
 * Auto-fades out after 4 seconds. Toggle with button press.
 * Positioned at the top of the viewport with semi-transparent background.
 */
class VideoInfoOverlay(private val parent: ViewGroup) {

    private var container: LinearLayout? = null
    private var hideJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isVisible = false

    data class VideoInfo(
        val fileName: String = "",
        val resolution: String = "",        // e.g., "3840x1920"
        val videoCodec: String = "",        // e.g., "HEVC", "AVC"
        val videoBitrate: String = "",      // e.g., "42.5 Mbps"
        val frameRate: String = "",         // e.g., "60 fps"
        val duration: String = "",          // e.g., "32:15"
        val fileSize: String = "",          // e.g., "8.2 GB"
        val container: String = "",         // e.g., "MP4", "MKV"
        val colorSpace: String = "",        // e.g., "BT.709", "BT.2020"
        val hdrType: String = "",           // e.g., "HDR10", "SDR"
        val audioCodec: String = "",        // e.g., "AAC", "Opus"
        val audioChannels: String = "",     // e.g., "Stereo", "5.1"
        val audioSampleRate: String = "",   // e.g., "48000 Hz"
        val projection: String = "",        // e.g., "VR180 SBS"
        val stereoMode: String = ""         // e.g., "Side-by-Side"
    )

    /**
     * Extract video metadata from a file.
     * Runs on IO dispatcher to avoid blocking.
     */
    suspend fun extractInfo(file: File, projection: String = "", stereoMode: String = ""): VideoInfo {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)

                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
                val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
                val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?: retriever.extractMetadata(24) // METADATA_KEY_VIDEO_FRAME_COUNT alternative
                    ?: ""
                val colorStandard = retriever.extractMetadata(40) ?: "" // COLOR_STANDARD
                val colorTransfer = retriever.extractMetadata(41) ?: "" // COLOR_TRANSFER

                // Determine codec from mime type
                val videoCodec = when {
                    mimeType.contains("hevc") || mimeType.contains("h265") -> "HEVC (H.265)"
                    mimeType.contains("avc") || mimeType.contains("h264") -> "AVC (H.264)"
                    mimeType.contains("vp9") -> "VP9"
                    mimeType.contains("av01") || mimeType.contains("av1") -> "AV1"
                    else -> mimeType.substringAfter("video/")
                }

                // Container from extension
                val container = file.extension.uppercase().let {
                    when (it) {
                        "MP4", "M4V" -> "MP4"
                        "MKV" -> "MKV (Matroska)"
                        "WEBM" -> "WebM"
                        "MOV" -> "MOV (QuickTime)"
                        "AVI" -> "AVI"
                        else -> it
                    }
                }

                // File size
                val sizeBytes = file.length()
                val fileSize = when {
                    sizeBytes > 1_000_000_000 -> String.format("%.1f GB", sizeBytes / 1e9)
                    sizeBytes > 1_000_000 -> String.format("%.0f MB", sizeBytes / 1e6)
                    else -> String.format("%.0f KB", sizeBytes / 1e3)
                }

                // Duration formatting
                val totalSec = durationMs / 1000
                val hours = totalSec / 3600
                val mins = (totalSec % 3600) / 60
                val secs = totalSec % 60
                val durationStr = if (hours > 0) String.format("%d:%02d:%02d", hours, mins, secs)
                else String.format("%d:%02d", mins, secs)

                // Bitrate formatting
                val bitrateStr = when {
                    bitrate > 1_000_000 -> String.format("%.1f Mbps", bitrate / 1e6)
                    bitrate > 1_000 -> String.format("%.0f Kbps", bitrate / 1e3)
                    bitrate > 0 -> "$bitrate bps"
                    else -> ""
                }

                // HDR detection
                val hdrType = when {
                    colorTransfer.contains("6") -> "HDR10"     // ST2084
                    colorTransfer.contains("7") -> "HLG"       // HLG
                    else -> "SDR"
                }

                // Color space
                val colorSpace = when {
                    colorStandard.contains("6") -> "BT.2020"
                    colorStandard.contains("1") -> "BT.709"
                    else -> "BT.709"
                }

                // Stereo/projection display
                val projDisplay = when {
                    projection.contains("360") -> "360° Sphere"
                    projection.contains("180") -> "VR180 Hemisphere"
                    projection.contains("fisheye") -> "Fisheye"
                    else -> "Flat Screen"
                }
                val stereoDisplay = when {
                    stereoMode.contains("sbs", ignoreCase = true) -> "Side-by-Side 3D"
                    stereoMode.contains("tb", ignoreCase = true) -> "Top-Bottom 3D"
                    else -> "Mono"
                }

                VideoInfo(
                    fileName = file.nameWithoutExtension,
                    resolution = if (width.isNotEmpty() && height.isNotEmpty()) "${width}x${height}" else "",
                    videoCodec = videoCodec,
                    videoBitrate = bitrateStr,
                    frameRate = if (fps.isNotEmpty()) "$fps fps" else "",
                    duration = durationStr,
                    fileSize = fileSize,
                    container = container,
                    colorSpace = colorSpace,
                    hdrType = hdrType,
                    audioCodec = "", // Would need ExoPlayer track info
                    audioChannels = "",
                    audioSampleRate = "",
                    projection = projDisplay,
                    stereoMode = stereoDisplay
                )
            } catch (e: Exception) {
                VideoInfo(fileName = file.nameWithoutExtension, fileSize = formatSize(file.length()))
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Show the video info overlay. Auto-hides after 4 seconds.
     */
    fun show(info: VideoInfo) {
        hideJob?.cancel()
        removeExisting()

        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density

        container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(190, 10, 10, 14))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            alpha = 0f
        }

        // Title
        addInfoLine(ctx, info.fileName, ThemeManager.TEXT_PRIMARY, 13f, Typeface.BOLD)

        // Technical details
        val details = mutableListOf<Pair<String, String>>()
        if (info.resolution.isNotEmpty()) details.add("Resolution" to info.resolution)
        if (info.videoCodec.isNotEmpty()) details.add("Codec" to info.videoCodec)
        if (info.videoBitrate.isNotEmpty()) details.add("Bitrate" to info.videoBitrate)
        if (info.frameRate.isNotEmpty()) details.add("Frame Rate" to info.frameRate)
        if (info.duration.isNotEmpty()) details.add("Duration" to info.duration)
        if (info.fileSize.isNotEmpty()) details.add("File Size" to info.fileSize)
        if (info.container.isNotEmpty()) details.add("Container" to info.container)
        if (info.hdrType != "SDR") details.add("HDR" to info.hdrType)
        if (info.colorSpace.isNotEmpty()) details.add("Color" to info.colorSpace)
        details.add("Projection" to info.projection)
        details.add("Stereo" to info.stereoMode)

        for ((label, value) in details) {
            addInfoRow(ctx, label, value)
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = (20 * dp).toInt()
            leftMargin = (20 * dp).toInt()
        }
        parent.addView(container, lp)
        isVisible = true

        // Fade in
        ObjectAnimator.ofFloat(container, "alpha", 0f, 1f).apply {
            duration = 300
            start()
        }

        // Auto-hide after 4 seconds
        hideJob = scope.launch {
            delay(4000)
            hide()
        }
    }

    fun hide() {
        val c = container ?: return
        ObjectAnimator.ofFloat(c, "alpha", c.alpha, 0f).apply {
            duration = 500
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeExisting()
                }
            })
            start()
        }
        isVisible = false
    }

    fun toggle(info: VideoInfo) {
        if (isVisible) hide() else show(info)
    }

    private fun removeExisting() {
        container?.let { parent.removeView(it) }
        container = null
    }

    private fun addInfoLine(ctx: Context, text: String, color: Int, size: Float, style: Int = Typeface.NORMAL) {
        container?.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = Typeface.create("monospace", style)
        })
    }

    private fun addInfoRow(ctx: Context, label: String, value: String) {
        val dp = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (1 * dp).toInt(), 0, (1 * dp).toInt())
        }
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(ThemeManager.TEXT_MUTED)
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                (80 * dp).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        row.addView(TextView(ctx).apply {
            text = value
            setTextColor(ThemeManager.TEXT_SECONDARY)
            textSize = 10f
            typeface = Typeface.create("monospace", Typeface.BOLD)
        })
        container?.addView(row)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes > 1_000_000_000 -> String.format("%.1f GB", bytes / 1e9)
            bytes > 1_000_000 -> String.format("%.0f MB", bytes / 1e6)
            else -> String.format("%.0f KB", bytes / 1e3)
        }
    }

    fun release() {
        hideJob?.cancel()
        scope.cancel()
        removeExisting()
    }
}
