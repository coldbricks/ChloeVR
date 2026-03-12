package com.ashairfoil.prism.effects

/**
 * DepthSimulationEffect — 6DOF Depth Simulation for VR video.
 *
 * DeoVR's signature feature: when the viewer moves their head (6DOF tracking),
 * the video projection shifts slightly to simulate parallax depth. This makes
 * the subjects in the video feel more "rooted" in space instead of stuck to
 * a sphere.
 *
 * How it works:
 * 1. Track the head position delta (x, y, z) from the initial viewing position
 * 2. Map the position delta to a UV offset in the video texture
 * 3. Apply the UV shift as a fragment shader effect
 *
 * The X/Y/Z sensitivity controls how much head movement maps to UV shift:
 * - X (lateral): moving left/right shifts the view horizontally
 * - Y (vertical): moving up/down shifts the view vertically
 * - Z (forward/back): moving forward zooms in slightly
 *
 * DeoVR uses a range of 0 to 0.40 for each axis.
 *
 * For ChloeVR, this is implemented differently than DeoVR — we adjust the
 * SurfaceEntity's pose rather than UV-shifting. This gives actual 3D parallax
 * via the Jetpack XR SDK's spatial rendering.
 *
 * Usage in MainActivity:
 *   val depth = DepthSimulation()
 *   // In the controller/tracking loop:
 *   val headDelta = currentHeadPos - initialHeadPos
 *   val adjustment = depth.compute(headDelta)
 *   surfaceEntity.setPose(basePose.translate(adjustment))
 */
class DepthSimulation {

    // Sensitivity per axis (0.0 to 0.4, matching DeoVR)
    @Volatile var sensitivityX: Float = 0.20f
    @Volatile var sensitivityY: Float = 0.10f
    @Volatile var sensitivityZ: Float = 0.20f
    @Volatile var enabled: Boolean = false

    // Head tracking reference point (set when video starts or recenter is pressed)
    private var refX: Float = 0f
    private var refY: Float = 0f
    private var refZ: Float = 0f
    private var isCalibrated: Boolean = false

    // Smoothed output
    private var smoothX: Float = 0f
    private var smoothY: Float = 0f
    private var smoothZ: Float = 0f
    private val smoothFactor = 0.15f // Low-pass filter (higher = more responsive)

    data class DepthAdjustment(
        val offsetX: Float,  // Horizontal shift (meters in XR space)
        val offsetY: Float,  // Vertical shift
        val scaleZ: Float    // Zoom factor (1.0 = no change)
    )

    /**
     * Set the reference head position (call on video start or recenter).
     */
    fun calibrate(headX: Float, headY: Float, headZ: Float) {
        refX = headX
        refY = headY
        refZ = headZ
        isCalibrated = true
        smoothX = 0f
        smoothY = 0f
        smoothZ = 0f
    }

    /**
     * Compute the depth-adjusted position offset based on current head position.
     * Returns the SurfaceEntity translation adjustment in XR space.
     */
    fun compute(headX: Float, headY: Float, headZ: Float): DepthAdjustment {
        if (!enabled || !isCalibrated) {
            return DepthAdjustment(0f, 0f, 1f)
        }

        // Raw delta from reference
        val deltaX = headX - refX
        val deltaY = headY - refY
        val deltaZ = headZ - refZ

        // Apply sensitivity (0.0-0.4 range → maps head movement to scene shift)
        // Invert X so moving left shifts view right (parallax)
        val targetX = -deltaX * sensitivityX * 2.5f
        val targetY = -deltaY * sensitivityY * 2.5f
        val targetZ = 1.0f + deltaZ * sensitivityZ * 0.5f // Forward = slight zoom in

        // Smooth (low-pass filter to prevent jitter)
        smoothX += (targetX - smoothX) * smoothFactor
        smoothY += (targetY - smoothY) * smoothFactor
        smoothZ += (targetZ - smoothZ) * smoothFactor

        // Clamp to reasonable range
        return DepthAdjustment(
            offsetX = smoothX.coerceIn(-0.5f, 0.5f),
            offsetY = smoothY.coerceIn(-0.3f, 0.3f),
            scaleZ = smoothZ.coerceIn(0.8f, 1.3f)
        )
    }

    /**
     * Reset calibration (forces recalibration on next compute).
     */
    fun reset() {
        isCalibrated = false
        smoothX = 0f
        smoothY = 0f
        smoothZ = 0f
    }
}
