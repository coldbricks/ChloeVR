package com.ashairfoil.prism.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Singleton persistence layer for all ChloeVR user settings.
 * Uses SharedPreferences with type-safe getters/setters.
 *
 * Thread-safe: all SharedPreferences reads are from memory (after first load),
 * all writes go through apply() (async disk write).
 */
object SettingsManager {

    private const val PREFS_NAME = "chloe_vr_settings"

    // ── Keys ────────────────────────────────────────────────────────────
    private const val KEY_LAST_PLAYED_FILE = "last_played_file"
    private const val KEY_RESUME_POSITIONS = "resume_positions"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val KEY_SEEK_INCREMENT_MS = "seek_increment_ms"
    private const val KEY_FAVORITE_FOLDERS = "favorite_folders"

    // Screen adjustments — stored per projection type (flat, hemisphere, sphere)
    private const val KEY_SCREEN_POS_X = "screen_pos_x_"
    private const val KEY_SCREEN_POS_Y = "screen_pos_y_"
    private const val KEY_SCREEN_POS_Z = "screen_pos_z_"
    private const val KEY_SCREEN_ROT_X = "screen_rot_x_"
    private const val KEY_SCREEN_ROT_Y = "screen_rot_y_"
    private const val KEY_SCREEN_ROT_Z = "screen_rot_z_"
    private const val KEY_SCREEN_SCALE = "screen_scale_"

    // Chroma key
    private const val KEY_CHROMA_KEY_R = "chroma_key_r"
    private const val KEY_CHROMA_KEY_G = "chroma_key_g"
    private const val KEY_CHROMA_KEY_B = "chroma_key_b"
    private const val KEY_CHROMA_TOLERANCE = "chroma_tolerance"
    private const val KEY_CHROMA_SOFTNESS = "chroma_softness"
    private const val KEY_CHROMA_ENABLED = "chroma_enabled"

    // Color grading
    private const val KEY_CG_BRIGHTNESS = "cg_brightness"
    private const val KEY_CG_CONTRAST = "cg_contrast"
    private const val KEY_CG_SATURATION = "cg_saturation"
    private const val KEY_CG_SHARPENING = "cg_sharpening"
    private const val KEY_CG_GAMMA = "cg_gamma"
    private const val KEY_CG_HUE_SHIFT = "cg_hue_shift"
    private const val KEY_CG_TONE_MAP_MODE = "cg_tone_map_mode"
    private const val KEY_CG_ENABLED = "cg_enabled"

    // Lens distortion
    private const val KEY_LENS_PRESET = "lens_preset"
    private const val KEY_LENS_K1 = "lens_k1"
    private const val KEY_LENS_K2 = "lens_k2"
    private const val KEY_LENS_FOV = "lens_fov"
    private const val KEY_LENS_CX = "lens_cx"
    private const val KEY_LENS_CY = "lens_cy"
    private const val KEY_LENS_ENABLED = "lens_enabled"

    // IPD / stereo adjustment
    private const val KEY_IPD_OFFSET = "ipd_offset"
    private const val KEY_STEREO_VERTICAL_OFFSET = "stereo_vertical_offset"
    private const val KEY_STEREO_ENABLED = "stereo_enabled"

    // Screen curvature — stored per projection type
    private const val KEY_SCREEN_CURVATURE = "screen_curvature_"

    // Zoom
    private const val KEY_ZOOM_LEVEL = "zoom_level"

    // ── Defaults ────────────────────────────────────────────────────────
    private const val DEFAULT_PLAYBACK_SPEED = 1.0f
    private const val DEFAULT_SEEK_INCREMENT_MS = 10_000L
    private const val DEFAULT_ZOOM_LEVEL = 1.0f

    // Color grading defaults (neutral)
    private const val DEFAULT_CG_BRIGHTNESS = 0.0f
    private const val DEFAULT_CG_CONTRAST = 1.0f
    private const val DEFAULT_CG_SATURATION = 1.0f
    private const val DEFAULT_CG_SHARPENING = 0.0f
    private const val DEFAULT_CG_GAMMA = 1.0f
    private const val DEFAULT_CG_HUE_SHIFT = 0.0f
    private const val DEFAULT_CG_TONE_MAP_MODE = 0 // 0=none, 1=Reinhard, 2=ACES

    // Lens distortion defaults (disabled/flat)
    private const val DEFAULT_LENS_K1 = 0.0f
    private const val DEFAULT_LENS_K2 = 0.0f
    private const val DEFAULT_LENS_FOV = 180.0f
    private const val DEFAULT_LENS_CX = 0.5f
    private const val DEFAULT_LENS_CY = 0.5f

    // Stereo defaults
    private const val DEFAULT_IPD_OFFSET = 0.0f
    private const val DEFAULT_STEREO_VERTICAL_OFFSET = 0.0f

    // Screen adjustment defaults
    private const val DEFAULT_SCREEN_POS = 0.0f
    private const val DEFAULT_SCREEN_ROT = 0.0f
    private const val DEFAULT_SCREEN_SCALE = 1.0f

    // ── State ───────────────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureInit() {
        check(::prefs.isInitialized) { "SettingsManager.init(context) must be called before use" }
    }

    // ── Generic accessors (for cross-module use) ──────────────────────
    fun getString(key: String, default: String): String {
        ensureInit(); return prefs.getString(key, default) ?: default
    }
    fun putString(key: String, value: String) {
        ensureInit(); prefs.edit().putString(key, value).apply()
    }

    // ── Last played file ────────────────────────────────────────────────

    var lastPlayedFile: String?
        get() { ensureInit(); return prefs.getString(KEY_LAST_PLAYED_FILE, null) }
        set(value) { ensureInit(); prefs.edit().putString(KEY_LAST_PLAYED_FILE, value).apply() }

    // ── Resume positions (per-file map) ─────────────────────────────────
    // Stored as a single serialized string: "path1|pos1;;path2|pos2;;..."
    // Compact and avoids needing JSON/Gson dependency.

    fun getResumePosition(filePath: String): Long {
        ensureInit()
        val raw = prefs.getString(KEY_RESUME_POSITIONS, "") ?: ""
        return parseResumeMap(raw)[filePath] ?: 0L
    }

    fun setResumePosition(filePath: String, positionMs: Long) {
        ensureInit()
        val raw = prefs.getString(KEY_RESUME_POSITIONS, "") ?: ""
        val map = parseResumeMap(raw).toMutableMap()
        if (positionMs <= 0) {
            map.remove(filePath)
        } else {
            map[filePath] = positionMs
        }
        // Keep only the most recent 200 entries to avoid unbounded growth
        val trimmed = if (map.size > 200) {
            map.entries.sortedByDescending { it.value }.take(200).associate { it.key to it.value }
        } else {
            map
        }
        prefs.edit().putString(KEY_RESUME_POSITIONS, serializeResumeMap(trimmed)).apply()
    }

    fun clearResumePosition(filePath: String) {
        setResumePosition(filePath, 0L)
    }

    private fun parseResumeMap(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";;").mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) {
                val path = parts[0]
                val pos = parts[1].toLongOrNull()
                if (pos != null) path to pos else null
            } else null
        }.toMap()
    }

    private fun serializeResumeMap(map: Map<String, Long>): String {
        return map.entries.joinToString(";;") { "${it.key}|${it.value}" }
    }

    // ── Playback speed ──────────────────────────────────────────────────

    var playbackSpeed: Float
        get() { ensureInit(); return prefs.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED) }
        set(value) { ensureInit(); prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply() }

    // ── Seek increment ──────────────────────────────────────────────────

    var seekIncrementMs: Long
        get() { ensureInit(); return prefs.getLong(KEY_SEEK_INCREMENT_MS, DEFAULT_SEEK_INCREMENT_MS) }
        set(value) { ensureInit(); prefs.edit().putLong(KEY_SEEK_INCREMENT_MS, value).apply() }

    // ── Zoom level ──────────────────────────────────────────────────────

    var zoomLevel: Float
        get() { ensureInit(); return prefs.getFloat(KEY_ZOOM_LEVEL, DEFAULT_ZOOM_LEVEL) }
        set(value) { ensureInit(); prefs.edit().putFloat(KEY_ZOOM_LEVEL, value).apply() }

    // ── Screen adjustments (per projection type) ────────────────────────

    fun getScreenPosX(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_POS_X + projection, DEFAULT_SCREEN_POS)
    }
    fun setScreenPosX(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_POS_X + projection, value).apply()
    }

    fun getScreenPosY(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_POS_Y + projection, DEFAULT_SCREEN_POS)
    }
    fun setScreenPosY(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_POS_Y + projection, value).apply()
    }

    fun getScreenPosZ(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_POS_Z + projection, DEFAULT_SCREEN_POS)
    }
    fun setScreenPosZ(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_POS_Z + projection, value).apply()
    }

    fun getScreenRotX(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_ROT_X + projection, DEFAULT_SCREEN_ROT)
    }
    fun setScreenRotX(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_ROT_X + projection, value).apply()
    }

    fun getScreenRotY(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_ROT_Y + projection, DEFAULT_SCREEN_ROT)
    }
    fun setScreenRotY(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_ROT_Y + projection, value).apply()
    }

    fun getScreenRotZ(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_ROT_Z + projection, DEFAULT_SCREEN_ROT)
    }
    fun setScreenRotZ(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_ROT_Z + projection, value).apply()
    }

    fun getScreenScale(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_SCALE + projection, DEFAULT_SCREEN_SCALE)
    }
    fun setScreenScale(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_SCALE + projection, value).apply()
    }

    fun getScreenCurvature(projection: String): Float {
        ensureInit(); return prefs.getFloat(KEY_SCREEN_CURVATURE + projection, 0f)
    }
    fun setScreenCurvature(projection: String, value: Float) {
        ensureInit(); prefs.edit().putFloat(KEY_SCREEN_CURVATURE + projection, value).apply()
    }

    // ── Chroma key settings ─────────────────────────────────────────────

    var chromaKeyR: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_R, 0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_R, v).apply() }

    var chromaKeyG: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_G, 1f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_G, v).apply() }

    var chromaKeyB: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_B, 0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_B, v).apply() }

    var chromaTolerance: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_TOLERANCE, 0.30f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_TOLERANCE, v).apply() }

    var chromaSoftness: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_SOFTNESS, 0.10f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_SOFTNESS, v).apply() }

    var chromaEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_CHROMA_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_CHROMA_ENABLED, v).apply() }

    // ── Color grading settings ──────────────────────────────────────────

    var cgBrightness: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_BRIGHTNESS, DEFAULT_CG_BRIGHTNESS) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_BRIGHTNESS, v).apply() }

    var cgContrast: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_CONTRAST, DEFAULT_CG_CONTRAST) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_CONTRAST, v).apply() }

    var cgSaturation: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_SATURATION, DEFAULT_CG_SATURATION) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_SATURATION, v).apply() }

    var cgSharpening: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_SHARPENING, DEFAULT_CG_SHARPENING) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_SHARPENING, v).apply() }

    var cgGamma: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_GAMMA, DEFAULT_CG_GAMMA) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_GAMMA, v).apply() }

    var cgHueShift: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_HUE_SHIFT, DEFAULT_CG_HUE_SHIFT) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_HUE_SHIFT, v).apply() }

    var cgToneMapMode: Int
        get() { ensureInit(); return prefs.getInt(KEY_CG_TONE_MAP_MODE, DEFAULT_CG_TONE_MAP_MODE) }
        set(v) { ensureInit(); prefs.edit().putInt(KEY_CG_TONE_MAP_MODE, v).apply() }

    var cgEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_CG_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_CG_ENABLED, v).apply() }

    // ── Lens distortion settings ────────────────────────────────────────

    var lensPreset: String
        get() { ensureInit(); return prefs.getString(KEY_LENS_PRESET, "none") ?: "none" }
        set(v) { ensureInit(); prefs.edit().putString(KEY_LENS_PRESET, v).apply() }

    var lensK1: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_K1, DEFAULT_LENS_K1) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_K1, v).apply() }

    var lensK2: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_K2, DEFAULT_LENS_K2) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_K2, v).apply() }

    var lensFov: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_FOV, DEFAULT_LENS_FOV) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_FOV, v).apply() }

    var lensCx: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_CX, DEFAULT_LENS_CX) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_CX, v).apply() }

    var lensCy: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_CY, DEFAULT_LENS_CY) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_CY, v).apply() }

    var lensEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_LENS_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_LENS_ENABLED, v).apply() }

    // ── IPD / stereo adjustment ─────────────────────────────────────────

    var ipdOffset: Float
        get() { ensureInit(); return prefs.getFloat(KEY_IPD_OFFSET, DEFAULT_IPD_OFFSET) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_IPD_OFFSET, v).apply() }

    var stereoVerticalOffset: Float
        get() { ensureInit(); return prefs.getFloat(KEY_STEREO_VERTICAL_OFFSET, DEFAULT_STEREO_VERTICAL_OFFSET) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_STEREO_VERTICAL_OFFSET, v).apply() }

    var stereoEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_STEREO_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_STEREO_ENABLED, v).apply() }

    // ── Favorite folders ────────────────────────────────────────────────
    // Stored as newline-separated paths

    fun getFavoriteFolders(): List<String> {
        ensureInit()
        val raw = prefs.getString(KEY_FAVORITE_FOLDERS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun addFavoriteFolder(path: String) {
        ensureInit()
        val current = getFavoriteFolders().toMutableList()
        if (path !in current) {
            current.add(path)
            prefs.edit().putString(KEY_FAVORITE_FOLDERS, current.joinToString("\n")).apply()
        }
    }

    fun removeFavoriteFolder(path: String) {
        ensureInit()
        val current = getFavoriteFolders().toMutableList()
        current.remove(path)
        prefs.edit().putString(KEY_FAVORITE_FOLDERS, current.joinToString("\n")).apply()
    }

    fun isFavoriteFolder(path: String): Boolean = path in getFavoriteFolders()

    // ── Color grading presets ───────────────────────────────────────────
    // Each preset stored as: "name|brightness|contrast|saturation|sharpening|gamma|hueShift|toneMapMode"

    data class ColorGradingPreset(
        val name: String,
        val brightness: Float = DEFAULT_CG_BRIGHTNESS,
        val contrast: Float = DEFAULT_CG_CONTRAST,
        val saturation: Float = DEFAULT_CG_SATURATION,
        val sharpening: Float = DEFAULT_CG_SHARPENING,
        val gamma: Float = DEFAULT_CG_GAMMA,
        val hueShift: Float = DEFAULT_CG_HUE_SHIFT,
        val toneMapMode: Int = DEFAULT_CG_TONE_MAP_MODE
    )

    private const val KEY_CG_PRESETS = "cg_presets"

    fun getColorGradingPresets(): List<ColorGradingPreset> {
        ensureInit()
        val raw = prefs.getString(KEY_CG_PRESETS, "") ?: ""
        if (raw.isBlank()) return builtInPresets()
        return raw.split(";;").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size >= 8) {
                ColorGradingPreset(
                    name = p[0],
                    brightness = p[1].toFloatOrNull() ?: DEFAULT_CG_BRIGHTNESS,
                    contrast = p[2].toFloatOrNull() ?: DEFAULT_CG_CONTRAST,
                    saturation = p[3].toFloatOrNull() ?: DEFAULT_CG_SATURATION,
                    sharpening = p[4].toFloatOrNull() ?: DEFAULT_CG_SHARPENING,
                    gamma = p[5].toFloatOrNull() ?: DEFAULT_CG_GAMMA,
                    hueShift = p[6].toFloatOrNull() ?: DEFAULT_CG_HUE_SHIFT,
                    toneMapMode = p[7].toIntOrNull() ?: DEFAULT_CG_TONE_MAP_MODE
                )
            } else null
        }
    }

    fun saveColorGradingPreset(preset: ColorGradingPreset) {
        ensureInit()
        val list = getColorGradingPresets().toMutableList()
        // Replace existing with same name, or append
        val idx = list.indexOfFirst { it.name == preset.name }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        val serialized = list.joinToString(";;") {
            "${it.name}|${it.brightness}|${it.contrast}|${it.saturation}|${it.sharpening}|${it.gamma}|${it.hueShift}|${it.toneMapMode}"
        }
        prefs.edit().putString(KEY_CG_PRESETS, serialized).apply()
    }

    fun deleteColorGradingPreset(name: String) {
        ensureInit()
        val list = getColorGradingPresets().filterNot { it.name == name }
        val serialized = list.joinToString(";;") {
            "${it.name}|${it.brightness}|${it.contrast}|${it.saturation}|${it.sharpening}|${it.gamma}|${it.hueShift}|${it.toneMapMode}"
        }
        prefs.edit().putString(KEY_CG_PRESETS, serialized).apply()
    }

    private fun builtInPresets(): List<ColorGradingPreset> = listOf(
        ColorGradingPreset("Neutral", 0f, 1f, 1f, 0f, 1f, 0f, 0),
        ColorGradingPreset("Vivid", 0.05f, 1.2f, 1.5f, 0.2f, 0.9f, 0f, 0),
        ColorGradingPreset("Cinematic", -0.05f, 1.15f, 0.85f, 0.1f, 1.1f, 0f, 2),
        ColorGradingPreset("Warm", 0.03f, 1.05f, 1.1f, 0f, 1f, 15f, 0),
        ColorGradingPreset("Cool", 0f, 1.05f, 0.95f, 0f, 1f, -10f, 0),
        ColorGradingPreset("Sharp", 0f, 1f, 1f, 0.6f, 1f, 0f, 0)
    )
}
