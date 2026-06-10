package com.ashairfoil.prism.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Singleton persistence layer for all ChloeVR user settings.
 * Uses SharedPreferences with type-safe getters/setters.
 *
 * Thread-safe: all SharedPreferences reads are from memory (after first load),
 * all writes go through apply() (async disk write).
 */
object SettingsManager {

    private const val TAG = "SettingsManager"
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

    // Depth simulation / spatial audio
    private const val KEY_DEPTH_SIM_ENABLED = "depth_sim_enabled"
    private const val KEY_SPATIAL_AUDIO_ENABLED = "spatial_audio_enabled"

    // Real-world scene occlusion (placed 3D models get culled by furniture)
    private const val KEY_OCCLUSION_ENABLED = "occlusion_enabled"

    // Screen curvature — stored per projection type
    private const val KEY_SCREEN_CURVATURE = "screen_curvature_"

    // Zoom
    private const val KEY_ZOOM_LEVEL = "zoom_level"

    // Chloe Vibes haptic engine (see ChloeVibesEngine.kt)
    private const val KEY_VIBES_ENABLED = "vibes_engine_enabled"
    private const val KEY_VIBES_PRESET = "vibes_preset_name"
    private const val KEY_VIBES_KNEE = "vibes_threshold_knee"
    private const val KEY_VIBES_DYN_CURVE = "vibes_dynamic_curve"
    private const val KEY_VIBES_GATE_THRESH = "vibes_gate_threshold"
    private const val KEY_VIBES_ATTACK_MS = "vibes_attack_ms"
    private const val KEY_VIBES_RELEASE_MS = "vibes_release_ms"
    private const val KEY_VIBES_OUTPUT_GAIN = "vibes_output_gain"

    // Haptic scripting (funscript/HSP playback) — values: Off|Reactive|Scripted|Mixed
    private const val KEY_HAPTIC_SCRIPT_MODE = "haptic_script_mode"

    // Native OpenXR tuning (applies in FilamentModelActivity / unmanaged space)
    private const val KEY_DISPLAY_REFRESH_RATE = "display_refresh_rate"
    private const val KEY_EYE_TRACKED_FOVEATION = "eye_tracked_foveation"
    private const val KEY_THERMAL_AUTO_DOWNGRADE = "thermal_auto_downgrade"
    private const val KEY_FOLLOW_ROOM_LIGHT = "follow_room_light"

    // Persistent spatial anchors (XR_EXT_spatial_entity). Default on — placed models stay in the
    // real room across sessions. Users who don't want the runtime to persist spatial data can
    // opt out, falling back to purely session-local positions.
    private const val KEY_USE_SPATIAL_ANCHORS = "use_spatial_anchors"

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
    private val resumeLock = Any()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureInit() {
        if (!::prefs.isInitialized) {
            Log.w(TAG, "SettingsManager used before init(), attempting late init")
            throw IllegalStateException("SettingsManager.init(context) must be called in Application.onCreate() or Activity.onCreate() before use")
        }
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
    // Stored as a single serialized string: "path1\tpos1\npath2\tpos2\n..."
    // Tab key-value separator and newline entry separator avoid collisions with file paths.
    // Legacy format used "|" and ";;" — parseResumeMap handles both for migration.

    fun getResumePosition(filePath: String): Long {
        ensureInit()
        synchronized(resumeLock) {
            val raw = prefs.getString(KEY_RESUME_POSITIONS, "") ?: ""
            return parseResumeMap(raw)[filePath] ?: 0L
        }
    }

    fun setResumePosition(filePath: String, positionMs: Long) {
        ensureInit()
        synchronized(resumeLock) {
            val raw = prefs.getString(KEY_RESUME_POSITIONS, "") ?: ""
            val map = LinkedHashMap(parseResumeMap(raw))
            if (positionMs <= 0) {
                map.remove(filePath)
            } else {
                // Remove and re-insert to move to end (most recently accessed)
                map.remove(filePath)
                map[filePath] = positionMs
            }
            // Keep only the most recent 200 entries by insertion order (oldest first, newest last)
            val trimmed = if (map.size > 200) {
                val entries = map.entries.toList()
                LinkedHashMap<String, Long>(200).also { m ->
                    entries.subList(entries.size - 200, entries.size).forEach { m[it.key] = it.value }
                }
            } else {
                map
            }
            prefs.edit().putString(KEY_RESUME_POSITIONS, serializeResumeMap(trimmed)).apply()
        }
    }

    fun clearResumePosition(filePath: String) {
        setResumePosition(filePath, 0L)
    }

    private fun parseResumeMap(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        // Detect legacy format (uses ";;" entry separator) vs new format (uses "\n")
        val entries = if (raw.contains(";;")) raw.split(";;") else raw.split("\n")
        val separator = if (raw.contains(";;")) "|" else "\t"
        return entries.mapNotNull { entry ->
            val parts = entry.split(separator, limit = 2)
            if (parts.size == 2) {
                // URL-decode the path to reverse the encoding applied in serializeResumeMap.
                // Legacy entries that were never encoded will pass through unchanged.
                val path = try { URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
                val pos = parts[1].toLongOrNull()
                if (pos != null) path to pos else null
            } else null
        }.toMap()
    }

    private fun serializeResumeMap(map: Map<String, Long>): String {
        // URL-encode paths so that tab (\t) and newline (\n) characters in file paths
        // cannot corrupt the delimiter-separated format.
        return map.entries.joinToString("\n") { (path, pos) ->
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            "$encodedPath\t$pos"
        }
    }

    // ── Playback speed ──────────────────────────────────────────────────

    var playbackSpeed: Float
        get() { ensureInit(); return prefs.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED).coerceIn(0.25f, 4.0f) }
        set(value) { ensureInit(); prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value.coerceIn(0.25f, 4.0f)).apply() }

    // ── Seek increment ──────────────────────────────────────────────────

    var seekIncrementMs: Long
        get() { ensureInit(); return prefs.getLong(KEY_SEEK_INCREMENT_MS, DEFAULT_SEEK_INCREMENT_MS).coerceIn(1_000L, 120_000L) }
        set(value) { ensureInit(); prefs.edit().putLong(KEY_SEEK_INCREMENT_MS, value.coerceIn(1_000L, 120_000L)).apply() }

    // ── Zoom level ──────────────────────────────────────────────────────

    var zoomLevel: Float
        get() { ensureInit(); return prefs.getFloat(KEY_ZOOM_LEVEL, DEFAULT_ZOOM_LEVEL).coerceIn(0.1f, 20.0f) }
        set(value) { ensureInit(); prefs.edit().putFloat(KEY_ZOOM_LEVEL, value.coerceIn(0.1f, 20.0f)).apply() }

    // ── Native OpenXR tuning ────────────────────────────────────────────
    // 0 = auto (highest supported); otherwise target Hz (72/90/120). Clamped
    // against the actual supported-rates list at apply time.
    var displayRefreshRate: Int
        get() { ensureInit(); return prefs.getInt(KEY_DISPLAY_REFRESH_RATE, 0) }
        set(value) { ensureInit(); prefs.edit().putInt(KEY_DISPLAY_REFRESH_RATE, value).apply() }

    // Enable eye-tracked foveation when supported. Falls back to the static
    // FB foveation cone if the runtime/hardware does not report support.
    var eyeTrackedFoveation: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_EYE_TRACKED_FOVEATION, true) }
        set(value) { ensureInit(); prefs.edit().putBoolean(KEY_EYE_TRACKED_FOVEATION, value).apply() }

    // Auto-downgrade effects / refresh / resolution when XR_EXT_performance_settings
    // reports a thermal warning. Users who want deterministic perf can disable.
    var thermalAutoDowngrade: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_THERMAL_AUTO_DOWNGRADE, true) }
        set(value) { ensureInit(); prefs.edit().putBoolean(KEY_THERMAL_AUTO_DOWNGRADE, value).apply() }

    // Galaxy XR: while Auto Light is on, also steer the key light's DIRECTION from
    // XR_ANDROID_light_estimation so shadows align with the real room. Manual
    // azimuth/elevation edits flip this off (same contract as ambient/autoAmbient).
    var followRoomLight: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_FOLLOW_ROOM_LIGHT, true) }
        set(value) { ensureInit(); prefs.edit().putBoolean(KEY_FOLLOW_ROOM_LIGHT, value).apply() }

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
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_R, 0f).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_R, v.coerceIn(0.0f, 1.0f)).apply() }

    var chromaKeyG: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_G, 1f).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_G, v.coerceIn(0.0f, 1.0f)).apply() }

    var chromaKeyB: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_KEY_B, 0f).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_KEY_B, v.coerceIn(0.0f, 1.0f)).apply() }

    var chromaTolerance: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_TOLERANCE, 0.30f).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_TOLERANCE, v.coerceIn(0.0f, 1.0f)).apply() }

    var chromaSoftness: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CHROMA_SOFTNESS, 0.10f).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CHROMA_SOFTNESS, v.coerceIn(0.0f, 1.0f)).apply() }

    var chromaEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_CHROMA_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_CHROMA_ENABLED, v).apply() }

    // ── Color grading settings ──────────────────────────────────────────

    var cgBrightness: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_BRIGHTNESS, DEFAULT_CG_BRIGHTNESS).coerceIn(-1.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_BRIGHTNESS, v.coerceIn(-1.0f, 1.0f)).apply() }

    var cgContrast: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_CONTRAST, DEFAULT_CG_CONTRAST).coerceIn(0.0f, 4.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_CONTRAST, v.coerceIn(0.0f, 4.0f)).apply() }

    var cgSaturation: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_SATURATION, DEFAULT_CG_SATURATION).coerceIn(0.0f, 4.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_SATURATION, v.coerceIn(0.0f, 4.0f)).apply() }

    var cgSharpening: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_SHARPENING, DEFAULT_CG_SHARPENING).coerceIn(0.0f, 2.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_SHARPENING, v.coerceIn(0.0f, 2.0f)).apply() }

    var cgGamma: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_GAMMA, DEFAULT_CG_GAMMA).coerceIn(0.1f, 5.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_GAMMA, v.coerceIn(0.1f, 5.0f)).apply() }

    var cgHueShift: Float
        get() { ensureInit(); return prefs.getFloat(KEY_CG_HUE_SHIFT, DEFAULT_CG_HUE_SHIFT).coerceIn(-180.0f, 180.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_CG_HUE_SHIFT, v.coerceIn(-180.0f, 180.0f)).apply() }

    var cgToneMapMode: Int
        get() { ensureInit(); return prefs.getInt(KEY_CG_TONE_MAP_MODE, DEFAULT_CG_TONE_MAP_MODE).coerceIn(0, 2) }
        set(v) { ensureInit(); prefs.edit().putInt(KEY_CG_TONE_MAP_MODE, v.coerceIn(0, 2)).apply() }

    var cgEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_CG_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_CG_ENABLED, v).apply() }

    // ── Lens distortion settings ────────────────────────────────────────

    var lensPreset: String
        get() { ensureInit(); return prefs.getString(KEY_LENS_PRESET, "none") ?: "none" }
        set(v) { ensureInit(); prefs.edit().putString(KEY_LENS_PRESET, v).apply() }

    var lensK1: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_K1, DEFAULT_LENS_K1).coerceIn(-2.0f, 2.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_K1, v.coerceIn(-2.0f, 2.0f)).apply() }

    var lensK2: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_K2, DEFAULT_LENS_K2).coerceIn(-2.0f, 2.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_K2, v.coerceIn(-2.0f, 2.0f)).apply() }

    var lensFov: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_FOV, DEFAULT_LENS_FOV).coerceIn(10.0f, 360.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_FOV, v.coerceIn(10.0f, 360.0f)).apply() }

    var lensCx: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_CX, DEFAULT_LENS_CX).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_CX, v.coerceIn(0.0f, 1.0f)).apply() }

    var lensCy: Float
        get() { ensureInit(); return prefs.getFloat(KEY_LENS_CY, DEFAULT_LENS_CY).coerceIn(0.0f, 1.0f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_LENS_CY, v.coerceIn(0.0f, 1.0f)).apply() }

    var lensEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_LENS_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_LENS_ENABLED, v).apply() }

    // ── IPD / stereo adjustment ─────────────────────────────────────────

    var ipdOffset: Float
        get() { ensureInit(); return prefs.getFloat(KEY_IPD_OFFSET, DEFAULT_IPD_OFFSET).coerceIn(-0.05f, 0.05f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_IPD_OFFSET, v.coerceIn(-0.05f, 0.05f)).apply() }

    var stereoVerticalOffset: Float
        get() { ensureInit(); return prefs.getFloat(KEY_STEREO_VERTICAL_OFFSET, DEFAULT_STEREO_VERTICAL_OFFSET).coerceIn(-0.05f, 0.05f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_STEREO_VERTICAL_OFFSET, v.coerceIn(-0.05f, 0.05f)).apply() }

    var stereoEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_STEREO_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_STEREO_ENABLED, v).apply() }

    // ── Depth simulation / spatial audio ────────────────────────────────

    var depthSimEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_DEPTH_SIM_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_DEPTH_SIM_ENABLED, v).apply() }

    var spatialAudioEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_SPATIAL_AUDIO_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_SPATIAL_AUDIO_ENABLED, v).apply() }

    // ── Real-world scene occlusion ───────────────────────────────────────
    // When enabled, detected room planes (walls/furniture) write into the
    // depth buffer before placed 3D models render, so the passthrough
    // camera shows through parts of the model that are behind real surfaces.

    var occlusionEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_OCCLUSION_ENABLED, true) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_OCCLUSION_ENABLED, v).apply() }

    // ── Persistent spatial anchors ──────────────────────────────────────
    // True by default: placed 3D models auto-anchor to the user's real-world space via
    // XR_EXT_spatial_entity so they reappear in the same physical spot next session.
    // Users who turn this off fall back to session-local poses (current legacy behavior).
    var useSpatialAnchors: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_USE_SPATIAL_ANCHORS, true) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_USE_SPATIAL_ANCHORS, v).apply() }

    // ── Favorite folders ────────────────────────────────────────────────
    // Stored as newline-separated paths

    fun getFavoriteFolders(): List<String> {
        ensureInit()
        val raw = prefs.getString(KEY_FAVORITE_FOLDERS, "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }.map {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }
    }

    fun addFavoriteFolder(path: String) {
        ensureInit()
        val current = getFavoriteFolders().toMutableList()
        if (path !in current) {
            current.add(path)
            prefs.edit().putString(KEY_FAVORITE_FOLDERS, current.joinToString("\n") {
                URLEncoder.encode(it, "UTF-8")
            }).apply()
        }
    }

    fun removeFavoriteFolder(path: String) {
        ensureInit()
        val current = getFavoriteFolders().toMutableList()
        current.remove(path)
        prefs.edit().putString(KEY_FAVORITE_FOLDERS, current.joinToString("\n") {
            URLEncoder.encode(it, "UTF-8")
        }).apply()
    }

    fun isFavoriteFolder(path: String): Boolean = path in getFavoriteFolders()

    // ── Chloe Vibes haptic engine ───────────────────────────────────────

    var vibesEngineEnabled: Boolean
        get() { ensureInit(); return prefs.getBoolean(KEY_VIBES_ENABLED, false) }
        set(v) { ensureInit(); prefs.edit().putBoolean(KEY_VIBES_ENABLED, v).apply() }

    /** Preset name: "", "Loose", "Medium", or "Ultimate". Empty = custom tuning. */
    var vibesPresetName: String
        get() { ensureInit(); return prefs.getString(KEY_VIBES_PRESET, "Medium") ?: "Medium" }
        set(v) { ensureInit(); prefs.edit().putString(KEY_VIBES_PRESET, v).apply() }

    var vibesThresholdKnee: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_KNEE, 0.22f).coerceIn(0f, 0.45f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_KNEE, v.coerceIn(0f, 0.45f)).apply() }

    var vibesDynamicCurve: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_DYN_CURVE, 1.35f).coerceIn(0.35f, 2.5f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_DYN_CURVE, v.coerceIn(0.35f, 2.5f)).apply() }

    var vibesGateThreshold: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_GATE_THRESH, 0.17f).coerceIn(0f, 1f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_GATE_THRESH, v.coerceIn(0f, 1f)).apply() }

    var vibesAttackMs: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_ATTACK_MS, 4.5f).coerceIn(0.5f, 200f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_ATTACK_MS, v.coerceIn(0.5f, 200f)).apply() }

    var vibesReleaseMs: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_RELEASE_MS, 95f).coerceIn(10f, 2000f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_RELEASE_MS, v.coerceIn(10f, 2000f)).apply() }

    var vibesOutputGain: Float
        get() { ensureInit(); return prefs.getFloat(KEY_VIBES_OUTPUT_GAIN, 1.45f).coerceIn(0.1f, 4f) }
        set(v) { ensureInit(); prefs.edit().putFloat(KEY_VIBES_OUTPUT_GAIN, v.coerceIn(0.1f, 4f)).apply() }

    // ── Haptic script playback mode ─────────────────────────────────────

    var hapticScriptMode: String
        get() { ensureInit(); return prefs.getString(KEY_HAPTIC_SCRIPT_MODE, "Reactive") ?: "Reactive" }
        set(v) { ensureInit(); prefs.edit().putString(KEY_HAPTIC_SCRIPT_MODE, v).apply() }

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
            // Sanitize name: "|" is the field delimiter, ";;" is the entry delimiter
            val safeName = it.name.replace('|', '-').replace(";;", "--")
            "$safeName|${it.brightness}|${it.contrast}|${it.saturation}|${it.sharpening}|${it.gamma}|${it.hueShift}|${it.toneMapMode}"
        }
        prefs.edit().putString(KEY_CG_PRESETS, serialized).apply()
    }

    fun deleteColorGradingPreset(name: String) {
        ensureInit()
        val list = getColorGradingPresets().filterNot { it.name == name }
        val serialized = list.joinToString(";;") {
            val safeName = it.name.replace('|', '-').replace(";;", "--")
            "$safeName|${it.brightness}|${it.contrast}|${it.saturation}|${it.sharpening}|${it.gamma}|${it.hueShift}|${it.toneMapMode}"
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
