package com.ashairfoil.prism.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * GazeController — Eye tracking integration for Samsung Galaxy XR.
 *
 * Uses the XR_ANDROID_eye_tracking OpenXR extension to get gaze direction,
 * enabling:
 * 1. Autofocus — adjust stereo convergence to where the user is looking
 * 2. Gaze-based UI — highlight menu items by looking at them
 * 3. Foveated rendering hints — tell the system where detail matters most
 *
 * The Galaxy XR supports both coarse (general direction) and fine (precise point)
 * eye tracking. Fine tracking requires explicit user permission.
 *
 * Implementation approach:
 * - Eye tracking is polled via the native OpenXR layer (openxr_input.cpp)
 * - Gaze direction is returned as a normalized 3D vector in head-relative space
 * - The gaze point is projected onto the video surface to get UV coordinates
 * - UV coordinates drive the autofocus convergence point
 *
 * Permissions required:
 * - com.google.android.xr.permission.EYE_TRACKING_FINE (for precise gaze)
 * - com.google.android.xr.permission.EYE_TRACKING_COARSE (for general direction)
 *
 * Usage:
 *   val gaze = GazeController(context)
 *   if (gaze.isAvailable) {
 *       gaze.startTracking()
 *       // In render loop:
 *       val point = gaze.gazePoint // normalized UV (0-1, 0-1)
 *       // Use point for autofocus convergence
 *   }
 */
class GazeController(private val context: Context) {

    companion object {
        private const val TAG = "GazeController"
        private const val PERMISSION_FINE = "com.google.android.xr.permission.EYE_TRACKING_FINE"
        private const val PERMISSION_COARSE = "com.google.android.xr.permission.EYE_TRACKING_COARSE"
    }

    // Gaze state (updated from native layer)
    data class GazeState(
        val isValid: Boolean = false,
        val directionX: Float = 0f,  // Head-relative gaze direction
        val directionY: Float = 0f,
        val directionZ: Float = -1f, // Forward
        val uvX: Float = 0.5f,      // Projected onto video surface (0-1)
        val uvY: Float = 0.5f,
        val confidence: Float = 0f   // 0-1 tracking confidence
    )

    @Volatile var gazeState: GazeState = GazeState()
        private set

    @Volatile var isTracking: Boolean = false
        private set

    // Autofocus
    @Volatile var autofocusEnabled: Boolean = false
    @Volatile var autofocusStrength: Float = 0.5f  // How aggressively to shift convergence

    // Smoothing
    @Volatile private var smoothUvX: Float = 0.5f
    @Volatile private var smoothUvY: Float = 0.5f
    private val smoothFactor: Float = 0.1f // Low-pass filter for gaze stability

    /**
     * Check if eye tracking hardware is available AND permissions are granted.
     */
    val isAvailable: Boolean
        get() {
            val hasFine = ContextCompat.checkSelfPermission(context, PERMISSION_FINE) ==
                    PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, PERMISSION_COARSE) ==
                    PackageManager.PERMISSION_GRANTED
            return hasFine || hasCoarse
        }

    /**
     * Check if we need to request permissions.
     */
    val needsPermission: Boolean
        get() {
            return ContextCompat.checkSelfPermission(context, PERMISSION_FINE) !=
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, PERMISSION_COARSE) !=
                    PackageManager.PERMISSION_GRANTED
        }

    /**
     * Get the permissions to request.
     */
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(PERMISSION_FINE, PERMISSION_COARSE)
    }

    /**
     * Start eye tracking. Must have permissions granted first.
     * The native layer (openxr_input.cpp) needs to be extended to support
     * XR_ANDROID_eye_tracking — this controller handles the Kotlin side.
     */
    fun startTracking() {
        if (!isAvailable) {
            Log.w(TAG, "Eye tracking not available or permissions not granted")
            return
        }

        // Native eye tracking (XR_ANDROID_eye_tracking) not yet wired — see architecture gaps.
        isTracking = true
        Log.i(TAG, "Eye tracking started")
    }

    fun stopTracking() {
        isTracking = false
        // Native eye tracking (XR_ANDROID_eye_tracking) not yet wired — see architecture gaps.
        Log.i(TAG, "Eye tracking stopped")
    }

    /**
     * Update gaze state from native polling data.
     * Called from the OpenXRInput polling loop.
     *
     * @param dirX Gaze direction X (head-relative, right is positive)
     * @param dirY Gaze direction Y (head-relative, up is positive)
     * @param dirZ Gaze direction Z (head-relative, forward is negative)
     * @param confidence Tracking confidence 0-1
     */
    fun updateFromNative(dirX: Float, dirY: Float, dirZ: Float, confidence: Float) {
        if (!isTracking) return

        // Project gaze direction onto a unit hemisphere/sphere to get UV coordinates
        // For VR180: gaze maps to the front hemisphere
        // dirX maps to horizontal (left-right), dirY maps to vertical (up-down)
        // Assuming forward is -Z, we normalize X and Y to 0-1 range
        val uvX = (dirX / (-dirZ).coerceAtLeast(0.01f) + 1f) * 0.5f
        val uvY = (-dirY / (-dirZ).coerceAtLeast(0.01f) + 1f) * 0.5f

        // Smooth the gaze point to reduce saccade jitter
        smoothUvX += (uvX.coerceIn(0f, 1f) - smoothUvX) * smoothFactor
        smoothUvY += (uvY.coerceIn(0f, 1f) - smoothUvY) * smoothFactor

        gazeState = GazeState(
            isValid = confidence > 0.3f,
            directionX = dirX,
            directionY = dirY,
            directionZ = dirZ,
            uvX = smoothUvX,
            uvY = smoothUvY,
            confidence = confidence
        )
    }

    /**
     * Get the smoothed gaze UV point on the video surface.
     * Returns (0.5, 0.5) if tracking is not active or not confident.
     */
    val gazePoint: Pair<Float, Float>
        get() {
            if (!isTracking || !gazeState.isValid) return Pair(0.5f, 0.5f)
            return Pair(gazeState.uvX, gazeState.uvY)
        }

    /**
     * Compute stereo convergence offset for autofocus.
     * Positive offset = converge closer (for near objects).
     * The autofocus adjusts the horizontal stereo shift so that
     * objects at the gaze point have zero disparity (comfortable viewing).
     *
     * @param currentConvergence Current convergence distance (arbitrary units)
     * @return Suggested convergence adjustment
     */
    fun computeAutofocusAdjustment(currentConvergence: Float): Float {
        if (!autofocusEnabled || !isTracking || !gazeState.isValid) return 0f

        // Gaze position relative to center determines depth hint:
        // - Center gaze → standard convergence
        // - Edge gaze → often means looking at background (farther convergence)
        // This is a simplified heuristic; DeoVR/HereSphere use actual depth estimation
        val distFromCenter = Math.sqrt(
            ((gazeState.uvX - 0.5f) * (gazeState.uvX - 0.5f) +
            (gazeState.uvY - 0.5f) * (gazeState.uvY - 0.5f)).toDouble()
        ).toFloat()

        // Center = closer convergence, edges = farther
        val targetOffset = (0.5f - distFromCenter) * autofocusStrength * 0.02f

        return targetOffset
    }
}
