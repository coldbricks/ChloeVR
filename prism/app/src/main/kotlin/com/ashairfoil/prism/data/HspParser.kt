package com.ashairfoil.prism.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * HspParser — HereSphere Script (.hsp) file parser.
 *
 * HSP files contain timestamped tags and metadata for VR videos.
 * They're the standard for scene-aware playback in the VR video community.
 *
 * HSP Format (JSON):
 * {
 *   "version": 1,
 *   "tags": [
 *     { "ts": 0, "name": "intro", "color": "#ff0000" },
 *     { "ts": 30000, "name": "scene1", "color": "#00ff00" },
 *     { "ts": 120000, "name": "position_change", "end_ts": 180000 },
 *     ...
 *   ],
 *   "corrections": {
 *     "x": 0, "y": 0, "z": 0,
 *     "brightness": 0, "contrast": 0, "saturation": 0,
 *     "ipd": 0, "fov": 180
 *   },
 *   "projection": "equirectangular180",
 *   "stereo": "sbs"
 * }
 *
 * ChloeVR supports HSP for:
 * - Scene navigation (jump to tagged timestamps)
 * - Chapter markers on the scrub bar
 * - Color correction presets per-scene
 * - Projection/stereo overrides
 */
class HspParser {

    companion object {
        private const val TAG = "HspParser"
    }

    data class HspTag(
        val timestampMs: Long,
        val endTimestampMs: Long?,   // null = point tag, non-null = range tag
        val name: String,
        val color: String?,          // Hex color for scrub bar marker
        val category: String?,       // Optional category grouping
    )

    data class HspCorrections(
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val saturation: Float = 0f,
        val ipd: Float = 0f,
        val fov: Float = 0f,
    )

    data class HspFile(
        val version: Int = 1,
        val tags: List<HspTag> = emptyList(),
        val corrections: HspCorrections? = null,
        val projection: String? = null,
        val stereo: String? = null,
        val source: String = "",  // File path or URL
    )

    /**
     * Load HSP file for a video. Looks for .hsp file alongside the video.
     */
    fun loadForVideo(videoFile: File): HspFile? {
        val hspFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.hsp")
        if (!hspFile.exists()) return null
        return parse(hspFile)
    }

    /**
     * Parse an HSP file.
     */
    fun parse(file: File): HspFile? {
        try {
            val json = file.readText()
            return parseJson(json, file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HSP file ${file.name}: ${e.message}")
            return null
        }
    }

    /**
     * Parse HSP from JSON string (for API responses).
     */
    fun parseJson(json: String, source: String = ""): HspFile? {
        try {
            val root = JSONObject(json)
            val version = root.optInt("version", 1)

            // Parse tags
            val tags = mutableListOf<HspTag>()
            val tagsArray = root.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tagsArray.length()) {
                val obj = tagsArray.getJSONObject(i)
                tags.add(HspTag(
                    timestampMs = obj.getLong("ts"),
                    endTimestampMs = if (obj.has("end_ts")) obj.getLong("end_ts") else null,
                    name = obj.optString("name", ""),
                    color = obj.optString("color", null),
                    category = obj.optString("category", null),
                ))
            }
            tags.sortBy { it.timestampMs }

            // Parse corrections
            val correctionsObj = root.optJSONObject("corrections")
            val corrections = if (correctionsObj != null) {
                HspCorrections(
                    x = correctionsObj.optDouble("x", 0.0).toFloat(),
                    y = correctionsObj.optDouble("y", 0.0).toFloat(),
                    z = correctionsObj.optDouble("z", 0.0).toFloat(),
                    brightness = correctionsObj.optDouble("brightness", 0.0).toFloat(),
                    contrast = correctionsObj.optDouble("contrast", 0.0).toFloat(),
                    saturation = correctionsObj.optDouble("saturation", 0.0).toFloat(),
                    ipd = correctionsObj.optDouble("ipd", 0.0).toFloat(),
                    fov = correctionsObj.optDouble("fov", 0.0).toFloat(),
                )
            } else null

            val hsp = HspFile(
                version = version,
                tags = tags,
                corrections = corrections,
                projection = root.optString("projection", null),
                stereo = root.optString("stereo", null),
                source = source,
            )

            Log.i(TAG, "Parsed HSP: ${tags.size} tags, corrections=${corrections != null}, source=$source")
            return hsp
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HSP JSON: ${e.message}")
            return null
        }
    }

    /**
     * Get the tag at or before the given timestamp.
     */
    fun getTagAt(hsp: HspFile, positionMs: Long): HspTag? {
        var result: HspTag? = null
        for (tag in hsp.tags) {
            if (tag.timestampMs <= positionMs) {
                // For range tags, check if we're within the range
                if (tag.endTimestampMs != null && positionMs > tag.endTimestampMs) continue
                result = tag
            }
        }
        return result
    }

    /**
     * Get the next tag after the given timestamp (for "next chapter" navigation).
     */
    fun getNextTag(hsp: HspFile, positionMs: Long): HspTag? {
        return hsp.tags.firstOrNull { it.timestampMs > positionMs }
    }

    /**
     * Get the previous tag before the given timestamp.
     */
    fun getPreviousTag(hsp: HspFile, positionMs: Long): HspTag? {
        return hsp.tags.lastOrNull { it.timestampMs < positionMs - 1000 }
    }

    /**
     * Get all tags as chapter markers for the scrub bar.
     * Returns pairs of (normalized position 0-1, tag name).
     */
    fun getChapterMarkers(hsp: HspFile, durationMs: Long): List<Pair<Float, String>> {
        if (durationMs <= 0) return emptyList()
        return hsp.tags.map { tag ->
            val pos = tag.timestampMs.toFloat() / durationMs
            pos.coerceIn(0f, 1f) to tag.name
        }
    }
}
