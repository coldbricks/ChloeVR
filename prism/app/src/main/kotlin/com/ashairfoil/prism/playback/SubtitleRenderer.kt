package com.ashairfoil.prism.playback

import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * SubtitleRenderer — SRT and basic ASS/SSA subtitle parsing and display.
 *
 * Scans for subtitle files alongside the video file (same name, .srt/.ass/.ssa extension).
 * Parses cue timing, renders as an overlay TextView positioned at the bottom of the viewport.
 *
 * Usage:
 *   val renderer = SubtitleRenderer(parentLayout)
 *   renderer.loadForVideo(File("/storage/.../video.mp4"))
 *   // In playback loop:
 *   renderer.update(currentPositionMs)
 *   // On stop:
 *   renderer.clear()
 */
class SubtitleRenderer(private val parent: ViewGroup) {

    companion object {
        private const val TAG = "SubtitleRenderer"
        private val SRT_EXTENSIONS = listOf(".srt", ".SRT")
        private val ASS_EXTENSIONS = listOf(".ass", ".ssa", ".ASS", ".SSA")
        private val SRT_TIME_PATTERN: Pattern = Pattern.compile(
            "(\\d{2}):(\\d{2}):(\\d{2})[,.]?(\\d{0,3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.]?(\\d{0,3})"
        )
        private val ASS_TIME_PATTERN: Pattern = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})"
        )
    }

    data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private var cues: List<SubtitleCue> = emptyList()
    private var currentCueIndex: Int = -1
    private var subtitleView: TextView? = null
    private var isLoaded: Boolean = false

    // Settings
    var fontSize: Float = 22f
        set(value) { field = value; subtitleView?.textSize = value }
    var fontColor: Int = Color.WHITE
        set(value) { field = value; subtitleView?.setTextColor(value) }
    var backgroundColor: Int = Color.argb(128, 0, 0, 0)
        set(value) { field = value; subtitleView?.setBackgroundColor(value) }
    var bottomMarginDp: Int = 40
    var isVisible: Boolean = true
        set(value) {
            field = value
            subtitleView?.visibility = if (value && currentCueIndex >= 0) View.VISIBLE else View.GONE
        }

    /**
     * Scan for subtitle files matching the video filename and load if found.
     * Returns true if subtitles were found and loaded.
     */
    fun loadForVideo(videoFile: File): Boolean {
        clear()
        val baseName = videoFile.nameWithoutExtension
        val dir = videoFile.parentFile ?: return false

        // Try SRT first, then ASS/SSA
        for (ext in SRT_EXTENSIONS) {
            val subFile = File(dir, "$baseName$ext")
            if (subFile.exists() && subFile.canRead()) {
                cues = parseSrt(subFile)
                if (cues.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${cues.size} SRT cues from ${subFile.name}")
                    isLoaded = true
                    ensureView()
                    return true
                }
            }
        }
        for (ext in ASS_EXTENSIONS) {
            val subFile = File(dir, "$baseName$ext")
            if (subFile.exists() && subFile.canRead()) {
                cues = parseAss(subFile)
                if (cues.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${cues.size} ASS cues from ${subFile.name}")
                    isLoaded = true
                    ensureView()
                    return true
                }
            }
        }

        Log.i(TAG, "No subtitle files found for ${videoFile.name}")
        return false
    }

    /**
     * Update subtitle display based on current playback position.
     * Call this from the playback loop (every ~100ms is fine).
     */
    fun update(positionMs: Long) {
        if (!isLoaded || cues.isEmpty()) return

        // Binary search for the active cue
        val cueIndex = findCueAt(positionMs)

        if (cueIndex != currentCueIndex) {
            currentCueIndex = cueIndex
            if (cueIndex >= 0) {
                showText(cues[cueIndex].text)
            } else {
                hideText()
            }
        }
    }

    fun clear() {
        cues = emptyList()
        currentCueIndex = -1
        isLoaded = false
        hideText()
    }

    fun hasSubtitles(): Boolean = isLoaded && cues.isNotEmpty()

    // -----------------------------------------------------------------------
    // SRT Parser
    // -----------------------------------------------------------------------

    private fun parseSrt(file: File): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var line: String?
                var state = 0 // 0=index, 1=timing, 2=text
                var startMs = 0L
                var endMs = 0L
                val textLines = mutableListOf<String>()

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()

                    when (state) {
                        0 -> {
                            // Expect cue index number
                            if (trimmed.matches(Regex("\\d+"))) {
                                state = 1
                            }
                        }
                        1 -> {
                            // Expect timing line
                            val matcher = SRT_TIME_PATTERN.matcher(trimmed)
                            if (matcher.find()) {
                                startMs = srtTimeToMs(
                                    matcher.group(1)!!, matcher.group(2)!!,
                                    matcher.group(3)!!, matcher.group(4) ?: "0"
                                )
                                endMs = srtTimeToMs(
                                    matcher.group(5)!!, matcher.group(6)!!,
                                    matcher.group(7)!!, matcher.group(8) ?: "0"
                                )
                                textLines.clear()
                                state = 2
                            }
                        }
                        2 -> {
                            // Collect text lines until blank line
                            if (trimmed.isEmpty()) {
                                if (textLines.isNotEmpty()) {
                                    val text = textLines.joinToString("\n")
                                        .replace(Regex("<[^>]+>"), "") // Strip HTML tags
                                        .replace(Regex("\\{[^}]*\\}"), "") // Strip ASS override tags
                                        .trim()
                                    if (text.isNotEmpty()) {
                                        result.add(SubtitleCue(startMs, endMs, text))
                                    }
                                }
                                state = 0
                            } else {
                                textLines.add(trimmed)
                            }
                        }
                    }
                }

                // Don't forget last cue if file doesn't end with blank line
                if (state == 2 && textLines.isNotEmpty()) {
                    val text = textLines.joinToString("\n")
                        .replace(Regex("<[^>]+>"), "")
                        .trim()
                    if (text.isNotEmpty()) {
                        result.add(SubtitleCue(startMs, endMs, text))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SRT: ${e.message}")
        }
        return result.sortedBy { it.startMs }
    }

    private fun srtTimeToMs(h: String, m: String, s: String, ms: String): Long {
        val msStr = ms.padEnd(3, '0').take(3)
        return h.toLong() * 3600000 + m.toLong() * 60000 + s.toLong() * 1000 + msStr.toLong()
    }

    // -----------------------------------------------------------------------
    // ASS/SSA Parser (basic — handles Dialogue lines)
    // -----------------------------------------------------------------------

    private fun parseAss(file: File): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var line: String?
                var inEvents = false
                var formatFields: List<String> = emptyList()
                var textIndex = -1
                var startIndex = -1
                var endIndex = -1

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()

                    if (trimmed.equals("[Events]", ignoreCase = true)) {
                        inEvents = true
                        continue
                    }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inEvents = false
                        continue
                    }

                    if (inEvents) {
                        if (trimmed.startsWith("Format:", ignoreCase = true)) {
                            formatFields = trimmed.substringAfter(":").split(",").map { it.trim() }
                            textIndex = formatFields.indexOfFirst { it.equals("Text", ignoreCase = true) }
                            startIndex = formatFields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                            endIndex = formatFields.indexOfFirst { it.equals("End", ignoreCase = true) }
                        } else if (trimmed.startsWith("Dialogue:", ignoreCase = true) && textIndex >= 0 && startIndex >= 0 && endIndex >= 0) {
                            val parts = trimmed.substringAfter(":").split(",", limit = formatFields.size)
                            if (parts.size > maxOf(textIndex, startIndex, endIndex)) {
                                val startMs = assTimeToMs(parts[startIndex].trim())
                                val endMs = assTimeToMs(parts[endIndex].trim())
                                val text = parts[textIndex].trim()
                                    .replace(Regex("\\{[^}]*\\}"), "") // Strip ASS style overrides
                                    .replace("\\N", "\n")
                                    .replace("\\n", "\n")
                                    .trim()
                                if (text.isNotEmpty()) {
                                    result.add(SubtitleCue(startMs, endMs, text))
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ASS: ${e.message}")
        }
        return result.sortedBy { it.startMs }
    }

    private fun assTimeToMs(timeStr: String): Long {
        val matcher = ASS_TIME_PATTERN.matcher(timeStr)
        if (!matcher.find()) return 0L
        val h = matcher.group(1)!!.toLong()
        val m = matcher.group(2)!!.toLong()
        val s = matcher.group(3)!!.toLong()
        val cs = matcher.group(4)!!.toLong() // centiseconds
        return h * 3600000 + m * 60000 + s * 1000 + cs * 10
    }

    // -----------------------------------------------------------------------
    // Binary search for active cue
    // -----------------------------------------------------------------------

    private fun findCueAt(positionMs: Long): Int {
        if (cues.isEmpty()) return -1

        // Quick check: is current cue still active?
        if (currentCueIndex in cues.indices) {
            val current = cues[currentCueIndex]
            if (positionMs in current.startMs..current.endMs) return currentCueIndex
        }

        // Quick check: is next cue active? (most common case during linear playback)
        val next = currentCueIndex + 1
        if (next in cues.indices) {
            val nextCue = cues[next]
            if (positionMs in nextCue.startMs..nextCue.endMs) return next
        }

        // Binary search
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return mid // positionMs is within this cue
            }
        }

        return -1 // No active cue at this position
    }

    // -----------------------------------------------------------------------
    // View management
    // -----------------------------------------------------------------------

    private fun ensureView() {
        if (subtitleView != null) return

        val tv = TextView(parent.context).apply {
            textSize = fontSize
            setTextColor(fontColor)
            setBackgroundColor(backgroundColor)
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            maxLines = 4
            // Shadow for readability
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (bottomMarginDp * parent.context.resources.displayMetrics.density).toInt()
        }

        val addAction = Runnable {
            parent.addView(tv, lp)
            subtitleView = tv
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addAction.run()
        } else {
            parent.post(addAction)
        }
    }

    private fun showText(text: String) {
        subtitleView?.let {
            it.text = text
            it.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun hideText() {
        subtitleView?.visibility = View.GONE
    }
}
