package com.ashairfoil.prism.data

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * FunscriptParser — Funscript (.funscript) file parser for haptic sync.
 *
 * Funscript is the de-facto standard for synchronized haptic device
 * scripts in VR video. It's a JSON format with timestamped position
 * values (0-100) that drive compatible devices.
 *
 * ChloeVR can read funscripts to:
 * 1. Display a visual timeline overlay showing the script activity
 * 2. Send position commands to connected Bluetooth/WiFi haptic devices
 *    (via the Buttplug.io protocol or direct device APIs)
 * 3. Sync the script to video playback with configurable offset
 *
 * Funscript Format:
 * {
 *   "version": "1.0",
 *   "inverted": false,
 *   "range": 100,
 *   "actions": [
 *     { "at": 0, "pos": 50 },
 *     { "at": 1000, "pos": 100 },
 *     { "at": 1500, "pos": 0 },
 *     ...
 *   ],
 *   "metadata": {
 *     "creator": "...",
 *     "description": "...",
 *     "duration": 1800000,
 *     "license": "...",
 *     "notes": "...",
 *     "performers": ["..."],
 *     "script_url": "...",
 *     "tags": ["..."],
 *     "title": "...",
 *     "type": "basic",
 *     "video_url": "..."
 *   }
 * }
 */
class FunscriptParser {

    companion object {
        private const val TAG = "FunscriptParser"
    }

    data class FunscriptAction(
        val atMs: Long,    // Timestamp in milliseconds
        val position: Int  // 0-100 (0 = bottom, 100 = top)
    )

    data class FunscriptMetadata(
        val creator: String = "",
        val description: String = "",
        val duration: Long = 0,
        val title: String = "",
        val type: String = "basic",
        val performers: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    data class Funscript(
        val version: String = "1.0",
        val inverted: Boolean = false,
        val range: Int = 100,
        val actions: List<FunscriptAction>,
        val metadata: FunscriptMetadata = FunscriptMetadata(),
        val source: String = "",
    ) {
        val isEmpty: Boolean get() = actions.isEmpty()
        val durationMs: Long get() = actions.lastOrNull()?.atMs ?: 0

        /**
         * Get the interpolated position at a given timestamp.
         */
        fun positionAt(timeMs: Long): Int {
            if (actions.isEmpty()) return 50
            if (timeMs <= actions.first().atMs) return actions.first().position
            if (timeMs >= actions.last().atMs) return actions.last().position

            // Binary search for surrounding actions
            var lo = 0
            var hi = actions.size - 1
            while (lo < hi - 1) {
                val mid = (lo + hi) / 2
                if (actions[mid].atMs <= timeMs) lo = mid else hi = mid
            }

            val a = actions[lo]
            val b = actions[hi]
            val range = (b.atMs - a.atMs).toFloat()
            if (range <= 0) return a.position

            val t = ((timeMs - a.atMs).toFloat() / range).coerceIn(0f, 1f)
            val pos = (a.position + (b.position - a.position) * t).toInt()

            return if (inverted) 100 - pos else pos
        }

        /**
         * Get actions in a time range (for timeline visualization).
         */
        fun actionsInRange(startMs: Long, endMs: Long): List<FunscriptAction> {
            return actions.filter { it.atMs in startMs..endMs }
        }

        /**
         * Compute "intensity" at a point — how fast/active the script is.
         * Returns 0-100 based on average speed of nearby actions.
         */
        fun intensityAt(timeMs: Long, windowMs: Long = 5000): Int {
            val nearby = actionsInRange(timeMs - windowMs / 2, timeMs + windowMs / 2)
            if (nearby.size < 2) return 0

            var totalSpeed = 0f
            for (i in 1 until nearby.size) {
                val dt = (nearby[i].atMs - nearby[i - 1].atMs).toFloat()
                val dp = Math.abs(nearby[i].position - nearby[i - 1].position).toFloat()
                if (dt > 0) totalSpeed += dp / dt
            }

            val avgSpeed = totalSpeed / (nearby.size - 1)
            // Normalize: typical fast speed is ~0.3 pos/ms = 300 pos/sec
            return (avgSpeed * 333f).toInt().coerceIn(0, 100)
        }

        /**
         * Generate a simplified heatmap for the scrub bar.
         * Returns a list of (normalized position 0-1, intensity 0-100) pairs.
         */
        fun generateHeatmap(durationMs: Long, segments: Int = 100): List<Pair<Float, Int>> {
            if (durationMs <= 0 || actions.isEmpty()) return emptyList()
            val segWidth = durationMs.toFloat() / segments
            return (0 until segments).map { i ->
                val t = i.toFloat() / segments
                val ms = (t * durationMs).toLong()
                t to intensityAt(ms, (segWidth * 2).toLong())
            }
        }
    }

    /**
     * Load funscript for a video file. Looks for .funscript alongside the video.
     */
    fun loadForVideo(videoFile: File): Funscript? {
        val dir = videoFile.parentFile ?: return null
        val fsFile = File(dir, "${videoFile.nameWithoutExtension}.funscript")
        if (!fsFile.exists()) {
            // Also try without spaces/special chars
            val altFile = File(dir, "${videoFile.nameWithoutExtension}.json")
            if (altFile.exists()) return parse(altFile)
            return null
        }
        return parse(fsFile)
    }

    /**
     * Parse a funscript file.
     */
    fun parse(file: File): Funscript? {
        try {
            if (file.length() > 50 * 1024 * 1024) {
                Log.w(TAG, "Funscript too large: ${file.length()} bytes, skipping")
                return null
            }
            val json = file.readText()
            return parseJson(json, file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading funscript ${file.name}: ${e.message}")
            return null
        }
    }

    /**
     * Parse funscript from JSON string.
     */
    fun parseJson(json: String, source: String = ""): Funscript? {
        try {
            val root = JSONObject(json)

            // Parse actions
            val actionsArray = root.optJSONArray("actions") ?: return null
            val actions = mutableListOf<FunscriptAction>()
            for (i in 0 until actionsArray.length()) {
                val obj = actionsArray.getJSONObject(i)
                actions.add(FunscriptAction(
                    atMs = obj.getLong("at"),
                    position = obj.getInt("pos").coerceIn(0, 100)
                ))
            }
            actions.sortBy { it.atMs }

            // Parse metadata
            val metaObj = root.optJSONObject("metadata")
            val metadata = if (metaObj != null) {
                val performers = mutableListOf<String>()
                val perfArray = metaObj.optJSONArray("performers")
                if (perfArray != null) {
                    for (i in 0 until perfArray.length()) performers.add(perfArray.getString(i))
                }
                val tags = mutableListOf<String>()
                val tagsArray = metaObj.optJSONArray("tags")
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) tags.add(tagsArray.getString(i))
                }
                FunscriptMetadata(
                    creator = metaObj.optString("creator", ""),
                    description = metaObj.optString("description", ""),
                    duration = metaObj.optLong("duration", 0),
                    title = metaObj.optString("title", ""),
                    type = metaObj.optString("type", "basic"),
                    performers = performers,
                    tags = tags,
                )
            } else FunscriptMetadata()

            val funscript = Funscript(
                version = root.optString("version", "1.0"),
                inverted = root.optBoolean("inverted", false),
                range = root.optInt("range", 100),
                actions = actions,
                metadata = metadata,
                source = source,
            )

            Log.d(TAG, "Parsed funscript: ${actions.size} actions, " +
                    "duration=${funscript.durationMs}ms, source=$source")
            return funscript
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing funscript JSON: ${e.message}")
            return null
        }
    }
}
