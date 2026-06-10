package com.ashairfoil.prism.scene

import android.opengl.Matrix

/**
 * Per-model skeleton state for GPU skinning.
 *
 * Holds bind-pose matrices + per-frame posed matrices, and produces a joint
 * palette (global_pose * inverse_bind) ready to upload into a std140 UBO.
 *
 * Zero-alloc hot path: all matrices live in flat FloatArrays of length
 * `jointCount * 16`, and `update()` composes them using the in-place
 * `Matrix.multiplyMM` overload.
 *
 * Topological ordering is a hard requirement — `parents[j] < j` must hold for
 * every non-root joint so the hierarchy walk fills parents before children in
 * a single forward pass.
 *
 * The caller writes per-frame pose changes into `localPose[j * 16 .. j * 16 + 15]`
 * (typically via [setJointEulerX] / [setJointQuat] / [resetJointToBind]) and
 * invokes [update] once per frame before the draw.
 *
 * Reference: see `TIER3_PLAN.md` §Tier 4 and the four research agents' reports
 * (glTF spec + Tripo rig inspection + GLES skinning design). The math is the
 * standard glTF LBS pipeline — this file is the runtime half of the joint
 * matrix equation, paired with the vertex-shader skin block.
 */
class SkeletonRuntime(
    val jointCount: Int,
    /** parents[j] = parent joint index, or -1 for root joints. Must be topo-sorted. */
    val parents: IntArray,
    /** Bind-pose local transform per joint (jointCount * 16 floats, row-major). */
    val bindLocal: FloatArray,
    /** Inverse bind matrix per joint (jointCount * 16 floats). */
    val invBind: FloatArray,
    /** Joint names, in joint-index order. Optional; empty array if not known. */
    val names: Array<String> = emptyArray()
) {
    /** Current per-joint local transform (starts as a copy of bindLocal, mutated each frame). */
    val localPose: FloatArray = bindLocal.copyOf()

    /** Scratch: global transform per joint, recomputed each [update]. */
    val globalPose: FloatArray = FloatArray(jointCount * 16)

    /** Final palette uploaded to the UBO. globalPose[j] * invBind[j]. */
    val palette: FloatArray = FloatArray(jointCount * 16)

    private val scratch = FloatArray(16)
    private val scratchQuat = FloatArray(16)
    private val scratchAxisQuat = FloatArray(16)

    init {
        require(parents.size == jointCount)
        require(bindLocal.size == jointCount * 16)
        require(invBind.size == jointCount * 16)
        // Warn-level check: verify topo order (parent < child).
        for (j in 0 until jointCount) {
            val p = parents[j]
            check(p < j) { "Joints must be topologically sorted; joint $j has parent $p" }
        }
    }

    /** Look up a joint index by name (case-insensitive). Returns -1 if not found. */
    fun indexOf(name: String): Int {
        if (names.isEmpty()) return -1
        for (i in 0 until jointCount) {
            if (names[i].equals(name, ignoreCase = true)) return i
        }
        return -1
    }

    /** Return the posed world-space position of joint `j`, written into `out` (size 3). */
    fun jointWorldPos(j: Int, out: FloatArray): FloatArray {
        val off = j * 16
        out[0] = globalPose[off + 12]
        out[1] = globalPose[off + 13]
        out[2] = globalPose[off + 14]
        return out
    }

    /** Reset joint `j`'s local transform to its bind-pose value. */
    fun resetJointToBind(j: Int) {
        if (j < 0 || j >= jointCount) return
        System.arraycopy(bindLocal, j * 16, localPose, j * 16, 16)
    }

    /** Reset every joint to bind pose. Call when the dance loop stops driving. */
    fun resetAllToBind() {
        System.arraycopy(bindLocal, 0, localPose, 0, bindLocal.size)
    }

    /**
     * Rotate joint `j` around its local +X axis by `deg` degrees, applied AFTER the
     * bind-pose transform. Common case: knee flexion, elbow bend. Sign follows glTF
     * convention (+X flex for knees in Tripo/Mixamo/Blender-exported rigs — verify
     * per-rig if a joint looks wrong).
     *
     * Semantics: localPose[j] = bindLocal[j] * rotX(deg). Preserves the bind pose's
     * translation (bone offset from parent) and applies a pure rotation on top.
     */
    fun setJointEulerX(j: Int, deg: Float) {
        if (j < 0 || j >= jointCount) return
        val rad = deg * (Math.PI.toFloat() / 180f)
        Matrix.setIdentityM(scratchAxisQuat, 0)
        Matrix.rotateM(scratchAxisQuat, 0, deg, 1f, 0f, 0f)
        // Compose: local = bind * axisRot
        Matrix.multiplyMM(scratchQuat, 0, bindLocal, j * 16, scratchAxisQuat, 0)
        System.arraycopy(scratchQuat, 0, localPose, j * 16, 16)
        // Suppress unused-var warning — rad documents the axis-angle source.
        @Suppress("UNUSED_EXPRESSION") rad
    }

    /** Rotate joint `j` around its local +Y axis by `deg` degrees (post-bind). */
    fun setJointEulerY(j: Int, deg: Float) {
        if (j < 0 || j >= jointCount) return
        Matrix.setIdentityM(scratchAxisQuat, 0)
        Matrix.rotateM(scratchAxisQuat, 0, deg, 0f, 1f, 0f)
        Matrix.multiplyMM(scratchQuat, 0, bindLocal, j * 16, scratchAxisQuat, 0)
        System.arraycopy(scratchQuat, 0, localPose, j * 16, 16)
    }

    /** Rotate joint `j` around its local +Z axis by `deg` degrees (post-bind). */
    fun setJointEulerZ(j: Int, deg: Float) {
        if (j < 0 || j >= jointCount) return
        Matrix.setIdentityM(scratchAxisQuat, 0)
        Matrix.rotateM(scratchAxisQuat, 0, deg, 0f, 0f, 1f)
        Matrix.multiplyMM(scratchQuat, 0, bindLocal, j * 16, scratchAxisQuat, 0)
        System.arraycopy(scratchQuat, 0, localPose, j * 16, 16)
    }

    /**
     * Set joint `j`'s local transform to `bind * rotXYZ(rx, ry, rz)` where the
     * rotation order is X→Y→Z (intrinsic). Useful when driving a joint with
     * multi-axis signals (hip yaw + pitch + roll all at once).
     */
    fun setJointEulerXYZ(j: Int, degX: Float, degY: Float, degZ: Float) {
        if (j < 0 || j >= jointCount) return
        Matrix.setIdentityM(scratchAxisQuat, 0)
        if (degZ != 0f) Matrix.rotateM(scratchAxisQuat, 0, degZ, 0f, 0f, 1f)
        if (degY != 0f) Matrix.rotateM(scratchAxisQuat, 0, degY, 0f, 1f, 0f)
        if (degX != 0f) Matrix.rotateM(scratchAxisQuat, 0, degX, 1f, 0f, 0f)
        Matrix.multiplyMM(scratchQuat, 0, bindLocal, j * 16, scratchAxisQuat, 0)
        System.arraycopy(scratchQuat, 0, localPose, j * 16, 16)
    }

    /**
     * Multiplicatively compose a local rotXYZ(rx, ry, rz) onto joint `j`'s
     * CURRENT local pose:
     *
     *     localPose[j] = currentLocalPose[j] * rotXYZ(degX, degY, degZ)
     *
     * Unlike [setJointEulerXYZ] (which replaces the local pose with
     * `bind * rotXYZ`), this reads the live `localPose[j]` so a prior writer's
     * rotation survives — the composed rotation is applied on top in the
     * joint's local frame. Mirrors the Z→Y→X rotation-application order used
     * by [setJointEulerXYZ] so a single-axis compose (e.g. degZ only) behaves
     * identically to [setJointEulerZ]-style right-multiply on the current pose.
     *
     * Used by [JointDriveLayer] to add Mixamo-extracted oscillation on top of
     * Tier 4 biomechanics writes without wiping them. Zero-alloc — reuses the
     * same scratch matrices as the setJointEuler* family.
     */
    fun composeJointEulerXYZ(j: Int, degX: Float, degY: Float, degZ: Float) {
        if (j < 0 || j >= jointCount) return
        if (degX == 0f && degY == 0f && degZ == 0f) return
        Matrix.setIdentityM(scratchAxisQuat, 0)
        if (degZ != 0f) Matrix.rotateM(scratchAxisQuat, 0, degZ, 0f, 0f, 1f)
        if (degY != 0f) Matrix.rotateM(scratchAxisQuat, 0, degY, 0f, 1f, 0f)
        if (degX != 0f) Matrix.rotateM(scratchAxisQuat, 0, degX, 1f, 0f, 0f)
        // Compose: local = currentLocal * axisRot (right-multiply in joint's local frame).
        Matrix.multiplyMM(scratchQuat, 0, localPose, j * 16, scratchAxisQuat, 0)
        System.arraycopy(scratchQuat, 0, localPose, j * 16, 16)
    }

    /**
     * Additively offset joint `j`'s local-pose origin (parent-space meters).
     * Used by the idle layer's CoM wander on the Root joint. Additive on the
     * translation column only — callers must re-base the joint each frame
     * (reset or absolute write first) or offsets accumulate.
     */
    fun composeJointTranslation(j: Int, tx: Float, ty: Float, tz: Float) {
        if (j < 0 || j >= jointCount) return
        val off = j * 16
        localPose[off + 12] += tx
        localPose[off + 13] += ty
        localPose[off + 14] += tz
    }

    /**
     * Compute `globalPose` and `palette` in a single forward pass over the joints.
     * Parents come before children (topological order is enforced in `init`), so
     * each child can multiply `parent.global * self.local` in place.
     *
     * After this call:
     *   globalPose[j] = (p >= 0) ? globalPose[p] * localPose[j] : localPose[j]
     *   palette[j]    = globalPose[j] * invBind[j]
     *
     * The palette is what the vertex shader consumes via the Joints UBO.
     */
    fun update() {
        for (j in 0 until jointCount) {
            val p = parents[j]
            val off = j * 16
            if (p < 0) {
                System.arraycopy(localPose, off, globalPose, off, 16)
            } else {
                Matrix.multiplyMM(globalPose, off, globalPose, p * 16, localPose, off)
            }
            Matrix.multiplyMM(palette, off, globalPose, off, invBind, off)
        }
    }

    /**
     * Transform a model-local (bind-pose) point by joint `j`'s current posed
     * transform. Used to re-anchor glute/chest/anatomy markers from bind space
     * into posed space each frame so Tier 3 vertex-push deform tracks the moving
     * body.
     *
     * @param local bind-space point (size 3)
     * @param out   posed-space point (size 3, may alias `local`)
     */
    fun transformPointByJoint(j: Int, local: FloatArray, out: FloatArray) {
        if (j < 0 || j >= jointCount) {
            out[0] = local[0]; out[1] = local[1]; out[2] = local[2]
            return
        }
        val off = j * 16
        val x = local[0]; val y = local[1]; val z = local[2]
        // Use PALETTE (globalPose × invBind), NOT globalPose alone.
        //
        // The vertex shader's skin op is `pos = palette[j] * v_bind` (for a
        // vertex rigidly weighted to joint j). For this function's result to
        // land at the same posed location as a bind-space point, we must
        // apply the same `palette[j]` transform. Applying `globalPose[j]`
        // would double-apply the joint's bind translation — the anchor
        // then flies to e.g. `globalPose × v_bind` which for a glute
        // anchor can end up by the head, exactly the "glute out of her
        // head" artifact the user reported.
        out[0] = palette[off + 0] * x + palette[off + 4] * y + palette[off + 8]  * z + palette[off + 12]
        out[1] = palette[off + 1] * x + palette[off + 5] * y + palette[off + 9]  * z + palette[off + 13]
        out[2] = palette[off + 2] * x + palette[off + 6] * y + palette[off + 10] * z + palette[off + 14]
    }
}
