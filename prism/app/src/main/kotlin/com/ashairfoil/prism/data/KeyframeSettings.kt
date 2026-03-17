package com.ashairfoil.prism.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * KeyframeSettings — Per-timestamp saved adjustments for a video.
 *
 * HereSphere's killer feature: you can set different color grading, zoom,
 * position, and lens corrections at different points in the video, and the
 * player interpolates between them as the video plays.
 *
 * Use cases:
 * - Scene changes require different brightness/contrast
 * - Camera switches need different lens correction
 * - Zoom in for close-up scenes, zoom out for wide shots
 * - Different IPD settings for different camera rigs in the same video
 *
 * Storage: JSON in SharedPreferences, keyed by file hash.
 *
 * Format:
 * {
 *   "keyframes": [
 *     {
 *       "time_ms": 0,
 *       "brightness": 0.0,
 *       "contrast": 1.0,
 *       "saturation": 1.0,
 *       "sharpening": 0.0,
 *       "gamma": 1.0,
 *       "zoom": 1.0,
 *       "ipd_offset": 0.0,
 *       "lens_k1": 0.0,
 *       "lens_k2": 0.0,
 *       "tilt": 0.0,
 *       "height": 0.0
 *     },
 *     { "time_ms": 60000, ... },
 *     ...
 *   ]
 * }
 */
class KeyframeSettings(private val context: Context) {

    companion object {
        private const val TAG = "Keyframes"
        private const val PREFS_NAME = "chloe_keyframes"
    }

    data class Keyframe(
        val timeMs: Long,
        val brightness: Float = 0f,
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val sharpening: Float = 0f,
        val gamma: Float = 1f,
        val hueShift: Float = 0f,
        val zoom: Float = 1f,
        val ipdOffset: Float = 0f,
        val verticalOffset: Float = 0f,
        val lensK1: Float = 0f,
        val lensK2: Float = 0f,
        val tilt: Float = 0f,
        val height: Float = 0f,
    )

    // Current interpolated state (for the GL thread to read)
    @Volatile var current: Keyframe = Keyframe(0)
        private set

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var keyframes: MutableList<Keyframe> = java.util.concurrent.CopyOnWriteArrayList<Keyframe>()
    private var currentFileId: String = ""

    /**
     * Load keyframes for a video file.
     */
    fun loadForFile(fileId: String) {
        currentFileId = fileId
        keyframes.clear()
        val json = prefs.getString("kf_$fileId", null)
        if (json != null) {
            try {
                val root = JSONObject(json)
                val arr = root.getJSONArray("keyframes")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    keyframes.add(Keyframe(
                        timeMs = obj.getLong("time_ms"),
                        brightness = obj.optDouble("brightness", 0.0).toFloat(),
                        contrast = obj.optDouble("contrast", 1.0).toFloat(),
                        saturation = obj.optDouble("saturation", 1.0).toFloat(),
                        sharpening = obj.optDouble("sharpening", 0.0).toFloat(),
                        gamma = obj.optDouble("gamma", 1.0).toFloat(),
                        hueShift = obj.optDouble("hue_shift", 0.0).toFloat(),
                        zoom = obj.optDouble("zoom", 1.0).toFloat(),
                        ipdOffset = obj.optDouble("ipd_offset", 0.0).toFloat(),
                        verticalOffset = obj.optDouble("vertical_offset", 0.0).toFloat(),
                        lensK1 = obj.optDouble("lens_k1", 0.0).toFloat(),
                        lensK2 = obj.optDouble("lens_k2", 0.0).toFloat(),
                        tilt = obj.optDouble("tilt", 0.0).toFloat(),
                        height = obj.optDouble("height", 0.0).toFloat(),
                    ))
                }
                val sorted = keyframes.sortedBy { it.timeMs }
                keyframes.clear()
                keyframes.addAll(sorted)
                Log.i(TAG, "Loaded ${keyframes.size} keyframes for $fileId")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading keyframes: ${e.message}")
                keyframes.clear()
            }
        }
    }

    /**
     * Save current keyframes for the loaded file.
     */
    fun save() {
        if (currentFileId.isEmpty()) return
        val root = JSONObject()
        val arr = JSONArray()
        for (kf in keyframes) {
            arr.put(JSONObject().apply {
                put("time_ms", kf.timeMs)
                put("brightness", kf.brightness.toDouble())
                put("contrast", kf.contrast.toDouble())
                put("saturation", kf.saturation.toDouble())
                put("sharpening", kf.sharpening.toDouble())
                put("gamma", kf.gamma.toDouble())
                put("hue_shift", kf.hueShift.toDouble())
                put("zoom", kf.zoom.toDouble())
                put("ipd_offset", kf.ipdOffset.toDouble())
                put("vertical_offset", kf.verticalOffset.toDouble())
                put("lens_k1", kf.lensK1.toDouble())
                put("lens_k2", kf.lensK2.toDouble())
                put("tilt", kf.tilt.toDouble())
                put("height", kf.height.toDouble())
            })
        }
        root.put("keyframes", arr)
        prefs.edit().putString("kf_$currentFileId", root.toString()).apply()
        Log.i(TAG, "Saved ${keyframes.size} keyframes for $currentFileId")
    }

    /**
     * Add or update a keyframe at the given time.
     * If a keyframe exists within 1 second, it's updated. Otherwise a new one is added.
     */
    fun setKeyframe(kf: Keyframe) {
        val existing = keyframes.indexOfFirst { Math.abs(it.timeMs - kf.timeMs) < 1000 }
        if (existing >= 0) {
            keyframes[existing] = kf
        } else {
            keyframes.add(kf)
            val sorted = keyframes.sortedBy { it.timeMs }
            keyframes.clear()
            keyframes.addAll(sorted)
        }
        save()
    }

    /**
     * Remove the keyframe nearest to the given time (within 2 seconds).
     */
    fun removeKeyframeNear(timeMs: Long): Boolean {
        val idx = keyframes.indexOfFirst { Math.abs(it.timeMs - timeMs) < 2000 }
        if (idx >= 0) {
            keyframes.removeAt(idx)
            save()
            return true
        }
        return false
    }

    /**
     * Get all keyframes (for UI display).
     */
    fun getKeyframes(): List<Keyframe> = keyframes.toList()

    /**
     * Clear all keyframes for the current file.
     */
    fun clearAll() {
        keyframes.clear()
        if (currentFileId.isNotEmpty()) {
            prefs.edit().remove("kf_$currentFileId").apply()
        }
    }

    /**
     * Update the interpolated state for the current playback position.
     * Call this from the playback progress monitor (every ~100ms).
     *
     * Uses linear interpolation between the two nearest keyframes.
     */
    fun update(positionMs: Long) {
        if (keyframes.isEmpty()) {
            current = Keyframe(positionMs)
            return
        }

        // Find the two keyframes surrounding the current position
        var before: Keyframe? = null
        var after: Keyframe? = null

        for (kf in keyframes) {
            if (kf.timeMs <= positionMs) before = kf
            if (kf.timeMs > positionMs && after == null) after = kf
        }

        if (before == null && after == null) {
            current = Keyframe(positionMs)
            return
        }

        if (before == null) {
            current = after!!.copy(timeMs = positionMs)
            return
        }

        if (after == null) {
            current = before.copy(timeMs = positionMs)
            return
        }

        // Interpolate between before and after
        val range = (after.timeMs - before.timeMs).toFloat()
        val t = if (range > 0) ((positionMs - before.timeMs).toFloat() / range).coerceIn(0f, 1f) else 0f

        current = Keyframe(
            timeMs = positionMs,
            brightness = lerp(before.brightness, after.brightness, t),
            contrast = lerp(before.contrast, after.contrast, t),
            saturation = lerp(before.saturation, after.saturation, t),
            sharpening = lerp(before.sharpening, after.sharpening, t),
            gamma = lerp(before.gamma, after.gamma, t),
            hueShift = lerp(before.hueShift, after.hueShift, t),
            zoom = lerp(before.zoom, after.zoom, t),
            ipdOffset = lerp(before.ipdOffset, after.ipdOffset, t),
            verticalOffset = lerp(before.verticalOffset, after.verticalOffset, t),
            lensK1 = lerp(before.lensK1, after.lensK1, t),
            lensK2 = lerp(before.lensK2, after.lensK2, t),
            tilt = lerp(before.tilt, after.tilt, t),
            height = lerp(before.height, after.height, t),
        )
    }

    fun hasKeyframes(): Boolean = keyframes.isNotEmpty()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
