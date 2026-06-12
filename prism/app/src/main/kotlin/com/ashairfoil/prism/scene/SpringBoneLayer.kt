package com.ashairfoil.prism.scene

import android.opengl.Matrix

/**
 * D10 — verlet spring bones for the flesh helper chains (Breast_L/R under
 * Spine02, Glute_L/R under Pelvis) that tools/flesh_rig/add_flesh_bones.py
 * appends to a rigged GLB. VRMC_springBone-style single-segment pendulums:
 *
 *     nextTail = currTail
 *              + (currTail - prevTail) * velRetain(dt)      // inertia
 *              + restDirWorld * (stiffness * dt * KS * L)   // return-to-rest
 *     nextTail = headWorld + normalize(nextTail - headWorld) * L
 *     nextTail = clampToFrontHemisphere(nextTail)           // vs restDirWorld
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
 *    localPose write is safe. resolve() verifies leaf-ness + parentage, so
 *    rigs that merely NAME a bone Breast_L keep their authored skinning.
 *  - Models without (genuine) helper bones resolve once per skeleton and no-op.
 *
 * D10 adversarial-review hardening (2026-06-12): front-hemisphere clamp
 * (anti-parallel was a stiffness-proof fixed point), exp-based frame-rate-
 * independent damping, teleport re-seed, NaN fail-safe guards (every guard
 * comparison is written so non-finite values take the safe branch), and
 * direction transforms through normalized parent columns so the beat's
 * non-uniform squash-stretch can't bias them.
 *
 * Zero-alloc (Invariant 6): per-model [State] allocated once on first sight
 * of a skinned model; all math runs in singleton scratch arrays (render
 * thread only).
 */
object SpringBoneLayer {

    // drag / stiffness per chain, audit-recommended starting points.
    private val CHAIN_NAMES = arrayOf("Breast_L", "Breast_R", "Glute_L", "Glute_R")
    // Provenance gate (review #12): a helper is only adopted when parented to
    // the bone add_flesh_bones.py anchors it to.
    private val CHAIN_PARENTS = arrayOf("Spine02", "Spine02", "Pelvis", "Pelvis")
    private val CHAIN_DRAG = floatArrayOf(0.40f, 0.40f, 0.50f, 0.50f)
    private val CHAIN_STIFF = floatArrayOf(0.65f, 0.65f, 0.80f, 0.80f)
    // ln(1 - drag): damping applies as exp(ln(1-drag) * dt * 72), which is
    // frame-rate independent and equals the on-head 72Hz tuning exactly at
    // dt = 1/72 (review #2 — per-frame damping was ~6x bouncier at 36Hz
    // reprojection and ~12x deader at 120Hz).
    private val CHAIN_LN_INERTIA = FloatArray(CHAIN_DRAG.size) { kotlin.math.ln(1f - CHAIN_DRAG[it]) }
    private const val N = 4
    // Nominal tail length in model units before model-matrix scale. Sets the
    // pendulum lever arm (shorter = snappier angle response), not geometry.
    private const val TAIL_LEN = 0.05f
    // Stiffness dimensioning: pull fraction/frame ~= stiff * dt * KS
    // (0.65 * 0.014 * 16 ~= 15%/frame at 72Hz -> ~100ms settle).
    private const val KS = 16f
    // Teleport guard (review #3/#13): head moved more than 4 tail-lengths in
    // one frame (pose snap, SHAKE reset, scene load) -> re-seed at rest
    // instead of integrating the jump as spring velocity.
    private const val TELEPORT_FACTOR_SQ = 16f

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
    private val rotM = FloatArray(9)

    private fun resolve(st: State, skel: SkeletonRuntime) {
        st.resolvedFor = skel
        st.anyResolved = false
        for (i in 0 until N) {
            var j = skel.indexOf(CHAIN_NAMES[i])
            if (j >= 0) {
                // Provenance (review #12): parent must be the expected anchor
                // bone AND the helper must be a leaf — otherwise this is a
                // foreign rig's own bone, not one of ours. resolve() runs once
                // per skeleton, so the O(jointCount) scans are free.
                val p = skel.parents[j]
                val parentOk = p >= 0 && p < skel.names.size &&
                    skel.names[p].equals(CHAIN_PARENTS[i], ignoreCase = true)
                var leaf = true
                if (parentOk) {
                    for (k in 0 until skel.jointCount) {
                        if (skel.parents[k] == j) { leaf = false; break }
                    }
                }
                if (!parentOk || !leaf) j = -1
            }
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

        // World tail length: nominal * mean model scale. Geometric mean of all
        // three column norms — column 0 alone breathes with the beat's
        // squash-stretch scaleMul (review #14).
        val m = modelMatrix
        val m0 = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val m1 = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val m2 = kotlin.math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        val len = TAIL_LEN * kotlin.math.cbrt(m0 * m1 * m2)
        if (!(len > 1e-6f)) return  // degenerate or non-finite scale

        for (i in 0 until N) {
            val j = st.jointIdx[i]
            val p = st.parentIdx[i]
            if (j < 0 || p < 0) continue
            val gOff = p * 16
            val g = skel.globalPose
            // First frame after load: globalPose still zeroed (forward pass
            // hasn't run). A real rotation column has norm ~1 — testing
            // diagonals instead would false-positive on Tripo's ±90° bone
            // frames, freezing the spring forever. Inverted comparison so a
            // non-finite pose also takes the skip (review #5).
            if (!(g[gOff] * g[gOff] + g[gOff + 1] * g[gOff + 1] + g[gOff + 2] * g[gOff + 2] > 1e-6f)) continue

            // parentWorld = modelMatrix * globalPose[parent]
            Matrix.multiplyMM(parentWorld, 0, modelMatrix, 0, g, gOff)
            val pw = parentWorld
            // Column norms of the parent 3x3: POSITIONS use the scaled matrix,
            // DIRECTIONS go through the normalized (pure-rotation) columns so
            // non-uniform scale can't bias them (review #4/#6).
            val c0 = kotlin.math.sqrt(pw[0] * pw[0] + pw[1] * pw[1] + pw[2] * pw[2])
            val c1 = kotlin.math.sqrt(pw[4] * pw[4] + pw[5] * pw[5] + pw[6] * pw[6])
            val c2 = kotlin.math.sqrt(pw[8] * pw[8] + pw[9] * pw[9] + pw[10] * pw[10])
            if (!(c0 > 1e-6f && c1 > 1e-6f && c2 > 1e-6f)) continue
            val i0 = 1f / c0; val i1 = 1f / c1; val i2 = 1f / c2

            val bOff = j * 16
            val b = skel.bindLocal
            // headWorld = parentWorld * bindTranslation(j)
            val hx = pw[0] * b[bOff + 12] + pw[4] * b[bOff + 13] + pw[8] * b[bOff + 14] + pw[12]
            val hy = pw[1] * b[bOff + 12] + pw[5] * b[bOff + 13] + pw[9] * b[bOff + 14] + pw[13]
            val hz = pw[2] * b[bOff + 12] + pw[6] * b[bOff + 13] + pw[10] * b[bOff + 14] + pw[14]
            // rest dir, parent-local = bind rotation column 1 (bone local +Y)
            var rlx = b[bOff + 4]; var rly = b[bOff + 5]; var rlz = b[bOff + 6]
            var n = kotlin.math.sqrt(rlx * rlx + rly * rly + rlz * rlz)
            if (!(n > 1e-6f)) continue
            n = 1f / n
            rlx *= n; rly *= n; rlz *= n
            // rest dir, world — normalized columns
            var rwx = pw[0] * i0 * rlx + pw[4] * i1 * rly + pw[8] * i2 * rlz
            var rwy = pw[1] * i0 * rlx + pw[5] * i1 * rly + pw[9] * i2 * rlz
            var rwz = pw[2] * i0 * rlx + pw[6] * i1 * rly + pw[10] * i2 * rlz
            n = kotlin.math.sqrt(rwx * rwx + rwy * rwy + rwz * rwz)
            if (!(n > 1e-6f)) continue
            n = 1f / n
            rwx *= n; rwy *= n; rwz *= n

            val o = i * 3
            // Teleport guard (review #3/#13): inverted comparison re-seeds on
            // non-finite state too.
            if (st.inited[i]) {
                val tx = st.curr[o] - hx; val ty = st.curr[o + 1] - hy; val tz = st.curr[o + 2] - hz
                if (!(tx * tx + ty * ty + tz * tz < TELEPORT_FACTOR_SQ * len * len)) st.inited[i] = false
            }
            if (!st.inited[i]) {
                st.curr[o] = hx + rwx * len; st.curr[o + 1] = hy + rwy * len; st.curr[o + 2] = hz + rwz * len
                st.prev[o] = st.curr[o]; st.prev[o + 1] = st.curr[o + 1]; st.prev[o + 2] = st.curr[o + 2]
                st.inited[i] = true
            }

            val inertia = kotlin.math.exp(CHAIN_LN_INERTIA[i] * dt * 72f)
            val stiffPull = CHAIN_STIFF[i] * dt * KS * len
            var nx = st.curr[o] + (st.curr[o] - st.prev[o]) * inertia + rwx * stiffPull
            var ny = st.curr[o + 1] + (st.curr[o + 1] - st.prev[o + 1]) * inertia + rwy * stiffPull
            var nz = st.curr[o + 2] + (st.curr[o + 2] - st.prev[o + 2]) * inertia + rwz * stiffPull
            // length constraint around the (moving) head
            var dx = nx - hx; var dy = ny - hy; var dz = nz - hz
            val dl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            // Degenerate or non-finite: drop the frame, re-seed at rest next
            // frame (review #5 — NaN previously failed open and was permanent).
            if (!(dl > 1e-6f)) { st.inited[i] = false; continue }
            val inv = len / dl
            nx = hx + dx * inv; ny = hy + dy * inv; nz = hz + dz * inv
            // Front-hemisphere clamp (review #1): the sphere projection above
            // can park the tail anti-parallel to rest — a stiffness-proof
            // fixed point that renders flipped/garbled for ~0.5s after a fast
            // axial grab/reel. Slide along the great circle to the equator.
            dx = nx - hx; dy = ny - hy; dz = nz - hz
            val dr = dx * rwx + dy * rwy + dz * rwz
            if (dr < 0f) {
                dx -= rwx * dr; dy -= rwy * dr; dz -= rwz * dr
                val tl = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (!(tl > 1e-6f)) { st.inited[i] = false; continue }  // exact pole: re-seed
                val eq = len / tl
                nx = hx + dx * eq; ny = hy + dy * eq; nz = hz + dz * eq
            }
            st.prev[o] = st.curr[o]; st.prev[o + 1] = st.curr[o + 1]; st.prev[o + 2] = st.curr[o + 2]
            st.curr[o] = nx; st.curr[o + 1] = ny; st.curr[o + 2] = nz

            // tail dir back to parent-local. For M3 = R*S the direction
            // inverse is S⁻¹Rᵀ = transpose with each component divided by the
            // SQUARED column norm (review #4); normalized after.
            dx = nx - hx; dy = ny - hy; dz = nz - hz
            var px = (pw[0] * dx + pw[1] * dy + pw[2] * dz) * i0 * i0
            var py = (pw[4] * dx + pw[5] * dy + pw[6] * dz) * i1 * i1
            var pz = (pw[8] * dx + pw[9] * dy + pw[10] * dz) * i2 * i2
            n = kotlin.math.sqrt(px * px + py * py + pz * pz)
            if (!(n > 1e-6f)) continue
            n = 1f / n
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
        if (!(s >= 1e-5f)) {
            // Parallel (c>0): identity. Anti-parallel is clamped out upstream
            // (front-hemisphere clamp in apply), and a non-finite s lands here
            // too — identity is the safe write in every remaining case.
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
