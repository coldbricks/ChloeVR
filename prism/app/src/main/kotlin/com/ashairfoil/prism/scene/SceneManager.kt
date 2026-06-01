package com.ashairfoil.prism.scene

import android.content.Context
import android.util.Log
import com.ashairfoil.prism.GlesModelRenderer
import java.io.File

/**
 * Manages the 3D model scene: loading/unloading GLB models, saving/loading scene JSON,
 * and computing model transforms. Extracted from FilamentModelActivity to keep the
 * activity focused on input handling and UI rendering.
 */
class SceneManager(
    private val context: Context,
    private val renderer: GlesModelRenderer
) {
    companion object {
        private const val TAG = "ChloeVR-SceneManager"

        /** Apply an easing curve to t in [0, 1]. Returns eased value in [0, 1]
         *  (or slightly beyond for BACK, which intentionally overshoots). */
        fun applyEase(t: Float, ease: DanceEase): Float {
            val tt = t.coerceIn(0f, 1f)
            return when (ease) {
                DanceEase.SINE -> 0.5f - 0.5f * kotlin.math.cos(Math.PI * tt).toFloat()
                DanceEase.LINEAR -> tt
                DanceEase.CUBIC -> tt * tt * (3f - 2f * tt)  // smoothstep
                DanceEase.EXPO -> {
                    // Ease-in-out expo: near-flat middle, sharp peaks
                    if (tt < 0.5f) Math.pow(2.0, 20.0 * tt - 10.0).toFloat() / 2f
                    else (2f - Math.pow(2.0, -20.0 * tt + 10.0).toFloat()) / 2f
                }
                DanceEase.CIRC -> {
                    if (tt < 0.5f) (1f - kotlin.math.sqrt(1f - (2f * tt) * (2f * tt))) / 2f
                    else (kotlin.math.sqrt(1f - (-2f * tt + 2f).let { it * it }) + 1f) / 2f
                }
                DanceEase.BACK -> {
                    val c1 = 1.70158f
                    val c2 = c1 * 1.525f
                    if (tt < 0.5f) (2f * tt).let { it * it * ((c2 + 1f) * 2f * it - c2) } / 2f
                    else ((2f * tt - 2f).let { it * it * ((c2 + 1f) * it + c2) } + 2f) / 2f
                }
            }
        }

        /** Evaluate the dance waveform at phase (0..1). Returns signed amplitude
         *  in ~[-1, +1]. Swing: 0 → +1 → 0 → -1 → 0 per cycle, eased per quarter. */
        fun waveAt(phase: Float, ease: DanceEase): Float {
            val p = ((phase % 1f) + 1f) % 1f
            val q = (p * 4f).toInt().coerceIn(0, 3)
            val t = (p * 4f) - q
            val eased = applyEase(t, ease)
            return when (q) {
                0 -> eased
                1 -> 1f - eased
                2 -> -eased
                else -> -1f + eased
            }
        }

        /** Tier 3 — Motion Expression ("character") shaping on top of waveAt.
         *  sharpness 0..1 blends the eased wave toward a true triangle wave
         *  (cusp'd peaks, constant velocity between them) — at 1 the hips
         *  change direction on a dime. complexity 0..1 layers a 2×-rate sine
         *  ghost at 0.4× amplitude — creates 1/8 accents over a 1/4 primary
         *  without asking the user to pick a new rate. Both zero → waveAt. */
        fun shapedWaveAt(phase: Float, ease: DanceEase, sharpness: Float, complexity: Float): Float {
            val p = ((phase % 1f) + 1f) % 1f
            val base = waveAt(p, ease)
            // Triangle wave matching sine's zero-crossings: p=0 → 0, p=0.25 → +1,
            // p=0.5 → 0, p=0.75 → -1, p=1 → 0. Cheaper than asin-based form,
            // no allocations, exact cusps at ±1.
            val tri = 1f - 4f * kotlin.math.abs(((p + 0.25f) % 1f) - 0.5f)
            val s = sharpness.coerceIn(0f, 1f)
            val shaped = if (s > 0.001f) base + (tri - base) * s else base
            val c = complexity.coerceIn(0f, 1f)
            if (c < 0.001f) return shaped
            val ghostPh = (p * 2f) % 1f
            val ghost = kotlin.math.sin(2.0 * Math.PI * ghostPh).toFloat()
            return shaped + c * 0.4f * ghost
        }
    }

    // ── PlacedModel ──

    data class PlacedModel(
        val file: File,
        val asset: Any?,
        var gpuModelId: Int = -1,
        var scale: Float = 1f,
        var baseScale: Float = 1f,
        var posX: Float = 0f,
        var posY: Float = 0f,
        var posZ: Float = -1f,
        var rotX: Float = 0f,
        var rotY: Float = 0f,
        var rotZ: Float = 0f,
        var rotW: Float = 1f,
        var metallic: Float = 0f,
        var roughness: Float = 0.9f,
        var exposure: Float = 0f,
        var contrast: Float = 1f,
        var saturation: Float = 1f,
        // ── Persistent anchor state ──
        // 16-byte UUID from the OpenXR persistence context once the async persist
        // completes, or null when the model is not (yet) anchored. Stored in
        // anchors.json keyed by UUID plus included in scene JSON for completeness.
        var anchorUuid: ByteArray? = null,
        // Pending client handle from submitCreate — matches against CreateEvent later.
        // Not persisted; reset each session.
        var pendingAnchorHandle: Int = -1,
        // Preview-only models float above the menu panel for inspection before the
        // user commits them to the scene. Skipped by hover/grab/gizmo and never
        // anchored. Flipped off by "Add to scene" in the GLB picker.
        var isPreview: Boolean = false,
        // Beat-driven pose animation: lerp between poseA and poseB each frame,
        // driven by audioReactor.boxFillPct. When both anchors are set, the model
        // oscillates in time with the beat. `animResponse` scales the amplitude.
        var animHasA: Boolean = false,
        var animHasB: Boolean = false,
        var animResponse: Float = 1f,
        val animPoseA: FloatArray = FloatArray(7),   // px,py,pz,rx,ry,rz,rw
        val animPoseB: FloatArray = FloatArray(7),
        // ── Multi-axis DANCE mode ──
        // Captures a single base pose and layers sinusoidal oscillation on top
        // of it along independent axes. Each axis can run at its own tempo
        // subdivision (set via danceYawRate etc.) so booty shakes at 1/16 while
        // the bend happens at 1/4. When any amplitude is non-zero + animHasBase
        // is true, dance takes priority over the A/B shake above.
        var animHasBase: Boolean = false,
        val animBasePose: FloatArray = FloatArray(7),
        // Defaults captured from user's end-of-session screenshot — the
        // "booty-master" preset. YAW at full 15° drives hip gyration, pitch
        // and bob stay near-zero because yaw + gyration reads best alone.
        // Rates 1/2 / 1/4 / 1/4 match the subdivisions the user settled on.
        var danceYawDeg: Float = 15f,
        var danceYawRate: Int = 2,
        var dancePitchDeg: Float = 0f,
        var dancePitchRate: Int = 4,
        var danceYMeters: Float = 0f,
        var danceYRate: Int = 4,
        // Per-axis phase offsets (0..1). Shuffle randomises these so moves
        // don't all peak at the exact same instant.
        var danceYawPhase: Float = 0f,
        var dancePitchPhase: Float = 0.25f,
        var danceYPhase: Float = 0f,
        // Easing curve applied to each quarter of the oscillation. Default SINE
        // is the smoothest / most neutral; BACK adds an overshoot for attitude,
        // EXPO for a punchy "whip then hold" snap, LINEAR for robotic metronome.
        var danceEase: DanceEase = DanceEase.LINEAR,
        // IMPROV: when on, the dance auto-shuffles every `improvBars` bars of
        // the locked BPM — so the model spontaneously swaps between booty shake,
        // ass bend, jump, etc. without manual intervention. Feels like jamming.
        var danceImprov: Boolean = false,
        var improvBars: Int = 4,
        var lastImprovMs: Long = 0L,
        // Physics feel — gives the unrigged wax model mass + weight cues:
        //  * inertia lag: body chases the dance target instead of snapping
        //  * squash & stretch on bob: Y-scale expands on the way up, compresses
        //    on landing, with compensatory X/Z so volume is preserved
        // 0 = off (original crisp motion), 1 = full wobble jelly.
        var physicsAmount: Float = 0.35f,
        var scaleMulX: Float = 1f,
        var scaleMulY: Float = 1f,
        var scaleMulZ: Float = 1f,
        // Beat-event tracking for impact kicks
        var lastBeatSeen: Long = 0L,
        var impactKickStartMs: Long = 0L,
        var impactKickAxis: Int = 0,  // 0 = pitch, 1 = yaw, 2 = roll-ish via yaw twist
        // Damped harmonic "jiggle" spring state — flesh rings after squash.
        var jiggleX: Float = 0f, var jiggleVelX: Float = 0f,
        var jiggleY: Float = 0f, var jiggleVelY: Float = 0f,
        var jiggleZ: Float = 0f, var jiggleVelZ: Float = 0f,
        var lastDanceNanos: Long = 0L,
        // Per-model fBm seed so the "slow random amplitude drift" is uncorrelated
        // between axes (prevents them all breathing in unison).
        var fbmSeed: Float = 0f,
        // ── BREATH (Tier1-G): gaze-saccade FSM (Tier1-F) state ──
        // Camera-aware yaw bias. Behaves like a dancer glancing at the viewer:
        // hold a heading 2–6s, then SACCADE (fast snap with BACK overshoot) to
        // re-target, then FREEZE all dance axes for 80–120ms on arrival.
        // gazeState: 0=HOLD, 1=SACCADE, 2=FREEZE
        var gazeState: Int = 0,
        var gazeNextCheckMs: Long = 0L,
        var gazeSaccadeStartMs: Long = 0L,
        var gazeSaccadeDurationMs: Long = 200L,
        var gazeSaccadeFromRad: Float = 0f,
        var gazeSaccadeToRad: Float = 0f,
        var gazeFreezeEndsMs: Long = 0L,
        var gazeCurrentBiasRad: Float = 0f,
        // The dir-to-camera yaw captured on dance arm. Target bias = current
        // dir-to-camera - anchor. Means "she keeps the same relative orientation
        // to the viewer as when you armed her."
        var gazeFaceCamAnchorRad: Float = 0f,
        var gazeFaceCamHasCapture: Boolean = false,
        // Track last camera Y for vestibular mirroring (user's bob feeds model).
        var lastSeenCamPosY: Float = 0f,
        var camYVelSmooth: Float = 0f,
        // Tier1.5-intensity: single knob multiplier for all three axis amps.
        // Default 0.25× because anything above reads as cartoony — real dance
        // motion is small. User can crank it if they want but 0.25 is sweet spot.
        var danceIntensity: Float = 0.5f,
        // Tier1.5-gaze-toggle: follow-gaze (saccade FSM) on/off per model.
        // When false, FSM never captures even if dance is armed.
        var danceGazeFollow: Boolean = false,
        // Tier1.5-roll: third dance axis. Applied in LOCAL frame so bank
        // matches the model's facing direction regardless of base orientation.
        var danceRollDeg: Float = 0f,
        var danceRollRate: Int = 4,
        var danceRollPhase: Float = 0f,
        // Tier2-pivot: dynamic pivot selection per axis. Fractional height along
        // the model's Y bbox (0 = feet, 1 = head). COUNTERINTUITIVE: to make
        // the hips swing, pivot at SHOULDERS. Part AT the pivot stays still.
        // pivotEnabled=false → pre-pivot behavior (rotate about model origin).
        // Presets set these via shuffleDance so it's opt-in per preset.
        var pivotEnabled: Boolean = false,
        var pitchPivotFrac: Float = 0.85f,    // shoulders by default
        var rollPivotFrac: Float = 0.85f,
        // Counter-roll always pivots at hips (per biomechanics research) —
        // keeps the "shoulders counter-drag the yaw" spinal-twist illusion
        // regardless of which pivot the main yaw/pitch use.
        var counterRollPivotFrac: Float = 0.50f,
        // Gain for the counter-roll amplitude. TWERK wants this near 0 (shoulders
        // MUST NOT react); SWAY wants 0.5 (shoulder lag is the whole move).
        var counterRollGain: Float = 0.35f,
        // Display: which preset was last rolled onto this model, for UI feedback.
        var currentPresetName: String = "",
        // User-marked anatomy anchors. Fraction of bbox height where the hip /
        // shoulder actually sit on THIS particular GLB (so presets that say
        // "pivot at 0.85 = shoulder" land on the model's real shoulder, not
        // a generic proportion). -1 = not marked, use 0.45 / 0.85 defaults.
        var markedHipFrac: Float = -1f,
        var markedShoulderFrac: Float = -1f,
        var markedKneeFrac: Float = -1f,
        // Face mark: captured angle (in model LOCAL yaw frame) of the direction
        // from model center to the viewer at mark time. Once set, the "front
        // dot" is rendered on that side of her body and rotates WITH her as
        // she spins (because we convert local → world via current model yaw).
        // NaN = not marked; no dot rendered.
        var markedFaceLocalYaw: Float = Float.NaN,
        // Foot anchor: captured heel position in world when dance is armed.
        // Each frame, after rotations are applied, we compute the current foot
        // world position and pull the model back by (drift × strength) so the
        // heels don't skate across the floor when the body pivots around the
        // shoulders/chest. Strength 1.0 = fully planted; 0 = current (skating).
        var footAnchorCaptured: Boolean = false,
        var footAnchorX: Float = 0f,
        var footAnchorZ: Float = 0f,
        var footAnchorStrength: Float = 0.85f,
        // ── Tier 3: Motion Expression (per-axis "character") ──
        // Sharpness (0..1) blends the eased wave toward a true triangle —
        // at 0 motion is round, at 1 hips change direction on a dime.
        // Complexity (0..1) layers a 2×-rate ghost over the primary signal:
        // 1/8 accents sitting on a 1/4 primary, syncopated groove without
        // picking a new rate.
        // Defaults 0 preserve pre-Tier3 motion exactly; presets (TWERK,
        // BOUNCE, etc.) crank sharpness to give each style a recognisable
        // feel beyond amp×rate×ease alone.
        var yawSharpness: Float = 0f,
        var yawComplexity: Float = 0.34f,
        var pitchSharpness: Float = 0f,
        var pitchComplexity: Float = 0.34f,
        var bobSharpness: Float = 0f,
        var bobComplexity: Float = 0.34f,
        // ── Tier 3 Feature 4: glute deformation ──
        // Vertex-shader push-out at two symmetric points near the hip, beat-
        // synced so bass kicks spike the push amount. A/B positions are auto-
        // derived each frame from `markedHipFrac` (or 0.45 default) + bbox.
        // gluteBasePush = 0 disables the feature (CPU zeroes uniforms → shader
        // skips). gluteCurrentPush is per-frame scratch (basePush × kick boost).
        var gluteBasePush: Float = 0f,        // meters, 0..0.08
        var gluteRadius: Float = 0.15f,       // meters influence radius
        var gluteShakeIntensity: Float = 0.5f,// 0..1 kick-spike multiplier
        var gluteCurrentPush: Float = 0f,     // per-frame (basePush × boost)
        var gluteLeftCurrentPush: Float = 0f, // per-side scratch (alt-step support)
        var gluteRightCurrentPush: Float = 0f,
        var gluteKickLastMs: Long = 0L,       // timestamp of most recent spike
        var gluteLastBeatSeen: Long = 0L,     // tracks snap.beatCounter
        // Tier 3.2 — glute rhythm controls.
        // gluteRate: 1 = every beat, 2 = every 8th note, 4 = every 16th.
        // When > 1, we use a sub-beat counter (BPM-derived) instead of the
        // blunt snap.beatCounter so the pop fires at the right subdivision.
        var gluteRate: Int = 1,
        var gluteLastSubBeat: Long = 0L,
        // BOOTY SHAKER — override mode. When true, fires rapid L-R-L-R
        // alternation during the last beat of every 4-beat bar (1/32 rate →
        // 8 pops in one beat, then 3 beats silent). Per-frame scratch fields
        // hold which side should fire THIS frame.
        var gluteShakerMode: Boolean = false,
        var gluteShakerSideL: Boolean = false,
        var gluteShakerSideR: Boolean = false,
        // Tier 4 — cached joint indices for the dance driver. -1 = looked up
        // and absent from the rig. Int.MIN_VALUE = not yet cached.
        var kneeLJointIdx: Int = Int.MIN_VALUE,
        var kneeRJointIdx: Int = Int.MIN_VALUE,
        var thighLJointIdx: Int = Int.MIN_VALUE,
        var thighRJointIdx: Int = Int.MIN_VALUE,
        var rootJointIdx: Int = Int.MIN_VALUE,
        var spine01JointIdx: Int = Int.MIN_VALUE,
        var spine02JointIdx: Int = Int.MIN_VALUE,
        var neckJointIdx: Int = Int.MIN_VALUE,
        var headJointIdx: Int = Int.MIN_VALUE,
        var claviceLJointIdx: Int = Int.MIN_VALUE,
        var claviceRJointIdx: Int = Int.MIN_VALUE,
        var upperArmLJointIdx: Int = Int.MIN_VALUE,
        var upperArmRJointIdx: Int = Int.MIN_VALUE,
        var forearmLJointIdx: Int = Int.MIN_VALUE,
        var forearmRJointIdx: Int = Int.MIN_VALUE,
        var pelvisJointIdx: Int = Int.MIN_VALUE,
        var waistJointIdx: Int = Int.MIN_VALUE,
        // Tier 4 — ARCH stance. 0 = bind pose (KNOWN GOOD), 1 = full booty
        // stance. Default returned to 0 after on-device testing revealed
        // sign-convention issues with the current stance math (pelvis rolls
        // wrong direction, waist flattens instead of arching, feet drift
        // through the floor via hierarchy propagation). The stance code is
        // still in place for controlled testing — user dials up to see.
        // Future fix: systematic sign probe on a spec-compliant rig to lock
        // down the correct direction per joint, then bake constants.
        var stanceArch: Float = 0f,
        // Per-side enables — lets the user isolate glute L or R, or run alt-step.
        var gluteLeftEnabled: Boolean = true,
        var gluteRightEnabled: Boolean = true,
        var gluteAltStep: Boolean = false,    // L on even beat, R on odd
        // Set true when the user drags a CHARACTER-panel slider (sharpness /
        // complexity). While true, shuffleDance (auto IMPROV) preserves
        // the user's custom values. setDancePreset (explicit pick) clears it.
        var characterCustomized: Boolean = false,
        // Phase 2 — active per-joint articulation layer for this model. Copied
        // from the active DancePreset.jointLayer each time a preset is applied
        // (shuffleDance / setDancePreset). Null = pure Tier 4 rigid-body only.
        var jointLayer: JointDriveLayer? = null,
        // Pose override: if true, the Tier 4 arm block writes a hands-on-knees
        // pose instead of the default drop+sway. Copied from the active
        // preset's handsOnKnees flag.
        var handsOnKnees: Boolean = false,
        // Set true when the user drags a main-panel AMP slider (YAW, PITCH,
        // BOB, PHYSICS). IMPROV's per-bar shuffleDance then leaves the amp
        // fields alone — only rates/phases/easing shuffle. Explicit preset
        // pick via setDancePreset clears the flag.
        var dancingCustomized: Boolean = false,
        // Explicit glute marks (model-local mesh coordinates — same frame
        // as the vertex shader's aPosition). When set, override the auto-
        // derived bbox approximation that put glutes in the wrong place on
        // models whose "front" isn't local +Z. NaN = unmarked.
        var markedGluteL_x: Float = Float.NaN,
        var markedGluteL_y: Float = Float.NaN,
        var markedGluteL_z: Float = Float.NaN,
        var markedGluteR_x: Float = Float.NaN,
        var markedGluteR_y: Float = Float.NaN,
        var markedGluteR_z: Float = Float.NaN
    )

    /** Easing curves available for dance motion — mirrors ShapesXR's options. */
    enum class DanceEase { SINE, LINEAR, CUBIC, EXPO, CIRC, BACK }

    // ── Yeet animation ──

    data class YeetingModel(
        val gpuModelId: Int,
        var posX: Float, var posY: Float, var posZ: Float,
        var velX: Float, var velY: Float, var velZ: Float,
        var scale: Float,
        var spin: Float = 0f,
        var timer: Float = 0f
    )

    // ── Public state ──

    val models: MutableList<PlacedModel> = java.util.concurrent.CopyOnWriteArrayList()
    var selectedModelIndex = -1
    val yeetingModels = ArrayList<YeetingModel>()
    val yeetingModelsLock = Object()

    /** Callback for posting work to the UI thread (set by the activity). */
    var runOnUiThread: (Runnable) -> Unit = {}

    /** Callback for showing a toast/message (set by the activity). */
    var showMessage: (String) -> Unit = {}

    // ── Model loading ──

    /**
     * Load a GLB file and add it to the scene.
     * Must be called on a thread with a current GL context.
     *
     * Auto-scales based on bounding box to a comfortable VR viewing size (~0.5m),
     * and places the model in front of the camera rather than at world origin.
     *
     * @param detectedFloorY current detected floor Y for auto-snap, or Float.MIN_VALUE if unknown
     * @param camPosX/Y/Z current camera (head) position
     * @param camFwdX/Y/Z current camera forward direction
     */
    fun loadModel(
        file: File, offsetIndex: Int = 0, detectedFloorY: Float = Float.MIN_VALUE,
        camPosX: Float = 0f, camPosY: Float = 1.6f, camPosZ: Float = 0f,
        camFwdX: Float = 0f, camFwdY: Float = 0f, camFwdZ: Float = -1f,
        asPreview: Boolean = false,
        previewAnchorX: Float = 0f, previewAnchorY: Float = 1.9f, previewAnchorZ: Float = -1f
    ): Int {
        try {
            val bytes = file.readBytes()
            Log.i(TAG, "Loading GLB: ${file.name} (${bytes.size} bytes)${if (asPreview) " [preview]" else ""}")

            val gpuId = renderer.loadGlb(bytes)
            if (gpuId < 0) {
                Log.e(TAG, "Failed to load GLB: ${file.name}")
                runOnUiThread(Runnable { showMessage("Failed to load 3D model: ${file.name}") })
                return -1
            }

            val gpuModel = renderer.getModel(gpuId) ?: return -1

            // Auto-scale: normalize very large/small models to ~0.5m, keep meter-scale models near original
            val diameter = (gpuModel.boundsRadius * 2f).coerceAtLeast(0.001f)
            val baseAutoScale = when {
                diameter < 0.05f -> 0.5f / diameter  // tiny model (probably cm/mm units), scale up
                diameter > 5f -> 1f / diameter        // huge model, scale down to ~1m
                else -> 0.75f                          // already meter-scale, use proven default
            }.coerceIn(0.1f, 5f)
            // Previews shrink a bit so the whole bounding box fits comfortably above the panel.
            val autoScale = if (asPreview) {
                val previewTargetDiam = 0.28f
                (previewTargetDiam / diameter).coerceIn(0.05f, 2f)
            } else baseAutoScale

            val placePosX: Float
            val placePosY: Float
            val placePosZ: Float
            if (asPreview) {
                placePosX = previewAnchorX
                placePosY = previewAnchorY
                placePosZ = previewAnchorZ
            } else {
                // Place in front of camera on the horizontal plane
                val hLen = kotlin.math.sqrt(camFwdX * camFwdX + camFwdZ * camFwdZ).coerceAtLeast(0.01f)
                val hFwdX = camFwdX / hLen
                val hFwdZ = camFwdZ / hLen
                val placeDistance = 1.5f
                val offsetX = if (models.isEmpty()) 0f else offsetIndex * 1.0f
                placePosX = camPosX + hFwdX * placeDistance + hFwdZ * offsetX
                placePosY = 0f
                placePosZ = camPosZ + hFwdZ * placeDistance - hFwdX * offsetX
            }

            val placed = PlacedModel(
                file = file,
                asset = null,
                gpuModelId = gpuId,
                scale = autoScale,
                baseScale = autoScale,
                posX = placePosX,
                posY = placePosY,
                posZ = placePosZ,
                metallic = gpuModel.metallic,
                roughness = gpuModel.roughness,
                isPreview = asPreview
            )
            models.add(placed)
            if (!asPreview) {
                selectedModelIndex = models.size - 1

                // Auto-snap to detected floor if available
                if (detectedFloorY != Float.MIN_VALUE) {
                    val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                    placed.posY += (detectedFloorY - worldMinY)
                } else if (renderer.gridHeight != 0f) {
                    val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                    placed.posY += (renderer.gridHeight - worldMinY)
                }
            } else {
                // Center the preview vertically on the anchor point rather than snapping
                // to floor — the panel-relative anchor is already at the correct height.
                placed.posY -= gpuModel.boundsCenterY * placed.scale
            }

            updateModelTransform(placed)

            Log.i(TAG, "Model loaded: ${file.name}, rawDiameter=%.3f, autoScale=%.3f, pos=(%.2f,%.2f,%.2f), gpuId=$gpuId"
                .format(diameter, autoScale, placed.posX, placed.posY, placed.posZ))
            return models.size - 1
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${file.name}", e)
            runOnUiThread(Runnable { showMessage("Error: ${e.message}") })
            return -1
        }
    }

    // ── Transform ──

    fun updateModelTransform(model: PlacedModel) {
        val gpuModel = renderer.getModel(model.gpuModelId) ?: return

        val sxF = model.scale * model.scaleMulX
        val syF = model.scale * model.scaleMulY
        val szF = model.scale * model.scaleMulZ
        val x = model.rotX; val y = model.rotY; val z = model.rotZ; val w = model.rotW

        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2

        gpuModel.modelMatrix = floatArrayOf(
            (1f - (yy + zz)) * sxF, (xy + wz) * sxF, (xz - wy) * sxF, 0f,
            (xy - wz) * syF, (1f - (xx + zz)) * syF, (yz + wx) * syF, 0f,
            (xz + wy) * szF, (yz - wx) * szF, (1f - (xx + yy)) * szF, 0f,
            model.posX, model.posY, model.posZ, 1f
        )
    }

    // ── Reload ──

    /**
     * Reload all models from disk, preserving transforms and material settings.
     * Useful when texture quality changes.
     */
    fun reloadAllModels(textureQuality: Int) {
        // Snapshot current state
        val snapshots = models.map { m ->
            Triple(m.file, m.copy(), m.gpuModelId)
        }
        // Remove old GPU models
        for ((_, _, gpuId) in snapshots) renderer.removeModel(gpuId)
        models.clear()
        selectedModelIndex = -1
        // Pass quality setting to renderer
        renderer.textureMaxSize = when (textureQuality) {
            1 -> 4096; 2 -> 2048; 3 -> 1024; else -> 0 // 0 = auto
        }
        // Reload each model preserving transforms
        var failedCount = 0
        for ((file, snap, _) in snapshots) {
            if (!file.exists()) {
                Log.w(TAG, "Reload skip — file missing: ${file.name}")
                failedCount++
                continue
            }
            try {
                val bytes = file.readBytes()
                val gpuId = renderer.loadGlb(bytes)
                if (gpuId < 0) { failedCount++; continue }
                val placed = snap.copy(gpuModelId = gpuId)
                models.add(placed)
                updateModelTransform(placed)
                val gpuModel = renderer.getModel(gpuId) ?: continue
                gpuModel.metallic = placed.metallic
                gpuModel.roughness = placed.roughness
                gpuModel.exposure = placed.exposure
                gpuModel.contrast = placed.contrast
                gpuModel.saturation = placed.saturation
            } catch (e: Exception) {
                Log.e(TAG, "Reload failed: ${file.name}", e)
                failedCount++
            }
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
        Log.i(TAG, "Reloaded ${models.size} models (texQuality=$textureQuality)")
        if (failedCount > 0) {
            runOnUiThread(Runnable { showMessage("$failedCount model(s) failed to reload") })
        }
    }

    // ── Scene Save/Load ──

    fun getScenesDir(): File {
        val dir = File(context.filesDir, "scenes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Saved scene file list, populated by [refreshSceneList]. */
    var savedSceneFiles: List<File> = emptyList()
        private set

    fun refreshSceneList() {
        savedSceneFiles = getScenesDir().listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Save the current scene (models + lighting) to a JSON file.
     *
     * @param autoAmbient current auto-ambient flag from the activity
     * @param gridVisible current grid visibility flag from the activity
     */
    fun saveScene(name: String, autoAmbient: Boolean, gridVisible: Boolean) {
        try {
            val scene = org.json.JSONObject()

            // Global lighting
            val lighting = org.json.JSONObject()
            lighting.put("lightIntensity", renderer.lightIntensity.toDouble())
            lighting.put("fillLightIntensity", renderer.fillLightIntensity.toDouble())
            lighting.put("ambientIntensity", renderer.ambientIntensity.toDouble())
            lighting.put("lightAngleDeg", renderer.lightAngleDeg.toDouble())
            lighting.put("lightElevDeg", renderer.lightElevDeg.toDouble())
            lighting.put("shadowDarkness", renderer.shadowDarkness.toDouble())
            lighting.put("shadowSoftness", renderer.shadowSoftness.toDouble())
            lighting.put("shadowSpread", renderer.shadowSpread.toDouble())
            lighting.put("autoAmbient", autoAmbient)
            lighting.put("gridVisible", gridVisible)
            lighting.put("gridHeight", renderer.gridHeight.toDouble())
            scene.put("lighting", lighting)

            // Models
            val modelsArr = org.json.JSONArray()
            for (m in models) {
                val obj = org.json.JSONObject()
                obj.put("path", m.file.absolutePath)
                obj.put("scale", m.scale.toDouble())
                obj.put("posX", m.posX.toDouble())
                obj.put("posY", m.posY.toDouble())
                obj.put("posZ", m.posZ.toDouble())
                obj.put("rotX", m.rotX.toDouble())
                obj.put("rotY", m.rotY.toDouble())
                obj.put("rotZ", m.rotZ.toDouble())
                obj.put("rotW", m.rotW.toDouble())
                obj.put("metallic", m.metallic.toDouble())
                obj.put("roughness", m.roughness.toDouble())
                obj.put("exposure", m.exposure.toDouble())
                obj.put("contrast", m.contrast.toDouble())
                obj.put("saturation", m.saturation.toDouble())
                // Optional spatial anchor binding — included for diagnostics; authoritative
                // copy still lives in anchors.json keyed by UUID.
                m.anchorUuid?.let { obj.put("anchorUuid", bytesToHex(it)) }
                // Dance parameters — everything needed to restore the exact
                // groove across sessions. animHasBase + preset name + amps/
                // rates/phases + pivots + intensity + improv + character +
                // glute + pose override.
                val dance = org.json.JSONObject()
                dance.put("animHasBase", m.animHasBase)
                dance.put("currentPresetName", m.currentPresetName)
                dance.put("danceYawDeg", m.danceYawDeg.toDouble())
                dance.put("danceYawRate", m.danceYawRate)
                dance.put("danceYawPhase", m.danceYawPhase.toDouble())
                dance.put("dancePitchDeg", m.dancePitchDeg.toDouble())
                dance.put("dancePitchRate", m.dancePitchRate)
                dance.put("dancePitchPhase", m.dancePitchPhase.toDouble())
                dance.put("danceYMeters", m.danceYMeters.toDouble())
                dance.put("danceYRate", m.danceYRate)
                dance.put("danceYPhase", m.danceYPhase.toDouble())
                dance.put("danceRollDeg", m.danceRollDeg.toDouble())
                dance.put("danceRollRate", m.danceRollRate)
                dance.put("danceRollPhase", m.danceRollPhase.toDouble())
                dance.put("danceEase", m.danceEase.ordinal)
                dance.put("physicsAmount", m.physicsAmount.toDouble())
                dance.put("pivotEnabled", m.pivotEnabled)
                dance.put("pitchPivotFrac", m.pitchPivotFrac.toDouble())
                dance.put("rollPivotFrac", m.rollPivotFrac.toDouble())
                dance.put("counterRollPivotFrac", m.counterRollPivotFrac.toDouble())
                dance.put("counterRollGain", m.counterRollGain.toDouble())
                dance.put("danceIntensity", m.danceIntensity.toDouble())
                dance.put("danceImprov", m.danceImprov)
                dance.put("improvBars", m.improvBars)
                dance.put("danceGazeFollow", m.danceGazeFollow)
                dance.put("yawSharpness", m.yawSharpness.toDouble())
                dance.put("yawComplexity", m.yawComplexity.toDouble())
                dance.put("pitchSharpness", m.pitchSharpness.toDouble())
                dance.put("pitchComplexity", m.pitchComplexity.toDouble())
                dance.put("bobSharpness", m.bobSharpness.toDouble())
                dance.put("bobComplexity", m.bobComplexity.toDouble())
                dance.put("gluteBasePush", m.gluteBasePush.toDouble())
                dance.put("gluteRadius", m.gluteRadius.toDouble())
                dance.put("gluteShakeIntensity", m.gluteShakeIntensity.toDouble())
                dance.put("gluteRate", m.gluteRate)
                dance.put("gluteLeftEnabled", m.gluteLeftEnabled)
                dance.put("gluteRightEnabled", m.gluteRightEnabled)
                dance.put("gluteAltStep", m.gluteAltStep)
                dance.put("gluteShakerMode", m.gluteShakerMode)
                dance.put("stanceArch", m.stanceArch.toDouble())
                dance.put("handsOnKnees", m.handsOnKnees)
                if (m.animHasBase) {
                    val bp = org.json.JSONArray()
                    for (v in m.animBasePose) bp.put(v.toDouble())
                    dance.put("animBasePose", bp)
                }
                obj.put("dance", dance)
                modelsArr.put(obj)
            }
            scene.put("models", modelsArr)

            val file = File(getScenesDir(), "$name.json")
            file.writeText(scene.toString(2))
            Log.i(TAG, "Scene saved: ${file.absolutePath} (${models.size} models)")
            runOnUiThread(Runnable { showMessage("Scene saved: $name") })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scene", e)
            runOnUiThread(Runnable { showMessage("Save failed: ${e.message}") })
        }
    }

    /**
     * Load a scene from a JSON file. Must be called on the render thread (needs GL context).
     *
     * @return a [SceneLoadResult] containing the restored autoAmbient and gridVisible values,
     *         or null if the load failed.
     */
    data class SceneLoadResult(val autoAmbient: Boolean, val gridVisible: Boolean)

    fun loadScene(sceneFile: File): SceneLoadResult? {
        // This must be called on the render thread (needs GL context for loadModel)
        try {
            val json = org.json.JSONObject(sceneFile.readText())

            // Clear existing models
            for (m in models) {
                renderer.removeModel(m.gpuModelId)
            }
            models.clear()
            selectedModelIndex = -1

            var restoredAutoAmbient = true
            var restoredGridVisible = true

            // Restore lighting
            val lighting = json.optJSONObject("lighting")
            if (lighting != null) {
                renderer.lightIntensity = lighting.optDouble("lightIntensity", 2.0).toFloat()
                renderer.fillLightIntensity = lighting.optDouble("fillLightIntensity", 0.5).toFloat()
                renderer.ambientIntensity = lighting.optDouble("ambientIntensity", 1.0).toFloat()
                renderer.lightAngleDeg = lighting.optDouble("lightAngleDeg", 0.0).toFloat()
                renderer.lightElevDeg = lighting.optDouble("lightElevDeg", 60.0).toFloat()
                renderer.updateLightDirFromAngles()
                renderer.shadowDarkness = lighting.optDouble("shadowDarkness", 0.7).toFloat()
                renderer.shadowSoftness = lighting.optDouble("shadowSoftness", 2.0).toFloat()
                renderer.shadowSpread = lighting.optDouble("shadowSpread", 8.0).toFloat()
                restoredAutoAmbient = lighting.optBoolean("autoAmbient", true)
                restoredGridVisible = lighting.optBoolean("gridVisible", true)
                renderer.gridHeight = lighting.optDouble("gridHeight", 0.0).toFloat()
            }

            // Load models
            var skippedModels = 0
            val modelsArr = json.optJSONArray("models")
            if (modelsArr != null) {
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.getJSONObject(i)
                    val file = File(obj.getString("path"))
                    val canonical = file.canonicalPath
                    if (!canonical.startsWith("/storage/") && !canonical.startsWith(context.filesDir.canonicalPath) && !canonical.startsWith(context.cacheDir.canonicalPath)) {
                        Log.w(TAG, "Rejected scene path outside storage: $canonical")
                        skippedModels++
                        continue
                    }
                    if (!file.exists()) {
                        Log.w(TAG, "Scene model not found: ${file.absolutePath}")
                        skippedModels++
                        continue
                    }
                    val bytes = file.readBytes()
                    val gpuId = renderer.loadGlb(bytes)
                    if (gpuId < 0) { skippedModels++; continue }

                    val gpuModel = renderer.getModel(gpuId) ?: continue
                    val placed = PlacedModel(
                        file = file, asset = null, gpuModelId = gpuId,
                        scale = obj.optDouble("scale", 0.75).toFloat(),
                        baseScale = obj.optDouble("scale", 0.75).toFloat(),
                        posX = obj.optDouble("posX", 0.0).toFloat(),
                        posY = obj.optDouble("posY", 0.0).toFloat(),
                        posZ = obj.optDouble("posZ", -1.0).toFloat(),
                        rotX = obj.optDouble("rotX", 0.0).toFloat(),
                        rotY = obj.optDouble("rotY", 0.0).toFloat(),
                        rotZ = obj.optDouble("rotZ", 0.0).toFloat(),
                        rotW = obj.optDouble("rotW", 1.0).toFloat(),
                        metallic = obj.optDouble("metallic", 0.0).toFloat(),
                        roughness = obj.optDouble("roughness", 0.9).toFloat(),
                        exposure = obj.optDouble("exposure", 0.0).toFloat(),
                        contrast = obj.optDouble("contrast", 1.0).toFloat(),
                        saturation = obj.optDouble("saturation", 1.0).toFloat(),
                        anchorUuid = hexToBytes(obj.optString("anchorUuid", ""))
                    )
                    gpuModel.metallic = placed.metallic
                    gpuModel.roughness = placed.roughness
                    gpuModel.exposure = placed.exposure
                    gpuModel.contrast = placed.contrast
                    gpuModel.saturation = placed.saturation
                    // Restore dance state if the save included it.
                    val dance = obj.optJSONObject("dance")
                    if (dance != null) {
                        placed.animHasBase = dance.optBoolean("animHasBase", false)
                        placed.currentPresetName = dance.optString("currentPresetName", "")
                        placed.danceYawDeg = dance.optDouble("danceYawDeg", 0.0).toFloat()
                        placed.danceYawRate = dance.optInt("danceYawRate", 2)
                        placed.danceYawPhase = dance.optDouble("danceYawPhase", 0.0).toFloat()
                        placed.dancePitchDeg = dance.optDouble("dancePitchDeg", 0.0).toFloat()
                        placed.dancePitchRate = dance.optInt("dancePitchRate", 4)
                        placed.dancePitchPhase = dance.optDouble("dancePitchPhase", 0.0).toFloat()
                        placed.danceYMeters = dance.optDouble("danceYMeters", 0.0).toFloat()
                        placed.danceYRate = dance.optInt("danceYRate", 4)
                        placed.danceYPhase = dance.optDouble("danceYPhase", 0.0).toFloat()
                        placed.danceRollDeg = dance.optDouble("danceRollDeg", 0.0).toFloat()
                        placed.danceRollRate = dance.optInt("danceRollRate", 2)
                        placed.danceRollPhase = dance.optDouble("danceRollPhase", 0.0).toFloat()
                        val easeOrdinal = dance.optInt("danceEase", 0)
                        val easeValues = DanceEase.entries
                        if (easeOrdinal in easeValues.indices) placed.danceEase = easeValues[easeOrdinal]
                        placed.physicsAmount = dance.optDouble("physicsAmount", 0.5).toFloat()
                        placed.pivotEnabled = dance.optBoolean("pivotEnabled", false)
                        placed.pitchPivotFrac = dance.optDouble("pitchPivotFrac", 0.85).toFloat()
                        placed.rollPivotFrac = dance.optDouble("rollPivotFrac", 0.85).toFloat()
                        placed.counterRollPivotFrac = dance.optDouble("counterRollPivotFrac", 0.5).toFloat()
                        placed.counterRollGain = dance.optDouble("counterRollGain", 0.35).toFloat()
                        placed.danceIntensity = dance.optDouble("danceIntensity", 0.5).toFloat()
                        placed.danceImprov = dance.optBoolean("danceImprov", false)
                        placed.improvBars = dance.optInt("improvBars", 4)
                        placed.danceGazeFollow = dance.optBoolean("danceGazeFollow", false)
                        placed.yawSharpness = dance.optDouble("yawSharpness", 0.0).toFloat()
                        placed.yawComplexity = dance.optDouble("yawComplexity", 0.34).toFloat()
                        placed.pitchSharpness = dance.optDouble("pitchSharpness", 0.0).toFloat()
                        placed.pitchComplexity = dance.optDouble("pitchComplexity", 0.34).toFloat()
                        placed.bobSharpness = dance.optDouble("bobSharpness", 0.0).toFloat()
                        placed.bobComplexity = dance.optDouble("bobComplexity", 0.34).toFloat()
                        placed.gluteBasePush = dance.optDouble("gluteBasePush", 0.0).toFloat()
                        placed.gluteRadius = dance.optDouble("gluteRadius", 0.15).toFloat()
                        placed.gluteShakeIntensity = dance.optDouble("gluteShakeIntensity", 0.5).toFloat()
                        placed.gluteRate = dance.optInt("gluteRate", 1)
                        placed.gluteLeftEnabled = dance.optBoolean("gluteLeftEnabled", true)
                        placed.gluteRightEnabled = dance.optBoolean("gluteRightEnabled", true)
                        placed.gluteAltStep = dance.optBoolean("gluteAltStep", false)
                        placed.gluteShakerMode = dance.optBoolean("gluteShakerMode", false)
                        placed.stanceArch = dance.optDouble("stanceArch", 0.0).toFloat()
                        placed.handsOnKnees = dance.optBoolean("handsOnKnees", false)
                        val bp = dance.optJSONArray("animBasePose")
                        if (bp != null && bp.length() == 7) {
                            for (k in 0 until 7) placed.animBasePose[k] = bp.optDouble(k, 0.0).toFloat()
                        }
                    }
                    models.add(placed)
                    updateModelTransform(placed)
                }
            }

            if (models.isNotEmpty()) selectedModelIndex = 0
            Log.i(TAG, "Scene loaded: ${sceneFile.name} (${models.size} models, $skippedModels skipped)")
            val loadMsg = if (skippedModels > 0) {
                "Scene loaded: ${sceneFile.nameWithoutExtension} ($skippedModels model(s) missing)"
            } else {
                "Scene loaded: ${sceneFile.nameWithoutExtension}"
            }
            runOnUiThread(Runnable { showMessage(loadMsg) })

            return SceneLoadResult(restoredAutoAmbient, restoredGridVisible)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scene", e)
            runOnUiThread(Runnable { showMessage("Load failed: ${e.message}") })
            return null
        }
    }

    // ── Anchor-driven spawn ─────────────────────────────────────────────
    // Call from render thread (needs a live GL context). Given an AnchorRecord and a
    // RESOLVED world pose (anchor origin in appSpace), load the model and place it so
    // the anchor is pinned to the stored local offset. Returns the newly-added PlacedModel
    // or null if the model file is missing/unreadable.
    fun spawnFromAnchor(
        record: SpatialAnchorManager.AnchorRecord,
        anchorPosX: Float, anchorPosY: Float, anchorPosZ: Float,
        anchorRotX: Float, anchorRotY: Float, anchorRotZ: Float, anchorRotW: Float
    ): PlacedModel? {
        val file = File(record.modelPath)
        if (!file.exists()) {
            Log.w(TAG, "spawnFromAnchor: model file missing: ${record.modelPath}")
            return null
        }
        return try {
            val bytes = file.readBytes()
            val gpuId = renderer.loadGlb(bytes)
            if (gpuId < 0) {
                Log.w(TAG, "spawnFromAnchor: GLB load failed: ${file.name}")
                return null
            }
            val gpuModel = renderer.getModel(gpuId) ?: return null
            // World pose = anchor pose * offset. For now the offset is zero in the common
            // case (anchor created at the model pivot itself); we still respect the stored
            // offset so future workflows that anchor to a wall/floor independent of the
            // model pivot will just work.
            val worldPosX = anchorPosX + rotateX(anchorRotX, anchorRotY, anchorRotZ, anchorRotW,
                record.offsetPosX, record.offsetPosY, record.offsetPosZ)
            val worldPosY = anchorPosY + rotateY(anchorRotX, anchorRotY, anchorRotZ, anchorRotW,
                record.offsetPosX, record.offsetPosY, record.offsetPosZ)
            val worldPosZ = anchorPosZ + rotateZ(anchorRotX, anchorRotY, anchorRotZ, anchorRotW,
                record.offsetPosX, record.offsetPosY, record.offsetPosZ)
            // World rotation = anchor rotation * offset rotation
            val (wrx, wry, wrz, wrw) = quatMul(
                anchorRotX, anchorRotY, anchorRotZ, anchorRotW,
                record.offsetRotX, record.offsetRotY, record.offsetRotZ, record.offsetRotW
            )
            val placed = PlacedModel(
                file = file, asset = null, gpuModelId = gpuId,
                scale = record.scale, baseScale = record.scale,
                posX = worldPosX, posY = worldPosY, posZ = worldPosZ,
                rotX = wrx, rotY = wry, rotZ = wrz, rotW = wrw,
                metallic = record.metallic, roughness = record.roughness,
                exposure = record.exposure, contrast = record.contrast, saturation = record.saturation,
                anchorUuid = record.uuid.copyOf()
            )
            gpuModel.metallic = placed.metallic
            gpuModel.roughness = placed.roughness
            gpuModel.exposure = placed.exposure
            gpuModel.contrast = placed.contrast
            gpuModel.saturation = placed.saturation
            models.add(placed)
            if (selectedModelIndex < 0) selectedModelIndex = models.size - 1
            updateModelTransform(placed)
            Log.i(TAG, "spawnFromAnchor: ${file.name} at ($worldPosX,$worldPosY,$worldPosZ)")
            placed
        } catch (e: Exception) {
            Log.e(TAG, "spawnFromAnchor error: ${e.message}", e)
            null
        }
    }

    // ── Hex utility (shared with scene JSON) ─────────────────────────
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun rotateX(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): Float {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return vx + qw * tx + (qy * tz - qz * ty)
    }
    private fun rotateY(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): Float {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return vy + qw * ty + (qz * tx - qx * tz)
    }
    private fun rotateZ(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): Float {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        return vz + qw * tz + (qx * ty - qy * tx)
    }

    private data class Quat(val x: Float, val y: Float, val z: Float, val w: Float)
    private fun quatMul(
        ax: Float, ay: Float, az: Float, aw: Float,
        bx: Float, by: Float, bz: Float, bw: Float
    ): Quat = Quat(
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz
    )
}
