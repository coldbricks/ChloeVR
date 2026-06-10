package com.ashairfoil.prism.scene

/**
 * D3: always-on skeletal idle layer — breath, weight sway, head life.
 *
 * Runs UNCONDITIONALLY every frame for every skinned model, dancing or not,
 * AFTER the dance loop (DANCE_REALISM_PLAN.md D3). Fixes two audited
 * artifacts:
 *
 *  1. Breath used to be a whole-model 3mm Y translation — her FEET sank and
 *     hovered against the real passthrough floor twice every 8 seconds, a
 *     grounding tell stereo vision catches at 1-3m. Breath now lives in the
 *     spine/clavicles/head where it belongs; feet stay planted.
 *  2. A non-dancing model received zero per-frame writes of any kind — the
 *     classic dead-mannequin AR moment. Perfect stillness is a top robotic
 *     tell; this layer keeps her subtly alive as a statue for ~8 joint
 *     writes/frame and zero allocations.
 *
 * Ordering contract (load-bearing — see D3 verifier note 2): the dance
 * groove block REPLACE-writes Neck/Head/Clavicles and the stance block
 * REPLACE-writes Waist every frame a skinned model dances, so this layer's
 * composes MUST run after the whole dance pass or they'd be wiped. When the
 * model did NOT dance this frame, nothing re-based the joints, so composes
 * would accumulate frame over frame — [apply] resets the idle joints to bind
 * first in that case.
 *
 * Axis choices reuse exactly the joints/axes the Tier-4 code already drives
 * successfully on Tripo rigs (Spine X, Clavicle -X lift, Head X/Y, Waist
 * X/Z), so the axis knowledge is proven in-tree (D3 verifier note 4).
 */
internal object IdleLayer {

    private const val TWO_PI = 2f * Math.PI.toFloat()

    /**
     * @param m            placed model (joint caches + head-life state)
     * @param skel         the model's skeleton (caller guarantees non-null/skinned)
     * @param danced       true when the dance loop wrote this model's joints
     *                     THIS frame (fresh bases → compose only)
     * @param tSec         session-relative monotonic seconds (small Float — see
     *                     monoClockBaseMs)
     * @param nowMs        SystemClock.uptimeMillis
     * @param musicalLevel 0 when no live audio; idle "listens" via ×(1+0.5·ml)
     */
    fun apply(
        m: SceneManager.PlacedModel,
        skel: SkeletonRuntime,
        danced: Boolean,
        tSec: Float,
        nowMs: Long,
        musicalLevel: Float
    ) {
        probeJoints(m, skel)
        if (m.fbmSeed == 0f) m.fbmSeed = (m.gpuModelId * 0.37f + 1.13f)
        val seed = m.fbmSeed

        if (!danced) {
            // No dance pass re-based these joints this frame — reset to bind
            // so the composes below stay absolute (no accumulation). Also
            // clears any stale dance pose left behind when dancing stopped.
            if (m.spine01JointIdx >= 0) skel.resetJointToBind(m.spine01JointIdx)
            if (m.spine02JointIdx >= 0) skel.resetJointToBind(m.spine02JointIdx)
            if (m.headJointIdx >= 0) skel.resetJointToBind(m.headJointIdx)
            if (m.claviceLJointIdx >= 0) skel.resetJointToBind(m.claviceLJointIdx)
            if (m.claviceRJointIdx >= 0) skel.resetJointToBind(m.claviceRJointIdx)
            if (m.waistJointIdx >= 0) skel.resetJointToBind(m.waistJointIdx)
            if (m.rootJointIdx >= 0) skel.resetJointToBind(m.rootJointIdx)
        }

        val live = 1f + 0.5f * musicalLevel.coerceIn(0f, 1f)

        // ── BREATH: chest rise at 0.22Hz, shoulders lag 90°, head counterphase ──
        val breathSin = kotlin.math.sin(TWO_PI * 0.22f * tSec + seed)
        val breathLag = kotlin.math.sin(TWO_PI * 0.22f * tSec + seed - 1.5707964f)
        if (m.spine01JointIdx >= 0) skel.composeJointEulerXYZ(m.spine01JointIdx, 0.6f * breathSin * live, 0f, 0f)
        if (m.spine02JointIdx >= 0) skel.composeJointEulerXYZ(m.spine02JointIdx, 0.5f * breathSin * live, 0f, 0f)
        // Clavicle lift = NEGATIVE X (matches the groove block's -shoulderLift).
        if (m.claviceLJointIdx >= 0) skel.composeJointEulerXYZ(m.claviceLJointIdx, -0.4f * breathLag * live, 0f, 0f)
        if (m.claviceRJointIdx >= 0) skel.composeJointEulerXYZ(m.claviceRJointIdx, -0.4f * breathLag * live, 0f, 0f)

        // ── WAIST: breath pitch (moved here from model space, D3) + weight sway ──
        // Two incommensurate sines never repeat — same trick as the fBm drift.
        val waistX = 0.3f * kotlin.math.sin(TWO_PI * 0.3f * tSec + seed * 1.3f) * live
        val waistZ = 0.8f * (kotlin.math.sin(TWO_PI * 0.07f * tSec + seed) +
            0.6f * kotlin.math.sin(TWO_PI * 0.13f * tSec + seed * 0.7f)) * live
        if (m.waistJointIdx >= 0) skel.composeJointEulerXYZ(m.waistJointIdx, waistX, 0f, waistZ)

        // ── HEAD LIFE: breath counterphase nod + micro-yaw retargeted every 4-8s ──
        if (m.headJointIdx >= 0) {
            if (m.idleHeadNextMs == 0L || nowMs >= m.idleHeadNextMs) {
                m.idleHeadYawFromDeg = m.idleHeadYawToDeg
                // Deterministic hash-based "random" — zero-alloc, decorrelated
                // across models via gpuModelId (same family as the bar hash).
                val h1 = ((nowMs + m.gpuModelId * 40503L) * 2654435761L ushr 16) and 0xFFFFL
                m.idleHeadYawToDeg = ((h1.toFloat() / 65535f) * 2f - 1f) * 1.5f
                // gpuModelId in BOTH hashes — without it in h2 every model
                // re-rolled the same interval and flicked heads in lockstep.
                val h2 = (((nowMs + m.gpuModelId * 104729L) xor 0x5DEECE66DL) * 25214903917L ushr 16) and 0xFFFFL
                m.idleHeadMoveStartMs = nowMs
                m.idleHeadNextMs = nowMs + 4000L + (h2 % 4000L)
            }
            val mvT = ((nowMs - m.idleHeadMoveStartMs) / 400f).coerceIn(0f, 1f)
            val mvEase = mvT * mvT * (3f - 2f * mvT)
            val headYaw = m.idleHeadYawFromDeg + (m.idleHeadYawToDeg - m.idleHeadYawFromDeg) * mvEase
            skel.composeJointEulerXYZ(m.headJointIdx, -0.25f * breathSin * live, headYaw, 0f)
        }

        // ── CoM WANDER: ±5mm Root X at 0.05Hz — statue only. When dancing,
        // the squat path owns Root (and only re-bases it on some paths), and
        // the dance already provides CoM motion; composing an additive
        // translation onto an un-re-based Root would accumulate.
        if (!danced && m.rootJointIdx >= 0) {
            skel.composeJointTranslation(m.rootJointIdx,
                0.005f * kotlin.math.sin(TWO_PI * 0.05f * tSec + seed * 2.1f) * live, 0f, 0f)
        }
    }

    /**
     * Lazy joint probe — a statue model may reach the idle layer before any
     * dance block cached its joints. Alias lists mirror the groove/stance/
     * squat caches exactly; probes are idempotent so whichever block runs
     * first wins and the others no-op or re-derive the same indices.
     */
    private fun probeJoints(m: SceneManager.PlacedModel, skel: SkeletonRuntime) {
        if (m.spine01JointIdx != Int.MIN_VALUE && m.spine02JointIdx != Int.MIN_VALUE &&
            m.headJointIdx != Int.MIN_VALUE && m.claviceLJointIdx != Int.MIN_VALUE &&
            m.claviceRJointIdx != Int.MIN_VALUE && m.waistJointIdx != Int.MIN_VALUE &&
            m.rootJointIdx != Int.MIN_VALUE) return
        fun find(vararg names: String): Int {
            for (n in names) { val i = skel.indexOf(n); if (i >= 0) return i }
            return -1
        }
        if (m.spine01JointIdx == Int.MIN_VALUE) m.spine01JointIdx = find("Spine01", "Spine", "mixamorig:Spine")
        if (m.spine02JointIdx == Int.MIN_VALUE) m.spine02JointIdx = find("Spine02", "Chest", "UpperChest", "mixamorig:Spine1")
        if (m.headJointIdx == Int.MIN_VALUE) m.headJointIdx = find("Head", "mixamorig:Head")
        if (m.claviceLJointIdx == Int.MIN_VALUE) m.claviceLJointIdx = find("L_Clavicle", "LeftShoulder", "mixamorig:LeftShoulder")
        if (m.claviceRJointIdx == Int.MIN_VALUE) m.claviceRJointIdx = find("R_Clavicle", "RightShoulder", "mixamorig:RightShoulder")
        if (m.waistJointIdx == Int.MIN_VALUE) m.waistJointIdx = find("Waist", "Spine", "Spine0", "LowerSpine", "mixamorig:Spine")
        if (m.rootJointIdx == Int.MIN_VALUE) m.rootJointIdx = find("Root")
    }
}
