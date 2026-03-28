package com.ashairfoil.prism.scene

import android.util.Log
import com.ashairfoil.prism.GlesModelRenderer
import com.ashairfoil.prism.SensorHub

/**
 * Polls OpenXR sensor extensions (hand/eye/face tracking, planes, perf metrics,
 * passthrough) and builds a debug HUD string. Decoupled from the Activity via
 * function references for the JNI calls.
 */
class XrSensorPoller(
    private val pollHandTracking: (hand: Int, outData: FloatArray) -> Boolean,
    private val pollEyeTracking: (outData: FloatArray) -> Boolean,
    private val pollFaceTracking: (outData: FloatArray) -> Boolean,
    private val pollPlanes: (outData: FloatArray) -> Boolean,
    private val pollPerfMetrics: (outData: FloatArray) -> Boolean,
    private val getSensorCaps: () -> Int,
    private val getPassthroughState: () -> Int
) {
    companion object {
        private const val TAG = "XrSensorPoller"
    }

    // ── XR Sensor buffers ──
    val handTrackingBufferL = FloatArray(209) // 1 + 26x8
    val handTrackingBufferR = FloatArray(209)
    val eyeTrackingBuffer = FloatArray(25)
    val faceTrackingBuffer = FloatArray(69) // 1 + 68
    val planeBuffer = FloatArray(4866) // 2 + 64x76
    private val perfMetricsBuffer = FloatArray(5)

    // ── Tracking toggles (disabled by default for performance) ──
    @Volatile var handTrackingEnabled = false
    @Volatile var eyeTrackingEnabled = false
    @Volatile var faceTrackingEnabled = false
    @Volatile var planeDetectionEnabled = false

    // Pre-allocated plane list to avoid allocation every 10 frames
    private val reusablePlanes = ArrayList<GlesModelRenderer.ShadowPlane>(32)

    // ── XR Sensor state ──
    @Volatile var xrSensorCaps = -1; private set
    @Volatile var handTrackingActive = booleanArrayOf(false, false); private set
    @Volatile var eyeTrackingActive = false; private set
    @Volatile var faceTrackingActive = false; private set
    @Volatile var detectedPlaneCount = 0
    @Volatile var gpuFrameTimeMs = 0f; private set
    @Volatile var cpuFrameTimeMs = 0f; private set
    @Volatile var displayRefreshRate = 0f; private set
    @Volatile var droppedFrames = 0f; private set
    @Volatile var passthroughState = 0; private set

    // Combined eye gaze (for gaze cursor)
    @Volatile var gazeOriginX = 0f; private set
    @Volatile var gazeOriginY = 0f; private set
    @Volatile var gazeOriginZ = 0f; private set
    @Volatile var gazeRotX = 0f; private set
    @Volatile var gazeRotY = 0f; private set
    @Volatile var gazeRotZ = 0f; private set
    @Volatile var gazeRotW = 1f; private set

    /**
     * Callback for plane-detection results that need renderer access.
     * Parameters: shadowPlanes list, lowestHorizY (or MAX_VALUE if none found).
     */
    var onPlanesUpdated: ((planes: List<GlesModelRenderer.ShadowPlane>, lowestHorizY: Float) -> Unit)? = null

    /**
     * Poll all XR sensors. Call from the render loop (typically every few frames).
     * [frameCount] is the monotonically increasing sensor-poll frame counter.
     */
    fun poll(frameCount: Int) {
        // Get capabilities once (-1 = not queried, 0 = no caps is valid)
        if (xrSensorCaps < 0) {
            xrSensorCaps = getSensorCaps()
            Log.i(TAG, "XR Sensor capabilities: 0x${xrSensorCaps.toString(16)}")
            if (xrSensorCaps and (1 shl 0) != 0) Log.i(TAG, "  + Hand tracking")
            if (xrSensorCaps and (1 shl 1) != 0) Log.i(TAG, "  + Eye tracking")
            if (xrSensorCaps and (1 shl 2) != 0) Log.i(TAG, "  + Face tracking")
            if (xrSensorCaps and (1 shl 3) != 0) Log.i(TAG, "  + Plane detection")
            if (xrSensorCaps and (1 shl 4) != 0) Log.i(TAG, "  + Refresh rate control")
            if (xrSensorCaps and (1 shl 5) != 0) Log.i(TAG, "  + Performance metrics")
            if (xrSensorCaps and (1 shl 6) != 0) Log.i(TAG, "  + Passthrough state")
        }

        // Hand tracking (both hands) — disabled by default for performance
        if (handTrackingEnabled && xrSensorCaps and (1 shl 0) != 0) {
            handTrackingActive[0] = pollHandTracking(0, handTrackingBufferL) &&
                    handTrackingBufferL[0] > 0.5f
            handTrackingActive[1] = pollHandTracking(1, handTrackingBufferR) &&
                    handTrackingBufferR[0] > 0.5f
        }

        // Eye tracking — disabled by default for performance
        if (eyeTrackingEnabled && xrSensorCaps and (1 shl 1) != 0) {
            if (pollEyeTracking(eyeTrackingBuffer) && eyeTrackingBuffer[0] > 0.5f) {
                eyeTrackingActive = true
                // Combined gaze: indices 17-24
                if (eyeTrackingBuffer[17] > 0.5f) {
                    gazeOriginX = eyeTrackingBuffer[18]; gazeOriginY = eyeTrackingBuffer[19]; gazeOriginZ = eyeTrackingBuffer[20]
                    gazeRotX = eyeTrackingBuffer[21]; gazeRotY = eyeTrackingBuffer[22]
                    gazeRotZ = eyeTrackingBuffer[23]; gazeRotW = eyeTrackingBuffer[24]
                }
            } else {
                eyeTrackingActive = false
            }
        }

        // Face tracking — disabled by default for performance
        if (faceTrackingEnabled && xrSensorCaps and (1 shl 2) != 0) {
            faceTrackingActive = pollFaceTracking(faceTrackingBuffer) &&
                    faceTrackingBuffer[0] > 0.5f
        }

        // Plane detection (every 10 frames) — disabled by default
        if (planeDetectionEnabled && xrSensorCaps and (1 shl 3) != 0 && frameCount % 10 == 0) {
            if (pollPlanes(planeBuffer) && planeBuffer[0] > 0.5f) {
                detectedPlaneCount = planeBuffer[1].toInt()

                // Parse planes and find lowest horizontal surface
                if (detectedPlaneCount > 0) {
                    reusablePlanes.clear()
                    val floatsPerPlane = 76 // 10 + 1 + 1 + 32*2
                    for (i in 0 until minOf(detectedPlaneCount, 64)) {
                        val off = 2 + i * floatsPerPlane
                        reusablePlanes.add(GlesModelRenderer.ShadowPlane(
                            posX = planeBuffer[off], posY = planeBuffer[off+1], posZ = planeBuffer[off+2],
                            rotX = planeBuffer[off+3], rotY = planeBuffer[off+4],
                            rotZ = planeBuffer[off+5], rotW = planeBuffer[off+6],
                            extentX = planeBuffer[off+7], extentY = planeBuffer[off+8],
                            label = planeBuffer[off+9].toInt()
                        ))
                    }

                    // Find lowest horizontal surface
                    var lowestHorizY = Float.MAX_VALUE
                    for (i in 0 until minOf(detectedPlaneCount, 64)) {
                        val off = 2 + i * floatsPerPlane
                        val posY = planeBuffer[off + 1]
                        val qx = planeBuffer[off + 3]; val qy = planeBuffer[off + 4]
                        val qz = planeBuffer[off + 5]; val qw = planeBuffer[off + 6]
                        val normalY = 1f - 2f * (qx * qx + qz * qz)
                        if (normalY > 0.7f && posY < lowestHorizY) {
                            lowestHorizY = posY
                        }
                    }

                    onPlanesUpdated?.invoke(ArrayList(reusablePlanes), lowestHorizY)
                }
            }
        }

        // Performance metrics (every 15 frames)
        if (xrSensorCaps and (1 shl 5) != 0 && frameCount % 15 == 0) {
            if (pollPerfMetrics(perfMetricsBuffer) && perfMetricsBuffer[0] > 0.5f) {
                gpuFrameTimeMs = perfMetricsBuffer[1]
                cpuFrameTimeMs = perfMetricsBuffer[2]
                droppedFrames = perfMetricsBuffer[3]
                displayRefreshRate = perfMetricsBuffer[4]
            }
        }

        // Passthrough state
        if (xrSensorCaps and (1 shl 6) != 0 && frameCount % 60 == 0) {
            passthroughState = getPassthroughState()
        }
    }

    /**
     * Build a multi-line debug string for the sensor HUD overlay.
     * [sensorHub] provides Android hardware sensor data (may be null).
     * [xrLightEstimateAvailable] / [xrLightDebugStr] come from the XR light estimate.
     */
    fun buildDebugString(
        sensorHub: SensorHub?,
        xrLightEstimateAvailable: Boolean,
        xrLightDebugStr: String
    ): String {
        val sb = StringBuilder()

        // Android hardware sensors
        sensorHub?.let { hub ->
            sb.appendLine(hub.getDebugString())
            sb.appendLine()
        }

        // XR extensions
        sb.appendLine("=== XR SENSORS (caps=0x${xrSensorCaps.toString(16)}) ===")

        // XR Light
        if (xrLightEstimateAvailable)
            sb.appendLine("XR Light: $xrLightDebugStr")

        // Hand tracking
        if (handTrackingActive[0] || handTrackingActive[1]) {
            val lPalm = if (handTrackingActive[0]) {
                val px = handTrackingBufferL[1]; val py = handTrackingBufferL[2]; val pz = handTrackingBufferL[3]
                "(%.2f,%.2f,%.2f)".format(px, py, pz)
            } else "inactive"
            val rPalm = if (handTrackingActive[1]) {
                val px = handTrackingBufferR[1]; val py = handTrackingBufferR[2]; val pz = handTrackingBufferR[3]
                "(%.2f,%.2f,%.2f)".format(px, py, pz)
            } else "inactive"
            sb.appendLine("Hands: L=$lPalm R=$rPalm")

            // Pinch distance (thumb tip to index tip)
            if (handTrackingActive[1]) {
                val thumbOff = 1 + 5 * 8 // thumb tip = joint 5
                val indexOff = 1 + 10 * 8 // index tip = joint 10
                val dx = handTrackingBufferR[thumbOff] - handTrackingBufferR[indexOff]
                val dy = handTrackingBufferR[thumbOff+1] - handTrackingBufferR[indexOff+1]
                val dz = handTrackingBufferR[thumbOff+2] - handTrackingBufferR[indexOff+2]
                val pinchDist = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
                sb.appendLine("R Pinch: %.3f m %s".format(pinchDist, if (pinchDist < 0.02f) "PINCHED" else ""))
            }
        } else if (xrSensorCaps and (1 shl 0) != 0) {
            sb.appendLine("Hands: not tracked (controllers active?)")
        }

        // Eye tracking
        if (eyeTrackingActive) {
            sb.appendLine("Gaze: (%.3f,%.3f,%.3f) rot(%.2f,%.2f,%.2f,%.2f)".format(
                gazeOriginX, gazeOriginY, gazeOriginZ,
                gazeRotX, gazeRotY, gazeRotZ, gazeRotW))
        } else if (xrSensorCaps and (1 shl 1) != 0) {
            sb.appendLine("Eyes: not tracked (need permission?)")
        }

        // Face tracking
        if (faceTrackingActive) {
            // Show a few key blend shapes
            val jaw = faceTrackingBuffer[1]
            val smileL = faceTrackingBuffer[2]
            val smileR = faceTrackingBuffer[3]
            val browL = faceTrackingBuffer[4]
            sb.appendLine("Face: jaw=%.2f smile(%.2f,%.2f) brow=%.2f".format(jaw, smileL, smileR, browL))
        } else if (xrSensorCaps and (1 shl 2) != 0) {
            sb.appendLine("Face: not tracked (need permission?)")
        }

        // Planes
        if (detectedPlaneCount > 0) {
            sb.appendLine("Planes: $detectedPlaneCount detected")
            val count = minOf(detectedPlaneCount, 5)
            for (i in 0 until count) {
                val off = 2 + i * 76
                val labels = arrayOf("unknown", "wall", "floor", "ceiling", "table")
                val lbl = planeBuffer[off + 9].toInt().coerceIn(0, 4)
                val vtxCount = planeBuffer[off + 11].toInt()
                sb.appendLine("  ${labels[lbl]}: (%.1f,%.1f,%.1f) %.1fx%.1f m vtx=$vtxCount".format(
                    planeBuffer[off], planeBuffer[off+1], planeBuffer[off+2],
                    planeBuffer[off+7]*2, planeBuffer[off+8]*2))
            }
        } else if (xrSensorCaps and (1 shl 3) != 0) {
            sb.appendLine("Planes: scanning...")
        }

        // Performance
        if (displayRefreshRate > 0) {
            sb.appendLine("Perf: GPU=%.1fms CPU=%.1fms @%.0fHz drop=%.0f".format(
                gpuFrameTimeMs, cpuFrameTimeMs, displayRefreshRate, droppedFrames))
        }

        // Passthrough
        val ptState = when(passthroughState) { 0 -> "disabled"; 1 -> "initializing"; 2 -> "enabled"; else -> "unknown" }
        sb.appendLine("Passthrough: $ptState")
        sb.appendLine("Blend: ${if (passthroughState == 2) "ALPHA_BLEND (MR)" else "OPAQUE (VR)"}")

        return sb.toString().trimEnd()
    }
}
