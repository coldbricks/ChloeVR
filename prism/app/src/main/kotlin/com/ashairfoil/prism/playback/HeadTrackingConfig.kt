package com.ashairfoil.prism.playback

/**
 * HeadTrackingConfig — Per-axis head tracking control.
 *
 * DeoVR allows enabling/disabling head tracking on each axis independently.
 * This is important for comfort — some users prefer locked pitch (no head tilt tracking)
 * or locked yaw (for seated viewing where they always face forward).
 *
 * Also controls the maximum tracking range per axis (DeoVR uses 0.4 max).
 *
 * For 6DOF headsets like Galaxy XR, position tracking can also be toggled per axis.
 */
class HeadTrackingConfig {

    // Rotation tracking per axis
    @Volatile var yawEnabled: Boolean = true    // Left/right look
    @Volatile var pitchEnabled: Boolean = true  // Up/down look
    @Volatile var rollEnabled: Boolean = false   // Head tilt (off by default — causes discomfort)

    // Position tracking per axis (6DOF)
    @Volatile var posXEnabled: Boolean = true   // Lateral movement
    @Volatile var posYEnabled: Boolean = true   // Vertical movement
    @Volatile var posZEnabled: Boolean = true   // Forward/back movement

    // Maximum tracking range (0.0 to 1.0, DeoVR uses max 0.4)
    @Volatile var maxYaw: Float = 1.0f          // Full range by default
    @Volatile var maxPitch: Float = 1.0f
    @Volatile var maxRoll: Float = 0.3f
    @Volatile var maxPosX: Float = 0.4f
    @Volatile var maxPosY: Float = 0.4f
    @Volatile var maxPosZ: Float = 0.4f

    /**
     * Apply tracking config to raw rotation values.
     * Returns filtered rotation (yaw, pitch, roll) in radians.
     */
    fun filterRotation(yaw: Float, pitch: Float, roll: Float): Triple<Float, Float, Float> {
        // maxYaw/maxPitch/maxRoll are 0-1 scalars.
        // Input is radians: yaw ±π, pitch ±π/2, roll ±π/4.
        // Clamp range = max * full_range (e.g. maxYaw=0.5 → ±π/2).
        val pi = Math.PI.toFloat()
        return Triple(
            if (yawEnabled) yaw.coerceIn(-maxYaw * pi, maxYaw * pi) else 0f,
            if (pitchEnabled) pitch.coerceIn(-maxPitch * pi / 2f, maxPitch * pi / 2f) else 0f,
            if (rollEnabled) roll.coerceIn(-maxRoll * pi / 4f, maxRoll * pi / 4f) else 0f,
        )
    }

    /**
     * Apply tracking config to raw position values.
     * Returns filtered position (x, y, z) in meters.
     */
    fun filterPosition(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        return Triple(
            if (posXEnabled) x.coerceIn(-maxPosX, maxPosX) else 0f,
            if (posYEnabled) y.coerceIn(-maxPosY, maxPosY) else 0f,
            if (posZEnabled) z.coerceIn(-maxPosZ, maxPosZ) else 0f,
        )
    }

    /**
     * Reset to defaults.
     */
    fun reset() {
        yawEnabled = true; pitchEnabled = true; rollEnabled = false
        posXEnabled = true; posYEnabled = true; posZEnabled = true
        maxYaw = 1.0f; maxPitch = 1.0f; maxRoll = 0.3f
        maxPosX = 0.4f; maxPosY = 0.4f; maxPosZ = 0.4f
    }

    /**
     * Comfort preset: minimal tracking for seated/stationary viewing.
     */
    fun applyComfortPreset() {
        yawEnabled = true; pitchEnabled = false; rollEnabled = false
        posXEnabled = false; posYEnabled = false; posZEnabled = false
        maxYaw = 0.5f
    }

    /**
     * Full tracking preset: everything enabled for roomscale.
     */
    fun applyFullPreset() {
        yawEnabled = true; pitchEnabled = true; rollEnabled = true
        posXEnabled = true; posYEnabled = true; posZEnabled = true
        maxYaw = 1.0f; maxPitch = 1.0f; maxRoll = 0.3f
        maxPosX = 0.4f; maxPosY = 0.4f; maxPosZ = 0.4f
    }
}
