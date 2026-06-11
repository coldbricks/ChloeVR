package com.ashairfoil.prism.scene

import android.opengl.Matrix

/**
 * D10 — verlet spring bones for the flesh helper chains (Breast_L/R under
 * Spine02, Glute_L/R under Pelvis) that tools/flesh_rig/add_flesh_bones.py
 * appends to a rigged GLB. VRMC_springBone-style single-segment pendulums:
 *
 *     nextTail = currTail
 *              + (currTail - prevTail) * (1 - drag)        // inertia
 *              + restDirWorld * (stiffness * dt * KS * L)  // return-to-rest
 *     nextTail = headWorld + normalize(nextTail - headWorld) * L
 *
 * then the tail direction is converted back into the joint's local rotation
 * (Rodrigues align of the bind +Y onto the new direction, composed onto the
 * bind orientation) and written into [SkeletonRuntime.localPose].
 *
 * Frame contract:
 *  - Called once per skinned model per frame, AFTER IdleLayer — the LAST
 *    pose writer, so dance + idle + whole-model motion all excite it.
 *  - Parent world transforms use the PREVIOUS frame's globalPose (the one
 *    forward pass per frame runs later, in the shadow/color passes) times
 *    the CURRENT GpuModel.modelMatrix — one frame of driver lag, invisible
 *    for secondary motion and blessed by the D10 audit notes.
 *  - Helper bones are leaves: nothing depends on their pose, so a pure
 *    localPose write is safe.
 *  - Models without helper bones resolve once per skeleton and no-op.
 *
 * Zero-alloc (Invariant 6): per-model [State] allocated once on first sight
 * of a skinned model; all math runs in singleton scratch arrays (render
 * thread only).
 */
object SpringBoneLayer {

    // drag / stiffness per chain, audit-recommended starting points.
    private val CHAIN_NAMES = arrayOf("Breast_L", "Breast_R", "Glute_L", "Glute_R")
    private val CHAIN_DRAG = floatArrayOf(0.40f, 0.40f, 0.50f, 0.50f)
    private val CHAIN_STIFF = floatArrayOf(0.65f, 0.65f, 0.80f, 0.80f)
    private const val N = 4
    // Nominal tail length in model units before model-matrix scale. Sets the
    // pendulum lever arm (shorter = snappier angle response), not geometry.
    private const val TAIL_LEN = 0.05f
    // Stiffness dimensioning: pull fraction/frame ~= stiff * dt * KS
    // (0.65 * 0.014 * 16 ~= 15%/frame at 72Hz -> ~100ms settle).
    private const val KS = 16f

    /** Per-model spring state. Allocated once per model (not per frame). */
    class State {
        var resolvedFor: SkeletonRuntime? = null
        val jointIdx = IntArray(N) { -1 }
        val parentIdx = IntArray(N) { -1 }
        var anyResolved = false
        val curr = FloatArray(N * 3)
        val prev = FloatArray(N * 3)
        val inited = BooleanArray(N)
        var lastMs = 0L
    }

    // Render-thread-only scratch (single consumer).
    private val parentWorld = FloatArray(16)
    private val scratchVecA = FloatArray(3)
    private val scratchVecB = FloatArray(3)
    private val scratchVecC = FloatArray(3)
    private val rotM = FloatArray(9)

    private fun resolve(st: State, skel: SkeletonRuntime) {
        st.resolvedFor = skel
        st.anyResolved = false
        for (i in 0 until N) {
            val j = skel.indexOf(CHAIN_NAMES[i])
            st.jointIdx[i] = j
            st.parentIdx[i] = if (j >= 0) skel.parents[j] else -1
            st.inited[i] = false
            if (j >= 0 && st.parentIdx[i] >= 0) st.anyResolved = true
        }
    }

    /**
     * @param modelMatrix the model's current world transform (GpuModel.modelMatrix)
     * @param nowMs       SystemClock.uptimeMillis()
     */
    fun apply(st: State, skel: SkeletonRuntime, modelMatrix: FloatArray, nowMs: Long) {
        if (st.resolvedFor !== skel) resolve(st, skel)
        if (!st.anyResolved) return

        val dt = if (st.lastMs == 0L) 1f / 72f
        else ((nowMs - st.lastMs) / 1000f).coerceIn(0.001f, 0.05f)
        st.lastMs = nowMs

        for (i in 0 until N) {
            val j = st.jointIdx[i]
            val p = st.parentIdx[i]
            if (j < 0 || p < 0) continue
            val gOff = p * 16
            val g = skel.globalPose
            // First frame after load: globalPose still zeroed (forward pass
            // hasn't run). A real rotation column has norm ~1 — testing
            // diagonals instead would false-positive on Tripo's ±90° bone
            // frames, freezing the spring forever.
            if (g[gOff] * g[gOff] + g[gOff + 1] * g[gOff + 1] + g[gOff + 2] * g[gOff + 2] < 1e-6f) continue

            // parentWorld = modelMatrix * globalPose[parent]
            Matrix.multiplyMM(parentWorld, 0, modelMatrix, 0, g, gOff)
            val bOff = j * 16
            val b = skel.bindLocal
            // headWorld = parentWorld * bindTranslation(j)
            val hx = parentWorld[0] * b[bOff + 12] + parentWorld[4] * b[bOff + 13] + parentWorld[8] * b[bOff + 14] + parentWorld[12]
            val hy = parentWorld[1] * b[bOff + 12] + parentWorld[5] * b[bOff + 13] + parentWorld[9] * b[bOff + 14] + parentWorld[13]
            val hz = parentWorld[2] * b[bOff + 12] + parentWorld[6] * b[bOff + 13] + parentWorld[10] * b[bOff + 14] + parentWorld[14]
            // rest dir, parent-local = bind rotation column 1 (bone local +Y)
            var rlx = b[bOff + 4]; var rly = b[bOff + 5]; var rlz = b[bOff + 6]
            var n = 1f / kotlin.math.sqrt(rlx * rlx + rly * rly + rlz * rlz)
            rlx *= n; rly *= n; rlz *= n
            // rest dir, world
            var rwx = parentWorld[0] * rlx + parentWorld[4] * rly + parentWorld[8] * rlz
            var rwy = parentWorld[1] * rlx + parentWorld[5] * rly + parentWorld[9] * rlz
            var rwz = parentWorld[2] * rlx + parentWorld[6] * rly + parentWorld[10] * rlz
            n = 1f / kotlin.math.sqrt(rwx * rwx + rwy * rwy + rwz * rwz)
            rwx *= n; rwy *= n; rwz *= n
            // world tail length: nominal * model scale (col0 norm of modelMatrix)
            val ms = kotlin.math.sqrt(
                modelMatrix[0] * modelMatrix[0] + modelMatrix[1] * modelMatrix[1] + modelMatrix[2] * modelMatrix[2])
            val len = TAIL_LEN * ms

            val o = i * 3
            if (!st.inited[i]) {
                st.curr[o] = hx + rwx * len; st.curr[o + 1] = hy + rwy * len; st.curr[o + 2] = hz + rwz * len
                st.prev[o] = st.curr[o]; st.prev[o + 1] = st.curr[o + 1]; st.prev[o + 2] = st.curr[o + 2]
                st.inited[i] = true
            }

            val inertia = 1f - CHAIN_DRAG[i]
            val stiffPull = CHAIN_STIFF[i] * dt * KS * len
            var nx = st.curr[o] + (st.curr[o] - st.prev[o]) * inertia + rwx * stiffPull
            var ny = st.curr[o + 1] + (st.curr[o + 1] - st.prev[o + 1]) * inertia + rwy * stiffPull
            var nz = st.curr[o + 2] + (st.curr[o + 2] - st.prev[o + 2]) * inertia + rwz * stiffPull
            // length constraint around the (moving) head
            var dx = nx - hx; var dy = ny - hy; var dz = nz - hz
            var dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (dl < 1e-6f) { dx = rwx; dy = rwy; dz = rwz; dl = 1f }
            val inv = len / dl
            nx = hx + dx * inv; ny = hy + dy * inv; nz = hz + dz * inv
            st.prev[o] = st.curr[o]; st.prev[o + 1] = st.curr[o + 1]; st.prev[o + 2] = st.curr[o + 2]
            st.curr[o] = nx; st.curr[o + 1] = ny; st.curr[o + 2] = nz

            // tail dir back to parent-local (transpose of parentWorld 3x3 —
            // rotation + uniform scale; normalized after)
            dx = (nx - hx); dy = (ny - hy); dz = (nz - hz)
            var px = parentWorld[0] * dx + parentWorld[1] * dy + parentWorld[2] * dz
            var py = parentWorld[4] * dx + parentWorld[5] * dy + parentWorld[6] * dz
            var pz = parentWorld[8] * dx + parentWorld[9] * dy + parentWorld[10] * dz
            n = 1f / kotlin.math.sqrt(px * px + py * py + pz * pz)
            px *= n; py *= n; pz *= n

            writeAlignedLocalPose(skel, j, rlx, rly, rlz, px, py, pz)
        }
    }

    /**
     * localPose[j] = R_align(restLocal -> dirLocal) * bindLocal[j] with the
     * bind translation preserved. Rodrigues with sin/cos straight from the
     * cross/dot — no trig calls.
     */
    private fun writeAlignedLocalPose(
        skel: SkeletonRuntime, j: Int,
        rx: Float, ry: Float, rz: Float,
        dx: Float, dy: Float, dz: Float
    ) {
        var ax = ry * dz - rz * dy
        var ay = rz * dx - rx * dz
        var az = rx * dy - ry * dx
        val s = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        val c = rx * dx + ry * dy + rz * dz
        val r = rotM
        if (s < 1e-5f) {
            // Parallel (c>0): identity. Anti-parallel can't happen for flesh
            // swing (length constraint keeps it in the front hemisphere) —
            // treat as identity rather than picking an arbitrary 180 axis.
            r[0] = 1f; r[1] = 0f; r[2] = 0f
            r[3] = 0f; r[4] = 1f; r[5] = 0f
            r[6] = 0f; r[7] = 0f; r[8] = 1f
        } else {
            ax /= s; ay /= s; az /= s
            val ic = 1f - c
            // column-major 3x3: r[col*3 + row]
            r[0] = c + ax * ax * ic
            r[1] = ay * ax * ic + az * s
            r[2] = az * ax * ic - ay * s
            r[3] = ax * ay * ic - az * s
            r[4] = c + ay * ay * ic
            r[5] = az * ay * ic + ax * s
            r[6] = ax * az * ic + ay * s
            r[7] = ay * az * ic - ax * s
            r[8] = c + az * az * ic
        }
        val b = skel.bindLocal
        val lp = skel.localPose
        val off = j * 16
        // localPose3 = R * bind3 (column-major 4x4 with 3x3 in cols 0..2)
        for (col in 0 until 3) {
            val bx = b[off + col * 4]; val by = b[off + col * 4 + 1]; val bz = b[off + col * 4 + 2]
            lp[off + col * 4] = r[0] * bx + r[3] * by + r[6] * bz
            lp[off + col * 4 + 1] = r[1] * bx + r[4] * by + r[7] * bz
            lp[off + col * 4 + 2] = r[2] * bx + r[5] * by + r[8] * bz
            lp[off + col * 4 + 3] = 0f
        }
        lp[off + 12] = b[off + 12]; lp[off + 13] = b[off + 13]; lp[off + 14] = b[off + 14]; lp[off + 15] = 1f
    }
}
