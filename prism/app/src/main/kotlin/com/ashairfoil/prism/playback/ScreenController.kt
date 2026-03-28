package com.ashairfoil.prism.playback

import android.util.Log

/**
 * ScreenController — Manages the VR screen's position, rotation, scale, and projection.
 *
 * Consolidates all the screen manipulation logic that was scattered through MainActivity:
 * - Position (x, y, z) in XR space
 * - Rotation (yaw, pitch, roll) in degrees
 * - Scale (uniform)
 * - Zoom (multiplicative on scale)
 * - Tilt lock / unlock
 * - Recenter to default position
 * - Per-projection-type default positions
 *
 * All values can be saved/loaded via SettingsManager per-video or per-projection.
 */
class ScreenController {

    companion object {
        private const val TAG = "ScreenController"

        // Default positions per projection type
        val DEFAULTS = mapOf(
            "flat" to ScreenState(z = -4.0f, scale = 1.0f),
            "180" to ScreenState(z = 0f, scale = 1.0f),
            "360" to ScreenState(z = 0f, scale = 1.0f),
            "fisheye" to ScreenState(z = 0f, scale = 1.0f),
        )
    }

    data class ScreenState(
        var x: Float = 0f,
        var y: Float = 0f,
        var z: Float = -4.0f,
        var yaw: Float = 0f,
        var pitch: Float = 0f,
        var roll: Float = 0f,
        var scale: Float = 1.0f,
        var zoom: Float = 1.0f,
    ) {
        val effectiveScale: Float get() = scale * zoom

        fun reset(projection: String = "flat") {
            val def = DEFAULTS[projection] ?: DEFAULTS["flat"]!!
            x = def.x; y = def.y; z = def.z
            yaw = def.yaw; pitch = def.pitch; roll = def.roll
            scale = def.scale; zoom = 1.0f
        }

        fun toFloatArray(): FloatArray = floatArrayOf(x, y, z, yaw, pitch, roll, scale, zoom)

        companion object {
            fun fromFloatArray(a: FloatArray): ScreenState {
                if (a.size < 8) return ScreenState()
                return ScreenState(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7])
            }
        }
    }

    var state = ScreenState()
        private set

    var projection: String = "flat"
        private set

    var tiltLocked: Boolean = false
    var heightLocked: Boolean = false

    // Smoothing for grab operations
    private val smoothFactor = 0.3f

    /**
     * Set projection type and reset to appropriate defaults.
     */
    fun setProjection(proj: String) {
        projection = proj
        state.reset(proj)
        Log.d(TAG, "Projection set to $proj, state reset to defaults")
    }

    /**
     * Apply saved state (from SettingsManager).
     */
    fun loadState(saved: ScreenState) {
        state = saved.copy()
    }

    /**
     * Adjust position by delta (from controller grab).
     */
    fun moveBy(dx: Float, dy: Float, dz: Float) {
        state.x += dx * smoothFactor
        if (!heightLocked) state.y += dy * smoothFactor
        state.z += dz * smoothFactor
        // Clamp to reasonable range
        state.x = state.x.coerceIn(-10f, 10f)
        state.y = state.y.coerceIn(-5f, 5f)
        state.z = state.z.coerceIn(-20f, 2f)
    }

    /**
     * Adjust rotation by delta (from controller grab).
     */
    fun rotateBy(dyaw: Float, dpitch: Float, droll: Float) {
        state.yaw += dyaw * smoothFactor
        if (!tiltLocked) state.pitch += dpitch * smoothFactor
        state.roll += droll * smoothFactor
        // Normalize yaw to -180..180
        state.yaw = (state.yaw % 360f).let { y ->
            when {
                y > 180f -> y - 360f
                y < -180f -> y + 360f
                else -> y
            }
        }
        state.pitch = state.pitch.coerceIn(-90f, 90f)
        state.roll = state.roll.coerceIn(-45f, 45f)
    }

    /**
     * Set zoom level (from trigger or thumbstick).
     */
    fun setZoom(zoom: Float) {
        state.zoom = zoom.coerceIn(0.3f, 5.0f)
    }

    fun zoomBy(delta: Float) {
        state.zoom = (state.zoom + delta).coerceIn(0.3f, 5.0f)
    }

    /**
     * Set height offset directly.
     */
    fun setHeight(height: Float) {
        state.y = height.coerceIn(-3f, 3f)
    }

    /**
     * Set tilt directly.
     */
    fun setTilt(tilt: Float) {
        state.pitch = tilt.coerceIn(-90f, 90f)
    }

    /**
     * Recenter screen to default position for current projection.
     */
    fun recenter() {
        state.reset(projection)
        Log.d(TAG, "Screen recentered for projection $projection")
    }

    /**
     * Get Euler angles as radians (for Jetpack XR SDK Pose).
     */
    fun getYawRadians(): Float = Math.toRadians(state.yaw.toDouble()).toFloat()
    fun getPitchRadians(): Float = Math.toRadians(state.pitch.toDouble()).toFloat()
    fun getRollRadians(): Float = Math.toRadians(state.roll.toDouble()).toFloat()

    /**
     * Check if position is at default (for "modified" indicator).
     */
    fun isModified(): Boolean {
        val def = DEFAULTS[projection] ?: DEFAULTS["flat"]!!
        return state.x != def.x || state.y != def.y || state.z != def.z ||
               state.yaw != 0f || state.pitch != 0f || state.roll != 0f ||
               state.scale != def.scale || state.zoom != 1.0f
    }
}
