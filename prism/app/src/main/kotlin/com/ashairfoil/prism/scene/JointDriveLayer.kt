package com.ashairfoil.prism.scene

import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 2 per-joint articulation layer. Composes WITH the rigid-body DancePreset
 * engine — does NOT replace Tier 4 joint writes.
 *
 * Composition model (locked in with user, captured in NOTES.md 2026-04-20):
 *   - Tier 4 owns Pelvis, Waist, Root, L/R_Thigh, L/R_Calf (biomechanics-coded).
 *   - JointDriveLayer owns Spine01, Spine02, L/R_Clavicle, L/R_Upperarm,
 *     L/R_Forearm, Head, optionally twist bones.
 *   - Overlap is tolerated: the layer calls [SkeletonRuntime.composeJointEulerXYZ]
 *     which right-multiplies the rotation onto whatever the prior writer left
 *     in `localPose`. Tier 4 writes its baseline via `setJointEulerXYZ` FIRST,
 *     then the layer composes its oscillation on top.
 *
 * Each [JointDrive] describes a single Euler axis of a single joint as a
 * two-harmonic Fourier function of beat-phase plus a rest-pose offset:
 *     angle(ph) = restDeg + A1 * sin(2π*rate*ph + phi1) + A2 * sin(4π*rate*ph + phi2)
 *
 * The extractor (tools/mixamo_extract/extract_preset.py, Phase 2 widening)
 * produces these parameters from Mixamo clips with per-bone axis calibration
 * baked in OFFLINE — the runtime never calibrates, it just evaluates.
 *
 * Zero-alloc hot path per CLAUDE.md invariant #6:
 *   - [drives] is Array<JointDrive>, not List — no iterator allocation.
 *   - [jointIndexCache] resolved once per skeleton and reused.
 *   - Per-joint XYZ accumulator is pre-allocated (no HashMap, no Set). Drives
 *     that share a joint share a slot in the accumulator so all axes combine
 *     into one `composeJointEulerXYZ` call at write time — this is what fixes
 *     the "multi-axis drive on same joint silently loses two axes" bug that
 *     disabled the layer at runtime.
 *   - No when-expression allocation in [evaluate] — explicit if chain.
 *
 * Runtime entry point: see the call site in FilamentModelActivity's per-frame
 * dance block, AFTER the rigid-body rotation writes but BEFORE `skel.update()`
 * composes the palette.
 */
class JointDriveLayer(val drives: Array<JointDrive>) {

    /** Cached joint indices per drive — resolved lazily on first use against a skeleton. */
    private val jointIndexCache: IntArray = IntArray(drives.size) { -2 }
    /**
     * Maps each drive index → the accumulator slot its joint owns. Drives that
     * share a joint share a slot. Filled alongside [jointIndexCache] on skeleton
     * identity change.
     */
    private val accumIdxPerDrive: IntArray = IntArray(drives.size)
    /** Slot → joint index. Only entries in [0, uniqueCount) are valid. */
    private val accumJointIdx: IntArray = IntArray(drives.size)
    /** Per-slot accumulated X/Y/Z degrees for the current evaluate call. */
    private val accumX: FloatArray = FloatArray(drives.size)
    private val accumY: FloatArray = FloatArray(drives.size)
    private val accumZ: FloatArray = FloatArray(drives.size)
    /** Number of distinct joints referenced by any resolved drive. */
    private var uniqueCount: Int = 0
    private var lastSkelIdentity: SkeletonRuntime? = null

    /**
     * Write posed rotations into [skel.localPose] for every drive in this layer.
     *
     * @param beatPhase  Current beat phase in [0, 1). Wrap is caller's problem.
     * @param skel       Target skeleton. Per-drive joint lookups cached.
     * @param ampGain    Global amplitude multiplier. Passed through from the
     *                   dance engine's accentGain * moodGain * musicalLevel
     *                   so the layer breathes with the music identically to
     *                   the rigid-body motion. The restAngleDeg offset is NOT
     *                   scaled by ampGain — it's a fixed bind-relative bias.
     */
    fun evaluate(beatPhase: Float, skel: SkeletonRuntime, ampGain: Float) {
        // Re-resolve joint indices + accumulator layout if the skeleton changed.
        if (skel !== lastSkelIdentity) {
            lastSkelIdentity = skel
            uniqueCount = 0
            for (i in drives.indices) {
                val jIdx = skel.indexOf(drives[i].jointName)
                jointIndexCache[i] = jIdx
                if (jIdx < 0) {
                    accumIdxPerDrive[i] = -1
                    continue
                }
                // Find an existing slot for this joint, else allocate one.
                var slot = -1
                for (s in 0 until uniqueCount) {
                    if (accumJointIdx[s] == jIdx) { slot = s; break }
                }
                if (slot < 0) {
                    slot = uniqueCount
                    accumJointIdx[slot] = jIdx
                    uniqueCount++
                }
                accumIdxPerDrive[i] = slot
            }
        }

        // Zero the accumulator slice for this frame.
        for (s in 0 until uniqueCount) {
            accumX[s] = 0f
            accumY[s] = 0f
            accumZ[s] = 0f
        }

        // Sum per-axis drive contributions into each joint's accumulator slot.
        val twoPi = (2.0 * PI).toFloat()
        for (i in drives.indices) {
            val slot = accumIdxPerDrive[i]
            if (slot < 0) continue
            val d = drives[i]
            val rate = d.rateCyclesPerMeasure.toFloat()
            val ph1 = beatPhase * rate + d.phaseOffset
            val ph2 = beatPhase * rate * 2f + d.phase2ndOffset
            val osc = (d.amplitudeDeg * sin(twoPi * ph1) +
                       d.amplitude2ndDeg * sin(twoPi * ph2)) * ampGain
            val angle = d.restAngleDeg + osc
            val ax = d.axis
            if (ax == AXIS_X) accumX[slot] += angle
            else if (ax == AXIS_Y) accumY[slot] += angle
            else if (ax == AXIS_Z) accumZ[slot] += angle
        }

        // Single compose call per unique joint — combines all axes in Z→Y→X
        // order inside composeJointEulerXYZ. Right-multiplies onto whatever
        // Tier 4 left in localPose, so biomechanics writes survive.
        for (s in 0 until uniqueCount) {
            skel.composeJointEulerXYZ(accumJointIdx[s], accumX[s], accumY[s], accumZ[s])
        }
    }

    companion object {
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_Z = 2
    }
}

/**
 * One Fourier-parameterised rotation drive for one joint axis.
 *
 * `axis` values are in the TARGET RIG'S local frame — the extractor applies
 * per-bone calibration quats at build time so the runtime can treat them as
 * opaque integers (JointDriveLayer.AXIS_X/Y/Z).
 *
 * `rateCyclesPerMeasure` follows the same convention as DancePreset.yawRate:
 * 4 = one cycle per beat, 8 = two cycles per beat, 16 = four, etc.
 *
 * `phaseOffset` ∈ [0, 1) is the cycle fraction at which the sinusoid reaches
 * its zero-crossing with positive slope. Matches `DancePreset.yawPhase`.
 *
 * Amplitudes are signed degrees, centered on the rest offset — NOT peak-to-peak.
 * A1 of 10° means the joint swings from rest-10° to rest+10°.
 *
 * `restAngleDeg` is the bind-relative rest-pose offset emitted by the Phase 2
 * extractor (2026-04-20). It lets clips like "Arms Hip Hop" encode the
 * ~30° arms-down-at-sides baseline without forcing the oscillation amplitude
 * to cover the static component. Defaults to 0f so pre-restAngle call sites
 * keep compiling.
 */
data class JointDrive(
    val jointName: String,
    val axis: Int,
    val rateCyclesPerMeasure: Int,
    val phaseOffset: Float,
    val amplitudeDeg: Float,
    val amplitude2ndDeg: Float = 0f,
    val phase2ndOffset: Float = 0f,
    val restAngleDeg: Float = 0f,
)
