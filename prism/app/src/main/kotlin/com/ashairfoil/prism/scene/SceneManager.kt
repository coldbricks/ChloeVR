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
        var danceYawDeg: Float = 0f,
        var danceYawRate: Int = 8,
        var dancePitchDeg: Float = 0f,
        var dancePitchRate: Int = 4,
        var danceYMeters: Float = 0f,
        var danceYRate: Int = 2,
        // Per-axis phase offsets (0..1). Shuffle randomises these so moves
        // don't all peak at the exact same instant.
        var danceYawPhase: Float = 0f,
        var dancePitchPhase: Float = 0.25f,
        var danceYPhase: Float = 0f,
        // Easing curve applied to each quarter of the oscillation. Default SINE
        // is the smoothest / most neutral; BACK adds an overshoot for attitude,
        // EXPO for a punchy "whip then hold" snap, LINEAR for robotic metronome.
        var danceEase: DanceEase = DanceEase.SINE,
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
        var danceIntensity: Float = 0.25f,
        // Tier1.5-gaze-toggle: follow-gaze (saccade FSM) on/off per model.
        // When false, FSM never captures even if dance is armed.
        var danceGazeFollow: Boolean = true,
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
        var footAnchorStrength: Float = 0.85f
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
