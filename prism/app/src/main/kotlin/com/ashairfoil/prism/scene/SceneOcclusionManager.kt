package com.ashairfoil.prism.scene

import android.util.Log

/**
 * Real-world scene occlusion manager.
 *
 * Feeds detected plane polygons from [XrSensorPoller] into the native
 * depth-only pre-pass (`chloe_vr::SceneOcclusion` in `xr_scene_occlusion.cpp`)
 * so that placed 3D models are visually blocked by the real-world bed /
 * dresser / wall / table when parts of the model are behind those surfaces.
 *
 * Extension tiering (checked at session init):
 *  1. `XR_ANDROID_scene_meshes` / `XR_MSFT_scene_understanding` — triangle
 *     mesh. NOT available on the Samsung Galaxy XR runtime (the only
 *     `XR_ANDROID_trackables` type exposed is `PLANE_ANDROID`).
 *  2. `XR_ANDROID_trackables` polygon-vertex planes — **what we ship**.
 *     Each plane exposes up to 32 polygon vertices approximating the
 *     detected surface contour, which gives far better occlusion than a
 *     flat rectangular quad on L-shaped desks, curved counters, etc.
 *  3. `XR_ANDROID_trackables` rectangular extents — silent fallback if a
 *     plane has no polygon (vertexCount < 3).
 *
 * Lifecycle
 * ---------
 * * [ensureInitialized] — call once from the render thread after the GL
 *   context has been made current. Compiles the depth shader and allocates
 *   a fixed-size VBO. Idempotent and cheap to call again.
 * * [onPlanesUpdated] — hand off fresh plane buffer + count every time
 *   [XrSensorPoller] updates (default cadence: every 10 frames).
 * * [renderDepthOnly] — call once per eye pass **before** the main color
 *   draw. Writes to the currently-bound framebuffer's depth attachment.
 * * [setEnabled] — runtime toggle; when disabled, [renderDepthOnly] becomes
 *   a no-op (no GL work).
 * * [shutdown] — release GL resources.
 *
 * Thread safety
 * -------------
 * [onPlanesUpdated] may run on any thread — geometry is staged under a
 * mutex and uploaded the next time [renderDepthOnly] runs on the GL
 * thread. The only thread-sensitive JNI calls are [ensureInitialized],
 * [renderDepthOnly] and [shutdown] which must all run on the render
 * thread (they touch GL resources).
 */
class SceneOcclusionManager {

    companion object {
        private const val TAG = "SceneOcclusionMgr"

        // Must match CHLOE_OCC_FLOATS_PER_PLANE / CHLOE_OCC_VERTICES_OFFSET in
        // xr_scene_occlusion.h. Any change requires updating both sides.
        private const val FLOATS_PER_PLANE = 75
        private const val VERTICES_OFFSET = 11  // centroid3 + quat4 + ext2 + label + count
        private const val MAX_PLANES = 64
        private const val MAX_VERTICES_PER_PLANE = 32
    }

    // ── JNI -------------------------------------------------------------

    private external fun nativeOcclusionInit(): Boolean
    private external fun nativeOcclusionShutdown()
    private external fun nativeOcclusionSetEnabled(enabled: Boolean)
    private external fun nativeOcclusionIsReady(): Boolean
    private external fun nativeOcclusionUpdateGeometry(
        packedPlanes: FloatArray,
        planeCount: Int,
        gridHeight: Float
    )

    private external fun nativeOcclusionRenderDepth(
        projection: FloatArray,
        viewMatrix: FloatArray
    ): Boolean

    private external fun nativeOcclusionDiagnostics(): IntArray

    // ── State -----------------------------------------------------------

    @Volatile
    var isInitialized: Boolean = false
        private set

    /**
     * Soft-disable reason: true if the runtime reports no plane-detection
     * extension. Keep [enabled] authoritative for user toggle; this flag
     * is used by the UI layer to grey-out the menu checkbox.
     */
    @Volatile
    var isExtensionSupported: Boolean = false

    /**
     * User-facing toggle. Persisted by [settings.SettingsManager]. When
     * false the render path short-circuits before any GL work.
     */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (isInitialized) {
                runCatching { nativeOcclusionSetEnabled(value) }
                    .onFailure { Log.w(TAG, "setEnabled JNI failed: ${it.message}") }
            }
        }

    // Pre-allocated scratch buffer — zero per-frame allocation.
    // Sized to exactly the native-side hard cap.
    private val packedScratch = FloatArray(MAX_PLANES * FLOATS_PER_PLANE)

    @Volatile private var lastGridHeight: Float = 0f
    @Volatile private var lastPlaneCount: Int = 0

    // ── Lifecycle -------------------------------------------------------

    /** Call once from the render thread after GL is current. Safe to re-call. */
    fun ensureInitialized(): Boolean {
        if (isInitialized) return true
        val ok = try {
            nativeOcclusionInit()
        } catch (e: UnsatisfiedLinkError) {
            // Native library not loaded yet — parent activity may be starting.
            Log.w(TAG, "Native lib not loaded: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            return false
        }
        if (ok) {
            isInitialized = true
            nativeOcclusionSetEnabled(enabled)
            Log.i(TAG, "initialized (enabled=$enabled, extSupported=$isExtensionSupported)")
        } else {
            Log.w(TAG, "nativeOcclusionInit returned false — shader compile or GL issue")
        }
        return ok
    }

    fun shutdown() {
        if (!isInitialized) return
        try {
            nativeOcclusionShutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown failed: ${e.message}")
        }
        isInitialized = false
    }

    // ── Geometry feed ---------------------------------------------------

    /**
     * Called from [XrSensorPoller.onPlanesUpdated] (or anywhere that owns
     * the raw plane buffer). Packs polygon vertices into the shared layout
     * and hands off to the native side.
     *
     * @param planeBuffer raw buffer from [XrSensorPoller.planeBuffer] with
     *        layout: `[valid, count, (76 floats per plane × 64)...]` where
     *        per-plane floats are `pos3, quat4, ext2, label, planeType, vtxCount,
     *        32×(x,y)` — defined by `XrRenderer::PlaneData` in C++.
     * @param planeCount how many planes are populated (0..64).
     * @param gridHeight detected floor Y used as a secondary safety cutoff.
     */
    fun onPlanesUpdated(planeBuffer: FloatArray, planeCount: Int, gridHeight: Float) {
        if (!isInitialized) return
        if (planeBuffer.size < 2) return

        val safeCount = planeCount.coerceIn(0, MAX_PLANES)
        if (safeCount == 0) {
            // Clear the native side — garbage scans should not leave stale occluders.
            nativeOcclusionUpdateGeometry(packedScratch, 0, gridHeight)
            lastPlaneCount = 0
            lastGridHeight = gridHeight
            return
        }

        val srcStride = 76  // XrRenderer::PlaneData::FLOATS_PER_PLANE
        var tinyPlanes = 0
        var writeRow = 0
        for (i in 0 until safeCount) {
            val srcOff = 2 + i * srcStride

            // Safety: abort if somehow the array is truncated.
            if (srcOff + srcStride > planeBuffer.size) break

            val label = planeBuffer[srcOff + 9].toInt()
            val vtxCount = planeBuffer[srcOff + 11].toInt().coerceIn(0, MAX_VERTICES_PER_PLANE)
            val halfEx = planeBuffer[srcOff + 7]
            val halfEy = planeBuffer[srcOff + 8]

            // Skip tiny noisy planes early — also skipped again on the native
            // side, but filtering here saves JNI bytes.
            if (halfEx < 0.075f || halfEy < 0.075f) {
                tinyPlanes++
                continue
            }

            val dstOff = writeRow * FLOATS_PER_PLANE
            // Header: centroid + quat + ext + label + vtxCount
            packedScratch[dstOff + 0] = planeBuffer[srcOff + 0]
            packedScratch[dstOff + 1] = planeBuffer[srcOff + 1]
            packedScratch[dstOff + 2] = planeBuffer[srcOff + 2]
            packedScratch[dstOff + 3] = planeBuffer[srcOff + 3]
            packedScratch[dstOff + 4] = planeBuffer[srcOff + 4]
            packedScratch[dstOff + 5] = planeBuffer[srcOff + 5]
            packedScratch[dstOff + 6] = planeBuffer[srcOff + 6]
            packedScratch[dstOff + 7] = halfEx
            packedScratch[dstOff + 8] = halfEy
            packedScratch[dstOff + 9] = label.toFloat()
            packedScratch[dstOff + 10] = vtxCount.toFloat()

            // Polygon vertices (2D plane-local). Source stride per vertex = 2,
            // destination stride per vertex = 2. arraycopy handles the bulk.
            val verticesSrcStart = srcOff + 12
            val verticesDstStart = dstOff + VERTICES_OFFSET
            val verticesCount = vtxCount * 2
            if (verticesCount > 0) {
                System.arraycopy(
                    planeBuffer, verticesSrcStart,
                    packedScratch, verticesDstStart,
                    verticesCount
                )
            }
            // Zero-fill the rest so old data doesn't leak into unused slots.
            val tailStart = verticesDstStart + verticesCount
            val tailEnd = dstOff + FLOATS_PER_PLANE
            for (k in tailStart until tailEnd) packedScratch[k] = 0f

            writeRow++
            if (writeRow >= MAX_PLANES) break
        }

        lastPlaneCount = writeRow
        lastGridHeight = gridHeight

        nativeOcclusionUpdateGeometry(packedScratch, writeRow, gridHeight)

        if (tinyPlanes > 0 && writeRow == 0) {
            // User's room scan is all noise / tiny fragments. Don't crash,
            // just log once and keep the pass disabled until a real scan lands.
            Log.w(TAG, "occlusion geometry empty ($tinyPlanes tiny planes filtered); skipping")
        }
    }

    // ── Render hook -----------------------------------------------------

    /**
     * Render the depth-only occlusion pass for one eye.
     *
     * MUST be called on the render thread, AFTER the target eye FBO is
     * bound and its depth buffer is cleared, and BEFORE the model color
     * pass. No-op when disabled, not yet initialized, or geometry is empty.
     *
     * @return true if a depth pass was drawn (used for HUD diagnostics).
     */
    fun renderDepthOnly(projection: FloatArray, viewMatrix: FloatArray): Boolean {
        if (!isInitialized) return false
        if (!enabled) return false
        if (lastPlaneCount <= 0) return false
        return try {
            nativeOcclusionRenderDepth(projection, viewMatrix)
        } catch (e: Exception) {
            Log.w(TAG, "renderDepthOnly crashed: ${e.message}")
            false
        }
    }

    // ── Diagnostics -----------------------------------------------------

    /**
     * Returns `[ready, planeCount, triangleCount, frameRenderCount]`
     * (four ints). Useful for the sensor-HUD overlay.
     */
    fun diagnostics(): IntArray {
        if (!isInitialized) return intArrayOf(0, 0, 0, 0)
        return try {
            nativeOcclusionDiagnostics()
        } catch (e: Exception) {
            intArrayOf(0, 0, 0, 0)
        }
    }

    /** Human-readable one-liner for the debug HUD. */
    fun debugString(): String {
        val d = diagnostics()
        val tier = when {
            !isExtensionSupported -> "none (ext unavailable)"
            !isInitialized -> "idle (not init)"
            !enabled -> "off (user toggle)"
            d[1] == 0 -> "scanning..."
            else -> "plane-polygon"
        }
        return "Occlusion: tier=$tier planes=${d[1]} tris=${d[2]} frames=${d[3]}"
    }
}
