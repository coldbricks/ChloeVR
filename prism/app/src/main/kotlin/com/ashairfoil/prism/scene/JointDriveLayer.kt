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
 *   - No conflicts because the driven sets don't overlap.
 *
 * Each [JointDrive] describes a single Euler axis of a single joint as a
 * two-harmonic Fourier function of beat-phase:
 *     angle(ph) = A1 * sin(2π*rate*ph + phi1) + A2 * sin(4π*rate*ph + phi2)
 *
 * The extractor (tools/mixamo_extract/extract_preset.py, Phase 2 widening)
 * produces these parameters from Mixamo clips with per-bone axis calibration
 * baked in OFFLINE — the runtime never calibrates, it just evaluates.
 *
 * Zero-alloc hot path per CLAUDE.md invariant #6:
 *   - [drives] is Array<JointDrive>, not List — no iterator allocation.
 *   - [jointIndexCache] is resolved once per skeleton and reused.
 *   - No when-expression allocation in [evaluate] — explicit if chain.
 *
 * Runtime entry point: see the call site added in FilamentModelActivity's
 * per-frame dance block, AFTER the rigid-body rotation writes but BEFORE
 * `skel.update()` composes the palette.
 */
class JointDriveLayer(val drives: Array<JointDrive>) {

    /** Cached joint indices per drive — resolved lazily on first use against a skeleton. */
    private var jointIndexCache: IntArray = IntArray(drives.size) { -2 }
    private var lastSkelIdentity: SkeletonRuntime? = null

    /**
     * Write posed rotations into [skel.localPose] for every drive in this layer.
     *
     * @param beatPhase  Current beat phase in [0, 1). Wrap is caller's problem.
     * @param skel       Target skeleton. Per-drive joint lookups cached.
     * @param ampGain    Global amplitude multiplier. Passed through from the
     *                   dance engine's accentGain * moodGain * musicalLevel
     *                   so the layer breathes with the music identically to
     *                   the rigid-body motion.
     */
    fun evaluate(beatPhase: Float, skel: SkeletonRuntime, ampGain: Float) {
        // Re-resolve joint indices if the skeleton identity changed.
        if (skel !== lastSkelIdentity) {
            lastSkelIdentity = skel
            for (i in drives.indices) {
                jointIndexCache[i] = skel.indexOf(drives[i].jointName)
            }
        }
        val twoPi = (2.0 * PI).toFloat()
        for (i in drives.indices) {
            val jIdx = jointIndexCache[i]
            if (jIdx < 0) continue
            val d = drives[i]
            val rate = d.rateCyclesPerMeasure.toFloat()
            val ph1 = beatPhase * rate + d.phaseOffset
            val ph2 = beatPhase * rate * 2f + d.phase2ndOffset
            val angle = (d.amplitudeDeg * sin(twoPi * ph1) +
                         d.amplitude2ndDeg * sin(twoPi * ph2)) * ampGain
            when (d.axis) {
                AXIS_X -> skel.setJointEulerX(jIdx, angle)
                AXIS_Y -> skel.setJointEulerY(jIdx, angle)
                AXIS_Z -> skel.setJointEulerZ(jIdx, angle)
            }
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
 * Amplitudes are signed degrees, centered on bind pose — NOT peak-to-peak.
 * A1 of 10° means the joint swings from bind-10° to bind+10°.
 */
data class JointDrive(
    val jointName: String,
    val axis: Int,
    val rateCyclesPerMeasure: Int,
    val phaseOffset: Float,
    val amplitudeDeg: Float,
    val amplitude2ndDeg: Float = 0f,
    val phase2ndOffset: Float = 0f,
)
