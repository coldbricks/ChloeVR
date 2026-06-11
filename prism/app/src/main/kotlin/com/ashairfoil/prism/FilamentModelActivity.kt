package com.ashairfoil.prism

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ashairfoil.prism.scene.InputHandler
import com.ashairfoil.prism.scene.JointDrive
import com.ashairfoil.prism.scene.JointDriveLayer
import com.ashairfoil.prism.scene.SceneManager
import com.ashairfoil.prism.scene.SceneOcclusionManager
import com.ashairfoil.prism.scene.SpatialAnchorManager
import com.ashairfoil.prism.scene.UiRenderer
import com.ashairfoil.prism.scene.XrSensorPoller
import com.ashairfoil.prism.settings.SettingsManager
import java.io.File

class FilamentModelActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("openxr_input")
            System.loadLibrary("filament-jni")
            System.loadLibrary("filament-utils-jni")
            System.loadLibrary("gltfio-jni")
        }
        const val TAG = "ChloeVR-ModelActivity"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODEL_PATHS = "model_paths"
    }

    // Rendering
    @Volatile internal var glesRenderer: GlesModelRenderer? = null
    private var renderThread: Thread? = null
    @Volatile internal var running = false

    // Scene management (models, transforms, save/load)
    internal lateinit var sceneManager: SceneManager

    // Type aliases for convenience — delegates into sceneManager
    private inline val models get() = sceneManager.models
    private inline var selectedModelIndex
        get() = sceneManager.selectedModelIndex
        set(value) { sceneManager.selectedModelIndex = value }
    private inline val yeetingModels get() = sceneManager.yeetingModels

    // ── Controller Input Handler (extracted) ──
    internal val inputHandler = InputHandler(this)

    // ── UI Renderer (extracted) ──
    internal val uiRenderer = UiRenderer(this)

    // ── Sensor Hub (ALL Android hardware sensors) ──
    internal var sensorHub: SensorHub? = null

    @Volatile internal var roomLux = 200f  // default indoor
    // Default OFF (user request 2026-06-10): with Auto Light on, the XR light
    // estimate steers ambient/SH (and direction when Follow is on) every
    // frame — a noisy evening-room estimate makes lighting/shadows subtly
    // swim, which reads as "something is off / ground unstable". Opt-in via
    // param 24; scenes saved with it on still restore it per-scene.
    @Volatile internal var autoAmbient = false
    // R2: while Auto Light is on, also steer the key light's DIRECTION from the
    // XR directional estimate so shadows align with the real room. Persisted in
    // SettingsManager; manual angle edits flip it off (same contract as the
    // ambient slider vs autoAmbient). Loaded in onCreate.
    @Volatile internal var followRoomLight = true
    private val lightEstimateBuffer = FloatArray(41)

    // Pre-allocated view/projection matrices to avoid per-frame copyOfRange allocations
    private val leftProjBuf = FloatArray(16)
    private val rightProjBuf = FloatArray(16)
    private val leftViewBuf = FloatArray(16)
    private val rightViewBuf = FloatArray(16)
    private val rawSHBuf = FloatArray(27)
    private val tccBuf = FloatArray(3)
    private val gizmoPosBuf = FloatArray(3)
    private val gizmoRotBuf = FloatArray(4)

    @Volatile internal var xrLightEstimateAvailable = false
    @Volatile internal var xrSHAvailable = false
    @Volatile internal var xrLightDebugStr = ""

    // Temporal smoothing for XR light estimation (prevents "moving light" jitter)
    private var smoothAmbientIntensity = 1f
    private var smoothAmbientColor = floatArrayOf(1f, 1f, 1f)
    private var smoothLightIntensity = 2f
    private var smoothLightDir = floatArrayOf(0f, 1f, 0f)
    private var smoothLightColor = floatArrayOf(1f, 1f, 1f)
    private var smoothSH = FloatArray(27)
    private var lightSmoothed = false  // first frame gets instant values

    // Follow Room Light state: require a stable run of valid direction estimates
    // before the first apply (~20 native polls at the 3-frame cadence), then a 5°
    // hysteresis so estimate noise can't make the emitter gizmo crawl.
    private var dirStableFrames = 0
    private val followWarmupFrames = 60
    private val followHysteresisCos = 0.99619f  // just below cos(5°), no equality strobe
    // useSH un-stick: drop frozen room SH after ~90 native polls without valid
    // SH (270 render frames at the 3-frame poll cadence, ~3.75s)
    private var shInvalidFrames = 0
    private val shInvalidLimit = 270
    private var lastShKindAmbient = false  // last SH kind sent to native

    private fun setRgb(dst: FloatArray, r: Float, g: Float, b: Float) {
        if (dst.size < 3) return
        dst[0] = r
        dst[1] = g
        dst[2] = b
    }

    /** Flip Follow Room Light and persist. Manual light-direction edits (sliders,
     *  thumbstick nudges, resets, presets, emitter drag) call this with on=false —
     *  mirroring the ambient-slider/autoAmbient contract. No-op when unchanged. */
    internal fun setFollowRoomLight(on: Boolean, reason: String) {
        if (followRoomLight == on) return
        followRoomLight = on
        SettingsManager.followRoomLight = on
        if (!on) dirStableFrames = 0
        Log.i(TAG, "Follow Room Light ${if (on) "ON" else "OFF"} ($reason)")
    }

    /** Keep the native SH kind in sync: AMBIENT while the key light follows the
     *  room direction (avoids double-counting it via TOTAL-kind SH), TOTAL
     *  otherwise. Crosses JNI only when the state actually changes. */
    private fun syncLightShKind() {
        val ambient = autoAmbient && followRoomLight
        if (ambient == lastShKindAmbient) return
        lastShKindAmbient = ambient
        nativeSetLightShAmbient(ambient)
    }

    // ── XR Sensor Poller (hand/eye/face tracking, planes, perf, passthrough) ──
    internal lateinit var sensorPoller: XrSensorPoller

    // ── Real-world scene occlusion (placed 3D models blocked by furniture) ──
    internal val sceneOcclusion = SceneOcclusionManager()

    // Sensor debug HUD
    @Volatile internal var sensorHudVisible = false
    private var lastRenderNanos = 0L
    @Volatile internal var sensorDebugStr = ""
    internal var sensorPollFrame = 0

    // Grid state
    @Volatile internal var gridVisible = false

    // Gizmo state
    internal var gizmoVisible = true

    // UI state
    @Volatile internal var menuVisible = false
    internal var handsLocked = false
    // uiTextureId, pendingUiBitmap, uiFlipBitmap/Canvas/Matrix moved to UiRenderer
    internal var selectedParam = 0
    internal val PARAM_NAMES = arrayOf("Metallic", "Roughness", "Exposure",
        "Contrast", "Saturation",
        "Light Intensity", "Fill Intensity", "Ambient", "Light Azimuth",
        "Light Height", "Shadow Dark", "Shadow Soft", "Shadow Spread",
        "BeatReactor", "Foveation", "Tex Quality", "Show Planes", "Room Track",
        "Room Edit", "RECENTER",
        "Mark Hip", "Mark Shldr", "Mark Knee", "Reset Marks",
        "Auto Light", "Mark Glute L", "Mark Glute R")

    // Tier2-pivot: user-marking mode. 0 = off. 1 = next laser-trigger captures
    // the hit Y as the selected model's hip anchor. 2 = shoulder. After capture
    // the mode resets to 0 and the anatomy fracs get remapped in the dance loop.
    @Volatile internal var markAnatomyMode: Int = 0
    // Slider ranges for continuous params 0-12. Toggles (13-16) have no range.
    internal val PARAM_RANGES = arrayOf(
        0f to 1f, 0.05f to 1f, -5f to 5f, 0.5f to 2.0f, 0f to 3f,
        0f to 10f, 0f to 5f, 0f to 5f, 0f to 360f, 5f to 90f,
        0f to 1f, 0.5f to 5f, 2f to 30f
    )
    // 0=auto (adaptive budget), 1=4096 (original), 2=2048, 3=1024
    internal var textureQuality = 0
    @Volatile internal var uiNeedsRefresh = false
    private var lastBCloseTime = 0L
    @Volatile private var uiRenderQueued = false
    private val loopHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Dedicated thread for menu bitmap rendering. Keeps Canvas work off the main
    // thread so cursor moves redraw at full display rate instead of fighting the
    // system event loop for time slices.
    private val uiRenderThread = android.os.HandlerThread("ChloeVR-UiRender").apply {
        priority = Thread.NORM_PRIORITY + 1
        start()
    }
    private val uiRenderHandler = android.os.Handler(uiRenderThread.looper)
    @Volatile private var loopRunning = false

    // Draggable panel state
    private var panelPosX = 0f; private var panelPosY = 1.6f; private var panelPosZ = -1.2f
    internal var panelRotX = 0f; internal var panelRotY = 0f; internal var panelRotZ = 0f; internal var panelRotW = 1f
    internal val PANEL_WIDTH = 1.1f   // meters in world space (base size)
    internal val PANEL_HEIGHT = 1.25f
    internal var panelScale = 1.0f    // zoom factor (0.5..2.0)

    // GLB picker sub-menu (shown when ADD MODEL is tapped)
    @Volatile internal var glbPickerMode = false
    internal var availableGlbFiles: List<File> = emptyList()
    internal var glbPickerScrollOffset = 0
    // RIGGED filter — when true, picker shows only files in /sdcard/RIGGED/
    // or with a "RIGGED_" / "_rigged" filename marker (DeoVR-style convention).
    @Volatile internal var riggedOnlyMode = false

    /** Returns the GLB files currently visible in the picker, respecting the
     *  RIGGED filter. Single source of truth — both UiRenderer's row draw and
     *  InputHandler's hit-test index into this, so indices always line up. */
    internal fun visibleGlbFiles(): List<File> {
        return if (riggedOnlyMode) availableGlbFiles.filter { FilePicker.isRiggedGlb(it) }
            else availableGlbFiles
    }

    // ── GLB rename (user request 2026-06-10: readable names instead of hash
    // soup). RENAME toggle in the picker header arms it; tapping a row then
    // opens the save-name keyboard prefilled, and SAVE renames on disk. ──
    @Volatile internal var glbRenameArmed = false
    internal var renameTargetFile: File? = null

    /** Rename a GLB on disk. Returns a user-facing status message. */
    internal fun renameGlbFile(target: File, newBaseName: String): String {
        val safe = newBaseName.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "").trim()
        if (safe.isEmpty()) return "Invalid name"
        val dest = File(target.parentFile, "$safe.${target.extension}")
        if (dest.exists()) return "Name already taken"
        if (!target.renameTo(dest)) return "Rename failed (storage permission?)"
        availableGlbFiles = availableGlbFiles.map {
            if (it.absolutePath == target.absolutePath) dest else it
        }
        // Keep the startup cache in sync so a restart doesn't resurrect the
        // old path (it filters on exists(), but the entry would just vanish).
        try {
            File(cacheDir, "glb_cache.txt")
                .writeText(availableGlbFiles.joinToString("\n") { it.absolutePath })
        } catch (e: Exception) { Log.w(TAG, "GLB cache rewrite failed: ${e.message}") }
        Log.i(TAG, "Renamed ${target.name} -> ${dest.name}")
        return "Renamed to ${dest.nameWithoutExtension}"
    }
    @Volatile internal var pendingModelLoad: File? = null  // queued for render thread (needs GL context)

    // Preview-before-add: tap a GLB and inspect it floating above the panel before
    // committing to the scene. Render thread picks up pendingPreviewLoad and routes
    // through SceneManager.loadModel(asPreview=true). previewModelIndex points into
    // sceneManager.models (or -1 when no active preview).
    @Volatile internal var pendingPreviewLoad: File? = null
    @Volatile internal var previewModelIndex: Int = -1
    internal var previewRotY: Float = 0f
    private var lastPreviewNanos: Long = 0L

    // When the user explicitly deselects (Y past the last model), we suspend the
    // "auto-select solo model" fallback so it doesn't immediately re-grab. Reset
    // to false whenever the model count changes (new load, delete) so fresh state
    // is still convenient.
    @Volatile internal var autoSelectSuspended: Boolean = false
    private var lastSeenModelCount: Int = -1

    // Scene save/load
    @Volatile internal var scenePickerMode = false  // true = showing load scene list
    private var scenePickerIsSave = false
    // savedSceneFiles now lives on SceneManager (delegated via inline val below)
    @Volatile internal var pendingSceneLoad: File? = null  // queued for render thread

    // Save name editor
    @Volatile internal var saveNameMode = false
    internal val SAVE_NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_- "
    internal var saveNameChars = CharArray(20) { if (it < 5) "Scene"[it] else ' ' }
    internal var saveNameLen = 5
    internal var saveNameCursor = 0
    internal var saveNameStickCooldown = 0

    // Audio-reactive lighting (BeatReactor)
    internal var audioReactor: AudioReactor? = null
    @Volatile internal var beatReactorEnabled = false
    @Volatile internal var beatIntensity = 1.0f

    // ── Climax Engine state ──
    private var hapticLeadBuffer = 0f  // buffered pct sent 1 frame early
    private var lastHapticIntensity = -1 // dedupe: skip BLE write if intensity unchanged
    private var lastHapticMotor1 = -1    // dedupe for dual motor (head/primary)
    private var lastHapticMotor2 = -1    // dedupe for dual motor (handle/secondary)
    // Base values captured when BeatReactor turns on — beat modulates FROM these
    internal var beatBaseStored = false
    private var beatBaseAmbient = 1f
    private var beatBaseLight = 2f
    private var beatBaseFill = 0.5f
    private var beatBaseShadow = 0.7f
    // Tier 3.1 — lighting uses its own slow attack/release envelope on top of
    // the reactor's snappy pct. Mimics real concert/club lighting where cans
    // warm up over a quarter-note and fade across a bar. Default 250ms attack
    // / 900ms release — close enough to the "hang" of halogen lamps. User
    // feedback: "the ATTACK AND RELEASE of the LIGHTING should have a slower
    // attack (check my screenshots) because this simulates concert/club
    // lighting in real life".
    @Volatile internal var lightAttackMs = 250f
    @Volatile internal var lightReleaseMs = 900f
    private var lightLevelSmooth = 0f
    private var lightLevelLastNs = 0L
    private var beatWashAlpha = 0f
    @Volatile internal var foveationLevel = 0  // 0=off, 1=low, 2=med, 3=high
    private var foveationOffPushed = false  // one-shot startup default-profile clear
    @Volatile internal var foveationAvailable = false
    @Volatile internal var eyeTrackedFoveationAvailable = false
    @Volatile internal var perfSettingsAvailable = false

    // ── Thermal auto-downgrade state ───────────────────────────────────
    // Stages: 0=normal, 1=effects-disabled, 2=refresh-reduced, 3=resolution-reduced
    @Volatile internal var thermalStage = 0
    @Volatile internal var thermalNotificationLevel = 0  // latest XrPerfSettingsNotificationLevelEXT (max across domains)
    // Per-frame recovery cooldown: stay in downgraded state for at least N
    // frames after dropping to a stage, so we don't oscillate when thermal
    // state hovers right at the threshold.
    private var thermalCooldownFrames = 0
    // Saved values so we can restore on recovery
    private var savedRefreshRatePref = 0
    private var savedFoveationLevel = 0
    /** Optional UI callback — Activity can set this to update a thermometer icon. */
    @Volatile internal var onThermalStageChanged: ((stage: Int, level: Int) -> Unit)? = null
    internal var beatToggleLatch = false
    internal var foveationToggleLatch = false
    internal var planeVisToggleLatch = false
    internal var roomTrackToggleLatch = false
    // Default ON so the floor is auto-detected at startup without requiring
    // the user to dig into the room-track toggle. XrSensorPoller picks up
    // horizontal planes; the lowest one seeds detectedFloorY which snaps
    // every loaded model's boundsMinY (= heel bottom) to that Y.
    @Volatile internal var roomTrackingEnabled = true

    // ── Room Edit (manual plane trim) ──
    @Volatile internal var roomEditMode = false
    internal var roomEditToggleLatch = false
    internal var selectedPlaneIndex = -1
    // Per-plane Y offset keyed by quantized centroid "x,z" (10cm grid)
    internal val planeAdjustments = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /** Quantize plane position to 10cm grid for stable matching across detection updates */
    internal fun planeKey(posX: Float, posZ: Float): String {
        val qx = (posX * 10).toInt()
        val qz = (posZ * 10).toInt()
        return "$qx,$qz"
    }

    /** Apply manual Y offsets to detected planes */
    private fun applyPlaneAdjustments(planes: List<GlesModelRenderer.ShadowPlane>): List<GlesModelRenderer.ShadowPlane> {
        if (planeAdjustments.isEmpty()) return planes
        val adjusted = ArrayList<GlesModelRenderer.ShadowPlane>(planes.size)
        for (p in planes) {
            val key = planeKey(p.posX, p.posZ)
            val offsetY = planeAdjustments[key]
            if (offsetY != null && offsetY != 0f) {
                adjusted.add(GlesModelRenderer.ShadowPlane(
                    p.posX, p.posY + offsetY, p.posZ,
                    p.rotX, p.rotY, p.rotZ, p.rotW,
                    p.extentX, p.extentY, p.label
                ))
            } else {
                adjusted.add(p)
            }
        }
        return adjusted
    }

    // ── Audio Player ──
    @Volatile internal var audioPlayer: AudioPlayer? = null
    @Volatile internal var audioPlayerMode = false
    @Volatile internal var audioPickerMode = false
    internal var availableAudioFiles: List<File> = emptyList()
    internal var audioPickerScrollOffset = 0
    @Volatile internal var audioScanInProgress = false
    @Volatile private var audioScanQueued = false
    @Volatile private var lastAudioScanStartMs = 0L

    // Lighting presets sub-menu
    @Volatile internal var lightingPresetMode = false
    @Volatile internal var activeLightingPresetName: String? = null

    // BeatReactor settings sub-menu
    @Volatile internal var beatSettingsMode = false
    @Volatile internal var beatCursorX = -1f  // laser position on panel (bitmap coords)
    @Volatile internal var beatCursorY = -1f

    // Tier 3 — motion CHARACTER sub-panel. When true (within beatSettingsMode),
    // the slider area shows per-axis sharpness/complexity sliders instead of
    // the reactor sliders. Toggled from Row C.
    @Volatile internal var characterMode = false

    // Haptic device (Lovense via BLE)
    internal var hapticManager: com.ashairfoil.prism.haptics.BleDeviceManager? = null
    @Volatile internal var hapticConnected = false
    @Volatile internal var hapticEnabled = false
    @Volatile internal var hapticDualMotorSplit = false  // true = independent bass/treble motors
    // Script playback scheduler — not used in the 3D model viewer (no video),
    // but declared here so InputHandler can reference it uniformly.
    internal var hapticScriptPlayer: com.ashairfoil.prism.haptics.HapticScriptPlayer? = null

    // Floor detection: snap grid once at startup, track floor Y for model placement
    @Volatile private var floorSnappedOnce = false  // true after first grid snap to detected floor
    @Volatile private var detectedFloorY = Float.MIN_VALUE

    // Head/camera position (extracted from view matrix each frame)
    internal var camPosX = 0f; internal var camPosY = 1.6f; internal var camPosZ = 0f
    internal var camFwdX = 0f; internal var camFwdY = 0f; internal var camFwdZ = -1f

    // ── Spatial anchors (persistent model positions across sessions) ──
    internal lateinit var spatialAnchors: SpatialAnchorManager
    @Volatile private var anchorResolveRequested = false
    @Volatile private var anchorRestoreCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "onCreate: initializing Filament model viewer")

        // SettingsManager is idempotent; ensure it's initialized even if this activity
        // was launched directly (e.g. from a model-file share) without going through MainActivity.
        SettingsManager.init(this)
        followRoomLight = SettingsManager.followRoomLight
        spatialAnchors = SpatialAnchorManager(this)

        showMessage("Initializing 3D renderer...")

        // DON'T requestPermissions here — it restarts the activity and kills OpenXR
        // Grant permissions via: Settings > Apps > ChloeVR > Permissions
        // Or via adb: adb shell pm grant com.ashairfoil.prism android.permission.RECORD_AUDIO (etc)
        val hasScenePerm = checkSelfPermission("android.permission.SCENE_UNDERSTANDING_COARSE") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "SCENE_UNDERSTANDING_COARSE: ${if (hasScenePerm) "granted" else "not granted (light est will use sensor)"}")

        // Initialize ALL Android hardware sensors via SensorHub
        sensorHub = SensorHub(this).also { it.start() }
        Log.i(TAG, "SensorHub started: ${sensorHub?.activeCount() ?: 0} sensors active")

        // Light sensor now handled by SensorHub — read via sensorHub?.lightLux

        // Ensure settings are initialized (idempotent — safe if MainActivity already did it).
        com.ashairfoil.prism.settings.SettingsManager.init(this)

        // Initialize audio reactor (BeatReactor-style) and restore persisted
        // Chloe-Vibes engine state (preset name or custom tunings).
        audioReactor = AudioReactor().also { ar ->
            val sm = com.ashairfoil.prism.settings.SettingsManager
            ar.useVibesEngine = sm.vibesEngineEnabled
            val preset = sm.vibesPresetName
            if (preset == "Loose" || preset == "Medium" || preset == "Ultimate") {
                ar.vibesEngine.applyPresetByName(preset)
            } else {
                ar.vibesEngine.thresholdKnee = sm.vibesThresholdKnee
                ar.vibesEngine.dynamicCurve = sm.vibesDynamicCurve
                ar.vibesEngine.gateThreshold = sm.vibesGateThreshold
                ar.vibesEngine.attackMs = sm.vibesAttackMs
                ar.vibesEngine.releaseMs = sm.vibesReleaseMs
                ar.vibesEngine.outputGain = sm.vibesOutputGain
                ar.vibesEngine.markCustom()
            }
        }

        // Load cached GLB file list, then rescan in background
        val cacheFile = File(cacheDir, "glb_cache.txt")
        if (cacheFile.exists()) {
            try {
                val cached = cacheFile.readLines().map { File(it) }.filter { it.exists() }
                if (cached.isNotEmpty()) {
                    availableGlbFiles = cached
                    Log.i(TAG, "Loaded ${cached.size} GLB files from cache")
                }
            } catch (e: Exception) { Log.w(TAG, "GLB cache read failed: ${e.message}") }
        }
        loadCachedAudioFiles()
        // Rescan in background and update cache
        Thread {
            val glbFiles = FilePicker.listVideoFiles(this).filter { FilePicker.isModelFile(it) }
            availableGlbFiles = glbFiles
            try { cacheFile.writeText(glbFiles.joinToString("\n") { it.absolutePath }) } catch (e: Exception) { Log.w(TAG, "GLB cache write failed: ${e.message}") }
            Log.i(TAG, "Scanned ${glbFiles.size} GLB files (cache updated)")
        }.start()

        Thread {
            if (!nativeRendererInit(this)) {
                Log.e(TAG, "Failed to initialize OpenXR renderer")
                runOnUiThread { showMessage("Failed to initialize OpenXR renderer.\nPress Back to return.") }
                return@Thread
            }
            Log.i(TAG, "OpenXR renderer initialized")

            // Initialize XR sensor poller (JNI calls are now available)
            sensorPoller = XrSensorPoller(
                pollHandTracking = ::nativePollHandTracking,
                pollEyeTracking = ::nativePollEyeTracking,
                pollFaceTracking = ::nativePollFaceTracking,
                pollPlanes = ::nativePollPlanes,
                pollPerfMetrics = ::nativePollPerfMetrics,
                getSensorCaps = ::nativeGetSensorCapabilities,
                getPassthroughState = ::nativeGetPassthroughState
            )
            sensorPoller.onPlanesUpdated = { planes, lowestHorizY ->
                val gr = glesRenderer
                if (gr != null) {
                    gr.shadowPlanes = applyPlaneAdjustments(planes)
                    if (lowestHorizY < Float.MAX_VALUE) {
                        // If using LOCAL space (fallback), the origin is at the head.
                        // Floor planes will be reported relative to head position.
                        // Detect this: if the lowest horizontal plane Y is above the
                        // camera (which makes no physical sense for a floor), it means
                        // the runtime put Y=0 somewhere below, so use the raw value.
                        // If Y is suspiciously close to head height (within 0.5m of camPosY),
                        // the runtime likely returned STAGE-like coords but with a bad origin.
                        // Use the raw plane Y — it's the best data we have from the runtime.
                        val floorY = lowestHorizY
                        if (!floorSnappedOnce) {
                            gr.gridHeight = floorY
                            detectedFloorY = floorY
                            floorSnappedOnce = true
                            val spaceType = if (nativeIsUsingStageSpace()) "STAGE" else "LOCAL"
                            Log.i(TAG, "Grid snap to lowest horizontal surface: $floorY (space=$spaceType, camY=$camPosY)")
                            // Heel-plant: models may have loaded BEFORE the floor was
                            // detected (plane tracking takes a second to converge). Now
                            // that we know where the floor is, drop every non-preview
                            // model so its boundsMinY (= lowest vertex = heel bottom)
                            // sits exactly on floorY. User request: "the bottom of her
                            // heels are on it".
                            for (placed in sceneManager.models) {
                                if (placed.isPreview) continue
                                val gpuModel = gr.getModel(placed.gpuModelId) ?: continue
                                val currentFootY = placed.posY + gpuModel.boundsMinY * placed.scale
                                placed.posY += (floorY - currentFootY)
                                sceneManager.updateModelTransform(placed)
                            }
                            Log.i(TAG, "Re-planted ${sceneManager.models.size} model heel(s) on floor y=$floorY")
                        } else if (floorY < detectedFloorY - 0.05f) {
                            gr.gridHeight = floorY
                            detectedFloorY = floorY
                            Log.i(TAG, "Grid re-snap to lower surface: $floorY")
                        }
                    }
                }
            }
            // Feed the raw plane buffer (with polygon vertices) into the
            // real-world scene-occlusion manager so placed 3D models get
            // visually blocked by detected furniture/walls.
            sensorPoller.onRawPlaneBufferUpdated = { buffer, count ->
                val gr = glesRenderer
                val gridY = gr?.gridHeight ?: 0f
                sceneOcclusion.onPlanesUpdated(buffer, count, gridY)
            }
            // Scene-occlusion extension availability: bit 3 of sensor caps
            // (XR_ANDROID_trackables). Drives the "Real-world occlusion"
            // menu checkbox greyed-out state.
            val caps = nativeGetSensorCapabilities()
            sceneOcclusion.isExtensionSupported = (caps and (1 shl 3)) != 0
            // Room Track is persisted: the user's last menu choice is the
            // startup default (fresh installs OFF — bad post-reboot room
            // scans left invisible walls clipping the dancer, and most
            // sessions don't need occluders). Plane detection and scene
            // occlusion both follow it.
            roomTrackingEnabled = com.ashairfoil.prism.settings.SettingsManager.roomTracking
            sensorPoller.planeDetectionEnabled = roomTrackingEnabled
            sceneOcclusion.enabled = roomTrackingEnabled &&
                sceneOcclusion.isExtensionSupported &&
                com.ashairfoil.prism.settings.SettingsManager.occlusionEnabled
            Log.i(TAG, "Room tracking (persisted): $roomTrackingEnabled (occlusion=${sceneOcclusion.enabled})")

            foveationAvailable = nativeHasFoveation()
            foveationLevel = 0
            Log.i(TAG, "Foveation available: $foveationAvailable")

            // ── Frame-pacing lock (A) ─────────────────────────────────
            // Apply the user's preferred refresh rate (0 = auto = highest).
            // primeDisplayRefreshRate() in the native layer fires on session
            // READY regardless; this call re-applies once Kotlin settings are
            // available so e.g. a user-selected 90Hz sticks on resume.
            runCatching {
                val rates = nativeGetAvailableRefreshRates()
                if (rates.isNotEmpty()) {
                    Log.i(TAG, "Supported refresh rates: ${rates.joinToString()}")
                    val pref = com.ashairfoil.prism.settings.SettingsManager.displayRefreshRate
                    val ok = nativeRequestDisplayRefreshRate(pref.toFloat())
                    Log.i(TAG, "Requested refresh rate ${if (pref == 0) "auto" else pref.toString()}Hz: $ok (active=${nativeGetDisplayRefreshRate()})")
                } else {
                    Log.w(TAG, "No display refresh rate control available on this runtime")
                }
            }.onFailure { Log.w(TAG, "Refresh-rate setup failed: ${it.message}") }

            // ── Eye-tracked foveation (B) ─────────────────────────────
            // The runtime chains gaze data into the foveation profile when
            // XR_META_foveation_eye_tracked + XR_ANDROID_eye_tracking are both
            // active. We just flip the preference flag; setFoveationLevelSafe()
            // rebuilds the profile with the new chain on the next call.
            runCatching {
                val hasEyeFov = nativeHasEyeTrackedFoveation()
                eyeTrackedFoveationAvailable = hasEyeFov
                val pref = com.ashairfoil.prism.settings.SettingsManager.eyeTrackedFoveation
                val effective = nativeSetEyeTrackedFoveation(pref && hasEyeFov)
                Log.i(TAG, "Eye-tracked foveation: supported=$hasEyeFov pref=$pref effective=$effective")
            }

            // ── Thermal / perf-settings (C) ───────────────────────────
            perfSettingsAvailable = runCatching { nativeHasPerfSettings() }.getOrDefault(false)
            Log.i(TAG, "Perf-settings extension: $perfSettingsAvailable")

            val renderer = GlesModelRenderer()
            if (!renderer.init()) {
                Log.e(TAG, "Failed to initialize GLES renderer")
                runOnUiThread { showMessage("Failed to initialize renderer.\nPress Back to return.") }
                return@Thread
            }
            glesRenderer = renderer
            Log.i(TAG, "GLES renderer initialized")

            // Install the depth-only scene occlusion hook so placed models are
            // z-tested against detected real-world surfaces during renderEye().
            // The native module is compiled/linked on first call below.
            renderer.sceneOcclusionHook = { proj, view ->
                sceneOcclusion.renderDepthOnly(proj, view)
            }
            if (sceneOcclusion.isExtensionSupported) {
                if (sceneOcclusion.ensureInitialized()) {
                    Log.i(TAG, "Scene occlusion: plane-polygon tier active (enabled=${sceneOcclusion.enabled})")
                } else {
                    Log.w(TAG, "Scene occlusion: native init failed; feature disabled")
                    sceneOcclusion.enabled = false
                }
            } else {
                Log.i(TAG, "Scene occlusion: extension unsupported — feature silently disabled")
                sceneOcclusion.enabled = false
            }

            // Initialize SceneManager now that the renderer is ready
            sceneManager = SceneManager(this@FilamentModelActivity, renderer).also { sm ->
                sm.runOnUiThread = { r -> this@FilamentModelActivity.runOnUiThread(r) }
                sm.showMessage = { msg -> showMessage(msg) }
            }
            Log.i(TAG, "SceneManager initialized")

            // Grid starts at Y=0 (stage space floor level)
            // Don't restore saved height — floor position changes between sessions/rooms
            renderer.gridHeight = 0f

            // Load single model from intent
            val path = intent.getStringExtra(EXTRA_MODEL_PATH)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    loadModel(file)
                } else {
                    Log.e(TAG, "Model file not found: $path")
                    runOnUiThread { showMessage("File not found: $path") }
                }
            }

            // Load multiple models from intent
            val paths = intent.getStringArrayListExtra(EXTRA_MODEL_PATHS)
            if (paths != null) {
                for ((i, p) in paths.withIndex()) {
                    val file = File(p)
                    if (file.exists()) {
                        loadModel(file, offsetIndex = i)
                    }
                }
            }

            // Auto-restore last scene if no intent models were loaded
            if (models.isEmpty()) {
                val autosave = File(getScenesDir(), "_autosave.json")
                if (autosave.exists()) {
                    Log.i(TAG, "Restoring last scene from autosave")
                    loadScene(autosave)
                }
            }

            startRenderLoop()

            if (models.isNotEmpty()) {
                menuVisible = false  // don't pop menu on restore
                requestUiRender()
            }
        }.start()
    }

    private fun loadModel(file: File, offsetIndex: Int = 0) {
        sceneManager.loadModel(file, offsetIndex, detectedFloorY,
            camPosX, camPosY, camPosZ, camFwdX, camFwdY, camFwdZ)
    }

    /** Teleport all (non-preview) models to an arm's-length position in front of
     *  the user AND re-snap the floor grid under the user's feet. Preserves
     *  inter-model offsets + any dance anchors so the dance survives. Used
     *  when the XR reference space was set up in a different room and the
     *  scene is sitting in the wrong place. Also drops each model to the new
     *  floor level so they don't float after the re-snap. */
    internal fun centerModelsToView() {
        val list = sceneManager.models.filter { !it.isPreview }
        val gr = glesRenderer
        // Floor reset — assume ~1.6m standing head height. If the user is
        // sitting, the floor will land a bit too high, but they can still
        // stick-adjust with right-click-drag on gridHeight afterward.
        val newFloor = (camPosY - 1.6f).coerceIn(-3f, 3f)
        val floorDelta = if (gr != null) newFloor - gr.gridHeight else 0f
        gr?.gridHeight = newFloor
        detectedFloorY = newFloor
        if (list.isEmpty()) {
            Log.i(TAG, "Recenter: no models, floor reset to y=${"%.2f".format(newFloor)}")
            return
        }
        var cx = 0f; var cz = 0f
        for (m in list) { cx += m.posX; cz += m.posZ }
        cx /= list.size; cz /= list.size
        val hLen = kotlin.math.sqrt(camFwdX * camFwdX + camFwdZ * camFwdZ).coerceAtLeast(0.01f)
        val targetX = camPosX + (camFwdX / hLen) * 1.5f
        val targetZ = camPosZ + (camFwdZ / hLen) * 1.5f
        val dx = targetX - cx
        val dz = targetZ - cz
        // Follow the floor: if the floor moved, slide all models by the same
        // delta so their relationship to the ground stays intact.
        val dy = floorDelta
        for (m in list) {
            m.posX += dx; m.posY += dy; m.posZ += dz
            if (m.animHasBase) { m.animBasePose[0] += dx; m.animBasePose[1] += dy; m.animBasePose[2] += dz }
            if (m.animHasA) { m.animPoseA[0] += dx; m.animPoseA[1] += dy; m.animPoseA[2] += dz }
            if (m.animHasB) { m.animPoseB[0] += dx; m.animPoseB[1] += dy; m.animPoseB[2] += dz }
            sceneManager.updateModelTransform(m)
        }
        Log.i(TAG, "Recentered ${list.size} model(s) to view (Δx=${"%.2f".format(dx)} Δy=${"%.2f".format(dy)} Δz=${"%.2f".format(dz)}); floor=${"%.2f".format(newFloor)}")
    }

    /** Named dance-style presets. IMPROV / SHUFFLE pick from these rather than
     *  spraying random numbers, so the model always moves like a real style.
     *  Tier2-pivot: per-preset pivot heights come from biomechanics of the
     *  style — to make hips MOVE, pivot at SHOULDERS (counterintuitive but
     *  correct for rigid bodies). Counter-roll gain is per-preset so TWERK
     *  can kill it (shoulders stay) and SWAY can boost it. */
    private data class DancePreset(
        val name: String,
        val yawDeg: Float, val yawRate: Int, val yawPhase: Float,
        val pitchDeg: Float, val pitchRate: Int, val pitchPhase: Float,
        val bobM: Float, val yRate: Int, val yPhase: Float,
        val ease: SceneManager.DanceEase,
        val physics: Float,
        val pitchPivot: Float = 0.85f,
        val rollPivot: Float = 0.85f,
        val counterRollPivot: Float = 0.50f,
        val counterRollGain: Float = 0.35f,
        // Tier 3 — motion character: sharpness blends eased-sine → triangle,
        // complexity layers a 2×-rate ghost. Per-preset defaults give each
        // style a recognisable feel beyond amp×rate×ease alone.
        val yawSharp: Float = 0f, val yawCmplx: Float = 0f,
        val pitSharp: Float = 0f, val pitCmplx: Float = 0f,
        val bobSharp: Float = 0f, val bobCmplx: Float = 0f,
        // Phase 2 — optional per-joint articulation on top of the rigid-body
        // rotation. Null (default) = pure Tier 4 behaviour. When set, the
        // layer drives spine/clavicle/arm/head joints AFTER Tier 4 writes.
        // Produced by tools/mixamo_extract (Phase 2 extension, pending).
        val jointLayer: JointDriveLayer? = null,
        // Pose override flag: when true, the stance block writes a
        // hands-on-knees arm pose (big forward reach + fully bent elbows)
        // instead of the default drop+sway. Used by squat-shake presets so
        // the arms land naturally on the thighs during a deep squat.
        val handsOnKnees: Boolean = false,
        // Body/character defaults. Presets now own the whole dance read, not
        // just root yaw/pitch/bob, so explicit PRESET taps can paint a full
        // club-style silhouette in one shot.
        val stanceArch: Float = 0.35f,
        // Glute push defaults to OFF on every preset (user verdict 2026-06-10,
        // on-head, screenshot evidence: balloon glute at the authored 4-5.5cm
        // pushes — "it's just that glute setting"). The CHARACTER sliders
        // remain the opt-in path; D10 spring bones are the planned real
        // flesh-motion replacement. Arch is fine and keeps its values.
        val gluteBasePush: Float = 0f,
        val gluteShakeIntensity: Float = 0.5f,
        val gluteRadius: Float = 0.15f,
        val gluteRate: Int = 1,
        val gluteAltStep: Boolean = false,
        val gluteShakerMode: Boolean = false,
    )
    // Tier1-C: all amps obey sweep-speed law amp × rate ≤ K per axis
    //   yaw K = 64   → max yaw at 1/2: 32°, at 1/4: 16°, at 1/8: 8°, at 1/16: 4°
    //   pitch K = 32 → max pitch at 1/2: 16°, at 1/4: 8°, at 1/8: 4°, at 1/16: 2°
    //   bob K = 0.14 → max bob at 1/2: 7cm, at 1/4: 3.5cm, at 1/8: 1.75cm
    // Tier1-D: preset eases SINE or LINEAR only. Fancier eases (BACK/EXPO/CIRC/
    //   CUBIC) stay reachable via the manual EASE button for slow axes where the
    //   curve reads — not in the randomizer pool. They stack badly with physics
    //   jiggle and look cheap without speed.
    // Dancer notes encoded in the values:
    //   SLOW WIND: pitch phase +¼ from yaw → hip traces a CIRCLE, not a figure-8
    //   BODY ROLL: bob phase at 0, pitch at ¼ → belly leads chest (undulation)
    //   BOUNCE: tiny yaw (pogo stays vertical), LINEAR for punch, all axes locked 1/4
    //   GRIND: slow 1/2 yaw gives the move direction, fast 1/8 pitch is the grind
    //   TWERK: LINEAR 1/8 weight shift + 1/16 pelvic tilt + 1/8 bob counter-phase
    //   SQUAT PULSE: asymmetric bob envelope (fast down, slow up) + slow yaw carrier
    private val dancePresets = listOf(
        //           name          yaw rate ph  pitch rate ph  bob   rate ph    ease                              phys  pP    rP    crP   crG    yawS  yawC  pitS  pitC  bobS  bobC
        DancePreset("SLOW WIND",    6f,  2, 0f,     5f,  2, 0.25f,   0.010f, 2, 0.50f, SceneManager.DanceEase.SINE,   0.65f, 0.85f, 0.85f, 0.50f, 0.35f, 0.0f, 0.2f, 0.0f, 0.2f, 0.0f, 0.0f),
        DancePreset("SWAY",         6f,  2, 0f,     1f,  2, 0.50f,   0.003f, 2, 0.25f, SceneManager.DanceEase.SINE,   0.25f, 0.80f, 0.90f, 0.50f, 0.50f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
        DancePreset("BODY ROLL",    4f,  2, 0f,    12f,  2, 0.25f,   0.020f, 2, 0f,    SceneManager.DanceEase.SINE,   0.70f, 0.55f, 0.55f, 0.50f, 0.35f, 0.0f, 0.3f, 0.3f, 0.3f, 0.2f, 0.0f),
        DancePreset("BOUNCE",       1f,  4, 0f,     4f,  4, 0.75f,   0.025f, 4, 0f,    SceneManager.DanceEase.LINEAR, 0.80f, 0.00f, 0.00f, 0.50f, 0.35f, 0.8f, 0.0f, 0.6f, 0.0f, 0.9f, 0.0f),
        DancePreset("GRIND",        6f,  2, 0f,     3f,  8, 0f,      0.008f, 8, 0.25f, SceneManager.DanceEase.SINE,   0.55f, 0.95f, 0.85f, 0.50f, 0.35f, 0.3f, 0.4f, 0.6f, 0.2f, 0.2f, 0.0f),
        DancePreset("TWERK",        3f,  8, 0.5f,   1.5f,16, 0f,     0.015f, 8, 0.50f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.10f, 0.7f, 0.5f, 0.9f, 0.4f, 0.6f, 0.3f,
            stanceArch = 0.70f,
            gluteRadius = 0.17f, gluteRate = 4, gluteAltStep = true, gluteShakerMode = true),
        DancePreset("CLUB HEAT",   12f,  4, 0.15f,  6f,  4, 0.75f,  0.035f, 8, 0f,    SceneManager.DanceEase.LINEAR, 0.92f, 0.85f, 0.90f, 0.50f, 0.08f, 0.8f, 0.8f, 0.7f, 0.5f, 0.9f, 0.5f,
            stanceArch = 0.78f,
            gluteRadius = 0.19f, gluteRate = 4, gluteAltStep = true, gluteShakerMode = true),
        DancePreset("SQUAT PULSE",  8f,  2, 0f,     4f,  4, 0.25f,   0.030f, 4, 0f,    SceneManager.DanceEase.SINE,   0.75f, 0.25f, 0.35f, 0.50f, 0.35f, 0.7f, 0.0f, 0.6f, 0.0f, 1.0f, 0.0f),
        // SQUAT SHAKE: deep squat + hands-on-knees pose + fast butt shake.
        // Max bob (4 cm) drives full squat depth; yaw at 1/4 rate gives hip
        // gyration; bobSharp=0.85 makes each dip punchy; handsOnKnees=true
        // triggers the Tier 4 arm override (big forward reach + 90° elbow).
        DancePreset("SQUAT SHAKE",  10f, 4, 0f,     3f,  4, 0f,      0.040f, 8, 0f,    SceneManager.DanceEase.LINEAR, 0.70f, 0.50f, 0.50f, 0.50f, 0.10f, 0.3f, 0.3f, 0.0f, 0.0f, 0.85f, 0.0f,
            jointLayer = null, handsOnKnees = true, stanceArch = 0.86f,
            gluteRadius = 0.20f,
            gluteRate = 4, gluteAltStep = true, gluteShakerMode = true),
        // --- Mixamo-extracted presets (Phase 1 rigid-body + Phase 2 joint drives) ---
        // Auto-generated by tools/mixamo_extract/extract_preset.py from 33 Mixamo FBX
        // clips (31 unique after byte-identical dedup). D9 Phase A (2026-06-10): per-bone
        // axis correction is BAKED IN — each bone's sampled rotation track is conjugated
        // from the Mixamo bone-local frame into the Tripo bone-local frame
        // (C = W_tripoBind^-1 @ W_mixamoBind per bone, from the production
        // ChloeVR_Bikini_FootPivot.glb bind pose + the clip's own PreRotation chain,
        // both validated against skinning ground truth: IBMs / TransformLink, 0.000 deg)
        // before per-axis Fourier refit. Verified: Mixamo elbow flex (forearm Z) now
        // lands on Tripo forearm X — the axis Tier 4 established empirically.
        // Rigid-body fields still saturate the schema caps (yawDeg<=15, pitchDeg<=10,
        // bobM<=0.04) on every non-trivial clip; the articulation lives in the layers.
        DancePreset("ARMS HIP HOP MIXAMO", 15.00f, 2, 0.270f, 10.00f, 1, 0.077f, 0.0400f, 4, 0.664f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.87f, 0.19f, 0.15f, 1.00f, 0.72f, 0.30f, 0.07f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.613f, 2.33f, 0.67f, 0.674f, -2.03f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.801f, 1.78f, 0.00f, 0.000f, -6.80f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.611f, 2.31f, 0.65f, 0.674f, -2.01f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.798f, 1.78f, 0.00f, 0.000f, -6.83f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.259f, 1.70f, 0.00f, 0.000f, 2.99f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.482f, 2.55f, 0.00f, 0.000f, 1.55f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.954f, 1.57f, 0.70f, 0.986f, 3.56f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.160f, 15.41f, 1.23f, 0.140f, 9.53f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.280f, 2.88f, 0.00f, 0.000f, 45.46f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.080f, 9.55f, 1.59f, 0.602f, 5.54f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.519f, 12.47f, 1.06f, 0.299f, 31.69f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.671f, 3.33f, 0.00f, 0.000f, 35.52f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.269f, 7.10f, 0.00f, 0.000f, -3.60f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.018f, 8.51f, 0.00f, 0.000f, 37.26f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.227f, 8.38f, 1.73f, 0.361f, 58.14f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.140f, 2.39f, 0.00f, 0.000f, -9.01f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.189f, 2.73f, 0.00f, 0.000f, 8.07f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.726f, 3.13f, 0.00f, 0.000f, 18.74f),
            ))),
        DancePreset("BELLY MIXAMO", 15.00f, 2, 0.347f, 10.00f, 1, 0.393f, 0.0400f, 4, 0.631f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.96f, 0.51f, 0.15f, 1.00f, 0.11f, 1.00f, 0.10f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.973f, 1.77f, 0.00f, 0.000f, -0.17f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.973f, 1.76f, 0.00f, 0.000f, -0.16f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.668f, 4.56f, 0.00f, 0.000f, -6.50f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.953f, 2.16f, 0.00f, 0.000f, -20.94f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.483f, 1.86f, 0.00f, 0.000f, 0.07f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.060f, 1.71f, 0.00f, 0.000f, -10.43f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 1.77f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.973f, 9.13f, 1.23f, 0.899f, 23.62f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.469f, 7.62f, 0.80f, 0.777f, 26.87f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.224f, 6.92f, 1.63f, 0.361f, -16.80f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.181f, 9.14f, 1.05f, 0.177f, 33.59f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.315f, 2.83f, 0.00f, 0.000f, 12.03f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.805f, 4.72f, 0.00f, 0.000f, -5.36f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.770f, 4.60f, 0.67f, 0.174f, 44.45f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.119f, 5.82f, 0.00f, 0.000f, 37.42f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 1, 0.149f, 1.51f, 0.69f, 0.731f, 1.10f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.206f, 3.50f, 0.00f, 0.000f, -1.71f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 1, 0.868f, 2.43f, 0.67f, 0.915f, -19.86f),
            ))),
        DancePreset("BELLYDANCING MIXAMO", 15.00f, 2, 0.072f, 10.00f, 1, 0.280f, 0.0400f, 4, 0.615f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 0.46f, 0.31f, 1.00f, 0.30f, 0.88f, 0.14f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 2, 0.627f, 3.73f, 0.00f, 0.000f, -0.25f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 2, 0.628f, 3.73f, 0.00f, 0.000f, -0.25f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.124f, 11.98f, 0.83f, 0.596f, -2.42f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.887f, 5.89f, 0.69f, 0.718f, -10.90f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.143f, 2.28f, 0.00f, 0.000f, 0.09f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.645f, 6.13f, 1.00f, 0.556f, 1.70f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.801f, 2.97f, 0.00f, 0.000f, -6.92f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.883f, 5.06f, 0.00f, 0.000f, -10.92f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.726f, 2.25f, 0.00f, 0.000f, 26.00f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.072f, 2.62f, 0.66f, 0.172f, -13.99f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.555f, 8.55f, 1.25f, 0.571f, 21.15f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.568f, 2.86f, 0.00f, 0.000f, 18.35f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.661f, 3.45f, 1.01f, 0.227f, 16.72f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.026f, 8.82f, 0.75f, 0.274f, 43.67f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.983f, 9.15f, 2.40f, 0.099f, 44.31f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.559f, 12.35f, 0.73f, 0.318f, -0.85f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.148f, 1.50f, 0.00f, 0.000f, 3.90f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.270f, 1.73f, 0.00f, 0.000f, -2.44f),
            ))),
        DancePreset("BOOTY HIP HOP MIXAMO", 15.00f, 1, 0.281f, 5.38f, 1, 0.120f, 0.0400f, 4, 0.422f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.31f, 0.71f, 0.09f, 1.00f, 0.31f, 0.64f, 0.30f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.333f, 6.73f, 0.90f, 0.060f, 1.54f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.66f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -5.40f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.332f, 6.69f, 0.89f, 0.061f, 1.43f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.70f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -5.50f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.553f, 2.73f, 0.87f, 0.232f, -1.71f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.744f, 3.47f, 0.00f, 0.000f, -11.34f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.878f, 1.87f, 0.00f, 0.000f, 1.90f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.826f, 1.54f, 0.00f, 0.000f, -2.52f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.352f, 10.77f, 2.66f, 0.964f, 7.59f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.355f, 4.00f, 1.43f, 0.979f, 34.55f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.979f, 10.29f, 1.72f, 0.071f, -44.46f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.891f, 13.13f, 6.19f, 0.874f, 22.19f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.725f, 6.56f, 2.35f, 0.848f, 40.73f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.915f, 13.47f, 4.96f, 0.889f, 32.45f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.903f, 5.65f, 0.83f, 0.573f, 103.24f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.337f, 12.76f, 1.61f, 0.035f, 75.31f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.812f, 3.49f, 0.00f, 0.000f, -4.88f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 1, 0.089f, 12.11f, 1.57f, 0.064f, 9.75f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 1, 0.498f, 2.59f, 1.91f, 0.137f, 13.57f),
            ))),
        DancePreset("BREAKDANCE UPROCK VAR 2 MIXAMO", 15.00f, 2, 0.472f, 10.00f, 1, 0.495f, 0.0400f, 4, 0.446f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.60f, 0.29f, 0.08f, 0.32f, 0.75f, 0.14f, 0.05f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 2, 0.227f, 4.09f, 1.21f, 0.135f, 0.97f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 2, 0.677f, 5.80f, 0.00f, 0.000f, -1.52f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 2, 0.671f, 3.68f, 0.00f, 0.000f, -14.12f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 2, 0.223f, 4.13f, 1.14f, 0.133f, 0.72f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 2, 0.678f, 5.77f, 0.00f, 0.000f, -1.77f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 2, 0.669f, 3.69f, 0.00f, 0.000f, -14.49f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.217f, 8.51f, 1.39f, 0.174f, 12.71f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.671f, 17.10f, 4.16f, 0.607f, 8.98f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.696f, 3.07f, 1.51f, 0.655f, 4.47f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.569f, 5.56f, 2.45f, 0.247f, 17.45f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.117f, 8.09f, 2.34f, 0.565f, 6.01f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.271f, 9.82f, 0.87f, 0.024f, -2.57f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.182f, 30.00f, 3.37f, 0.362f, -0.13f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.742f, 5.85f, 1.42f, 0.545f, 30.99f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.986f, 9.66f, 3.73f, 0.748f, -2.08f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.705f, 22.54f, 5.17f, 0.697f, 23.91f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.175f, 9.40f, 4.02f, 0.119f, 40.45f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.704f, 8.54f, 0.73f, 0.046f, 8.37f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.254f, 30.00f, 5.28f, 0.100f, 51.11f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.692f, 29.75f, 6.05f, 0.042f, 56.56f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.238f, 6.00f, 1.06f, 0.664f, -3.06f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.746f, 6.13f, 0.97f, 0.466f, 6.37f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.733f, 4.71f, 0.65f, 0.372f, 16.38f),
            ))),
        DancePreset("CHICKEN MIXAMO", 15.00f, 4, 0.801f, 7.58f, 2, 0.873f, 0.0400f, 4, 0.331f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.84f, 0.80f, 0.25f, 1.00f, 0.19f, 1.00f, 0.28f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.60f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.546f, 1.54f, 0.00f, 0.000f, 0.68f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.60f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.856f, 7.22f, 2.92f, 0.596f, 8.19f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.603f, 8.04f, 2.68f, 0.206f, -3.51f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.093f, 8.33f, 2.07f, 0.076f, 6.43f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.619f, 4.61f, 2.50f, 0.463f, 4.77f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 3.17f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.161f, 17.06f, 5.90f, 0.599f, 3.83f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.996f, 7.28f, 3.37f, 0.863f, 23.89f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.429f, 9.27f, 10.93f, 0.402f, -16.56f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 8, 0.250f, 4.15f, 0.91f, 0.554f, 17.92f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.456f, 9.65f, 0.00f, 0.000f, 18.58f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.600f, 12.89f, 3.98f, 0.731f, -3.54f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 8, 0.117f, 8.32f, 1.19f, 0.400f, 109.80f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Y, 4, 0.783f, 5.99f, 4.55f, 0.146f, -1.83f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Z, 4, 0.049f, 4.15f, 1.75f, 0.512f, -4.70f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 8, 0.486f, 5.58f, 2.33f, 0.897f, 95.62f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.102f, 6.75f, 0.75f, 0.942f, -1.92f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.663f, 4.23f, 0.61f, 0.337f, 4.26f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.787f, 3.85f, 0.00f, 0.000f, 9.82f),
            ))),
        DancePreset("CROSSLEG FREEZE MIXAMO", 15.00f, 1, 0.682f, 10.00f, 1, 0.966f, 0.0400f, 4, 0.574f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 1.00f, 0.71f, 1.00f, 0.37f, 0.77f, 0.25f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 1, 0.869f, 1.87f, 1.19f, 0.536f, 0.09f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 4, 0.675f, 1.81f, 0.00f, 0.000f, 0.40f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.161f, 4.70f, 3.95f, 0.639f, -6.97f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 1, 0.881f, 2.01f, 1.53f, 0.544f, 0.03f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 4, 0.679f, 1.82f, 0.00f, 0.000f, 0.21f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.114f, 2.16f, 0.00f, 0.000f, -8.04f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.669f, 9.73f, 1.12f, 0.204f, 19.77f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.115f, 5.68f, 1.13f, 0.878f, 0.06f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.262f, 9.11f, 0.00f, 0.000f, -22.48f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.218f, 2.23f, 0.00f, 0.000f, 15.80f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.216f, 7.95f, 2.10f, 0.972f, 7.06f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.565f, 1.74f, 1.48f, 0.053f, 3.90f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.446f, 8.06f, 7.23f, 0.101f, -58.01f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.269f, 6.36f, 1.12f, 0.351f, 43.88f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.254f, 7.54f, 7.08f, 0.060f, -66.89f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.229f, 30.00f, 8.14f, 0.896f, 31.19f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.634f, 7.72f, 0.00f, 0.000f, 45.61f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.178f, 30.00f, 9.31f, 0.913f, -29.80f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.437f, 3.45f, 2.58f, 0.486f, 61.06f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.632f, 7.02f, 3.55f, 0.342f, 54.19f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.566f, 3.97f, 0.00f, 0.000f, 5.84f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.519f, 4.28f, 0.00f, 0.000f, -23.54f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.274f, 3.06f, 0.00f, 0.000f, 2.53f),
            ))),
        DancePreset("DANCING 1 MIXAMO", 15.00f, 2, 0.007f, 10.00f, 1, 0.905f, 0.0400f, 4, 0.662f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.96f, 0.87f, 0.07f, 1.00f, 0.28f, 0.03f, 0.12f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.49f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.48f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 1, 0.546f, 3.01f, 0.68f, 0.204f, 4.31f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.099f, 1.70f, 0.00f, 0.000f, 1.15f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.73f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 1, 0.552f, 2.47f, 1.24f, 0.832f, 2.49f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.471f, 2.33f, 0.62f, 0.165f, 1.43f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.324f, 2.54f, 0.00f, 0.000f, 13.46f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.756f, 2.89f, 0.00f, 0.000f, 14.38f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.436f, 1.68f, 0.66f, 0.700f, -17.03f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.527f, 2.11f, 0.98f, 0.881f, 3.09f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.436f, 1.59f, 0.77f, 0.298f, 8.01f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.370f, 2.79f, 1.23f, 0.575f, -11.20f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.342f, 4.65f, 1.16f, 0.430f, 32.02f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.649f, 3.39f, 0.87f, 0.837f, 48.93f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 1, 0.584f, 1.56f, 0.94f, 0.035f, -1.15f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.167f, 5.36f, 0.00f, 0.000f, -12.59f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.064f, 1.54f, 0.00f, 0.000f, -2.96f),
            ))),
        DancePreset("DANCING TWERK 1 MIXAMO", 15.00f, 2, 0.107f, 10.00f, 4, 0.519f, 0.0400f, 4, 0.657f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.41f, 0.84f, 0.14f, 0.30f, 0.06f, 0.12f, 0.06f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 2, 0.998f, 1.54f, 0.00f, 0.000f, 0.08f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 2, 0.843f, 1.67f, 0.00f, 0.000f, -1.66f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.666f, 2.32f, 0.00f, 0.000f, -2.33f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 2, 0.999f, 1.52f, 0.00f, 0.000f, -0.02f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 2, 0.869f, 1.60f, 0.00f, 0.000f, -1.68f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.666f, 2.36f, 0.00f, 0.000f, -2.15f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.762f, 2.90f, 0.00f, 0.000f, -6.62f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.167f, 1.92f, 0.00f, 0.000f, 12.13f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.478f, 1.69f, 0.90f, 0.435f, -6.10f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.542f, 1.66f, 0.00f, 0.000f, 14.63f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.833f, 2.09f, 0.00f, 0.000f, 0.17f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.834f, 6.50f, 1.25f, 0.715f, 24.83f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.784f, 4.25f, 2.42f, 0.771f, 35.81f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.857f, 5.28f, 0.77f, 0.554f, 31.30f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.551f, 3.66f, 0.00f, 0.000f, 39.86f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.333f, 1.81f, 0.00f, 0.000f, 19.73f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.226f, 3.73f, 0.00f, 0.000f, 25.86f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.461f, 8.57f, 0.90f, 0.988f, 87.79f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.907f, 4.95f, 0.00f, 0.000f, 97.13f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.736f, 3.09f, 1.39f, 0.196f, 1.73f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.360f, 4.91f, 2.91f, 0.990f, -7.85f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.721f, 5.59f, 1.05f, 0.263f, 20.13f),
            ))),
        DancePreset("DANCING MIXAMO", 9.62f, 2, 0.010f, 10.00f, 2, 0.535f, 0.0400f, 4, 0.213f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.28f, 1.00f, 0.13f, 1.00f, 0.24f, 0.44f, 0.12f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 8, 0.504f, 1.85f, 0.00f, 0.000f, 0.26f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 2, 0.523f, 6.62f, 1.44f, 0.720f, -1.99f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.273f, 8.37f, 0.00f, 0.000f, -12.69f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.498f, 1.88f, 0.00f, 0.000f, 0.28f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 4, 0.728f, 1.58f, 0.00f, 0.000f, -2.17f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.273f, 8.38f, 0.00f, 0.000f, -12.61f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.784f, 5.19f, 1.18f, 0.606f, 0.30f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.821f, 5.12f, 1.45f, 0.689f, -6.39f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.124f, 4.69f, 0.00f, 0.000f, 2.22f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.018f, 4.48f, 0.00f, 0.000f, -9.19f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 8, 0.483f, 9.99f, 2.28f, 0.010f, -5.69f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.970f, 11.31f, 3.47f, 0.780f, 50.76f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.588f, 10.85f, 2.71f, 0.056f, -28.61f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.776f, 30.00f, 5.12f, 0.286f, 21.44f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.838f, 14.12f, 7.87f, 0.815f, 50.50f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.769f, 30.00f, 2.95f, 0.463f, -19.10f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.327f, 5.81f, 2.85f, 0.069f, 7.99f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.163f, 8.29f, 1.59f, 0.840f, 13.81f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 8, 0.314f, 3.58f, 0.00f, 0.000f, -2.27f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 8, 0.201f, 9.04f, 0.00f, 0.000f, 1.09f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.876f, 9.73f, 1.84f, 0.692f, -8.24f),
            ))),
        DancePreset("HIP HOP 1 MIXAMO", 15.00f, 2, 0.839f, 10.00f, 2, 0.004f, 0.0400f, 4, 0.473f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.66f, 0.04f, 0.48f, 1.00f, 0.11f, 0.08f, 0.12f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 8, 0.071f, 2.14f, 0.00f, 0.000f, -1.23f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 4, 0.973f, 1.69f, 0.00f, 0.000f, 0.50f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.910f, 4.42f, 1.48f, 0.225f, 0.88f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.068f, 2.15f, 0.00f, 0.000f, -1.25f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 4, 0.979f, 1.64f, 0.00f, 0.000f, 0.47f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.911f, 4.41f, 1.48f, 0.224f, 0.88f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.341f, 1.51f, 1.22f, 0.937f, -6.86f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.404f, 2.04f, 1.26f, 0.030f, 4.99f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.930f, 3.85f, 0.96f, 0.510f, -5.82f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.439f, 2.49f, 0.69f, 0.965f, -0.34f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.316f, 16.82f, 3.99f, 0.066f, 53.19f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.723f, 14.04f, 2.09f, 0.014f, 17.34f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.367f, 11.19f, 2.04f, 0.074f, 64.06f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.228f, 15.20f, 2.75f, 0.545f, 48.80f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.183f, 14.26f, 2.06f, 0.908f, 14.39f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.765f, 16.51f, 2.08f, 0.274f, 43.42f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.751f, 10.70f, 1.10f, 0.033f, 19.09f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.687f, 12.77f, 2.00f, 0.145f, 24.82f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.748f, 5.59f, 0.62f, 0.982f, 3.55f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.377f, 3.57f, 0.00f, 0.000f, 1.54f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.383f, 5.92f, 0.97f, 0.069f, -6.12f),
            ))),
        DancePreset("HIP HOP 2 MIXAMO", 15.00f, 1, 0.146f, 10.00f, 2, 0.428f, 0.0400f, 4, 0.588f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.81f, 0.49f, 0.16f, 1.00f, 0.58f, 0.21f, 0.50f,
            JointDriveLayer(arrayOf(
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.674f, 3.99f, 0.00f, 0.000f, 5.93f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.262f, 3.22f, 1.05f, 0.495f, -3.05f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.282f, 3.50f, 0.00f, 0.000f, 4.28f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.216f, 3.67f, 0.87f, 0.382f, 2.38f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.327f, 18.26f, 3.57f, 0.534f, 36.01f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.083f, 4.55f, 0.00f, 0.000f, 44.51f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.972f, 8.88f, 3.42f, 0.175f, 36.45f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.983f, 6.13f, 1.97f, 0.095f, 50.48f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.516f, 3.45f, 0.00f, 0.000f, 22.70f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.609f, 5.76f, 1.38f, 0.095f, 42.98f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.598f, 8.01f, 0.85f, 0.937f, 38.16f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.272f, 6.39f, 0.85f, 0.468f, 39.05f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.358f, 2.80f, 0.00f, 0.000f, 5.88f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.076f, 2.56f, 0.00f, 0.000f, -6.91f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.632f, 3.44f, 1.43f, 0.837f, -13.93f),
            ))),
        DancePreset("HIP HOP 3 MIXAMO", 15.00f, 2, 0.976f, 10.00f, 4, 0.799f, 0.0400f, 4, 0.174f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.54f, 0.61f, 0.20f, 0.72f, 0.23f, 0.24f, 0.08f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.413f, 2.56f, 0.00f, 0.000f, -0.33f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.029f, 3.27f, 0.00f, 0.000f, -8.91f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.413f, 2.58f, 0.00f, 0.000f, -0.27f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.029f, 3.27f, 0.00f, 0.000f, -8.91f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.968f, 2.28f, 0.84f, 0.839f, 5.11f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.214f, 2.51f, 1.19f, 0.955f, -10.81f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.856f, 2.41f, 0.00f, 0.000f, 10.19f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.517f, 2.19f, 0.66f, 0.642f, 2.18f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 1.81f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.884f, 18.20f, 3.09f, 0.632f, 30.47f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.238f, 5.36f, 0.76f, 0.764f, 19.65f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.028f, 9.45f, 1.29f, 0.440f, 32.13f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.964f, 19.63f, 1.99f, 0.666f, 38.38f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.560f, 4.86f, 0.67f, 0.239f, 20.39f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.461f, 8.70f, 2.83f, 0.170f, 20.51f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.915f, 11.53f, 2.70f, 0.124f, 52.60f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.364f, 15.88f, 1.03f, 0.299f, 42.67f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.034f, 2.31f, 0.00f, 0.000f, -1.43f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.202f, 7.08f, 0.00f, 0.000f, -3.14f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.922f, 3.69f, 0.00f, 0.000f, 17.42f),
            ))),
        DancePreset("HIP HOP 4 MIXAMO", 9.39f, 2, 0.184f, 6.25f, 2, 0.906f, 0.0321f, 4, 0.571f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.48f, 1.00f, 0.54f, 1.00f, 0.15f, 0.33f, 0.10f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 8, 0.353f, 2.98f, 0.00f, 0.000f, -0.49f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.425f, 4.09f, 0.85f, 0.275f, 0.00f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.353f, 2.98f, 0.00f, 0.000f, -0.49f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.425f, 4.09f, 0.85f, 0.275f, 0.01f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.887f, 1.71f, 0.00f, 0.000f, -4.07f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.776f, 3.71f, 0.00f, 0.000f, -0.06f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.914f, 7.86f, 2.55f, 0.755f, -4.15f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.386f, 5.36f, 2.00f, 0.263f, -1.04f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 8, 0.963f, 5.58f, 1.51f, 0.205f, 18.98f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.465f, 6.16f, 5.00f, 0.380f, 8.14f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.825f, 15.24f, 2.53f, 0.858f, 44.60f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.350f, 15.90f, 4.68f, 0.299f, -3.79f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.277f, 6.93f, 6.85f, 0.396f, 5.24f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.863f, 30.00f, 9.38f, 0.691f, 8.16f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.724f, 11.98f, 0.00f, 0.000f, 31.70f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.833f, 14.75f, 0.87f, 0.162f, 38.94f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 8, 0.771f, 5.32f, 0.00f, 0.000f, 0.74f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 8, 0.149f, 1.60f, 1.04f, 0.879f, 8.56f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.914f, 9.48f, 2.21f, 0.839f, -2.75f),
            ))),
        DancePreset("HIP HOP 5 MIXAMO", 15.00f, 1, 0.972f, 10.00f, 2, 0.719f, 0.0400f, 4, 0.651f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.94f, 1.00f, 0.32f, 1.00f, 0.22f, 0.20f, 0.06f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.266f, 4.97f, 0.00f, 0.000f, -2.26f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.784f, 1.80f, 0.00f, 0.000f, -6.52f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.266f, 4.95f, 0.00f, 0.000f, -2.23f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.782f, 1.75f, 0.00f, 0.000f, -6.63f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.361f, 1.96f, 0.00f, 0.000f, -0.74f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -5.84f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.670f, 2.01f, 0.00f, 0.000f, -2.12f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.324f, 1.75f, 0.00f, 0.000f, -1.67f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.209f, 14.48f, 2.38f, 0.857f, -1.89f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.221f, 7.64f, 0.72f, 0.645f, 20.10f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.321f, 11.40f, 2.26f, 0.618f, -49.04f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.447f, 14.58f, 2.74f, 0.504f, 38.67f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, 28.01f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.537f, 10.64f, 2.58f, 0.601f, -41.87f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 1, 0.561f, 1.61f, 0.91f, 0.237f, 52.42f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 34.65f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.356f, 2.12f, 0.00f, 0.000f, -11.32f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.717f, 2.32f, 0.00f, 0.000f, 0.38f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 1, 0.281f, 5.60f, 1.44f, 0.668f, 1.59f),
            ))),
        DancePreset("HIP HOP 6 MIXAMO", 11.44f, 2, 0.082f, 10.00f, 8, 0.956f, 0.0348f, 4, 0.633f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.01f, 1.00f, 0.26f, 0.14f, 0.10f, 0.50f, 0.87f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 8, 0.280f, 2.59f, 0.00f, 0.000f, -0.40f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 2, 0.923f, 3.29f, 0.69f, 0.755f, -1.79f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 8, 0.225f, 9.13f, 0.62f, 0.969f, -0.85f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.276f, 2.63f, 0.00f, 0.000f, -0.34f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 2, 0.925f, 3.01f, 0.69f, 0.740f, -1.78f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 8, 0.225f, 9.11f, 0.62f, 0.970f, -0.83f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 8, 0.658f, 3.43f, 0.00f, 0.000f, -6.00f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.127f, 5.12f, 0.00f, 0.000f, 0.43f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 8, 0.657f, 8.38f, 0.00f, 0.000f, -1.13f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.136f, 5.33f, 0.00f, 0.000f, -3.03f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 8, 0.678f, 20.68f, 4.24f, 0.659f, 56.54f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.110f, 20.82f, 6.89f, 0.617f, 20.25f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.540f, 5.50f, 0.91f, 0.614f, 3.84f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 8, 0.729f, 21.14f, 3.24f, 0.647f, 32.76f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.722f, 10.10f, 0.93f, 0.551f, 16.06f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.062f, 4.60f, 2.83f, 0.982f, -1.40f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 8, 0.161f, 30.00f, 0.00f, 0.000f, 76.57f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 8, 0.192f, 30.00f, 0.76f, 0.059f, 83.17f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 8, 0.738f, 3.83f, 0.63f, 0.188f, 5.71f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 8, 0.248f, 2.06f, 0.00f, 0.000f, 2.11f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 8, 0.765f, 4.98f, 0.00f, 0.000f, -4.26f),
            ))),
        DancePreset("HIP HOP 7 MIXAMO", 5.14f, 1, 0.878f, 2.92f, 1, 0.474f, 0.0079f, 4, 0.155f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.00f, 0.95f, 0.40f, 1.00f, 0.06f, 1.00f, 0.60f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 2, 0.595f, 1.75f, 0.00f, 0.000f, -1.63f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -5.53f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 2, 0.594f, 1.75f, 0.00f, 0.000f, -1.59f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -5.55f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.877f, 1.57f, 0.00f, 0.000f, 7.87f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.902f, 2.69f, 0.74f, 0.754f, 0.29f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 7.71f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.927f, 2.76f, 0.90f, 0.739f, 16.66f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 1.80f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.412f, 4.46f, 1.54f, 0.209f, -27.23f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, 44.98f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.438f, 5.57f, 2.23f, 0.215f, 20.37f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.450f, 2.51f, 1.02f, 0.360f, -24.27f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, 49.10f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.454f, 4.81f, 1.78f, 0.297f, 18.34f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 1, 0.221f, 1.67f, 0.00f, 0.000f, 99.16f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.021f, 1.56f, 0.82f, 0.928f, 97.57f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.423f, 1.82f, 0.00f, 0.000f, -3.75f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.951f, 1.65f, 0.00f, 0.000f, 17.55f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.371f, 2.91f, 0.00f, 0.000f, 21.97f),
            ))),
        DancePreset("HIP HOP 8 MIXAMO", 15.00f, 1, 0.253f, 10.00f, 1, 0.303f, 0.0400f, 4, 0.581f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 0.91f, 0.28f, 1.00f, 0.57f, 0.24f, 0.08f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 8, 0.347f, 2.42f, 0.00f, 0.000f, -0.01f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -7.38f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 8, 0.348f, 2.32f, 0.00f, 0.000f, 0.02f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -7.44f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 1, 0.286f, 2.55f, 1.47f, 0.747f, 2.96f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.955f, 2.11f, 1.18f, 0.063f, 0.94f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.410f, 1.88f, 0.00f, 0.000f, 6.06f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.258f, 1.75f, 1.22f, 0.778f, 8.15f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.08f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.543f, 10.65f, 3.56f, 0.866f, 10.76f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.479f, 2.60f, 0.69f, 0.532f, 45.11f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.598f, 7.59f, 1.58f, 0.940f, -6.04f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.440f, 7.56f, 2.78f, 0.412f, 21.82f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.707f, 3.66f, 0.83f, 0.790f, 47.52f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.083f, 9.58f, 3.98f, 0.136f, -3.45f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.974f, 10.47f, 0.93f, 0.894f, 46.02f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.026f, 9.72f, 0.93f, 0.896f, 53.13f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 8, 0.888f, 3.22f, 0.00f, 0.000f, -5.84f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.913f, 3.07f, 1.78f, 0.449f, 1.68f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.635f, 2.05f, 0.00f, 0.000f, 0.52f),
            ))),
        DancePreset("HIP HOP 9 MIXAMO", 15.00f, 1, 0.210f, 10.00f, 4, 0.465f, 0.0400f, 4, 0.480f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.00f, 0.81f, 0.42f, 0.14f, 0.04f, 0.14f, 0.10f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 1, 0.506f, 2.65f, 0.00f, 0.000f, -0.44f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 1, 0.438f, 1.92f, 0.00f, 0.000f, -0.02f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.840f, 1.51f, 0.00f, 0.000f, -0.62f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 1, 0.506f, 2.66f, 0.00f, 0.000f, -0.44f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 1, 0.438f, 1.92f, 0.00f, 0.000f, -0.03f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.840f, 1.51f, 0.00f, 0.000f, -0.59f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.169f, 1.66f, 0.00f, 0.000f, 1.99f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.183f, 10.53f, 1.03f, 0.421f, -1.90f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.185f, 1.57f, 0.00f, 0.000f, 0.61f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.669f, 5.37f, 0.79f, 0.979f, 1.57f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.172f, 7.35f, 0.91f, 0.476f, -3.11f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.165f, 5.72f, 1.46f, 0.281f, 33.05f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.201f, 9.97f, 2.41f, 0.582f, 44.81f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.213f, 16.42f, 1.49f, 0.072f, -30.80f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.360f, 8.52f, 3.51f, 0.333f, 37.29f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.194f, 15.87f, 4.26f, 0.652f, 50.64f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.213f, 17.82f, 2.14f, 0.120f, -30.08f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.122f, 30.00f, 4.54f, 0.514f, 72.69f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.166f, 30.00f, 4.45f, 0.622f, 66.37f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.946f, 1.59f, 0.00f, 0.000f, -4.82f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.493f, 3.00f, 0.91f, 0.854f, -2.81f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.654f, 7.16f, 0.69f, 0.263f, 3.50f),
            ))),
        DancePreset("HIP HOP MIXAMO", 15.00f, 2, 0.027f, 9.65f, 4, 0.124f, 0.0400f, 4, 0.182f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.20f, 1.00f, 0.32f, 0.97f, 0.72f, 1.00f, 0.20f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.456f, 2.55f, 0.00f, 0.000f, 0.26f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 8, 0.450f, 1.99f, 0.00f, 0.000f, 0.94f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 8, 0.468f, 2.62f, 0.00f, 0.000f, -2.36f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.453f, 2.55f, 0.00f, 0.000f, 0.25f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 8, 0.451f, 1.96f, 0.00f, 0.000f, 0.90f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 8, 0.468f, 2.61f, 0.00f, 0.000f, -2.43f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.451f, 2.81f, 0.70f, 0.233f, -6.19f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.832f, 2.51f, 0.00f, 0.000f, 7.75f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 8, 0.408f, 6.88f, 0.98f, 0.177f, -5.92f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 8, 0.913f, 5.04f, 0.00f, 0.000f, -2.81f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.819f, 19.74f, 2.25f, 0.392f, 49.97f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.771f, 7.90f, 0.89f, 0.578f, 9.89f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.415f, 13.05f, 1.98f, 0.370f, 48.35f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.825f, 22.65f, 0.00f, 0.000f, 45.30f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.528f, 7.00f, 1.25f, 0.301f, 16.02f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.388f, 16.59f, 1.60f, 0.020f, 37.36f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 8, 0.446f, 19.82f, 2.40f, 0.201f, 25.85f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.910f, 20.06f, 2.78f, 0.527f, 21.36f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.992f, 1.74f, 0.00f, 0.000f, 2.52f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.132f, 3.47f, 1.96f, 0.916f, 1.10f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.970f, 5.17f, 3.24f, 0.419f, -2.05f),
            ))),
        DancePreset("HOUSE MIXAMO", 15.00f, 1, 0.868f, 10.00f, 4, 0.451f, 0.0400f, 4, 0.187f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.90f, 0.21f, 0.06f, 1.00f, 0.32f, 0.34f, 0.30f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -8.48f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -8.49f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.786f, 2.00f, 0.00f, 0.000f, 4.85f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.826f, 2.11f, 0.00f, 0.000f, -2.61f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 1.78f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.659f, 2.09f, 0.00f, 0.000f, -6.52f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.15f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.337f, 14.23f, 4.99f, 0.041f, 26.92f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.627f, 3.58f, 1.56f, 0.255f, 39.40f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.657f, 9.42f, 0.00f, 0.000f, 15.71f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.062f, 15.11f, 2.86f, 0.011f, 23.06f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.204f, 4.83f, 1.55f, 0.683f, 32.34f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.867f, 11.95f, 1.53f, 0.245f, 3.64f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.372f, 11.71f, 2.48f, 0.706f, 69.05f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 8, 0.093f, 9.25f, 1.13f, 0.776f, 82.79f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.686f, 5.85f, 1.28f, 0.534f, -2.54f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.178f, 5.26f, 1.47f, 0.710f, -0.59f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.783f, 9.23f, 1.16f, 0.872f, 20.54f),
            ))),
        DancePreset("MACARENA MIXAMO", 15.00f, 1, 0.082f, 10.00f, 4, 0.997f, 0.0400f, 4, 0.582f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.49f, 1.00f, 0.38f, 0.15f, 0.08f, 0.05f, 0.15f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 4, 0.258f, 1.71f, 0.00f, 0.000f, -7.86f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 4, 0.258f, 1.70f, 0.00f, 0.000f, -7.87f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 1, 0.214f, 3.77f, 2.87f, 0.373f, 3.87f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.643f, 2.09f, 1.11f, 0.809f, -11.02f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 1, 0.343f, 2.23f, 0.00f, 0.000f, 6.18f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.282f, 2.16f, 0.00f, 0.000f, 1.50f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.293f, 10.98f, 5.12f, 0.948f, 35.76f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.156f, 4.31f, 2.44f, 0.692f, 35.80f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.288f, 9.33f, 3.27f, 0.929f, 26.97f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.822f, 10.88f, 2.86f, 0.588f, 30.46f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.328f, 6.41f, 2.11f, 0.878f, 31.04f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.710f, 8.37f, 0.00f, 0.000f, 25.30f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.738f, 7.17f, 2.34f, 0.924f, 86.87f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.060f, 7.62f, 2.54f, 0.476f, 99.95f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.259f, 3.49f, 0.00f, 0.000f, -3.74f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.195f, 2.47f, 0.00f, 0.000f, 5.16f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.619f, 1.71f, 0.00f, 0.000f, 16.92f),
            ))),
        DancePreset("RUMBA MIXAMO", 15.00f, 4, 0.992f, 3.56f, 1, 0.119f, 0.0299f, 4, 0.564f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.48f, 0.21f, 0.08f, 1.00f, 0.78f, 1.00f, 0.15f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.088f, 9.41f, 0.00f, 0.000f, -3.06f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 4, 0.715f, 2.81f, 0.00f, 0.000f, 0.16f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -3.99f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.085f, 9.27f, 0.00f, 0.000f, -3.03f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 4, 0.711f, 2.83f, 0.00f, 0.000f, 0.12f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -4.15f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.348f, 4.48f, 0.80f, 0.695f, -0.23f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.348f, 5.23f, 1.38f, 0.625f, -1.38f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.356f, 2.02f, 0.00f, 0.000f, 0.48f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.720f, 5.87f, 1.13f, 0.481f, -3.99f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.993f, 2.65f, 1.31f, 0.495f, 3.40f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.130f, 30.00f, 2.82f, 0.057f, 23.73f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.301f, 11.87f, 2.78f, 0.385f, 32.58f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.015f, 30.00f, 5.08f, 0.273f, 22.99f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.642f, 30.00f, 6.74f, 0.127f, 17.91f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.910f, 12.46f, 5.10f, 0.431f, 43.60f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.564f, 23.35f, 3.62f, 0.249f, 16.33f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.987f, 6.61f, 0.92f, 0.366f, 104.87f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.497f, 5.97f, 1.11f, 0.539f, 109.36f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.528f, 4.25f, 0.00f, 0.000f, 2.51f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.029f, 2.27f, 0.78f, 0.087f, -6.92f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 1, 0.912f, 2.83f, 2.18f, 0.462f, -5.23f),
            ))),
        DancePreset("SALSA MIXAMO", 15.00f, 1, 0.257f, 10.00f, 2, 0.944f, 0.0314f, 4, 0.695f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.98f, 1.00f, 0.37f, 0.22f, 0.30f, 0.23f, 0.04f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 2, 0.448f, 2.31f, 0.00f, 0.000f, 0.56f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -3.09f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 2, 0.448f, 2.29f, 0.00f, 0.000f, 0.55f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -3.11f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.031f, 2.13f, 0.00f, 0.000f, -0.65f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.253f, 3.97f, 0.00f, 0.000f, 8.71f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.051f, 2.15f, 0.00f, 0.000f, -2.93f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.056f, 2.47f, 0.00f, 0.000f, 5.24f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.345f, 5.84f, 2.57f, 0.266f, 30.74f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.014f, 4.32f, 0.00f, 0.000f, 35.50f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.956f, 6.57f, 3.00f, 0.082f, 22.87f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.426f, 5.97f, 1.38f, 0.909f, 48.51f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.795f, 4.38f, 0.73f, 0.205f, 18.63f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.626f, 4.57f, 0.00f, 0.000f, 25.93f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.989f, 7.04f, 1.00f, 0.457f, 59.30f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.893f, 4.96f, 0.80f, 0.443f, 38.82f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.214f, 2.26f, 0.00f, 0.000f, -4.28f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.541f, 3.98f, 0.00f, 0.000f, 5.47f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.229f, 2.86f, 0.00f, 0.000f, 3.69f),
            ))),
        DancePreset("SAMBA 1 MIXAMO", 15.00f, 1, 0.201f, 10.00f, 1, 0.451f, 0.0400f, 4, 0.334f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.98f, 1.00f, 0.07f, 1.00f, 0.43f, 0.14f, 0.40f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.952f, 2.25f, 0.00f, 0.000f, 0.37f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.06f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.952f, 2.25f, 0.00f, 0.000f, 0.38f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.12f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.990f, 2.09f, 0.00f, 0.000f, 5.01f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.284f, 3.85f, 0.00f, 0.000f, -0.14f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.480f, 2.65f, 0.00f, 0.000f, 3.60f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.274f, 3.26f, 1.30f, 0.990f, 0.17f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.038f, 14.82f, 4.60f, 0.516f, 16.14f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.322f, 9.89f, 1.27f, 0.178f, 39.92f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.058f, 6.66f, 2.26f, 0.235f, -0.67f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.485f, 13.90f, 4.92f, 0.936f, 23.62f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.230f, 9.35f, 5.51f, 0.970f, 34.64f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.390f, 9.30f, 4.48f, 0.989f, 1.40f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.566f, 6.68f, 0.73f, 0.747f, 45.87f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.641f, 12.86f, 1.36f, 0.153f, 61.49f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.201f, 1.84f, 0.00f, 0.000f, 5.47f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.675f, 7.54f, 1.66f, 0.067f, -12.54f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.874f, 2.94f, 0.95f, 0.985f, 7.00f),
            ))),
        DancePreset("SAMBA MIXAMO", 15.00f, 1, 0.914f, 10.00f, 2, 0.430f, 0.0400f, 4, 0.025f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.63f, 0.65f, 0.26f, 1.00f, 0.23f, 0.07f, 0.01f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.252f, 3.51f, 0.00f, 0.000f, 0.10f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.45f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.252f, 3.50f, 0.00f, 0.000f, 0.09f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.55f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 2, 0.494f, 3.44f, 0.61f, 0.292f, 2.93f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.949f, 4.53f, 0.71f, 0.685f, -9.48f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.119f, 4.35f, 1.62f, 0.156f, 6.62f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 2, 0.357f, 3.63f, 0.74f, 0.153f, -6.63f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 2, 0.080f, 22.16f, 4.55f, 0.817f, 24.48f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.047f, 7.93f, 2.33f, 0.799f, 33.67f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.947f, 6.98f, 2.76f, 0.643f, -2.03f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 2, 0.103f, 23.22f, 1.40f, 0.470f, 33.41f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.509f, 9.25f, 5.51f, 0.828f, 28.34f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 2, 0.516f, 6.77f, 0.84f, 0.501f, 5.78f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 2, 0.144f, 17.59f, 7.48f, 0.438f, 48.27f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.885f, 8.03f, 1.68f, 0.863f, 52.59f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.637f, 3.94f, 0.00f, 0.000f, 6.37f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.307f, 5.64f, 1.49f, 0.316f, -5.66f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.713f, 4.26f, 0.78f, 0.587f, 4.71f),
            ))),
        DancePreset("SHUFFLING MIXAMO", 15.00f, 2, 0.448f, 10.00f, 4, 0.332f, 0.0400f, 4, 0.072f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.87f, 0.90f, 0.02f, 0.55f, 0.29f, 0.25f, 0.57f,
            JointDriveLayer(arrayOf(
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.545f, 10.50f, 0.86f, 0.384f, 18.12f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.082f, 15.71f, 4.11f, 0.382f, 2.89f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.072f, 5.97f, 1.83f, 0.399f, 5.54f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.563f, 15.74f, 0.00f, 0.000f, 18.89f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.114f, 9.52f, 3.74f, 0.407f, 10.03f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.513f, 2.00f, 0.91f, 0.469f, 8.15f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.549f, 26.96f, 4.15f, 0.419f, 24.85f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.443f, 7.69f, 2.91f, 0.842f, 33.79f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.115f, 25.71f, 4.63f, 0.451f, 9.75f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.605f, 13.10f, 1.65f, 0.447f, 32.81f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.116f, 12.37f, 1.59f, 0.773f, 26.49f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.074f, 30.00f, 2.11f, 0.733f, 9.75f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.046f, 24.51f, 4.76f, 0.428f, 43.83f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Y, 4, 0.918f, 2.80f, 0.76f, 0.467f, -12.94f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Z, 4, 0.546f, 13.07f, 0.00f, 0.000f, -3.66f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.066f, 30.00f, 3.13f, 0.468f, 39.84f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_Y, 4, 0.627f, 7.22f, 0.87f, 0.744f, -4.05f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_Z, 4, 0.511f, 9.92f, 3.35f, 0.469f, -1.31f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.448f, 1.74f, 0.00f, 0.000f, 5.70f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.112f, 2.05f, 0.00f, 0.000f, -1.91f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.550f, 4.86f, 0.00f, 0.000f, -4.08f),
            ))),
        DancePreset("SNAKE HIP HOP MIXAMO", 15.00f, 2, 0.289f, 10.00f, 1, 0.240f, 0.0400f, 4, 0.400f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.44f, 0.41f, 0.13f, 1.00f, 0.41f, 0.38f, 0.08f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.875f, 2.89f, 0.00f, 0.000f, -0.91f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 2, 0.068f, 3.13f, 1.03f, 0.622f, -4.42f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.874f, 2.88f, 0.00f, 0.000f, -0.89f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 2, 0.068f, 3.13f, 1.02f, 0.622f, -4.46f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.822f, 1.67f, 0.00f, 0.000f, 6.38f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.778f, 4.81f, 2.17f, 0.820f, -7.25f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 1, 0.795f, 3.01f, 1.15f, 0.639f, 5.77f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.782f, 4.89f, 1.43f, 0.689f, -10.85f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, 2.24f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.944f, 10.52f, 1.13f, 0.786f, 40.31f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.102f, 3.14f, 0.70f, 0.640f, 43.41f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.247f, 7.46f, 1.65f, 0.936f, 36.28f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.381f, 6.17f, 2.24f, 0.987f, 51.03f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.943f, 5.90f, 0.70f, 0.795f, 39.10f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.152f, 8.44f, 3.38f, 0.125f, 21.18f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.087f, 7.64f, 1.64f, 0.404f, 49.27f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.479f, 6.46f, 0.00f, 0.000f, 58.85f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 1, 0.789f, 4.93f, 2.10f, 0.522f, -11.58f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.471f, 3.71f, 0.00f, 0.000f, -1.30f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.921f, 2.06f, 0.00f, 0.000f, 24.73f),
            ))),
        DancePreset("TWIST MIXAMO", 15.00f, 4, 0.681f, 10.00f, 1, 0.189f, 0.0400f, 4, 0.567f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.90f, 0.01f, 0.03f, 1.00f, 0.26f, 0.35f, 0.50f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 1, 0.000f, 0.00f, 0.00f, 0.000f, 1.72f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 4, 0.402f, 2.45f, 0.00f, 0.000f, -1.39f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.024f, 3.22f, 0.64f, 0.781f, -2.25f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 4, 0.401f, 2.44f, 0.00f, 0.000f, -1.51f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.023f, 3.22f, 0.65f, 0.782f, -2.24f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.539f, 7.73f, 0.00f, 0.000f, 12.21f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.097f, 7.42f, 0.00f, 0.000f, 6.61f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.324f, 2.33f, 1.31f, 0.024f, 3.88f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.431f, 12.51f, 0.00f, 0.000f, 19.74f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.352f, 3.88f, 0.00f, 0.000f, 7.81f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 4, 0.945f, 3.21f, 0.00f, 0.000f, 8.32f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.933f, 12.25f, 1.73f, 0.816f, 32.91f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.087f, 5.94f, 1.42f, 0.025f, 16.48f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.111f, 9.76f, 2.50f, 0.708f, 28.50f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.572f, 14.04f, 1.37f, 0.566f, 23.62f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.520f, 5.35f, 1.55f, 0.001f, 22.62f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.366f, 11.91f, 3.08f, 0.745f, 14.75f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.022f, 4.24f, 0.97f, 0.081f, 58.34f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.084f, 4.90f, 1.40f, 0.436f, 58.27f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.938f, 3.05f, 0.00f, 0.000f, -2.91f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.213f, 2.06f, 0.90f, 0.334f, 11.55f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 1, 0.386f, 12.81f, 0.87f, 0.393f, 3.57f),
            ))),
        DancePreset("WAVE HIP HOP MIXAMO", 3.81f, 8, 0.321f, 2.99f, 2, 0.975f, 0.0205f, 4, 0.553f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.22f, 0.15f, 0.22f, 1.00f, 0.21f, 0.67f, 0.25f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 4, 0.187f, 1.89f, 1.59f, 0.730f, -0.85f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.83f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.55f),
                JointDrive("Spine02", JointDriveLayer.AXIS_X, 4, 0.187f, 1.92f, 1.58f, 0.730f, -0.86f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -1.84f),
                JointDrive("Spine02", JointDriveLayer.AXIS_Z, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.53f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.251f, 2.26f, 0.72f, 0.258f, 12.28f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, -2.39f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 4, 0.215f, 5.93f, 2.16f, 0.572f, 37.94f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 1, 0.000f, 0.00f, 0.00f, 0.000f, 9.21f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.727f, 1.84f, 1.17f, 0.224f, 6.79f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.151f, 10.57f, 1.34f, 0.089f, 75.64f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.644f, 25.20f, 4.84f, 0.583f, 27.31f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.706f, 12.78f, 0.88f, 0.577f, 9.67f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 8, 0.126f, 4.30f, 1.56f, 0.165f, 25.21f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 8, 0.891f, 6.29f, 0.94f, 0.858f, 29.65f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 8, 0.238f, 25.81f, 1.15f, 0.297f, -13.15f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.709f, 17.67f, 6.76f, 0.165f, 26.81f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 4, 0.831f, 6.37f, 6.15f, 0.860f, 67.55f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 4, 0.733f, 2.76f, 0.00f, 0.000f, -1.32f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 4, 0.608f, 3.94f, 1.12f, 0.556f, -5.60f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 2, 0.765f, 1.55f, 1.19f, 0.281f, 4.83f),
            ))),
        DancePreset("YMCA MIXAMO", 15.00f, 1, 0.183f, 10.00f, 1, 0.705f, 0.0400f, 4, 0.488f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.82f, 1.00f, 0.58f, 1.00f, 0.47f, 0.05f, 0.06f,
            JointDriveLayer(arrayOf(
                JointDrive("Spine01", JointDriveLayer.AXIS_X, 1, 0.970f, 2.72f, 1.50f, 0.264f, 0.72f),
                JointDrive("Spine01", JointDriveLayer.AXIS_Z, 2, 0.901f, 1.65f, 0.00f, 0.000f, -0.06f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_X, 4, 0.782f, 4.97f, 0.95f, 0.791f, 9.95f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.145f, 4.42f, 0.00f, 0.000f, -4.29f),
                JointDrive("L_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.811f, 3.72f, 0.00f, 0.000f, 12.59f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_X, 2, 0.380f, 6.49f, 0.69f, 0.711f, 8.20f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Y, 4, 0.514f, 8.44f, 1.91f, 0.951f, -10.32f),
                JointDrive("R_Clavicle", JointDriveLayer.AXIS_Z, 2, 0.797f, 5.46f, 1.76f, 0.643f, 13.42f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_X, 4, 0.182f, 10.97f, 2.19f, 0.862f, 8.38f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Y, 4, 0.363f, 6.06f, 1.70f, 0.724f, 13.60f),
                JointDrive("L_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.873f, 11.25f, 0.00f, 0.000f, -56.16f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_X, 4, 0.781f, 4.21f, 0.00f, 0.000f, 17.89f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Y, 2, 0.900f, 5.89f, 1.20f, 0.324f, 14.80f),
                JointDrive("R_Upperarm", JointDriveLayer.AXIS_Z, 4, 0.732f, 14.61f, 2.02f, 0.644f, -53.84f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_X, 4, 0.148f, 14.87f, 2.88f, 0.439f, 57.73f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Y, 4, 0.099f, 6.23f, 3.56f, 0.720f, -4.71f),
                JointDrive("L_Forearm", JointDriveLayer.AXIS_Z, 4, 0.210f, 5.43f, 1.35f, 0.931f, -6.32f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_X, 2, 0.995f, 10.54f, 4.71f, 0.349f, 57.26f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_Y, 4, 0.777f, 4.49f, 0.95f, 0.415f, -9.25f),
                JointDrive("R_Forearm", JointDriveLayer.AXIS_Z, 4, 0.423f, 10.11f, 1.70f, 0.907f, -7.95f),
                JointDrive("Head", JointDriveLayer.AXIS_X, 2, 0.297f, 3.49f, 0.00f, 0.000f, -3.79f),
                JointDrive("Head", JointDriveLayer.AXIS_Y, 2, 0.848f, 4.93f, 0.00f, 0.000f, 1.42f),
                JointDrive("Head", JointDriveLayer.AXIS_Z, 4, 0.184f, 2.88f, 0.00f, 0.000f, -4.08f),
            ))),
    )

    // ── Tier1-A: hoisted per-frame scratch — avoid closure/listOf alloc in hot loop ──
    // Audio snapshot reused each frame (read-once vs 6+ @Volatile loads per model).
    private val danceSnapshot = AudioReactor.FrameSnapshot()

    // Session base for monotonic-clock → Float seconds conversions (tSec, idle
    // layer). Subtracting a base keeps the Float small — raw uptimeMillis/1000f
    // loses sub-second precision once the device has been up for weeks (D1).
    private var monoClockBaseMs = 0L

    // Tier2-pivot: zero-alloc scratch for rotating a local-frame vec3 by a
    // quaternion into world frame. Single-threaded (render thread) so safe.
    private val pivotRotScratch = FloatArray(3)

    // Laser dot color — matches renderer default. Used when NOT in mark mode.
    private val LASER_DEFAULT_COLOR = floatArrayOf(0f, 0.8f, 1f)
    // Mark reticle color — hot pink. Shows when anatomy-mark mode is armed.
    private val MARK_RETICLE_COLOR = floatArrayOf(1f, 0.2f, 0.6f)

    /** Remap a preset's pivot fraction through the user's marked anatomy.
     *  Default preset vocabulary assumes hip ≈ 0.45 and shoulder ≈ 0.85 of bbox
     *  height. If the user has marked the real hip or shoulder Y on THIS model,
     *  piecewise-linear remap so the preset's intent (e.g. TWERK at 0.95 ≈ head)
     *  lands on the model's actual anatomy. Unmarked → preset value unchanged. */
    private fun remapAnatomyFrac(presetFrac: Float, kneeMark: Float, hipMark: Float, shldrMark: Float): Float {
        val k = if (kneeMark >= 0f) kneeMark else 0.25f
        val h = if (hipMark >= 0f) hipMark else 0.45f
        val s = if (shldrMark >= 0f) shldrMark else 0.85f
        return when {
            presetFrac <= 0.25f -> presetFrac * (k / 0.25f)
            presetFrac <= 0.45f -> k + (presetFrac - 0.25f) * (h - k) / 0.20f
            presetFrac <= 0.85f -> h + (presetFrac - 0.45f) * (s - h) / 0.40f
            else -> s + (presetFrac - 0.85f) * (1f - s) / 0.15f
        }
    }

    /** Rotate (vx, vy, vz) by unit quaternion q = (qx, qy, qz, qw). Writes result
     *  into pivotRotScratch[0..2]. Zero-alloc. Formula: v + 2·q×(q×v + qw·v). */
    private fun rotateVecQ(qx: Float, qy: Float, qz: Float, qw: Float,
                           vx: Float, vy: Float, vz: Float) {
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)
        pivotRotScratch[0] = vx + qw * tx + (qy * tz - qz * ty)
        pivotRotScratch[1] = vy + qw * ty + (qz * tx - qx * tz)
        pivotRotScratch[2] = vz + qw * tz + (qx * ty - qy * tx)
    }

    /** fBm modulation — 3 incommensurable sines composed to ~0.4..1.1. Hoisted
     *  out of the per-frame closure to kill the ~720 alloc/sec GC spike. */
    private fun fbmMod(tSec: Float, seed: Float, phaseOff: Float): Float {
        val s = (kotlin.math.sin((tSec * 0.11f + seed + phaseOff).toDouble()).toFloat()
            + 0.5f * kotlin.math.sin((tSec * 0.19f + seed * 1.7f + phaseOff).toDouble()).toFloat()
            + 0.33f * kotlin.math.sin((tSec * 0.37f + seed * 2.3f + phaseOff).toDouble()).toFloat()) / 1.83f
        return 0.75f + 0.35f * s  // ~0.4..1.1
    }

    /** Normalize a radian angle to [-π, π]. Used by gaze saccade FSM to pick
     *  the shorter angular direction when camera crosses the model's back. */
    private fun normAngle(a: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var r = a
        while (r > Math.PI.toFloat()) r -= twoPi
        while (r < -Math.PI.toFloat()) r += twoPi
        return r
    }

    /** Tier1-F: arm the gaze FSM — capture current dir-to-camera as the
     *  "anchor" so the model remembers its relative orientation to the viewer
     *  at dance-arm time. Saccade FSM reactivates automatically. */
    internal fun armGazeCapture(m: SceneManager.PlacedModel) {
        val dxC = camPosX - m.posX
        val dzC = camPosZ - m.posZ
        m.gazeFaceCamAnchorRad = kotlin.math.atan2(dxC, dzC)
        m.gazeFaceCamHasCapture = true
        m.gazeCurrentBiasRad = 0f
        m.gazeState = 0
        m.gazeNextCheckMs = 0L
    }

    /** Tier1-F: clear gaze capture on dance disarm so the model returns to
     *  its base pose and a future arm re-captures from a fresh heading. */
    internal fun clearGazeCapture(m: SceneManager.PlacedModel) {
        m.gazeFaceCamHasCapture = false
        m.gazeCurrentBiasRad = 0f
        m.gazeState = 0
    }

    /** Apply preset-level body-character defaults for expressive full-body styles. */
    private fun applyPresetBodyCharacter(
        m: SceneManager.PlacedModel,
        p: DancePreset,
        force: Boolean
    ) {
        if (!force && m.characterCustomized) return
        m.stanceArch = p.stanceArch.coerceIn(0f, 1f)
        m.gluteBasePush = p.gluteBasePush.coerceIn(0f, 0.08f)
        m.gluteShakeIntensity = p.gluteShakeIntensity.coerceIn(0f, 1f)
        m.gluteRadius = p.gluteRadius.coerceIn(0.05f, 0.30f)
        m.gluteRate = p.gluteRate.coerceAtLeast(1)
        m.gluteAltStep = p.gluteAltStep
        m.gluteShakerMode = p.gluteShakerMode
        m.gluteLeftEnabled = true
        m.gluteRightEnabled = true
        m.gluteLastSubBeat = 0L
        // (Glute push is OFF on every preset by default — see the DancePreset
        // field comment, user verdict 2026-06-10. Arch keeps its authored
        // values; the on-head verdict cleared it.)
    }

    /** Shuffle a model's dance parameters by cycling through named style presets. */
    internal fun shuffleDance(m: SceneManager.PlacedModel) {
        // Deterministic cycle through the 7 presets so the user can walk the
        // library (TWERK, GRIND, BODY ROLL, ...) by repeated taps. Previously
        // this rolled a uniform random — which meant the user had no way to
        // dial in a specific style they wanted. Name shows on the DANCE button.
        val currentIdx = dancePresets.indexOfFirst { it.name == m.currentPresetName }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % dancePresets.size
        val p = dancePresets[nextIdx]
        // Amp fields — preserved when user has dialled in their own values.
        // This was the "beat panel sometimes resets" bug: IMPROV re-shuffles
        // every N bars, and pre-Tier 3.1 that silently overwrote the user's
        // YAW/PITCH/BOB/PHYSICS dial-ins. Now IMPROV only shifts rhythm.
        if (!m.dancingCustomized) {
            m.danceYawDeg = p.yawDeg.coerceIn(0f, 15f)
            m.dancePitchDeg = p.pitchDeg.coerceIn(0f, 10f)
            m.danceYMeters = p.bobM.coerceIn(0f, 0.04f)
            m.physicsAmount = p.physics
        }
        m.danceYawRate = p.yawRate
        m.danceYawPhase = p.yawPhase
        m.dancePitchRate = p.pitchRate
        m.dancePitchPhase = p.pitchPhase
        m.danceYRate = p.yRate
        m.danceYPhase = p.yPhase
        m.danceEase = p.ease
        // Tier2-pivot: bake biomechanical pivot heights from the preset so
        // hips actually isolate (pivot at shoulders for TWERK, shoulders stay
        // for SWAY via high roll pivot, thighs anchor for SQUAT PULSE, etc.).
        m.pivotEnabled = true
        m.pitchPivotFrac = p.pitchPivot
        m.rollPivotFrac = p.rollPivot
        m.counterRollPivotFrac = p.counterRollPivot
        m.counterRollGain = p.counterRollGain
        // Tier 3 — per-preset motion character. Only applied when the user
        // has NOT customised these fields; otherwise IMPROV's per-bar reshuffle
        // would erase their tweaks. setDancePreset() (explicit style pick)
        // still overwrites them — that's a deliberate user action.
        if (!m.characterCustomized) {
            m.yawSharpness = p.yawSharp; m.yawComplexity = p.yawCmplx
            m.pitchSharpness = p.pitSharp; m.pitchComplexity = p.pitCmplx
            m.bobSharpness = p.bobSharp; m.bobComplexity = p.bobCmplx
        }
        applyPresetBodyCharacter(m, p, force = false)
        // Fresh instance per model (shared immutable drives array): the layer's
        // per-skeleton resolve cache must not thrash between two dancers on the
        // same preset, nor retain a removed model's SkeletonRuntime.
        m.jointLayer = p.jointLayer?.let { JointDriveLayer(it.drives) }
        m.handsOnKnees = p.handsOnKnees
        m.currentPresetName = p.name
        android.util.Log.i(TAG, "Dance preset: ${p.name} (pP=${p.pitchPivot} rP=${p.rollPivot} crG=${p.counterRollGain})")
    }

    /** Set a specific preset by index 0..6 (order matches dancePresets list).
     *  Used by the PRESET cycle button to walk through presets deterministically
     *  instead of rolling random. Explicit user action — clears the character
     *  customisation lock so presets paint the full style (user can then retweak). */
    internal fun setDancePreset(m: SceneManager.PlacedModel, index: Int) {
        val i = ((index % dancePresets.size) + dancePresets.size) % dancePresets.size
        val p = dancePresets[i]
        // Explicit pick → paint full preset (amps + rhythm + character).
        m.danceYawDeg = p.yawDeg.coerceIn(0f, 15f)
        m.danceYawRate = p.yawRate
        m.danceYawPhase = p.yawPhase
        m.dancePitchDeg = p.pitchDeg.coerceIn(0f, 10f)
        m.dancePitchRate = p.pitchRate
        m.dancePitchPhase = p.pitchPhase
        m.danceYMeters = p.bobM.coerceIn(0f, 0.04f)
        m.danceYRate = p.yRate
        m.danceYPhase = p.yPhase
        m.danceEase = p.ease
        m.physicsAmount = p.physics
        m.dancingCustomized = false
        m.pivotEnabled = true
        m.pitchPivotFrac = p.pitchPivot
        m.rollPivotFrac = p.rollPivot
        m.counterRollPivotFrac = p.counterRollPivot
        m.counterRollGain = p.counterRollGain
        // Tier 3 — per-preset motion character. Explicit pick → paint the
        // preset and clear the customised flag so IMPROV can evolve from here.
        m.yawSharpness = p.yawSharp; m.yawComplexity = p.yawCmplx
        m.pitchSharpness = p.pitSharp; m.pitchComplexity = p.pitCmplx
        m.bobSharpness = p.bobSharp; m.bobComplexity = p.bobCmplx
        m.characterCustomized = false
        applyPresetBodyCharacter(m, p, force = true)
        // Fresh instance per model (shared immutable drives array): the layer's
        // per-skeleton resolve cache must not thrash between two dancers on the
        // same preset, nor retain a removed model's SkeletonRuntime.
        m.jointLayer = p.jointLayer?.let { JointDriveLayer(it.drives) }
        m.handsOnKnees = p.handsOnKnees
        m.currentPresetName = p.name
        android.util.Log.i(TAG, "Dance preset (direct): ${p.name}")
    }

    internal val dancePresetCount: Int get() = dancePresets.size
    internal fun dancePresetIndexByName(name: String): Int =
        dancePresets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    internal fun dancePresetNameAt(index: Int): String =
        dancePresets[((index % dancePresets.size) + dancePresets.size) % dancePresets.size].name

    /** Drop any active preview model from the scene. Safe to call at any time. */
    internal fun disposePreviewIfAny() {
        val idx = previewModelIndex
        if (idx in models.indices && models[idx].isPreview) {
            val gpuId = models[idx].gpuModelId
            models.removeAt(idx)
            glesRenderer?.removeModel(gpuId)
            if (selectedModelIndex == idx) selectedModelIndex = if (models.isNotEmpty()) 0 else -1
            else if (selectedModelIndex > idx) selectedModelIndex--
        }
        previewModelIndex = -1
        lastPreviewNanos = 0L
    }

    /** Commit the preview to the scene: clear preview flag, reposition at arm's length,
     *  snap to floor if known. Called from the GLB picker's "Add to scene" button. */
    internal fun commitPreview() {
        val idx = previewModelIndex
        if (idx !in models.indices) { previewModelIndex = -1; return }
        val placed = models[idx]
        if (!placed.isPreview) { previewModelIndex = -1; return }
        val gr = glesRenderer
        // Re-place in front of the user at arm's length, same logic as a normal load.
        val hLen = kotlin.math.sqrt(camFwdX * camFwdX + camFwdZ * camFwdZ).coerceAtLeast(0.01f)
        val hFwdX = camFwdX / hLen
        val hFwdZ = camFwdZ / hLen
        placed.posX = camPosX + hFwdX * 1.5f
        placed.posZ = camPosZ + hFwdZ * 1.5f
        // Upright — clear the auto-rotate spin.
        placed.rotX = 0f; placed.rotY = 0f; placed.rotZ = 0f; placed.rotW = 1f
        // Bump scale back up to the normal "0.75 of meter-scale" default the preview shrank from.
        val gpuModel = gr?.getModel(placed.gpuModelId)
        if (gpuModel != null) {
            val diameter = (gpuModel.boundsRadius * 2f).coerceAtLeast(0.001f)
            val normalScale = when {
                diameter < 0.05f -> 0.5f / diameter
                diameter > 5f -> 1f / diameter
                else -> 0.75f
            }.coerceIn(0.1f, 5f)
            placed.scale = normalScale
            placed.baseScale = normalScale
        }
        placed.posY = 0f
        if (gpuModel != null) {
            if (detectedFloorY != Float.MIN_VALUE) {
                placed.posY += (detectedFloorY - (placed.posY + gpuModel.boundsMinY * placed.scale))
            } else if (gr != null && gr.gridHeight != 0f) {
                placed.posY += (gr.gridHeight - (placed.posY + gpuModel.boundsMinY * placed.scale))
            }
        }
        placed.isPreview = false
        sceneManager.updateModelTransform(placed)
        selectedModelIndex = idx
        previewModelIndex = -1
        lastPreviewNanos = 0L
    }

    internal fun updateModelTransform(model: SceneManager.PlacedModel) {
        sceneManager.updateModelTransform(model)
    }

    internal fun reloadAllModels() {
        sceneManager.reloadAllModels(textureQuality)
    }

    internal fun setFoveationLevelSafe(level: Int): Boolean {
        val clamped = level.coerceIn(0, 3)
        // Quest: changing the foveation level at runtime null-derefs inside Meta's VR
        // driver (SIGSEGV in XrRenderer::setFoveationLevel via xrUpdateSwapchainFB).
        // Skip the native call on the quest flavor — the swapchain keeps its create-time
        // foveation and never crashes. Galaxy XR keeps full dynamic/eye-tracked foveation.
        if (com.ashairfoil.prism.BuildConfig.FLAVOR == "quest") {
            foveationLevel = clamped
            return true
        }
        foveationAvailable = nativeHasFoveation()
        if (!foveationAvailable) {
            foveationLevel = 0
            return false
        }
        foveationLevel = clamped
        nativeSetFoveationLevel(clamped)
        foveationAvailable = nativeHasFoveation()
        if (!foveationAvailable) {
            foveationLevel = 0
            return false
        }
        return true
    }

    /**
     * Drain any pending thermal events from the native layer and step the
     * auto-downgrade state machine. Call at most once per render frame.
     *
     * Stages:
     *  1. disable bloom (the only expensive effect in this activity)
     *  2. drop refresh rate one tier
     *  3. reduce swapchain render scale to 0.75x
     *
     * On recovery (level NORMAL sustained for ~600 frames) we step back up.
     */
    internal fun updateThermalDowngrade() {
        if (!perfSettingsAvailable) return
        if (!com.ashairfoil.prism.settings.SettingsManager.thermalAutoDowngrade) return

        // Consume events — int packing: -1 = no event, else (domain<<16 | from<<8 | to)
        var newLevel = thermalNotificationLevel
        while (true) {
            val ev = nativeConsumeThermalEvent()
            if (ev < 0) break
            val to = ev and 0xFF
            val dom = (ev shr 16) and 0xFF
            if (to > newLevel) newLevel = to
            // Continuous poll of current level ensures recovery logic sees drops too
            val cur = nativeGetThermalLevel(dom)
            if (cur > newLevel) newLevel = cur
        }
        // Also sample current levels even when no event arrived (runtimes
        // sometimes fire once and we rely on polling thereafter)
        val cpu = nativeGetThermalLevel(0)
        val gpu = nativeGetThermalLevel(1)
        val currentMax = maxOf(cpu, gpu)
        if (currentMax > newLevel) newLevel = currentMax

        if (newLevel != thermalNotificationLevel) {
            thermalNotificationLevel = newLevel
            Log.i(TAG, "Thermal level: $newLevel (CPU=$cpu GPU=$gpu)")
        }

        // SAMSUNG TRAP (hardware-verified 2026-06-10): this runtime reports
        // notification level 75 (IMPAIRED) on BOTH domains ~8s after launch
        // with the GPU at ~33% utilization and Android thermal status nominal.
        // Trusting the bare level walked the ladder to stage 3 within two
        // seconds on EVERY session — foveation HIGH + 72Hz + 0.75x render
        // scale (the user-visible "pixelation"). Corroborate with the REAL
        // measured GPU frame time (perf-metrics, polled every 15 frames):
        // only allow stage-ups when the GPU is actually near frame budget.
        // Devices without perf metrics (gpuMs == 0) keep the old behavior.
        val gpuMs = sensorPoller.gpuFrameTimeMs
        val hz = sensorPoller.displayRefreshRate.takeIf { it > 0f } ?: 72f
        val gpuBusy = if (gpuMs > 0f) gpuMs > (1000f / hz) * 0.8f else true
        val warn = newLevel >= 25 && gpuBusy // WARNING_EXT
        val impaired = newLevel >= 75 && gpuBusy // IMPAIRED_EXT

        // Decide target stage. IMPAIRED → stage 3; WARNING → stage 1; NORMAL → 0.
        // We never jump more than one stage per call to avoid visual shock.
        val targetStage = when {
            impaired -> 3
            warn -> 1
            else -> 0
        }

        if (targetStage > thermalStage) {
            // Step up by one
            val next = (thermalStage + 1).coerceAtMost(targetStage)
            applyThermalStage(next)
            thermalCooldownFrames = 600 // ~5s at 120Hz, ~8s at 72Hz
        } else if (targetStage < thermalStage) {
            // Only recover after cooldown
            if (thermalCooldownFrames > 0) {
                thermalCooldownFrames--
            } else {
                val next = (thermalStage - 1).coerceAtLeast(targetStage)
                applyThermalStage(next)
                thermalCooldownFrames = 300
            }
        } else if (thermalCooldownFrames > 0) {
            thermalCooldownFrames--
        }
    }

    private fun applyThermalStage(stage: Int) {
        if (stage == thermalStage) return
        val prev = thermalStage
        thermalStage = stage
        Log.i(TAG, "Thermal downgrade stage: $prev -> $stage (notif=$thermalNotificationLevel)")
        val gr = glesRenderer
        when (stage) {
            0 -> {
                // Recovery — restore everything in reverse order.
                // Restore resolution
                nativeSetRenderScale(1.0f)
                // Restore refresh rate
                nativeRequestDisplayRefreshRate(savedRefreshRatePref.toFloat())
                // Restore effects
                gr?.bloomEnabled = true
            }
            1 -> {
                // Stage 1: disable effects (here: bloom). The old foveation→
                // HIGH escalation is REMOVED: center-fixated binning visibly
                // blocks the dancer — the one thing the user is looking at —
                // the worst possible visual trade for this app, and it fired
                // on the bogus Samsung notif=75 signal every session
                // (hardware-verified 2026-06-10). R7/R8 rebuild the ladder on
                // real GPU-time budgets with eye-tracked FFR as the lever.
                gr?.bloomEnabled = false
            }
            2 -> {
                // Stage 2: drop refresh rate one tier.
                val rates = nativeGetAvailableRefreshRates().sortedArray()
                if (rates.isNotEmpty()) {
                    savedRefreshRatePref =
                        com.ashairfoil.prism.settings.SettingsManager.displayRefreshRate
                    val current = nativeGetDisplayRefreshRate()
                    // Next lower rate from current, or the lowest if current not found.
                    val lower = rates.filter { it < current - 0.5f }.maxOrNull() ?: rates.first()
                    if (lower > 0f && lower < current - 0.5f) {
                        nativeRequestDisplayRefreshRate(lower)
                    }
                }
            }
            3 -> {
                // Stage 3: reduce swapchain resolution.
                nativeSetRenderScale(0.75f)
            }
        }
        onThermalStageChanged?.invoke(stage, thermalNotificationLevel)
    }

    private fun getScenesDir(): File = sceneManager.getScenesDir()

    internal fun refreshSceneList() {
        sceneManager.refreshSceneList()
    }

    internal inline val savedSceneFiles get() = sceneManager.savedSceneFiles

    internal fun saveScene(name: String) {
        sceneManager.saveScene(name, autoAmbient, gridVisible)
    }

    private fun loadScene(sceneFile: File) {
        val result = sceneManager.loadScene(sceneFile)
        if (result != null) {
            autoAmbient = result.autoAmbient
            gridVisible = result.gridVisible
            // D9: scenes persist currentPresetName but NOT the (code-defined)
            // JointDriveLayer — without rehydration a restored Mixamo preset
            // silently dances the old Tier-4 arms (the arm gate sees null and
            // evaluate() never runs) while the UI still shows the Mixamo name.
            // Fresh instance per model (shared immutable drives array) — see
            // the setDancePreset note on per-skeleton cache identity.
            for (m in models) {
                val preset = dancePresets.firstOrNull { it.name == m.currentPresetName }
                m.jointLayer = preset?.jointLayer?.let { JointDriveLayer(it.drives) }
            }
        }
    }

    // ── Spatial anchor plumbing ────────────────────────────────────────
    //
    // Called every frame on the render thread. Submits one-shot resolve on first
    // ready-tick, drains completed create/resolve futures, and surfaces user-visible
    // feedback (toast on first restore, anchor-icon refresh on commit).
    internal fun pollSpatialAnchors() {
        if (!SettingsManager.useSpatialAnchors) return
        if (!spatialAnchors.isSupported()) return

        // One-shot: once the context + persistence contexts are ready, submit a resolve
        // for every persisted UUID we know about. The manager dedupes internally.
        if (!anchorResolveRequested && spatialAnchors.isReady()) {
            anchorResolveRequested = true
            val n = spatialAnchors.submitResolveAll()
            if (n > 0) {
                Log.i(TAG, "Resolving $n persisted spatial anchors on session start")
            }
        }

        // Drain completed create+persist events (usually 0 or 1 per frame).
        while (true) {
            val ev = spatialAnchors.pollCreate() ?: break
            onAnchorCreateComplete(ev)
        }

        // Drain resolved anchor poses (batched on session start).
        val gr = glesRenderer ?: return
        while (true) {
            val rev = spatialAnchors.pollResolve() ?: break
            onAnchorResolveComplete(rev, gr)
        }
    }

    private fun onAnchorCreateComplete(ev: SpatialAnchorManager.CreateEvent) {
        // Find the placed model whose pendingAnchorHandle matches.
        val m = models.firstOrNull { it.pendingAnchorHandle == ev.clientHandle } ?: return
        m.pendingAnchorHandle = -1
        if (!ev.success) {
            Log.w(TAG, "Anchor persist failed for ${m.file.name}")
            return
        }
        m.anchorUuid = ev.uuid
        // Capture the model's world pose at the moment the anchor was created — this
        // becomes the "offset zero" reference. Later movement without re-anchoring will
        // drift relative to the anchor but remains within the scene JSON.
        val record = SpatialAnchorManager.AnchorRecord(
            uuid = ev.uuid,
            modelPath = m.file.absolutePath,
            // Anchor was created at the model's pose, so offset is identity.
            offsetPosX = 0f, offsetPosY = 0f, offsetPosZ = 0f,
            offsetRotX = 0f, offsetRotY = 0f, offsetRotZ = 0f, offsetRotW = 1f,
            scale = m.scale,
            metallic = m.metallic, roughness = m.roughness,
            exposure = m.exposure, contrast = m.contrast, saturation = m.saturation
        )
        spatialAnchors.upsertRecord(record)
        if (menuVisible) uiNeedsRefresh = true
    }

    private fun onAnchorResolveComplete(
        rev: SpatialAnchorManager.ResolveEvent,
        gr: GlesModelRenderer
    ) {
        val rec = rev.record
        if (rec == null) {
            Log.w(TAG, "Resolve event with no matching on-disk record — ignoring")
            return
        }
        if (!rev.valid) {
            // Anchor is gone (user wiped map / new room). Drop the record and the
            // corresponding placed model (if spawned), and notify the user so they
            // understand why the model is missing.
            Log.w(TAG, "Anchor ${rec.modelPath} lost — removing stored entry")
            spatialAnchors.deleteRecord(rec.uuid)
            return
        }
        // Avoid double-spawning when the scene autosave already restored this model.
        val already = models.firstOrNull { m ->
            m.anchorUuid?.contentEquals(rec.uuid) == true ||
                m.file.absolutePath == rec.modelPath
        }
        if (already != null) {
            // Update its pose to the fresh anchor location and ensure UUID is attached.
            already.posX = rev.posX; already.posY = rev.posY; already.posZ = rev.posZ
            already.rotX = rev.rotX; already.rotY = rev.rotY; already.rotZ = rev.rotZ; already.rotW = rev.rotW
            if (already.anchorUuid == null) already.anchorUuid = rec.uuid.copyOf()
            sceneManager.updateModelTransform(already)
            return
        }
        val spawned = sceneManager.spawnFromAnchor(
            rec,
            rev.posX, rev.posY, rev.posZ,
            rev.rotX, rev.rotY, rev.rotZ, rev.rotW
        )
        if (spawned != null) {
            anchorRestoreCount++
            // Defer the toast until we've drained the batch to avoid N toasts in quick succession.
            runOnUiThread {
                if (anchorRestoreCount > 0 && !spatialAnchors.hasPendingResolutions()) {
                    showMessage("Restored $anchorRestoreCount anchored model(s)")
                    anchorRestoreCount = 0
                }
            }
        }
    }

    /**
     * Called by InputHandler when a grip release is detected. Creates a spatial anchor
     * at the model's final world pose and stores its pending-handle so we can stamp the
     * UUID once async persist completes.
     */
    internal fun commitAnchorForGrip(model: SceneManager.PlacedModel) {
        if (!SettingsManager.useSpatialAnchors) return
        if (!spatialAnchors.isReady()) return
        // If the model already has an anchor, drop the old one first so we don't
        // leak a persisted UUID for a pose we no longer care about.
        model.anchorUuid?.let { old ->
            spatialAnchors.deleteRecord(old)
            model.anchorUuid = null
        }
        val handle = spatialAnchors.submitCreate(
            model.posX, model.posY, model.posZ,
            model.rotX, model.rotY, model.rotZ, model.rotW
        )
        if (handle < 0) {
            Log.w(TAG, "submitCreate failed — anchor not committed for ${model.file.name}")
            return
        }
        model.pendingAnchorHandle = handle
        Log.i(TAG, "Submitted anchor create handle=$handle for ${model.file.name}")
    }

    /** Wipe every persisted anchor (runtime + local record). */
    internal fun clearAllAnchors() {
        spatialAnchors.clearAll()
        for (m in models) { m.anchorUuid = null; m.pendingAnchorHandle = -1 }
        uiNeedsRefresh = true
        showMessage("All spatial anchors cleared")
    }

    private fun startRenderLoop() {
        running = true
        renderThread = Thread({
            val frameData = FloatArray(69)

            Log.i(TAG, "Render loop started")
            nativeMakeGLContextCurrent()

            // Init XR compositor quad for stereo-correct menu panel
            if (nativeInitUiQuad(1024, 1280)) {
                Log.i(TAG, "Compositor UI quad initialized (1024x1280)")
            }

            while (running) {
                if (!nativeWaitFrame(frameData)) {
                    try { Thread.sleep(10) } catch (_: Exception) {}
                    continue
                }

                val shouldRender = frameData[0] > 0.5f
                if (shouldRender) {
                    // One-shot: clear the runtime's DEFAULT foveation profile.
                    // The SCALED_BIN swapchain create flag leaves Samsung's
                    // center-fixated binning active even though the app's
                    // level is 0 (hardware-verified: blocky head/arms away
                    // from lens center). setFoveationLevel(0) can't send the
                    // disable (level unchanged), so push it explicitly once
                    // the session renders. Quest excluded: xrUpdateSwapchainFB
                    // SIGSEGVs in Meta's driver (see setFoveationLevelSafe).
                    if (!foveationOffPushed) {
                        foveationOffPushed =
                            if (com.ashairfoil.prism.BuildConfig.FLAVOR == "quest") true
                            else nativeForceFoveationOff()
                    }
                    val leftTexId = frameData[1].toInt()
                    val rightTexId = frameData[2].toInt()
                    System.arraycopy(frameData, 3, leftProjBuf, 0, 16)
                    System.arraycopy(frameData, 19, rightProjBuf, 0, 16)
                    System.arraycopy(frameData, 35, leftViewBuf, 0, 16)
                    System.arraycopy(frameData, 51, rightViewBuf, 0, 16)
                    val leftProj = leftProjBuf
                    val rightProj = rightProjBuf
                    val leftView = leftViewBuf
                    val rightView = rightViewBuf

                    // Extract camera position + forward from left view matrix
                    val v = leftView
                    camPosX = -(v[0]*v[12] + v[1]*v[13] + v[2]*v[14])
                    camPosY = -(v[4]*v[12] + v[5]*v[13] + v[6]*v[14])
                    camPosZ = -(v[8]*v[12] + v[9]*v[13] + v[10]*v[14])
                    camFwdX = -v[2]; camFwdY = -v[6]; camFwdZ = -v[10]
                    val width = frameData[67].toInt()
                    val height = frameData[68].toInt()

                    val gr = glesRenderer
                    if (gr != null) {
                        // Process pending model loads on this thread (has GL context)
                        val pendingFile = pendingModelLoad
                        if (pendingFile != null) {
                            pendingModelLoad = null
                            loadModel(pendingFile, offsetIndex = models.size)
                            uiNeedsRefresh = true
                            requestUiRender()
                        }
                        // Process pending preview loads (inspect a GLB before committing)
                        val pendingPreviewFile = pendingPreviewLoad
                        if (pendingPreviewFile != null) {
                            pendingPreviewLoad = null
                            disposePreviewIfAny()
                            val anchorX = gr.panelX
                            val anchorY = gr.panelY + gr.panelH * 0.5f + 0.18f
                            val anchorZ = gr.panelZ
                            val idx = sceneManager.loadModel(
                                pendingPreviewFile,
                                asPreview = true,
                                previewAnchorX = anchorX,
                                previewAnchorY = anchorY,
                                previewAnchorZ = anchorZ
                            )
                            if (idx >= 0) {
                                previewModelIndex = idx
                                previewRotY = 0f
                                lastPreviewNanos = 0L
                            }
                            uiNeedsRefresh = true
                            requestUiRender()
                        }
                        // Process pending scene loads
                        val pendingScene = pendingSceneLoad
                        if (pendingScene != null) {
                            pendingSceneLoad = null
                            loadScene(pendingScene)
                            uiNeedsRefresh = true
                            requestUiRender()
                        }

                        // Animate preview: slow auto-rotation + re-anchor above panel every frame
                        val pIdx = previewModelIndex
                        if (pIdx in models.indices) {
                            val preview = models[pIdx]
                            if (preview.isPreview) {
                                val nowNs = System.nanoTime()
                                val dt = if (lastPreviewNanos == 0L) 1f / 72f else (nowNs - lastPreviewNanos) / 1_000_000_000f
                                lastPreviewNanos = nowNs
                                previewRotY += dt * 0.6f  // radians/sec — 0.6 rad/s ≈ 34°/s
                                val half = previewRotY * 0.5f
                                preview.rotX = 0f
                                preview.rotY = kotlin.math.sin(half)
                                preview.rotZ = 0f
                                preview.rotW = kotlin.math.cos(half)
                                // Track panel: keep preview floating above wherever the user dragged the panel to.
                                preview.posX = gr.panelX
                                preview.posZ = gr.panelZ
                                val gpuModel = gr.getModel(preview.gpuModelId)
                                val bboxCenterY = gpuModel?.boundsCenterY ?: 0f
                                preview.posY = gr.panelY + gr.panelH * 0.5f + 0.18f - bboxCenterY * preview.scale
                                updateModelTransform(preview)
                            } else {
                                previewModelIndex = -1
                            }
                        } else if (pIdx >= 0) {
                            previewModelIndex = -1
                        }

                        try {
                            // Environment lighting: prefer XR light estimation, fall back to lux sensor
                            if (autoAmbient) {
                                syncLightShKind()
                                val hasXrLight = nativePollLightEstimate(lightEstimateBuffer)
                                if (hasXrLight && lightEstimateBuffer[0] > 0.5f) {
                                    // XR_ANDROID_light_estimation: real room lighting
                                    xrLightEstimateAvailable = true
                                    val ambR = lightEstimateBuffer[1]; val ambG = lightEstimateBuffer[2]; val ambB = lightEstimateBuffer[3]
                                    val ccR = lightEstimateBuffer[4]; val ccG = lightEstimateBuffer[5]; val ccB = lightEstimateBuffer[6]
                                    val dirR = lightEstimateBuffer[7]; val dirG = lightEstimateBuffer[8]; val dirB = lightEstimateBuffer[9]
                                    val dirX = lightEstimateBuffer[10]; val dirY = lightEstimateBuffer[11]; val dirZ = lightEstimateBuffer[12]
                                    val shValid = lightEstimateBuffer[13] > 0.5f

                                    // Temporal smoothing: EMA — very smooth to prevent visible jolts
                                    val aI = if (lightSmoothed) 0.03f else 1f  // intensity/color (slow)
                                    val aD = if (lightSmoothed) 0.12f else 1f  // direction
                                    val aS = if (lightSmoothed) 0.03f else 1f  // SH coefficients (slow)
                                    lightSmoothed = true

                                    // Smooth ambient
                                    val targetAmbInt = ((ambR + ambG + ambB) / 3f * 2f).coerceIn(0.1f, 3f)
                                    smoothAmbientIntensity += (targetAmbInt - smoothAmbientIntensity) * aI
                                    gr.ambientIntensity = smoothAmbientIntensity

                                    tccBuf[0] = ccR.coerceIn(0.5f, 1.5f); tccBuf[1] = ccG.coerceIn(0.5f, 1.5f); tccBuf[2] = ccB.coerceIn(0.5f, 1.5f)
                                    for (i in 0..2) smoothAmbientColor[i] += (tccBuf[i] - smoothAmbientColor[i]) * aI
                                    setRgb(gr.ambientColor, smoothAmbientColor[0], smoothAmbientColor[1], smoothAmbientColor[2])

                                    // Key-light intensity/color from the XR directional estimate
                                    val dirScale = (dirR + dirG + dirB) / 3f
                                    if (dirScale > 0.01f) {
                                        val targetInt = (dirScale * 3f).coerceIn(0.5f, 5f)
                                        smoothLightIntensity += (targetInt - smoothLightIntensity) * aI
                                        gr.lightIntensity = smoothLightIntensity

                                        val maxDir = maxOf(dirR, dirG, dirB).coerceAtLeast(0.01f)
                                        tccBuf[0] = dirR / maxDir; tccBuf[1] = dirG / maxDir; tccBuf[2] = dirB / maxDir
                                        for (i in 0..2) smoothLightColor[i] += (tccBuf[i] - smoothLightColor[i]) * aI
                                        setRgb(gr.lightColor, smoothLightColor[0], smoothLightColor[1], smoothLightColor[2])
                                    }

                                    // Follow Room Light: steer azimuth/elevation toward the
                                    // estimated direction ("direction toward light", same
                                    // convention as lightDir). BOTH gates are required: on
                                    // INVALID the runtime emits defaults dir=(0,1,0) — length
                                    // 1.0! — with intensity 0, so only the intensity gate
                                    // filters that out.
                                    val dirLen = kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
                                    if (followRoomLight && dirScale > 0.3f && dirLen > 0.5f) {
                                        // HARDWARE-VERIFIED 2026-06-10 (Galaxy XR, ceiling-lit room):
                                        // the runtime reports the direction light TRAVELS (consistent
                                        // -Y), NOT "toward the light" as developer.android.com claims.
                                        // Negate to get the toward-light L vector lightDir expects.
                                        smoothLightDir[0] += (-dirX / dirLen - smoothLightDir[0]) * aD
                                        smoothLightDir[1] += (-dirY / dirLen - smoothLightDir[1]) * aD
                                        smoothLightDir[2] += (-dirZ / dirLen - smoothLightDir[2]) * aD
                                        val sLen = kotlin.math.sqrt(
                                            smoothLightDir[0] * smoothLightDir[0] +
                                                smoothLightDir[1] * smoothLightDir[1] +
                                                smoothLightDir[2] * smoothLightDir[2])
                                        if (dirStableFrames < followWarmupFrames) dirStableFrames++
                                        if (dirStableFrames >= followWarmupFrames && sLen > 0.001f) {
                                            // Build the CANDIDATE angles first — elevation clamped,
                                            // azimuth frozen when the smoothed dir is near-vertical
                                            // (atan2 of two near-zero components is pure noise) —
                                            // then apply 5° hysteresis between the candidate and
                                            // the APPLIED direction. Comparing the raw estimate
                                            // instead would leave a permanent >5° gap for
                                            // out-of-clamp lights (overhead/below-horizon) and
                                            // re-trigger writes forever: emitter + shadow crawl.
                                            val horizMag = kotlin.math.sqrt(
                                                smoothLightDir[0] * smoothLightDir[0] +
                                                    smoothLightDir[2] * smoothLightDir[2]) / sLen
                                            var candAz = gr.lightAngleDeg
                                            if (horizMag > 0.05f) {
                                                candAz = Math.toDegrees(kotlin.math.atan2(
                                                    smoothLightDir[0].toDouble(),
                                                    smoothLightDir[2].toDouble())).toFloat()
                                                if (candAz < 0f) candAz += 360f
                                            }
                                            val candEl = Math.toDegrees(kotlin.math.asin(
                                                (smoothLightDir[1] / sLen).toDouble().coerceIn(-1.0, 1.0)))
                                                .toFloat().coerceIn(5f, 85f)
                                            val azRad = Math.toRadians(candAz.toDouble())
                                            val elRad = Math.toRadians(candEl.toDouble())
                                            val cosEl = kotlin.math.cos(elRad).toFloat()
                                            val candX = cosEl * kotlin.math.sin(azRad).toFloat()
                                            val candY = kotlin.math.sin(elRad).toFloat()
                                            val candZ = cosEl * kotlin.math.cos(azRad).toFloat()
                                            val dot = candX * gr.lightDir[0] +
                                                candY * gr.lightDir[1] + candZ * gr.lightDir[2]
                                            if (dot < followHysteresisCos) {
                                                gr.lightAngleDeg = candAz
                                                gr.lightElevDeg = candEl
                                            }
                                        }
                                    } else {
                                        dirStableFrames = 0
                                    }
                                    // Apply angles (manual sliders, or Follow Room Light above)
                                    gr.updateLightDirFromAngles()

                                    // Smooth SH coefficients
                                    xrSHAvailable = shValid
                                    if (shValid) {
                                        shInvalidFrames = 0
                                        System.arraycopy(lightEstimateBuffer, 14, rawSHBuf, 0, 27)
                                        for (i in 0 until 27) smoothSH[i] += (rawSHBuf[i] - smoothSH[i]) * aS
                                        System.arraycopy(smoothSH, 0, gr.shCoefficients, 0, 27)
                                        gr.useSH = true
                                    } else if (gr.useSH && ++shInvalidFrames >= shInvalidLimit) {
                                        // SH stopped arriving — drop to the hemisphere fallback
                                        // instead of rendering frozen room lighting (R2 un-stick)
                                        gr.useSH = false
                                    }

                                    // Debug string for HUD (only allocate when visible)
                                    if (sensorHudVisible) {
                                        xrLightDebugStr = "Amb(%.1f,%.1f,%.1f) Dir(%.1f,%.1f,%.1f) %s%s az=%d el=%d".format(
                                            ambR, ambG, ambB, dirX, dirY, dirZ,
                                            if (shValid) "SH:L2" else "SH:off",
                                            if (followRoomLight) " FOLLOW" else "",
                                            gr.lightAngleDeg.toInt(), gr.lightElevDeg.toInt())
                                    }
                                } else {
                                    // Fallback: lux sensor
                                    xrLightEstimateAvailable = false
                                    dirStableFrames = 0
                                    if (gr.useSH && ++shInvalidFrames >= shInvalidLimit) {
                                        gr.useSH = false
                                        xrSHAvailable = false
                                    }
                                    val lux = roomLux
                                    gr.ambientIntensity = when {
                                        lux <= 0f -> 0.15f
                                        lux < 100f -> 0.15f + (lux / 100f) * 0.55f
                                        lux < 400f -> 0.7f + ((lux - 100f) / 300f) * 0.5f
                                        lux < 1500f -> 1.2f + ((lux - 400f) / 1100f) * 0.6f
                                        else -> (1.8f + kotlin.math.ln(lux / 1500f) * 0.3f).coerceAtMost(2.5f)
                                    }
                                    val warmth = (1f - (lux / 800f).coerceIn(0f, 1f))
                                    setRgb(gr.ambientColor,
                                        1f + warmth * 0.15f,
                                        1f - warmth * 0.02f,
                                        1f - warmth * 0.12f)
                                    setRgb(gr.lightColor,
                                        1f + warmth * 0.08f,
                                        0.95f + warmth * 0.02f,
                                        0.9f - warmth * 0.05f)
                                }
                            } else {
                                dirStableFrames = 0
                                if (gr.useSH) {
                                    // Auto Light toggled off — drop live-SH ambient immediately
                                    // so frozen room lighting can't persist (R2 un-stick)
                                    gr.useSH = false
                                    xrSHAvailable = false
                                }
                            }

                            // ── BeatReactor — pure box-fill driven ──
                            val reactor = audioReactor
                            if (reactor != null && beatReactorEnabled) {
                                reactor.update()
                                if (menuVisible && sensorPollFrame % 3 == 0) uiNeedsRefresh = true

                                // Capture base values once
                                if (!beatBaseStored) {
                                    beatBaseAmbient = gr.ambientIntensity
                                    beatBaseLight = gr.lightIntensity
                                    beatBaseFill = gr.fillLightIntensity
                                    beatBaseShadow = gr.shadowDarkness
                                    beatBaseStored = true
                                }

                                val pct = reactor.boxFillPct
                                val bi = beatIntensity

                                // Concert-light envelope: slow attack, slower release.
                                // Only the LIGHTING uses this — haptic + dance keep the
                                // reactor's snap. Track real wall time so values decay
                                // correctly even when this code path runs at variable dt.
                                run {
                                    val nowNs = System.nanoTime()
                                    val dtSec = if (lightLevelLastNs > 0L)
                                        ((nowNs - lightLevelLastNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                                        else 0.016f
                                    lightLevelLastNs = nowNs
                                    val tau = if (pct > lightLevelSmooth)
                                        (lightAttackMs / 1000f).coerceAtLeast(0.01f)
                                        else (lightReleaseMs / 1000f).coerceAtLeast(0.01f)
                                    val alpha = (1f - kotlin.math.exp(-dtSec / tau)).coerceIn(0f, 1f)
                                    lightLevelSmooth += (pct - lightLevelSmooth) * alpha
                                }
                                val lightPct = lightLevelSmooth
                                val c = reactor.getBeatColor()
                                val bass = reactor.bass
                                // Musical "breath" — 0..1 loudness envelope. Scales beat-driven
                                // consumers so breakdowns quiet down naturally. 1.0 when reactor off.
                                val ml = reactor.musicalLevel

                                // Straight box output: pct * bi. No accumulation, no drift.
                                val intensity = pct * bi

                                val affectsModels = reactor.washScope != AudioReactor.WashScope.ROOM
                                val affectsRoom = reactor.washScope != AudioReactor.WashScope.MODELS

                                if (affectsModels) {
                                    // Ambient: direct beat-driven glow
                                    gr.ambientIntensity = beatBaseAmbient + intensity * 2.5f
                                    setRgb(gr.ambientColor,
                                        c[0] * pct * bi * 1.5f + (1f - pct) * 0.3f,
                                        c[1] * pct * bi * 1.5f + (1f - pct) * 0.3f,
                                        c[2] * pct * bi * 1.5f + (1f - pct) * 0.3f
                                    )

                                    // Main light: direct from box fill
                                    gr.lightIntensity = beatBaseLight + intensity * 4f
                                    setRgb(gr.lightColor,
                                        c[0] * pct + (1f - pct) * 1f,
                                        c[1] * pct + (1f - pct) * 0.95f,
                                        c[2] * pct + (1f - pct) * 0.9f
                                    )

                                    // Fill: contrast light
                                    gr.fillLightIntensity = beatBaseFill + intensity * 2f
                                    setRgb(gr.fillLightColor,
                                        c[2] * pct + (1f - pct) * 0.85f,
                                        c[0] * pct + (1f - pct) * 0.9f,
                                        c[1] * pct + (1f - pct) * 1f
                                    )

                                    // Per-model: exposure + emissive pulse
                                    for (placed in models) {
                                        val gpuModel = gr.getModel(placed.gpuModelId)
                                        if (gpuModel != null) {
                                            gpuModel.exposure = placed.exposure + intensity * 1f
                                            val emGlow = pct * bi * 0.8f
                                            setRgb(gpuModel.emissiveFactor,
                                                c[0] * emGlow,
                                                c[1] * emGlow,
                                                c[2] * emGlow
                                            )
                                            gpuModel.saturation = placed.saturation
                                        }
                                    }

                                    // Shadows soften proportionally — floor at 20% of base so
                                    // the beat wash can't zero or invert shadow darkness when the
                                    // user cranks beatIntensity > 2.5. Wash amount tracks musical
                                    // loudness (ml) AND the slow-smoothed lightPct so we get
                                    // concert-style hang instead of reactor's snappy attack.
                                    gr.shadowDarkness = (beatBaseShadow * (1f - lightPct * 0.4f * bi * ml))
                                        .coerceAtLeast(beatBaseShadow * 0.2f)
                                }

                                // Bloom: direct from box fill, scaled by musical level so quiet
                                // passages don't bloom at full magnitude. Uses the slow lightPct
                                // envelope (lightAttackMs / lightReleaseMs) so kicks ramp in like
                                // a stage blinder warming up, not a snappy digital flash.
                                if (gr.bloomEnabled) {
                                    gr.bloomThreshold = (0.8f - lightPct * bi * 0.3f * ml).coerceAtLeast(0.2f)
                                    gr.bloomIntensity = 0.3f + lightPct * bi * 0.5f * ml
                                }

                                // Room wash: direct from box fill
                                beatWashAlpha = if (affectsRoom) {
                                    (intensity * 0.4f).coerceAtMost(0.85f)
                                } else 0f

                                // Haptic output: drives vibrator from audio signal.
                                // DANCE: vibrator tracks the BPM oscillator (reactor.boxFillPct)
                                // so you feel each beat sync'd to the motion.
                                // SHAKE (legacy A/B): smoothstep of boxFillPct * animResponse.
                                val shakeMdl = models.getOrNull(selectedModelIndex)
                                val shakeAmp = when {
                                    shakeMdl == null || shakeMdl.isPreview -> -1f
                                    shakeMdl.animHasBase && (shakeMdl.danceYawDeg != 0f ||
                                        shakeMdl.dancePitchDeg != 0f || shakeMdl.danceYMeters != 0f) ->
                                        reactor.boxFillPct
                                    shakeMdl.animHasA && shakeMdl.animHasB -> {
                                        val tS = (reactor.boxFillPct * shakeMdl.animResponse).coerceIn(0f, 1f)
                                        tS * tS * (3f - 2f * tS)
                                    }
                                    else -> -1f
                                }
                                if (hapticEnabled && hapticConnected) {
                                    val hm = hapticManager
                                    if (hm != null && shakeAmp >= 0f) {
                                        // Scale by ml so the motor breathes with the track —
                                        // BPM-locked pulses stay on the beat but ease off during
                                        // quiet passages.
                                        val hIntensity = (shakeAmp * ml * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        if (hIntensity != lastHapticIntensity) {
                                            lastHapticIntensity = hIntensity
                                            if (hm.isDualMotor) hm.setDualIntensity(hIntensity, hIntensity)
                                            else hm.setIntensity(hIntensity)
                                            lastHapticMotor1 = hIntensity; lastHapticMotor2 = hIntensity
                                        }
                                    } else if (hm != null && reactor.useVibesEngine) {
                                        // ── Chloe-Vibes engine: dithered output + dual motor phasing ──
                                        val engine = reactor.vibesEngine
                                        val m1 = engine.getDitheredLevel()
                                        val m2 = if (hm.isDualMotor) engine.getDitheredLevel2() else m1
                                        if (m1 != lastHapticMotor1 || m2 != lastHapticMotor2) {
                                            lastHapticMotor1 = m1
                                            lastHapticMotor2 = m2
                                            if (hm.isDualMotor) hm.setDualIntensity(m1, m2)
                                            else hm.setIntensity(m1)
                                        }
                                    } else if (hm != null && hm.isDualMotor && hapticDualMotorSplit) {
                                        // Split mode: box A → motor 1, box B → motor 2. Musical
                                        // level gates so quiet passages don't drive at full amp.
                                        val m1 = (pct * ml * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        val m2 = (reactor.box2FillPct * ml * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        if (m1 != lastHapticMotor1 || m2 != lastHapticMotor2) {
                                            lastHapticMotor1 = m1
                                            lastHapticMotor2 = m2
                                            hm.setDualIntensity(m1, m2)
                                        }
                                    } else if (hm != null) {
                                        // Unified: both motors follow box fill directly, breathed by ml.
                                        val hIntensity = (pct * ml * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        if (hIntensity != lastHapticIntensity) {
                                            lastHapticIntensity = hIntensity
                                            if (hm.isDualMotor) {
                                                hm.setDualIntensity(hIntensity, hIntensity)
                                            } else {
                                                hm.setIntensity(hIntensity)
                                            }
                                            lastHapticMotor1 = hIntensity; lastHapticMotor2 = hIntensity
                                        }
                                    }
                                }

                            } else if (beatBaseStored) {
                                // Reactor turned off — restore base values
                                gr.ambientIntensity = beatBaseAmbient
                                gr.lightIntensity = beatBaseLight
                                gr.fillLightIntensity = beatBaseFill
                                gr.shadowDarkness = beatBaseShadow
                                gr.bloomThreshold = 0.8f
                                gr.bloomIntensity = 0.3f
                                beatBaseStored = false
                                lightLevelSmooth = 0f
                                lightLevelLastNs = 0L
                                // Safety: zero haptic output when reactor disabled
                                if (hapticConnected) {
                                    val hm2 = hapticManager
                                    if (hm2 != null && hm2.isDualMotor) {
                                        hm2.setDualIntensity(0, 0)
                                    } else {
                                        hm2?.setIntensity(0)
                                    }
                                    lastHapticIntensity = -1; lastHapticMotor1 = -1; lastHapticMotor2 = -1
                                }
                                for (placed in models) {
                                    val gpuModel = gr.getModel(placed.gpuModelId)
                                    if (gpuModel != null) {
                                        gpuModel.exposure = placed.exposure
                                        setRgb(gpuModel.emissiveFactor, 1f, 1f, 1f)
                                        gpuModel.saturation = placed.saturation
                                    }
                                }
                            }

                            // A/B loop: start/stop the UI-thread loop handler as needed
                            if (audioPlayer?.hasLoop() == true && audioPlayer?.isPlaying == true) {
                                if (!loopRunning) startLoopHandler()
                            } else if (loopRunning) {
                                stopLoopHandler()
                            }
                            if (audioPlayerMode && sensorPollFrame % 18 == 0) {
                                uiNeedsRefresh = true
                            }
                            // Save name mode: refresh for cursor tracking
                            if (saveNameMode && menuVisible && sensorPollFrame % 3 == 0) {
                                uiNeedsRefresh = true
                            }

                            // Upload UI bitmap to compositor quad layer every visible frame.
                            // We always submit the latest flipped frame to whichever swapchain image is acquired
                            // to avoid historical cursor trails from stale images in the rotating swapchain.
                            if (menuVisible) {
                                // Consume pending flag (flip-copy already done on UI thread)
                                if (uiRenderer.pendingUiBitmap != null) {
                                    uiRenderer.pendingUiBitmap = null
                                }
                                // Upload flip buffer every frame — tryLock so we never stall the render thread
                                val quadTex = nativeAcquireUiImage()
                                if (quadTex > 0) {
                                    if (uiRenderer.bitmapLock.tryLock()) {
                                        try {
                                            val fb = uiRenderer.uiFlipBitmap
                                            if (fb != null) {
                                                android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, quadTex)
                                                android.opengl.GLUtils.texSubImage2D(
                                                    android.opengl.GLES30.GL_TEXTURE_2D,
                                                    0, 0, 0, fb
                                                )
                                                android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0)
                                            }
                                        } finally {
                                            uiRenderer.bitmapLock.unlock()
                                        }
                                    }
                                    // Always release swapchain image even if we skipped the upload
                                    nativeReleaseUiImage()
                                }
                            }

                            // Auto-select the only model if the user hasn't picked one
                            // and hasn't explicitly deselected (Y-button cycle past last).
                            if (models.size != lastSeenModelCount) {
                                lastSeenModelCount = models.size
                                autoSelectSuspended = false   // fresh model count = fresh auto-select
                            }
                            if (models.size == 1 && selectedModelIndex < 0 && !autoSelectSuspended && !models[0].isPreview) {
                                selectedModelIndex = 0
                            }

                            // ── Beat-driven model animation ──
                            // Priority: multi-axis DANCE (absolute-time phase per axis)
                            // > legacy A/B SHAKE > static. Grab wins — if the user is
                            // holding the selected model, anim yields to them.
                            val ar = audioReactor
                            if (ar != null) {
                                // Tier1-B: read once, consult N times. Kills 6+ volatile loads
                                // + phaseAt() currentTimeMillis syscalls per model per frame.
                                ar.snapshot(danceSnapshot)
                                val snap = danceSnapshot
                                val rawFill = snap.boxFillPct
                                // Tier1-H (Gemini #6): sub-perceptual phase slop — she drifts
                                // in/out of the grid like a real dancer sitting in the pocket.
                                // Session-relative seconds: raw nowMs/1000f promoted Long→Float
                                // at full clock magnitude, where Float ULP froze tSec in
                                // multi-second steps (same precision class as the D1 grid bug —
                                // every fBm/slop/jitter consumer was stair-stepping).
                                if (monoClockBaseMs == 0L) monoClockBaseMs = snap.nowMs
                                val tSecFrame = (snap.nowMs - monoClockBaseMs) / 1000f
                                val phaseSlop = 0.05f * kotlin.math.sin((tSecFrame * 0.1f * 2.0 * Math.PI).toFloat())
                                // Metronome sync: find the first armed dancing model and use it
                                // as the master clock. Every other model inherits its yaw/pitch/
                                // bob/roll phase offsets so they all hit the downbeat together.
                                // Per-preset phase variety still lives on the first model, just
                                // not as cross-model desynchronization. Prevents "slightly
                                // different downbeats" across multiple models.
                                var masterModel: SceneManager.PlacedModel? = null
                                for (k in models.indices) {
                                    val mk = models[k]
                                    if (mk.isPreview) continue
                                    if (mk.animHasBase && (mk.danceYawDeg != 0f || mk.dancePitchDeg != 0f || mk.danceYMeters != 0f)) {
                                        masterModel = mk; break
                                    }
                                }

                                for (i in models.indices) {
                                    val m = models[i]
                                    if (m.isPreview) continue
                                    // Note: previously this skipped the selected model while the
                                    // user was grabbing it, to prevent the dance loop from fighting
                                    // the grab's direct posX/Y/Z write. The grab code now mirrors
                                    // the target pose into animBasePose each frame, so the dance
                                    // loop reads the moving base and composes on top — she keeps
                                    // dancing while you relocate her.

                                    // Inherit master beat phases so all dancing models align on
                                    // the same downbeat. Only overrides when a master exists and
                                    // this is NOT the master itself.
                                    if (masterModel != null && m !== masterModel) {
                                        m.danceYawPhase = masterModel.danceYawPhase
                                        m.dancePitchPhase = masterModel.dancePitchPhase
                                        m.danceYPhase = masterModel.danceYPhase
                                        m.danceRollPhase = masterModel.danceRollPhase
                                    }

                                    // ── Tier 3 Feature 4: glute deformation sync ──
                                    // Runs BEFORE the dance check so a non-dancing model with
                                    // positive basePush still gets a static push. On every beat,
                                    // the push ramps up over `reactor.attackMs` then exp-decays
                                    // over `reactor.releaseMs`, so the pop lives inside the same
                                    // attack/release envelope as kicks/haptics/bloom — the whole
                                    // reactor breathes in unison. `musicalLevel` scales each hit
                                    // so breakdowns don't blast the same magnitude as the drop.
                                    //
                                    // Per-side: gluteLeftEnabled / gluteRightEnabled gate A (left)
                                    // and B (right) independently. gluteAltStep fires them on
                                    // alternating beats for a true walking-jiggle pattern.
                                    //
                                    // A positions = left glute (bounds X - sideOff).
                                    // B positions = right glute (bounds X + sideOff).
                                    val gluteGpu = glesRenderer?.getModel(m.gpuModelId)
                                    if (gluteGpu != null) {
                                        if (m.gluteBasePush > 0.0001f) {
                                            // Pulse trigger: subdivision + bar-locked syncopation
                                            // when BPM is locked; legacy beatCounter fallback when
                                            // BPM is free-running.
                                            //
                                            // gluteRate chooses the grid (1 = per beat, 2 = 8ths,
                                            // 4 = 16ths). Syncopation (pulled from the SYNCOPATION
                                            // slider's avg-complexity value) gives each bar a
                                            // deterministic "random" mask — different bars feel
                                            // different, same bar replayed is identical.
                                            val beatPeriodMsG = if (snap.autoBpm && snap.bpm > 0f) 60000f / snap.bpm else 0f
                                            val syncAmt = ((m.yawComplexity + m.pitchComplexity + m.bobComplexity) / 3f).coerceIn(0f, 1f)
                                            // BOOTY SHAKER mode: rapid L-R alternation on last beat
                                            // of each bar, silent on first 3 beats. Overrides the
                                            // regular subdivision/syncopation path when active.
                                            val useShaker = m.gluteShakerMode && beatPeriodMsG > 0f
                                            val useSubdivision = !useShaker && beatPeriodMsG > 0f && (m.gluteRate > 1 || syncAmt > 0.01f)
                                            // D1 grid snap: sub-beat indices divide ELAPSED-SINCE-EPOCH,
                                            // not absolute nowMs. Absolute ms at full clock magnitude
                                            // promoted Long→Float with ~131s ULP (indices froze for
                                            // minutes, then jumped thousands of sub-beats), and even
                                            // without that the grid sat a random offset from the body
                                            // oscillators, which are all epoch-phased.
                                            if (useShaker) {
                                                val shakeRate = 8 // 8 subs/beat = 1/32 note
                                                val subBeatMs = beatPeriodMsG / shakeRate
                                                val subBeatNow = ((snap.nowMs - snap.epochMs) / subBeatMs).toLong()
                                                if (subBeatNow != m.gluteLastSubBeat) {
                                                    if (m.gluteLastSubBeat != 0L) {
                                                        val subsPerBar = (shakeRate * 4).toLong() // 32
                                                        val posInBar = subBeatNow % subsPerBar
                                                        // Burst window = last beat (positions 24..31)
                                                        val inBurst = posInBar >= 24L
                                                        if (inBurst) {
                                                            m.gluteKickLastMs = snap.nowMs
                                                            val leftTurn = (subBeatNow and 1L) == 0L
                                                            m.gluteShakerSideL = leftTurn
                                                            m.gluteShakerSideR = !leftTurn
                                                        } else {
                                                            m.gluteShakerSideL = false
                                                            m.gluteShakerSideR = false
                                                        }
                                                    }
                                                    m.gluteLastSubBeat = subBeatNow
                                                }
                                            } else if (useSubdivision) {
                                                val subBeatMs = beatPeriodMsG / m.gluteRate
                                                val subBeatNow = ((snap.nowMs - snap.epochMs) / subBeatMs).toLong()
                                                if (subBeatNow != m.gluteLastSubBeat) {
                                                    // Skip the very first sub-beat transition so
                                                    // we don't fire spuriously on mode toggle.
                                                    if (m.gluteLastSubBeat != 0L) {
                                                        val isDownBeat = (subBeatNow % m.gluteRate.toLong()) == 0L
                                                        val subsPerBar = (m.gluteRate * 4).toLong()
                                                        val posInBar = (subBeatNow % subsPerBar)
                                                        val barIdx = subBeatNow / subsPerBar
                                                        // Bar-locked hash: golden-ratio constant ⊕ position.
                                                        val hash = ((barIdx * 2654435761L) xor (posInBar * 40503L)) and 0xFFFFL
                                                        val fireSync = syncAmt > 0.01f &&
                                                            hash < (syncAmt * 65535f).toLong()
                                                        if (isDownBeat || fireSync) {
                                                            m.gluteKickLastMs = snap.nowMs
                                                        }
                                                    }
                                                    m.gluteLastSubBeat = subBeatNow
                                                }
                                            } else if (beatPeriodMsG > 0f) {
                                                // BPM is locked (via tap-tempo or auto-BPM) — tick
                                                // the glute kick off the LOCKED CLOCK directly,
                                                // don't wait for audio-detected bass kicks. This
                                                // was the core "shake not firing in time" bug:
                                                // the old fallback used `snap.beatCounter`, which
                                                // only increments when the audio reactor detects
                                                // a bass transient. Tap-tempo locks BPM without
                                                // feeding beatCounter, so the glute kick never
                                                // fired even with BPM locked.
                                                val beatNow = ((snap.nowMs - snap.epochMs) / beatPeriodMsG).toLong()
                                                if (beatNow != m.gluteLastBeatSeen) {
                                                    if (m.gluteLastBeatSeen != 0L) {
                                                        m.gluteKickLastMs = snap.nowMs
                                                    }
                                                    m.gluteLastBeatSeen = beatNow
                                                }
                                            } else {
                                                // No BPM lock AND no subdivision — fall back to
                                                // audio-detected beatCounter (needs real bass
                                                // kicks from playing audio).
                                                if (snap.beatCounter != m.gluteLastBeatSeen && snap.beatCounter > 0L) {
                                                    m.gluteKickLastMs = snap.nowMs
                                                    m.gluteLastBeatSeen = snap.beatCounter
                                                }
                                            }
                                            val elapsedMs = if (m.gluteKickLastMs > 0L) (snap.nowMs - m.gluteKickLastMs).toFloat() else 1_000_000f
                                            // Decay time: prefer beat-period-derived when BPM is
                                            // locked so the pop naturally hangs for most of the
                                            // beat and resets just in time for the next kick. Falls
                                            // back to reactor.releaseMs if no BPM lock.
                                            val beatPeriodMs = if (snap.autoBpm && snap.bpm > 0f) 60000f / snap.bpm else 0f
                                            val atkMs = ar.attackMs.coerceAtLeast(4f)
                                            val relMs = if (beatPeriodMs > 0f) (beatPeriodMs * 0.8f).coerceIn(80f, 900f)
                                                else ar.releaseMs.coerceAtLeast(40f)
                                            val env = when {
                                                elapsedMs < atkMs -> elapsedMs / atkMs
                                                else -> kotlin.math.exp(-(elapsedMs - atkMs) * 3f / relMs)
                                            }.coerceIn(0f, 1f)
                                            val shake = m.gluteShakeIntensity * env * snap.musicalLevel
                                            val pushAmt = m.gluteBasePush * (1f + shake)
                                            m.gluteCurrentPush = pushAmt
                                            // Per-side gating. Shaker mode uses its per-subBeat
                                            // side flags (set above). Non-shaker: alt-step fires L
                                            // on even beats and R on odd; otherwise both fire.
                                            val beatIsEven = (snap.beatCounter % 2L) == 0L
                                            val leftFires = if (m.gluteShakerMode) m.gluteShakerSideL
                                                else m.gluteLeftEnabled && (!m.gluteAltStep || beatIsEven)
                                            val rightFires = if (m.gluteShakerMode) m.gluteShakerSideR
                                                else m.gluteRightEnabled && (!m.gluteAltStep || !beatIsEven)
                                            val pushL = if (leftFires) pushAmt else m.gluteBasePush  // baseline when gated
                                            val pushR = if (rightFires) pushAmt else m.gluteBasePush
                                            m.gluteLeftCurrentPush = pushL
                                            m.gluteRightCurrentPush = pushR
                                            // Glute positions — prefer explicit user marks; fall back to
                                            // bbox-derived estimate.
                                            //
                                            // Axis convention on Tripo rigs (confirmed on-device via the
                                            // floor forward arrow): local +X = her face, -X = her back,
                                            // +Y = up, +Z = her left, -Z = her right. So the butt sits at
                                            // negative X from center (rearOff on X), and L/R glutes split
                                            // along Z (positive = left, negative = right). Previous code
                                            // had these axes swapped — placed glute markers on her hips
                                            // instead of her butt, which is why nothing ever looked right.
                                            if (!m.markedGluteL_x.isNaN()) {
                                                gluteGpu.gluteAPos[0] = m.markedGluteL_x
                                                gluteGpu.gluteAPos[1] = m.markedGluteL_y
                                                gluteGpu.gluteAPos[2] = m.markedGluteL_z
                                            } else {
                                                // Auto-hip height default bumped 0.45 → 0.52 after user
                                                // saw the glutes landing on her hamstrings. Adult-female
                                                // proportion: glutes centered at ~52% of total height.
                                                // MARK HIP still overrides if user sets one explicitly.
                                                val hipFrac = if (m.markedHipFrac >= 0f) m.markedHipFrac else 0.52f
                                                val height = 2f * (gluteGpu.boundsCenterY - gluteGpu.boundsMinY)
                                                val hipY = gluteGpu.boundsMinY + hipFrac * height
                                                val sideOff = 0.18f * gluteGpu.boundsRadius
                                                val rearOff = 0.10f * gluteGpu.boundsRadius
                                                gluteGpu.gluteAPos[0] = gluteGpu.boundsCenterX - rearOff  // -X = behind
                                                gluteGpu.gluteAPos[1] = hipY
                                                gluteGpu.gluteAPos[2] = gluteGpu.boundsCenterZ + sideOff  // +Z = her LEFT
                                            }
                                            if (!m.markedGluteR_x.isNaN()) {
                                                gluteGpu.gluteBPos[0] = m.markedGluteR_x
                                                gluteGpu.gluteBPos[1] = m.markedGluteR_y
                                                gluteGpu.gluteBPos[2] = m.markedGluteR_z
                                            } else {
                                                // Auto-hip height default bumped 0.45 → 0.52 after user
                                                // saw the glutes landing on her hamstrings. Adult-female
                                                // proportion: glutes centered at ~52% of total height.
                                                // MARK HIP still overrides if user sets one explicitly.
                                                val hipFrac = if (m.markedHipFrac >= 0f) m.markedHipFrac else 0.52f
                                                val height = 2f * (gluteGpu.boundsCenterY - gluteGpu.boundsMinY)
                                                val hipY = gluteGpu.boundsMinY + hipFrac * height
                                                val sideOff = 0.18f * gluteGpu.boundsRadius
                                                val rearOff = 0.10f * gluteGpu.boundsRadius
                                                gluteGpu.gluteBPos[0] = gluteGpu.boundsCenterX - rearOff  // -X = behind
                                                gluteGpu.gluteBPos[1] = hipY
                                                gluteGpu.gluteBPos[2] = gluteGpu.boundsCenterZ - sideOff  // -Z = her RIGHT
                                            }
                                            // Glute anchor posing DISABLED — even with palette math
                                            // corrected, the user saw NO glute shake at all (not just
                                            // "disappears during motion", but invisible even at rest).
                                            // Keeping anchor in bind-space for now so the static
                                            // push at least registers visibly. Shader uses
                                            // distance(posed_vertex, bind_anchor) and for a vertex
                                            // near bind cheek location this should hit within the
                                            // 0.15m radius.
                                            // User can tap MARK GLUTE L + MARK GLUTE R at the actual
                                            // cheek locations to override auto-position.
                                            // In shaker mode both radii stay active so the static
                                            // push renders on both cheeks between burst pops.
                                            gluteGpu.gluteARadius = if (m.gluteShakerMode || m.gluteLeftEnabled) m.gluteRadius else 0f
                                            gluteGpu.gluteBRadius = if (m.gluteShakerMode || m.gluteRightEnabled) m.gluteRadius else 0f
                                            gluteGpu.gluteAPush = pushL
                                            gluteGpu.gluteBPush = pushR
                                        } else {
                                            m.gluteCurrentPush = 0f
                                            m.gluteLeftCurrentPush = 0f
                                            m.gluteRightCurrentPush = 0f
                                            gluteGpu.gluteARadius = 0f
                                            gluteGpu.gluteBRadius = 0f
                                            gluteGpu.gluteAPush = 0f
                                            gluteGpu.gluteBPush = 0f
                                        }
                                    }

                                    val dancing = m.animHasBase && (m.danceYawDeg != 0f ||
                                        m.dancePitchDeg != 0f || m.danceYMeters != 0f)
                                    if (dancing) {
                                        // Improv re-shuffle every N bars of locked BPM.
                                        if (m.danceImprov && snap.autoBpm && snap.bpm > 0f) {
                                            val barMs = (60000f / snap.bpm) * 4f
                                            val intervalMs = barMs * m.improvBars.coerceAtLeast(1)
                                            if (m.lastImprovMs == 0L || snap.nowMs - m.lastImprovMs >= intervalMs) {
                                                m.lastImprovMs = snap.nowMs
                                                shuffleDance(m)
                                            }
                                        }

                                        // ── CHOREO: accent / mood / fill ──
                                        // Gated on IMPROV so the user can pick: predictable steady
                                        // dance (IMPROV off) vs auto-musical chaos (IMPROV on).
                                        val hasTempo = snap.autoBpm && snap.bpm > 0f
                                        val choreoActive = hasTempo && m.danceImprov
                                        val accentGain = if (choreoActive) {
                                            1f + 0.22f * kotlin.math.cos(2.0 * Math.PI * snap.phase1bar).toFloat().coerceAtLeast(0f)
                                        } else 1f
                                        val moodGain = if (choreoActive) {
                                            0.8f + 0.45f * (0.5f - 0.5f * kotlin.math.cos(2.0 * Math.PI * snap.phase16bar).toFloat())
                                        } else 1f
                                        // D1(B) anticipation basis — computed BEFORE fillActive because
                                        // the fill window itself must shift by the lead: a rate flip is
                                        // only pop-free where the LEADED lookups cross zero. Lead is
                                        // zero without a tempo lock (phases are frozen at 0 there; a
                                        // constant offset would just bend the static pose).
                                        val leadMs = if (hasTempo) 60f + 60f * m.physicsAmount else 0f
                                        val quarterMsLead = if (hasTempo) 60000f / snap.bpm else 500f
                                        // Fill window shifted earlier by leadMs: at elapsed = N·bar −
                                        // leadMs every leaded lookup ≡ 0 REGARDLESS of rate, so flipping
                                        // rates there stays continuous (the raw bar boundary had that
                                        // property pre-lead; the lead moved it — review finding).
                                        val fillActive = choreoActive &&
                                            (((snap.phase4bar + leadMs / (quarterMsLead * 16f)) % 1f + 1f) % 1f) > 0.75f
                                        // Softened: during fill, drop amps to 40% (was 100%) and use
                                        // 1/8 (was 1/16) on yaw+pitch. Full 1/16 on three axes at once
                                        // read as spaz; 1/8 at reduced amp reads as a tasteful fill.
                                        val fillAmp = if (fillActive) 0.4f else 1f
                                        // Musical breathing: multiply amp by track loudness so the
                                        // dance visibly quiets during breakdowns and explodes on
                                        // drops. Band gates (bass/mid) stay intact — this layers
                                        // on top as a global magnitude shaper.
                                        val ampGain = accentGain * moodGain * fillAmp * snap.musicalLevel
                                        val yawRate = if (fillActive) 8 else m.danceYawRate
                                        val pitchRate = if (fillActive) 8 else m.dancePitchRate
                                        val yRate = if (fillActive) 8 else m.danceYRate

                                        // ── D1(B): anticipation lead, all three axes ──
                                        // Trained dancers lead the beat by ~45-90ms: preparatory motion
                                        // starts early so the kinematic ARRIVAL (deceleration / lowest
                                        // CoM) lands on the audible beat — which sits EARLIER than grid
                                        // phase 0 by the Visualizer-capture + render latency. A POSITIVE
                                        // phase offset moves the waveform earlier in time by the code's
                                        // own shear semantics (bob 0 = "hips lead", yaw -0.09 = lag).
                                        // The previous yaw-only -(45ms × physics) lead was sign-inverted
                                        // — it DELAYED arrival (audit D1 trap 2). VERIFY DIRECTION ON
                                        // DEVICE: if she reads early, flip the sign back with evidence.
                                        // The lead is constant in TIME, so each lookup needs it as a
                                        // fraction of ITS OWN rate's cycle — leadPhBobBase serves the
                                        // pelvis-thrust and squash lookups, which deliberately stay at
                                        // the un-fill-adjusted danceYRate (review finding: reusing the
                                        // fill-rate lead there inflated it up to 8x during fills).
                                        val leadPhYaw = leadMs / (quarterMsLead * 4f / yawRate.coerceAtLeast(1))
                                        val leadPhPitch = leadMs / (quarterMsLead * 4f / pitchRate.coerceAtLeast(1))
                                        val leadPhBob = leadMs / (quarterMsLead * 4f / yRate.coerceAtLeast(1))
                                        val leadPhBobBase = leadMs / (quarterMsLead * 4f / m.danceYRate.coerceAtLeast(1))

                                        val b = m.animBasePose
                                        var px = b[0]; var py = b[1]; var pz = b[2]
                                        var qx = b[3]; var qy = b[4]; var qz = b[5]; var qw = b[6]

                                        // Tier1-A: fbmMod + easeFor hoisted to class level — no per-frame
                                        // closure or listOf allocation (was ~720 alloc/sec at 4 models × 120Hz).
                                        val tSec = (snap.nowMs - monoClockBaseMs) / 1000f
                                        val seed = if (m.fbmSeed == 0f) { m.fbmSeed = (m.gpuModelId * 0.37f + 1.13f); m.fbmSeed } else m.fbmSeed
                                        // Quarter-scale intensity internally so the UI's "1.0x" feels
                                        // like the previous "0.25x" (which the user settled on as correct).
                                        // Labels stay familiar; the gain just shifted down two notches.
                                        // Tier 3 — user felt 5x was too timid ("1x should be what
                                        // 2x is"). Doubled the internal multiplier so every labeled
                                        // step hits roughly twice as hard. Label 1.0x now ≈ old 2.0x,
                                        // and the new 10.0x slot (see InputHandler cycle) gives real
                                        // blowout headroom.
                                        val effInt = m.danceIntensity * 0.5f

                                        // ── Tier 4: ARCH stance + pelvic thrust ──
                                        // Skinned models only. Two jobs:
                                        //   1. Apply stance baseline offsets (pelvic tilt, spine counter,
                                        //      arm drop) scaled by stanceArch.
                                        //   2. Oscillate pelvis X rotation with the BOB phase so her hips
                                        //      THRUST even if the user hasn't cranked BOB translation amp.
                                        //      That's the actual "she's dancing" read — ARCH without this
                                        //      just freezes her in the stance like a doll.
                                        val stanceGpu = glesRenderer?.getModel(m.gpuModelId)
                                        val stanceSkel = stanceGpu?.skeleton
                                        // D1(B): the +kSkew weight-arrival recenter assumes the squat's
                                        // root drop makes phase01's peak the body's LOWEST point. That
                                        // only holds when the squat path actually drives (skinned rig
                                        // with L/R_Calf + Root joints found — cached lazily, so the
                                        // first bob frame runs unrecentered once). Otherwise the
                                        // strictly-upward py bump makes phase01's peak the HIGHEST
                                        // point and recentering would invert weight arrival (review
                                        // finding); without the recenter the bump's own minimum
                                        // already sits at lookup phase 0, which is correct as-is.
                                        val squatDrives = stanceGpu?.isSkinned == true &&
                                            m.kneeLJointIdx >= 0 && m.kneeRJointIdx >= 0 && m.rootJointIdx >= 0
                                        val bobRecenter = if (hasTempo && squatDrives) 0.5f - 0.2f * m.physicsAmount else 0f
                                        if (stanceGpu?.isSkinned == true && stanceSkel != null) {
                                            if (m.pelvisJointIdx == Int.MIN_VALUE) {
                                                m.pelvisJointIdx = stanceSkel.indexOf("Pelvis")
                                                m.waistJointIdx = stanceSkel.indexOf("Waist")
                                                // Fallback probe — not every rig literally names these
                                                // "Pelvis" and "Waist". Try common aliases before giving
                                                // up. A -1 here silently disables the whole Tier 4
                                                // stance/yaw pipeline, which was the user-visible symptom
                                                // "I don't see any rotation around that axis".
                                                if (m.pelvisJointIdx < 0) {
                                                    for (alt in arrayOf("Hips", "Hip", "pelvis", "hips", "mixamorig:Hips")) {
                                                        val i = stanceSkel.indexOf(alt); if (i >= 0) { m.pelvisJointIdx = i; break }
                                                    }
                                                }
                                                if (m.waistJointIdx < 0) {
                                                    for (alt in arrayOf("Spine", "Spine0", "LowerSpine", "waist", "mixamorig:Spine")) {
                                                        val i = stanceSkel.indexOf(alt); if (i >= 0) { m.waistJointIdx = i; break }
                                                    }
                                                }
                                                android.util.Log.i(TAG, "Stance joints cached: pelvis=${m.pelvisJointIdx} waist=${m.waistJointIdx} (skel has ${stanceSkel.jointCount} joints)")
                                            }
                                            val arch = m.stanceArch.coerceIn(0f, 1f)

                                            // ── Why arch lives at the WAIST, not the pelvis ──
                                            // Rotating the pelvis joint around X propagates through the
                                            // hierarchy: thigh origins ride on the pelvis transform, so
                                            // even with a thigh counter-rotation cancelling ORIENTATION,
                                            // the thigh origin translates in an arc (±dz · sin θ for
                                            // the L/R offset — one foot drops below floor, the other
                                            // lifts above). User kept seeing "HEELS floating above the
                                            // floor" when arch went up — that's this exact effect.
                                            // Solution: leave the pelvis at rest orientation (only
                                            // sway Z + thrust X during bob, both small). Put the full
                                            // lordosis arch at the WAIST where it only moves the upper
                                            // body — legs stay planted, feet stay on the shadow.
                                            //
                                            // Waist sign: real lordosis = lumbar arches BACKWARD (chest
                                            // goes back relative to pelvis), not forward tilt. So the
                                            // waist uses NEGATIVE X here — opposite sign to what a
                                            // "same-direction pelvis+waist" approach would use. User's
                                            // earlier note "the arch flattened her back, not lordosis"
                                            // was the +20° (same-direction) build; flipping the sign
                                            // gives the butt-out / chest-back silhouette.

                                            // ── BOOTY-MASTER HIP GYRATION (driven by YAW slider) ──
                                            // YAW drives a true hip gyration: pelvis Z (sway) + pelvis X
                                            // (thrust) phase-offset 90° so the pelvis traces a big circle
                                            // in the XZ plane. Extras on top:
                                            //   · Bass-triggered thrust POP: transient X spike on each
                                            //     detected bass hit — that sharp "boop" on top of the
                                            //     smooth circle is what separates "generic gyration" from
                                            //     booty-master read.
                                            //   · Figure-8 harmonic: when yawComplexity is cranked, a
                                            //     2×-rate perturbation folds the circle into a figure-8
                                            //     trace. Smooth blend so it only kicks in when the user
                                            //     asks for it.
                                            //
                                            // Phase layout (at yawRate=1, one cycle per beat):
                                            //   ph=0.00 → sway=0, thrust=+max   (hips forward)
                                            //   ph=0.25 → sway=+max, thrust=0   (hips left)
                                            //   ph=0.50 → sway=0, thrust=-max   (hips back)
                                            //   ph=0.75 → sway=-max, thrust=0   (hips right)
                                            //
                                            // Amplitudes are deliberately big (18° sway, 14° thrust) —
                                            // user asked for "booty in big circles". Thigh-origin drift
                                            // at 14° thrust = ±dz·sin(14°) ≈ ±2.4cm asymmetric foot
                                            // shift, which is visible but acceptable given the payoff.
                                            val bobActive = m.danceYMeters > 0.001f
                                            val yawDrivenStance = m.danceYawDeg > 0.001f
                                            val gyrateScale = (m.danceYawDeg / 15f).coerceIn(0f, 1f)
                                            val yawPh = if (yawDrivenStance)
                                                ((ar.phaseAtSnap(snap, yawRate) + m.danceYawPhase + phaseSlop) % 1f + 1f) % 1f
                                            else 0f

                                            // Figure-8 blend: yawComplexity drives how much the 2×-rate
                                            // harmonic perturbs the pure circle. At 0 = clean circle, at
                                            // 1 = full figure-8 trace. Cranked complexity is exactly the
                                            // "she's putting flair on it" knob.
                                            val fig8Blend = m.yawComplexity.coerceIn(0f, 1f)

                                            // ── Power-biased "pop wave" ──
                                            // Replace raw sin/cos with a sign-preserving power curve so
                                            // the pelvis SNAPS to the extreme of each cycle then HANGS
                                            // there before transitioning. Exponent < 1 accelerates the
                                            // approach to ±1 — at 0.55 the wave spends ~30% of the cycle
                                            // within 10% of the peak. That "hang-time" at the extreme is
                                            // what turns math-ticking gyration into a sultry groove.
                                            // At exponent 1.0 this degenerates to the original sine.
                                            val hangExp = 0.55f
                                            fun popWave(x: Float): Float {
                                                val a = kotlin.math.abs(x).toDouble()
                                                val p = Math.pow(a, hangExp.toDouble()).toFloat()
                                                return if (x >= 0f) p else -p
                                            }

                                            // Hip sway: pelvis Z, pop-shaped fundamental + sine2 for fig-8
                                            val hipSwayDeg = if (yawDrivenStance) {
                                                val raw1 = kotlin.math.sin(2f * Math.PI.toFloat() * yawPh)
                                                val pop1 = popWave(raw1)
                                                val sine2 = kotlin.math.sin(4f * Math.PI.toFloat() * yawPh)
                                                val swayShape = pop1 * (1f - 0.4f * fig8Blend) + sine2 * 0.4f * fig8Blend
                                                swayShape * 12f * gyrateScale * snap.musicalLevel
                                            } else 0f

                                            // Hip thrust: pelvis X, pop-shaped cosine (90° ahead). The
                                            // hang-time at the extremes is what reads as "she pushes
                                            // forward... and sits there... and rolls back". 6° peak keeps
                                            // asymmetric L/R thigh drift at ±1cm (foot-safe).
                                            val yawThrustDeg = if (yawDrivenStance) {
                                                val rawCos = kotlin.math.cos(2f * Math.PI.toFloat() * yawPh)
                                                val popCos = popWave(rawCos)
                                                popCos * 6f * gyrateScale * snap.musicalLevel
                                            } else 0f
                                            val bobThrustDeg = if (bobActive) {
                                                val yRateS = m.danceYRate.coerceAtLeast(1)
                                                val kSkewS = 0.5f - 0.2f * m.physicsAmount
                                                // Same recenter + lead family as the main bob lookup (see
                                                // the D1(B) notes) so the hip-thrust peak stays in sync
                                                // with the weight drop landing on the beat. This lookup
                                                // stays at the base danceYRate, so it takes the BASE-rate
                                                // lead, not the fill-adjusted one.
                                                val pelvisPh = ((ar.phaseAtSnap(snap, yRateS) + m.danceYPhase + bobRecenter + leadPhBobBase + phaseSlop) % 1f + 1f) % 1f
                                                val p01 = if (pelvisPh < kSkewS) pelvisPh / kSkewS else 1f - (pelvisPh - kSkewS) / (1f - kSkewS)
                                                p01 * 4f * snap.musicalLevel
                                            } else 0f
                                            val pelvisThrustDeg = yawThrustDeg + bobThrustDeg

                                            // Pelvis Z sway KILLED. Previous "small 40% carryover for
                                            // visible hip rotation" contributed ~7mm/foot at the low end
                                            // of yaw amp and up to ~2cm/foot at full yaw — the latter is
                                            // exactly the "feet sway across the floor like a pendulum"
                                            // read the user flagged. Waist keeps full sway so upper body
                                            // still reads dramatic; mesh skinning-weights at the
                                            // pelvis/waist boundary give enough visual hip motion from
                                            // the waist rotation alone.
                                            val pelvisSwayZ = 0f
                                            if (m.pelvisJointIdx >= 0) {
                                                stanceSkel.setJointEulerXYZ(m.pelvisJointIdx, 0f, 0f, pelvisSwayZ)
                                            }
                                            // Waist X: arch sign reverted to POSITIVE. User's two data
                                            // points bracketed the answer:
                                            //   +20° → "flat back, not lordosis"  (too much backward arch,
                                            //          overshoots and straightens the mesh's natural curve)
                                            //   -25° → "forward lean"              (wrong direction entirely)
                                            // So +X IS the backward-arch direction for this rig's waist
                                            // bind frame — just needed a smaller magnitude. +12° should
                                            // add a subtle lumbar curve without flattening. Tuning knob:
                                            // bump toward +18° for more pronounced arch if user wants,
                                            // drop toward +6° for subtler read.
                                            // Waist carries EVERYTHING now:
                                            //   X = arch + thrust  (forward-back pump)
                                            //   Z = full sway      (lateral sway, no foot pendulum)
                                            // Pelvis is at rest. Waist has no leg children, so waist
                                            // rotations don't propagate to feet — arch/thrust/sway
                                            // all land cleanly on the upper body. Her hips visually
                                            // sway from mesh skinning-weight blending at the
                                            // pelvis/waist boundary, but the skeleton itself is
                                            // foot-planted.
                                            val waistArchX = 12f * arch + pelvisThrustDeg
                                            val waistSwayZ = hipSwayDeg
                                            if (m.waistJointIdx >= 0) {
                                                stanceSkel.setJointEulerXYZ(m.waistJointIdx, waistArchX, 0f, waistSwayZ)
                                            }

                                            // ── Thigh counter-rotation ──
                                            // Pelvis rotation still propagates (thrust X + sway Z), so
                                            // counter at the thighs to keep legs world-vertical. With
                                            // arch removed from pelvis, magnitudes are tiny (thrust
                                            // peaks at 6° · arch · level, sway at 12° · level) — but we
                                            // still need the counter or feet wiggle on beat.
                                            // Thigh Z counter MATCHES pelvis Z sway (pelvisSwayZ)
                                            // so legs stay pointing same direction in world while
                                            // pelvis rotates. Position drift is unavoidable (rigid
                                            // hierarchy) but at 5° pelvis Z it's ~7mm per foot,
                                            // below the visual threshold.
                                            val thighCounterDegX = 0f
                                            val thighCounterDegZ = -pelvisSwayZ
                                            if (m.thighLJointIdx == Int.MIN_VALUE) {
                                                m.thighLJointIdx = stanceSkel.indexOf("L_Thigh")
                                                m.thighRJointIdx = stanceSkel.indexOf("R_Thigh")
                                            }
                                            if (m.thighLJointIdx >= 0) {
                                                stanceSkel.setJointEulerXYZ(m.thighLJointIdx, thighCounterDegX, 0f, thighCounterDegZ)
                                            }
                                            if (m.thighRJointIdx >= 0) {
                                                stanceSkel.setJointEulerXYZ(m.thighRJointIdx, thighCounterDegX, 0f, thighCounterDegZ)
                                            }
                                            // ── ARM REWRITE — drive UPPER ARM, not clavicle ──
                                            // Clavicle is the sternum→shoulder-socket bone. Rotating
                                            // it moves the shoulder socket sideways into the ribcage
                                            // (this is why 40° AND 75° BOTH clipped through the
                                            // torso — the socket was being jammed into the chest,
                                            // not the arm being swung down). Actual arm abduction/
                                            // adduction happens at the glenohumeral joint, which
                                            // in Tripo is the "L_Upperarm" (lowercase 'a' — case-
                                            // insensitive lookup catches both spellings).
                                            //
                                            // Axis ground truth (from direct GLB inspection of 4
                                            // Tripo rigs): Upperarm local +X points down in model
                                            // space with a slight forward tilt. A rotation around
                                            // local X sweeps the arm from out-to-the-side down
                                            // toward the body. Left side uses NEGATIVE X to drop
                                            // (−60° brings arm to side); right side uses POSITIVE
                                            // (its +X is mirrored). Clavicle stays at bind.
                                            //
                                            // Forearm local +Z points sideways in model space
                                            // (across the body). Rotation around Z flexes elbow
                                            // forward-and-inward. Magnitudes ~45° give a natural
                                            // rest where hands land at mid-thigh.
                                            if (m.upperArmLJointIdx == Int.MIN_VALUE) {
                                                m.upperArmLJointIdx = stanceSkel.indexOf("L_Upperarm")
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("L_UpperArm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("L_Arm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("L_Shoulder") }
                                                m.upperArmRJointIdx = stanceSkel.indexOf("R_Upperarm")
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("R_UpperArm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("R_Arm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("R_Shoulder") }
                                                m.forearmLJointIdx = stanceSkel.indexOf("L_Forearm")
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("L_LowerArm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("L_Elbow") }
                                                m.forearmRJointIdx = stanceSkel.indexOf("R_Forearm")
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("R_LowerArm") }
                                                    .let { if (it >= 0) it else stanceSkel.indexOf("R_Elbow") }
                                                android.util.Log.i(TAG, "Arm joints: upperarm=${m.upperArmLJointIdx}/${m.upperArmRJointIdx} " +
                                                    "forearm=${m.forearmLJointIdx}/${m.forearmRJointIdx}")
                                            }
                                            // Upper arm drop + SEXY ARM CHOREOGRAPHY.
                                            //
                                            // Previous impl was symmetric-mirrored Z abduction: both
                                            // arms swept ±armSwayAmp in unison — classic "windshield
                                            // wiper" read. Rewritten to decouple L/R in three ways:
                                            //   1. Z abduction sway uses 180° OFFSET phase between
                                            //      arms, so one is at peak-up while the other is at
                                            //      peak-down (natural dance contrapposto, not mirror).
                                            //   2. Y axis adds a half-rate forward/back "reach"
                                            //      oscillation (shoulder anteflexion) with another
                                            //      180° offset — L reaches forward while R retracts.
                                            //   3. The reach amplitude is driven by a slow fBm signal
                                            //      per arm so the timing drifts over bars — prevents
                                            //      strictly metronomic pulsing.
                                            val armDropZ = 60f
                                            // Arm sway: lock to a max rate of 4 (1 cycle/beat) regardless
                                            // of the preset's yawRate so fast-yaw presets (rate 8+) don't
                                            // drive the arms at double speed. The arm sway phase still
                                            // tracks the hip cycle (15% lag, L/R inverted) but at a
                                            // beat-friendly cadence.
                                            val armRate = kotlin.math.min(yawRate, 4)
                                            val armBasePh = ar.phaseAtSnap(snap, armRate) + m.danceYawPhase
                                            val armLagPhL = if (yawDrivenStance) ((armBasePh - 0.15f) % 1f + 1f) % 1f else 0f
                                            val armLagPhR = if (yawDrivenStance) ((armBasePh - 0.15f + 0.5f) % 1f + 1f) % 1f else 0f
                                            val armLagSineL = kotlin.math.sin(2f * Math.PI.toFloat() * armLagPhL)
                                            val armLagSineR = kotlin.math.sin(2f * Math.PI.toFloat() * armLagPhR)
                                            // Magnitude halved 35° → 18° per user "arms spazzy at yaw".
                                            val armSwayAmp = if (yawDrivenStance) 18f * gyrateScale * snap.musicalLevel else 0f
                                            // Forward/back reach on upperarm local Y, per arm with
                                            // opposite phase so one reaches while the other pulls.
                                            val reachPh = ar.phaseAtSnap(snap, 2)  // 1/2 rate — slower than abduction sway
                                            val reachGainL = (0.6f + 0.4f * fbmMod(tSec, seed, 11.3f))  // 0.2..1.0 range, drifts over bars
                                            val reachGainR = (0.6f + 0.4f * fbmMod(tSec, seed, 13.7f))
                                            val reachDegL = kotlin.math.sin(2f * Math.PI.toFloat() * reachPh) * 22f * effInt * snap.musicalLevel * reachGainL
                                            val reachDegR = kotlin.math.sin(2f * Math.PI.toFloat() * reachPh + Math.PI.toFloat()) * 22f * effInt * snap.musicalLevel * reachGainR
                                            // D9 Phase A: Tier 4 arm writes are SKIPPED when a
                                            // JointDriveLayer OWNS the arms on this rig — the layer's
                                            // Mixamo upperarm drives (restAngleDeg DC + oscillation)
                                            // own them and re-base to bind in evaluate() each frame.
                                            // Without this gate the drop/sway baseline and the layer
                                            // double-drive ("spazzing arms"). ownsArms() requires the
                                            // exact Tripo arm bones: on alias-named rigs the layer
                                            // resolves nothing, so Tier 4 MUST keep writing (its SETs
                                            // are the only per-frame re-base — gating them off there
                                            // would let the groove arm composes accumulate without
                                            // bound). NOTE: this `if` IS the gate the old comment
                                            // claimed existed — comment-only until the layer was
                                            // actually enabled.
                                            val armLayer = m.jointLayer
                                            if (armLayer == null || !armLayer.ownsArms(stanceSkel)) {
                                            if (m.handsOnKnees) {
                                                // Hands-on-knees pose override: shoulders forward ~55°
                                                // (Y-axis anteflexion so arms reach forward) with a
                                                // mild drop (Z ~35°) so arms angle down toward the
                                                // bent knees during a squat. Both arms get the same
                                                // Y sign (forward for both); Z is still mirrored
                                                // L-negative / R-positive.
                                                if (m.upperArmLJointIdx >= 0)
                                                    stanceSkel.setJointEulerXYZ(m.upperArmLJointIdx, 0f, 55f, -35f)
                                                if (m.upperArmRJointIdx >= 0)
                                                    stanceSkel.setJointEulerXYZ(m.upperArmRJointIdx, 0f, 55f, 35f)
                                            } else {
                                                if (m.upperArmLJointIdx >= 0)
                                                    stanceSkel.setJointEulerXYZ(m.upperArmLJointIdx, 0f, reachDegL, -(armDropZ - armSwayAmp * armLagSineL))
                                                if (m.upperArmRJointIdx >= 0)
                                                    stanceSkel.setJointEulerXYZ(m.upperArmRJointIdx, 0f, reachDegR, armDropZ + armSwayAmp * armLagSineR)
                                            }
                                            // ── ELBOW BEND — BEAT-SYNCED, ALTERNATING L/R ──
                                            // Old impl was a slow fBm drift in [4°, 16°] — basically
                                            // straight all the time, drowned out by JointDriveLayer
                                            // overwrites on top. New design:
                                            //   · Beat-rate pulse: 1 cycle/beat (rate 4) so elbows
                                            //     punctuate with the music.
                                            //   · Alternating L/R via 0.5 phase offset → one elbow at
                                            //     peak bend while the other is at peak straight.
                                            //   · Resting bend 25° + pulse 50° = range 25..75° (peak
                                            //     bend reads as "hand near shoulder/face"; resting
                                            //     bend reads as "elbow casually bent, never locked").
                                            //   · (sin+1)/2 envelope keeps bend ALWAYS positive (elbows
                                            //     don't hyperextend) and oscillates at the SAME rate as
                                            //     the underlying sine. Earlier code used |sin| which
                                            //     doubled the visible rate (each beat produced TWO peaks
                                            //     of bend) — user read that as "arms at 2x time".
                                            //
                                            // Axis: forearm LOCAL X (the original ARM REWRITE comment
                                            // about Z was empirically wrong — Z gull-winged her).
                                            //
                                            // Inside the jointLayer==null gate: the Mixamo forearm
                                            // drives (with restAngleDeg) own the bend for layer presets.
                                            val elbowPhL = ar.phaseAtSnap(snap, 4)
                                            val elbowPhR = ((elbowPhL + 0.5f) % 1f + 1f) % 1f
                                            val elbowBaseDeg = 25f
                                            val elbowPulseDeg = 50f * effInt * snap.musicalLevel
                                            // (sin + 1) / 2 → 0..1 oscillation at the same rate as sin
                                            // (no |sin| frequency doubling).
                                            val lFlex = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * elbowPhL)
                                            val rFlex = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * elbowPhR)
                                            val lElbowDeg = elbowBaseDeg + elbowPulseDeg * lFlex
                                            val rElbowDeg = elbowBaseDeg + elbowPulseDeg * rFlex
                                            if (m.handsOnKnees) {
                                                // Fixed deep elbow flex so hands land at the knees.
                                                // Ignore the beat-pulse bend — we want a stable pose.
                                                if (m.forearmLJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmLJointIdx, 95f)
                                                if (m.forearmRJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmRJointIdx, 95f)
                                            } else {
                                                if (m.forearmLJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmLJointIdx, lElbowDeg)
                                                if (m.forearmRJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmRJointIdx, rElbowDeg)
                                            }
                                            } // end jointLayer == null gate (Tier-4 arm + elbow writes)
                                        }

                                        val fbmYaw = fbmMod(tSec, seed, 0f)
                                        val fbmPitch = fbmMod(tSec, seed, 1.7f)
                                        val fbmBob = fbmMod(tSec, seed, 3.1f)

                                        // (Anticipation lead now computed per-axis above the stance
                                        // block — see leadPhYaw/leadPhPitch/leadPhBob at the top of
                                        // the dancing branch.)

                                        // Tier1-D: ease downgrade — fast rates with non-SINE/LINEAR ease
                                        // read as twitchy. Inline check (no listOf alloc).
                                        val baseEase = m.danceEase
                                        val downgrade = baseEase != SceneManager.DanceEase.SINE && baseEase != SceneManager.DanceEase.LINEAR
                                        val easeYaw = if (yawRate > 4 && downgrade) SceneManager.DanceEase.SINE else baseEase
                                        val easePitch = if (pitchRate > 4 && downgrade) SceneManager.DanceEase.SINE else baseEase
                                        val easeBob = if (yRate > 4 && downgrade) SceneManager.DanceEase.SINE else baseEase

                                        // Tier1-I (Gemini #5 orig): proximity inversion. Near viewer
                                        // (<1m) scales macro swings down to 40% and micro (breath/jiggle)
                                        // up to 130%. Beyond 2m both at 100%. Smoothstep between.
                                        val dxCam = camPosX - m.posX
                                        val dyCam = camPosY - m.posY
                                        val dzCam = camPosZ - m.posZ
                                        val distToView = kotlin.math.sqrt(dxCam*dxCam + dyCam*dyCam + dzCam*dzCam)
                                        val proxT = ((distToView - 1.0f) / 1.0f).coerceIn(0f, 1f)
                                        val proxMacro = 0.4f + 0.6f * proxT   // 0.4..1.0
                                        val proxMicro = 1.3f - 0.3f * proxT   // 1.3..1.0

                                        // Tier1 band gating: bass → bob+pitch, low-mid → yaw, high → micro.
                                        // Floor 0.35 so quiet breakdowns don't fully freeze her.
                                        val bassGate = 0.35f + 0.65f * snap.bandBass.coerceIn(0f, 1f)
                                        val midGate = 0.35f + 0.65f * snap.bandMid.coerceIn(0f, 1f)
                                        val highGate = 0.35f + 0.65f * snap.bandHigh.coerceIn(0f, 1f)

                                        // ── Gaze follow: smooth continuous slew toward viewer ──
                                        // User wanted LOCKED tracking (not the saccade FSM). Each frame
                                        // the bias eases toward the dir-to-camera by (1 - exp(-dt/τ))
                                        // with τ≈1.2s. She faces you no matter where you walk. Gated on
                                        // danceGazeFollow per model. Angle-wrapped so she takes the
                                        // shorter path when you cross her back.
                                        var gazeVoDipRad = 0f
                                        val gazeGain = 1f
                                        if (m.gazeFaceCamHasCapture && m.danceGazeFollow) {
                                            val dirToCamRad = kotlin.math.atan2(dxCam, dzCam)
                                            val target = normAngle(dirToCamRad - m.gazeFaceCamAnchorRad)
                                            val delta = normAngle(target - m.gazeCurrentBiasRad)
                                            val dtGaze = 1f / 72f  // XR refresh
                                            val alpha = (1f - kotlin.math.exp(-dtGaze / 1.2f)).coerceIn(0f, 1f)
                                            m.gazeCurrentBiasRad = normAngle(m.gazeCurrentBiasRad + delta * alpha)
                                        }

                                        // ── Tier2-pivot: derive local-frame pivot Y per axis ──
                                        // Preset fractions assume hip ≈ 0.45, shoulder ≈ 0.85 of bbox.
                                        // If the user MARKED real hip/shoulder on this model, remap
                                        // the preset vocabulary through their marks so TWERK@0.95
                                        // lands on their actual head, not a generic proportion.
                                        val pvGpu = glesRenderer?.getModel(m.gpuModelId)
                                        val pvMinY = pvGpu?.boundsMinY ?: 0f
                                        val pvCenterY = pvGpu?.boundsCenterY ?: 0f
                                        val pvHeight = 2f * (pvCenterY - pvMinY)
                                        val fPitch = remapAnatomyFrac(m.pitchPivotFrac, m.markedKneeFrac, m.markedHipFrac, m.markedShoulderFrac)
                                        val fRoll = remapAnatomyFrac(m.rollPivotFrac, m.markedKneeFrac, m.markedHipFrac, m.markedShoulderFrac)
                                        val fCtr = remapAnatomyFrac(m.counterRollPivotFrac, m.markedKneeFrac, m.markedHipFrac, m.markedShoulderFrac)
                                        // Pitch pivot forced to feet (= model origin). Head-level pivot
                                        // made the body TRANSLATE forward/back per pitch cycle up to
                                        // ±24 cm on high-amplitude Mixamo presets (10° × 1.36 m pivotY),
                                        // which read as "feet moving across the floor" to the user.
                                        // With pivot at feet, pitch rotates the whole body forward/back
                                        // around the heels — head swings, feet stay. Trade-off: loses
                                        // the "head-stationary, butt-pop" visual from the old TWERK
                                        // design. User accepted this trade on-device to keep feet planted.
                                        val pivotYPitch = 0f
                                        val pivotYRoll = if (m.pivotEnabled) (pvMinY + fRoll * pvHeight) * m.scale else 0f
                                        val pivotYCtrRoll = if (m.pivotEnabled) (pvMinY + fCtr * pvHeight) * m.scale else 0f

                                        var yawSig = 0f
                                        // Hoisted so the deferred world-Y quat yaw at the end of the
                                        // dance loop can use the same magnitude computed in the yaw
                                        // block. Keeping the computation DRY avoids a second gate/
                                        // reactor/gaze pass just to rebuild the same value.
                                        var yawMagDegOut = 0f
                                        // YAW semantics: TRUE rudder rotation around the world-Y axis
                                        // through the model origin. On a FootPivot rig the origin sits
                                        // at the feet, so the Y-axis passes straight through the heels
                                        // — feet don't translate, everything above swivels. This IS what
                                        // "yaw" means physically and matches the user's mental model.
                                        //
                                        // Historical note: a prior pass moved yaw to Spine01-Y only
                                        // (upper-body twist) because rigs with origin at the pelvis
                                        // would pendulum their feet under world-Y rotation. That
                                        // concern is moot for FootPivot rigs (which is the supported
                                        // export target now — see Bottom-Center-Pivot export doc).
                                        //
                                        // Spine01-Y counter-twist stays on top of the world yaw so the
                                        // shoulders lag the hips by ~45% — "hips lead, shoulders follow"
                                        // — which is the dance read that selling the booty move.
                                        if (m.danceYawDeg != 0f) {
                                            val ph = ((ar.phaseAtSnap(snap, yawRate) + m.danceYawPhase - 0.09f + leadPhYaw + phaseSlop) % 1f + 1f) % 1f
                                            yawSig = SceneManager.shapedWaveAt(ph, easeYaw, m.yawSharpness, m.yawComplexity)

                                            // Counter-roll ribcage proxy — now applied at the WAIST
                                            // joint Z axis (upper body only) instead of the world
                                            // quaternion. Before: q × qZ rolled the ENTIRE mesh,
                                            // which meant feet ~1m below pelvis swung 1m·sin(θ) arcs.
                                            // At the default 0.35 gain × 0.35 physics × 15° yaw × 1
                                            // yawSig = 1.8° world roll → 3cm foot arc at beat rate.
                                            // Classic pendulum contribution the user kept seeing.
                                            // Applying at the waist joint keeps the "shoulders tilt
                                            // opposite hips" visual (waist rolls in contra direction
                                            // → chest tilts the other way from pelvis) without touching
                                            // anything below pelvis.
                                            //
                                            // This value is stored here and applied in the stance
                                            // block's waist XYZ composition at the end of yaw handling.
                                            val counterRollDeg = if (m.physicsAmount > 0.001f && m.counterRollGain > 0.001f) {
                                                -m.counterRollGain * m.danceYawDeg * effInt * yawSig * m.physicsAmount
                                            } else 0f

                                            // ── Tier 4: spine counter-twist + arm counter-sway ──
                                            // The rigid yaw above rotates the WHOLE mesh. On a skinned rig
                                            // we can partially unwind that at the spine joints so the
                                            // shoulders lag behind the hips — classic "hips go, upper body
                                            // stays more oriented at the viewer" dance read.
                                            //
                                            // Accumulation: Spine01 and Spine02 each counter by half of
                                            // `counterFrac * yawMag`. Total counter at chest = full
                                            // counterFrac × yaw. Setting counterFrac=0.7 leaves the upper
                                            // body following about 30% of the hip yaw, which reads natural.
                                            //
                                            // Arms get an ADDITIONAL Z-opposition through clavicles so they
                                            // swing slightly opposite the hips (gait mechanics).
                                            val gpuMYaw = glesRenderer?.getModel(m.gpuModelId)
                                            val skelYaw = gpuMYaw?.skeleton
                                            if (gpuMYaw?.isSkinned == true && skelYaw != null) {
                                                if (m.spine01JointIdx == Int.MIN_VALUE) {
                                                    m.spine01JointIdx = skelYaw.indexOf("Spine01")
                                                    m.spine02JointIdx = skelYaw.indexOf("Spine02")
                                                    m.claviceLJointIdx = skelYaw.indexOf("L_Clavicle")
                                                    m.claviceRJointIdx = skelYaw.indexOf("R_Clavicle")
                                                    android.util.Log.i(TAG, "Yaw-chain joints cached: spine=${m.spine01JointIdx}/${m.spine02JointIdx} " +
                                                        "clavicle=${m.claviceLJointIdx}/${m.claviceRJointIdx}")
                                                }
                                                // ── Axis distribution across spine joints ──
                                                // To avoid Euler-stack gimbal distortion, each joint
                                                // carries ONE dance-axis rotation rather than stacking
                                                // X/Y/Z on a single joint:
                                                //   · Waist X  → lordosis arch  (stance block)
                                                //   · Waist Z  → sway counter   (stance block)
                                                //   · Spine01 Y → torso twist (contra-body yaw lag)
                                                //   · Spine02 Z → counter-roll (shoulders tilt opp hips)
                                                // Each of these is small enough that its isolated
                                                // axis rotation composes naturally through the chain
                                                // without the "diagonal bend" gimbal pathology that
                                                // shows up when you stack Y+Z on the same joint.
                                                val yawMagDeg = m.danceYawDeg * effInt * ampGain * fbmYaw * midGate * proxMacro * gazeGain * yawSig
                                                yawMagDegOut = yawMagDeg
                                                // World-Y quat yaw is applied LATER (after pitch/roll/
                                                // pivot corrections) — if we apply it here, the pitch
                                                // pivot correction reads the post-yaw quat and rotates
                                                // its local (0, +Y, -Z) offset into world space that
                                                // includes X. Result: px drifts laterally by ~20 mm/cycle
                                                // per 10° pitch × 5° yaw. That was the remaining "feet
                                                // going to her left and right" after the thigh counter
                                                // was zeroed. Yaw-deferred (see post-roll block).
                                                //
                                                // Spine01 still gets a mild counter-twist here so the
                                                // shoulders LAG the upcoming world-Y yaw by ~30%.
                                                if (m.spine01JointIdx >= 0) skelYaw.setJointEulerY(m.spine01JointIdx, -yawMagDeg * 0.30f)
                                                // TORSO SHIMMY on Spine02 Z: fast 1/16-rate side-tilt
                                                // layered on top of the counter-roll. Scales with the
                                                // yaw slider (gyrateScale = yaw/15) so it ramps in
                                                // smoothly with the rest of the dance — without this
                                                // scaling, shimmy fired at full 3°/8 Hz the moment yaw
                                                // crossed 0, producing a "spaz" discontinuity at low
                                                // yaw values.
                                                val shimmyPh = ar.phaseAtSnap(snap, 16)
                                                val shimmyGyr = (m.danceYawDeg / 15f).coerceIn(0f, 1f)
                                                val shimmyDeg = kotlin.math.sin(2f * Math.PI.toFloat() * shimmyPh) * 3f * effInt * snap.musicalLevel * shimmyGyr
                                                if (m.spine02JointIdx >= 0) skelYaw.setJointEulerZ(m.spine02JointIdx, counterRollDeg + shimmyDeg)
                                                // Arm sway disabled temporarily — clavicle is no longer
                                                // the arm-drop joint (upperarm took over in the stance
                                                // block). Wiring sway onto upperarm X with phase lag is
                                                // next pass; for now, arms rest at their stance pose
                                                // without beat-sway so the primary "arms look right"
                                                // fix lands cleanly first.
                                            }
                                        } else {
                                            // Yaw off: clear the spine counter-twist so upper body
                                            // returns to neutral. DO NOT reset clavicles — the stance
                                            // block owns the arm-drop write and resetting to bind here
                                            // would snap arms back to T-pose horizontal whenever yaw
                                            // is zero.
                                            val gpuMYaw = glesRenderer?.getModel(m.gpuModelId)
                                            val skelYaw = gpuMYaw?.skeleton
                                            if (gpuMYaw?.isSkinned == true && skelYaw != null && m.spine01JointIdx != Int.MIN_VALUE) {
                                                if (m.spine01JointIdx >= 0) skelYaw.resetJointToBind(m.spine01JointIdx)
                                                if (m.spine02JointIdx >= 0) skelYaw.resetJointToBind(m.spine02JointIdx)
                                            }
                                        }
                                        // Tripo-native performance layer. This is intentionally late:
                                        // yaw has already written spine counter-twist, and the stance
                                        // block has already written arm baselines, so this can add
                                        // small musical head/shoulder/arm details without wiping the
                                        // rig-safe base pose or accumulating frame-to-frame.
                                        val grooveGpu = glesRenderer?.getModel(m.gpuModelId)
                                        val grooveSkel = grooveGpu?.skeleton
                                        if (grooveGpu?.isSkinned == true && grooveSkel != null) {
                                            if (m.neckJointIdx == Int.MIN_VALUE ||
                                                m.headJointIdx == Int.MIN_VALUE ||
                                                m.claviceLJointIdx == Int.MIN_VALUE ||
                                                m.upperArmLJointIdx == Int.MIN_VALUE ||
                                                m.forearmLJointIdx == Int.MIN_VALUE
                                            ) {
                                                fun findFirstJoint(vararg names: String): Int {
                                                    for (name in names) {
                                                        val idx = grooveSkel.indexOf(name)
                                                        if (idx >= 0) return idx
                                                    }
                                                    return -1
                                                }
                                                if (m.spine01JointIdx < 0) {
                                                    m.spine01JointIdx = findFirstJoint("Spine01", "Spine", "mixamorig:Spine")
                                                }
                                                if (m.spine02JointIdx < 0) {
                                                    m.spine02JointIdx = findFirstJoint("Spine02", "Chest", "UpperChest", "mixamorig:Spine1")
                                                }
                                                if (m.neckJointIdx < 0) {
                                                    m.neckJointIdx = findFirstJoint("Neck", "mixamorig:Neck")
                                                }
                                                if (m.headJointIdx < 0) {
                                                    m.headJointIdx = findFirstJoint("Head", "mixamorig:Head")
                                                }
                                                if (m.claviceLJointIdx < 0) {
                                                    m.claviceLJointIdx = findFirstJoint("L_Clavicle", "LeftShoulder", "mixamorig:LeftShoulder")
                                                    m.claviceRJointIdx = findFirstJoint("R_Clavicle", "RightShoulder", "mixamorig:RightShoulder")
                                                }
                                                if (m.upperArmLJointIdx < 0) {
                                                    m.upperArmLJointIdx = findFirstJoint("L_Upperarm", "L_UpperArm", "L_Arm", "LeftArm", "mixamorig:LeftArm")
                                                    m.upperArmRJointIdx = findFirstJoint("R_Upperarm", "R_UpperArm", "R_Arm", "RightArm", "mixamorig:RightArm")
                                                }
                                                if (m.forearmLJointIdx < 0) {
                                                    m.forearmLJointIdx = findFirstJoint("L_Forearm", "L_LowerArm", "LeftForeArm", "mixamorig:LeftForeArm")
                                                    m.forearmRJointIdx = findFirstJoint("R_Forearm", "R_LowerArm", "RightForeArm", "mixamorig:RightForeArm")
                                                }
                                                android.util.Log.i(TAG, "Groove joints cached: spine=${m.spine01JointIdx}/${m.spine02JointIdx} " +
                                                    "neck/head=${m.neckJointIdx}/${m.headJointIdx} clavicle=${m.claviceLJointIdx}/${m.claviceRJointIdx} " +
                                                    "upperarm=${m.upperArmLJointIdx}/${m.upperArmRJointIdx} forearm=${m.forearmLJointIdx}/${m.forearmRJointIdx}")
                                            }

                                            val twoPiGroove = 2f * Math.PI.toFloat()
                                            fun normGroovePhase(value: Float): Float = ((value % 1f) + 1f) % 1f
                                            val beatPh = normGroovePhase(ar.phaseAtSnap(snap, 4) + m.danceYawPhase + phaseSlop + seed * 0.017f)
                                            val halfPh = normGroovePhase(ar.phaseAtSnap(snap, 2) + m.dancePitchPhase + phaseSlop + seed * 0.011f)
                                            val fastPh = normGroovePhase(ar.phaseAtSnap(snap, 8) + m.danceYPhase + phaseSlop + seed * 0.023f)
                                            val phrasePh = if (hasTempo) snap.phase4bar else normGroovePhase(tSec * 0.125f + seed * 0.031f)
                                            val beatSin = kotlin.math.sin(twoPiGroove * beatPh)
                                            val beatCos = kotlin.math.cos(twoPiGroove * beatPh)
                                            val halfSin = kotlin.math.sin(twoPiGroove * halfPh)
                                            val fastSin = kotlin.math.sin(twoPiGroove * fastPh)
                                            val fastOpp = kotlin.math.sin(twoPiGroove * normGroovePhase(fastPh + 0.5f))
                                            val phraseLift = 0.5f - 0.5f * kotlin.math.cos(twoPiGroove * phrasePh)
                                            val fastAccent = (0.5f + 0.5f * fastSin).let { it * it * (3f - 2f * it) }
                                            val fastAccentOpp = (0.5f + 0.5f * fastOpp).let { it * it * (3f - 2f * it) }
                                            val styleBoost = when (m.currentPresetName) {
                                                "CLUB HEAT" -> 1.28f
                                                "TWERK", "SQUAT SHAKE" -> 1.18f
                                                else -> 1f
                                            }
                                            val fillBoost = if (fillActive) 1.22f else 1f
                                            val phraseBoost = 0.78f + 0.36f * phraseLift
                                            val grooveGain = (effInt * ampGain * snap.musicalLevel * proxMacro * gazeGain *
                                                styleBoost * fillBoost * phraseBoost).coerceIn(0f, 1.65f)
                                            val torsoGain = grooveGain * (0.55f + 0.45f * m.physicsAmount)
                                            val armGain = torsoGain * if (m.handsOnKnees) 0.30f else 1f

                                            if (m.danceYawDeg == 0f) {
                                                if (m.spine01JointIdx >= 0) grooveSkel.resetJointToBind(m.spine01JointIdx)
                                                if (m.spine02JointIdx >= 0) grooveSkel.resetJointToBind(m.spine02JointIdx)
                                            }
                                            if (m.spine01JointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(
                                                    m.spine01JointIdx,
                                                    1.3f * halfSin * torsoGain,
                                                    1.5f * beatSin * torsoGain,
                                                    -1.1f * beatCos * torsoGain
                                                )
                                            }
                                            if (m.spine02JointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(
                                                    m.spine02JointIdx,
                                                    -0.8f * halfSin * torsoGain,
                                                    0.9f * beatSin * torsoGain,
                                                    (2.4f * beatCos + 1.5f * fastAccent) * torsoGain
                                                )
                                            }

                                            if (m.neckJointIdx >= 0) {
                                                grooveSkel.setJointEulerXYZ(
                                                    m.neckJointIdx,
                                                    -0.9f * halfSin * torsoGain,
                                                    -1.0f * beatSin * torsoGain,
                                                    -1.2f * beatCos * torsoGain
                                                )
                                            }
                                            if (m.headJointIdx >= 0) {
                                                grooveSkel.setJointEulerXYZ(
                                                    m.headJointIdx,
                                                    -1.1f * halfSin * torsoGain,
                                                    -1.4f * beatSin * torsoGain,
                                                    -1.5f * beatCos * torsoGain
                                                )
                                            }

                                            val shoulderLift = (1.1f + 1.6f * fastAccent) * torsoGain
                                            val shoulderLiftOpp = (1.1f + 1.6f * fastAccentOpp) * torsoGain
                                            if (m.claviceLJointIdx >= 0) {
                                                grooveSkel.setJointEulerXYZ(
                                                    m.claviceLJointIdx,
                                                    -shoulderLift,
                                                    0.5f * beatCos * torsoGain,
                                                    1.6f * beatSin * torsoGain
                                                )
                                            }
                                            if (m.claviceRJointIdx >= 0) {
                                                grooveSkel.setJointEulerXYZ(
                                                    m.claviceRJointIdx,
                                                    -shoulderLiftOpp,
                                                    -0.5f * beatCos * torsoGain,
                                                    -1.6f * beatSin * torsoGain
                                                )
                                            }

                                            val armPulse = 0.65f + 0.35f * fastAccent
                                            val armPulseOpp = 0.65f + 0.35f * fastAccentOpp
                                            if (m.upperArmLJointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(
                                                    m.upperArmLJointIdx,
                                                    1.0f * halfSin * armGain,
                                                    1.7f * beatCos * armGain,
                                                    3.8f * beatSin * armGain * armPulse
                                                )
                                            }
                                            if (m.upperArmRJointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(
                                                    m.upperArmRJointIdx,
                                                    -1.0f * halfSin * armGain,
                                                    -1.7f * beatCos * armGain,
                                                    -3.8f * beatSin * armGain * armPulseOpp
                                                )
                                            }
                                            if (m.forearmLJointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(m.forearmLJointIdx, 4.0f * fastAccent * armGain, 0f, 0f)
                                            }
                                            if (m.forearmRJointIdx >= 0) {
                                                grooveSkel.composeJointEulerXYZ(m.forearmRJointIdx, 4.0f * fastAccentOpp * armGain, 0f, 0f)
                                            }
                                        }
                                        // PITCH around LOCAL X (right-multiply q * qX) — so pitch tilts
                                        // the model in its OWN forward-back axis regardless of base facing.
                                        // Phase-shear -0.04 (lower back between hips & shoulders).
                                        // Tier2-pivot: if pivotEnabled, pitch pivots at preset-specified
                                        // height (e.g. 0.95 head for TWERK → hips pop, head stays fixed).
                                        if (m.dancePitchDeg != 0f) {
                                            val ph = ((ar.phaseAtSnap(snap, pitchRate) + m.dancePitchPhase - 0.04f + leadPhPitch + phaseSlop) % 1f + 1f) % 1f
                                            // Tier 3 — character shaping (see yaw above).
                                            val sig = SceneManager.shapedWaveAt(ph, easePitch, m.pitchSharpness, m.pitchComplexity)
                                            val half = Math.toRadians((m.dancePitchDeg * effInt * ampGain * fbmPitch * bassGate * proxMacro * gazeGain * sig).toDouble()).toFloat() * 0.5f
                                            val sp = kotlin.math.sin(half); val cp = kotlin.math.cos(half)
                                            val qxP = qx; val qyP = qy; val qzP = qz; val qwP = qw
                                            val nx = qx * cp + qw * sp
                                            val ny = qy * cp + qz * sp
                                            val nz = qz * cp - qy * sp
                                            val nw = qw * cp - qx * sp
                                            qx = nx; qy = ny; qz = nz; qw = nw
                                            // Pivot correction for local-X pitch about (0, pivotY, 0):
                                            // local offset = (0, pivotY · (1-cosθ), -pivotY · sinθ)
                                            if (m.pivotEnabled && pivotYPitch != 0f) {
                                                val sTh = 2f * sp * cp
                                                val cTh = 1f - 2f * sp * sp
                                                rotateVecQ(qxP, qyP, qzP, qwP,
                                                    0f, pivotYPitch * (1f - cTh), -pivotYPitch * sTh)
                                                px += pivotRotScratch[0]
                                                py += pivotRotScratch[1]
                                                pz += pivotRotScratch[2]
                                            }
                                        }
                                        // BOB on world Y — asymmetric fast-down/slow-up envelope gives
                                        // the "body drops into the beat" gravity feel. Phase-shear 0 (hips lead).
                                        // Tier 3 — bob is a one-sided bump (not a ±sine), so character
                                        // shaping is applied bespoke: sharpness lerps smoothstep → raw
                                        // triangle (cusp'd peak); complexity adds a secondary 2×-rate
                                        // bump. Both 0 → pre-Tier3 behaviour.
                                        if (m.danceYMeters != 0f) {
                                            val kSkew = 0.5f - 0.2f * m.physicsAmount  // fast segment duration (the drop)
                                            // D1(B) weight-arrival audit: phase01 peaks at ph==kSkew, and
                                            // at that peak the squat's root drop (≤12cm) dominates the py
                                            // bump (≤4cm × gains) — the body's LOWEST point used to land
                                            // mid-beat. Offsetting the lookup by +kSkew puts the deepest
                                            // point at grid phase 0: the fast kSkew segment becomes the
                                            // descent INTO the beat (starting ~(1-kSkew) of the prior
                                            // beat), recovery is the slow segment. leadPhBob then pulls
                                            // the whole waveform ~80ms early like the other axes.
                                            // bobRecenter (== kSkew when the squat drives, else 0) is
                                            // computed above the stance block — see its gating note.
                                            val ph = ((ar.phaseAtSnap(snap, yRate) + m.danceYPhase + bobRecenter + leadPhBob + phaseSlop) % 1f + 1f) % 1f
                                            val phase01 = if (ph < kSkew) ph / kSkew else 1f - (ph - kSkew) / (1f - kSkew)
                                            val smoothBob = phase01 * phase01 * (3f - 2f * phase01)
                                            val bobS = m.bobSharpness.coerceIn(0f, 1f)
                                            // phase01 itself is the sharp (cusp'd) bump; smoothBob is round.
                                            val bob = smoothBob + (phase01 - smoothBob) * bobS
                                            val bobC = m.bobComplexity.coerceIn(0f, 1f)
                                            val bobFinal = if (bobC > 0.001f) {
                                                val gPh = (ph * 2f) % 1f
                                                val g01 = if (gPh < kSkew) gPh / kSkew else 1f - (gPh - kSkew) / (1f - kSkew)
                                                bob + bobC * 0.4f * (g01 * g01 * (3f - 2f * g01))
                                            } else bob
                                            py += m.danceYMeters * effInt * ampGain * fbmBob * bassGate * proxMacro * gazeGain * bobFinal

                                            // ── Tier 4: squat motion driven by bob phase ──
                                            // Real squat requires three coordinated motions:
                                            //   1. Calf rotates BACK (so shin pivots, foot goes behind heel)
                                            //   2. Thigh rotates FORWARD about half as much (thigh tilts,
                                            //      knee moves forward, hip drops)
                                            //   3. Root Y translates down to anchor the motion vertically
                                            //      (without it, only the legs move — body hovers)
                                            //
                                            // First-pass fix report (from on-device): user saw ONLY the
                                            // calf bend (shins pivoting, body static above the knee).
                                            // Correct — rotating Calf alone doesn't propagate to anything
                                            // above it in the joint hierarchy. Thigh + root drop fixes that.
                                            //
                                            // phase01 = 0 at bob-top (standing), 1 at bob-bottom (deep squat).
                                            // effInt caps the amplitude so INTENSITY controls squat depth.
                                            val gpuM = glesRenderer?.getModel(m.gpuModelId)
                                            val skel = gpuM?.skeleton
                                            if (gpuM?.isSkinned == true && skel != null) {
                                                if (m.kneeLJointIdx == Int.MIN_VALUE) {
                                                    m.kneeLJointIdx = skel.indexOf("L_Calf")
                                                    m.kneeRJointIdx = skel.indexOf("R_Calf")
                                                    m.thighLJointIdx = skel.indexOf("L_Thigh")
                                                    m.thighRJointIdx = skel.indexOf("R_Thigh")
                                                    m.rootJointIdx = skel.indexOf("Root")
                                                    android.util.Log.i(TAG, "Squat joints cached: knee=${m.kneeLJointIdx}/${m.kneeRJointIdx} " +
                                                        "thigh=${m.thighLJointIdx}/${m.thighRJointIdx} root=${m.rootJointIdx}")
                                                }
                                                if (m.kneeLJointIdx >= 0 && m.kneeRJointIdx >= 0) {
                                                    val gate = effInt.coerceIn(0f, 1f) * bassGate
                                                    val squat = phase01 * gate

                                                    // ── Stance baselines REMOVED ──
                                                    // Previous: stanceCalfDeg=18°*arch, stanceThighDeg=-6°*arch,
                                                    // stanceRootDrop=0.04*arch. Each rotated thighs/calves at
                                                    // rest which shifted their children's world positions via
                                                    // hierarchy arc — calf origin moves ±dy·sin(thighX) in Z,
                                                    // foot likewise. User kept seeing "heels floating" and
                                                    // "stepping through floor" whenever arch was up, even when
                                                    // bob was quiet. Arch now only tilts the WAIST (handled in
                                                    // the stance block above), which doesn't touch legs.

                                                    // Pelvis Z sway is now zeroed in the stance block
                                                    // (killed the pendulum), so the thigh Z counter that
                                                    // used to cancel it must also be zero — otherwise
                                                    // thighs rotate 4.8° sideways with no pelvis parent
                                                    // to counter, and the feet sweep ~3 cm laterally per
                                                    // cycle. That was the "heels going to her left and
                                                    // right" the user reported.
                                                    val pelvisSwayZBob = 0f

                                                    // Squat magnitudes boosted so she ACTUALLY squats when
                                                    // bob is cranked. Previous 40°/20°/4cm with effInt gate
                                                    // produced ~10° knee bend at default intensity 0.5 —
                                                    // barely visible. Also scale by bob-amp so a small bob
                                                    // (5mm) = shallow bob, full bob (4cm) = deep squat.
                                                    val squatDepth = (m.danceYMeters / 0.04f).coerceIn(0f, 1f)
                                                    // Idle knee flex: 6° gentle bend at 1/beat even when
                                                    // squat depth is low. Keeps the legs from looking
                                                    // locked/stiff — user noted "legs too stiff like
                                                    // they have to move a LITTLE". Always pulses even at
                                                    // low bob so she's never fully rigid.
                                                    val idleKneePh = ar.phaseAtSnap(snap, 4)
                                                    val idleKneeDeg = -6f * (0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * idleKneePh)) * effInt * snap.musicalLevel
                                                    val kneeDeg = idleKneeDeg - squat * 85f * squatDepth
                                                    val thighDeg = squat * 45f * squatDepth
                                                    skel.setJointEulerX(m.kneeLJointIdx, kneeDeg)
                                                    skel.setJointEulerX(m.kneeRJointIdx, kneeDeg)
                                                    if (m.thighLJointIdx >= 0) {
                                                        skel.setJointEulerXYZ(m.thighLJointIdx, thighDeg, 0f, -pelvisSwayZBob)
                                                    }
                                                    if (m.thighRJointIdx >= 0) {
                                                        skel.setJointEulerXYZ(m.thighRJointIdx, thighDeg, 0f, -pelvisSwayZBob)
                                                    }
                                                    // Root Y drop: up to 12 cm at full depth — enough to
                                                    // read as a real squat without her feet clipping into
                                                    // the floor (the posed feet rise slightly via the
                                                    // bent-knee arc; 12 cm of root drop is balanced by
                                                    // the 85° knee bend on a ~45 cm thigh/shin).
                                                    if (m.rootJointIdx >= 0) {
                                                        skel.resetJointToBind(m.rootJointIdx)
                                                        val dropMeters = squat * 0.12f * squatDepth
                                                        skel.localPose[m.rootJointIdx * 16 + 13] -= dropMeters
                                                    }
                                                }
                                            }
                                        } else {
                                            // Bob off: reset knees + root. Thighs are owned by the stance
                                            // block (it writes them every frame with thrust + sway counters)
                                            // so we MUST NOT reset thighs here or the Z counter is lost and
                                            // legs wobble sideways whenever sway is active without bob.
                                            val gpuM = glesRenderer?.getModel(m.gpuModelId)
                                            val skel = gpuM?.skeleton
                                            if (gpuM?.isSkinned == true && skel != null && m.kneeLJointIdx != Int.MIN_VALUE) {
                                                if (m.kneeLJointIdx >= 0) skel.resetJointToBind(m.kneeLJointIdx)
                                                if (m.kneeRJointIdx >= 0) skel.resetJointToBind(m.kneeRJointIdx)
                                                if (m.rootJointIdx >= 0) skel.resetJointToBind(m.rootJointIdx)
                                            }
                                        }

                                        // ── Phase 2 JointDriveLayer (D9 Phase A — ENABLED) ──
                                        // Mixamo-extracted per-joint articulation, conjugated into
                                        // the Tripo bone-local frame at extraction time (per-bone
                                        // axis correction from the production GLB bind pose — the
                                        // old "spinning bent-over" Mixamo-frame data is gone).
                                        // evaluate() re-bases its owned joints (Spine01/02,
                                        // clavicles, upperarms, forearms, Head) to bind each frame,
                                        // then composes rest + Fourier drives — superseding the
                                        // groove/counter-twist writes on those joints (mocap spine
                                        // motion is already hips-relative; the hand-tuned counters
                                        // would double-count) while Tier 4 keeps Waist/Pelvis/Root/
                                        // legs. Tier-4 arm writes are gated on jointLayer == null in
                                        // the stance block. IdleLayer breath still composes on top
                                        // (it runs after the dance pass).
                                        // beatPhase = snap.phase1bar — the snapshot's measure phase
                                        // (1 cycle per 4 beats), matching rateCyclesPerMeasure
                                        // semantics. NOT ar.phaseAtSnap(snap, 1): rate 1 isn't in the
                                        // snapshot cache, so that path re-reads three @Volatile
                                        // fields + the clock per model per frame and can skew from
                                        // the frame snapshot mid-frame.
                                        // ampGain mirrors the rigid-body axes' musical breathing but
                                        // deliberately EXCLUDES effInt: drive amplitudes are absolute
                                        // mocap degrees, not user-intensity-scaled oscillations.
                                        run {
                                            val layer = m.jointLayer
                                            val layerGpu = glesRenderer?.getModel(m.gpuModelId)
                                            val layerSkel = layerGpu?.skeleton
                                            if (layer != null && layerGpu?.isSkinned == true && layerSkel != null) {
                                                layer.evaluate(snap.phase1bar, layerSkel, ampGain)
                                            }
                                        }

                                        // ROLL around LOCAL Z (Tier1.5) — banking axis. Completes the 3-axis
                                        // set so the user can build "wave" or "leaning" patterns beyond what
                                        // yaw/pitch alone can express. Right-multiplied for local frame.
                                        // Tier2-pivot: respects rollPivotFrac per preset.
                                        if (m.danceRollDeg != 0f) {
                                            val rollRate = if (fillActive) 16 else m.danceRollRate
                                            val easeRoll = if (rollRate > 4 && downgrade) SceneManager.DanceEase.SINE else baseEase
                                            val phR = ((ar.phaseAtSnap(snap, rollRate) + m.danceRollPhase + phaseSlop) % 1f + 1f) % 1f
                                            val sigR = SceneManager.waveAt(phR, easeRoll)
                                            val halfR = Math.toRadians(
                                                (m.danceRollDeg * effInt * ampGain * fbmPitch * midGate * proxMacro * gazeGain * sigR).toDouble()
                                            ).toFloat() * 0.5f
                                            val srR = kotlin.math.sin(halfR); val crR = kotlin.math.cos(halfR)
                                            val qxR = qx; val qyR = qy; val qzR = qz; val qwR = qw
                                            val nxR = qx * crR + qy * srR
                                            val nyR = qy * crR - qx * srR
                                            val nzR = qz * crR + qw * srR
                                            val nwR = qw * crR - qz * srR
                                            qx = nxR; qy = nyR; qz = nzR; qw = nwR
                                            // Pivot correction for local-Z roll about (0, pivotY, 0):
                                            // local offset = (pivotY · sinθ, pivotY · (1-cosθ), 0)
                                            if (m.pivotEnabled && pivotYRoll != 0f) {
                                                val sTh = 2f * srR * crR
                                                val cTh = 1f - 2f * srR * srR
                                                rotateVecQ(qxR, qyR, qzR, qwR,
                                                    pivotYRoll * sTh, pivotYRoll * (1f - cTh), 0f)
                                                px += pivotRotScratch[0]
                                                py += pivotRotScratch[1]
                                                pz += pivotRotScratch[2]
                                            }
                                        }

                                        // Lissajous hip-8 DISABLED — was injecting ±6mm world X +
                                        // ±3mm world Y translation per beat onto model position px/py.
                                        // Even at 6mm, over a cycle it reads as the model gliding in
                                        // an ∞-arc which on top of hip gyration looks like pendulum
                                        // feet. The hip gyration is already doing the belly-dance
                                        // signature via pelvis joint rotation — we don't need the
                                        // additional world translation on top.

                                        // ── Tier1-E/G/J: breath + axis coupling + vestibular mirror ──
                                        // Dancer: a body is causally chained, not a tripod. Yaw pulls pitch
                                        // into the turn (contrapposto), and bob dips on the loaded side.
                                        // Breath runs independent of the beat — she's alive on stillness.

                                        // Tier1-E weight-shift bob dip DISABLED — was subtracting
                                        // up to 1cm from world Y on yaw extremes, creating asymmetric
                                        // vertical motion that contributed to the pendulum read
                                        // since it only fires on yaw peaks, not continuously. Pelvis
                                        // Z sway already gives the visual weight shift through rig
                                        // rotation, no world-position write needed.
                                        // py -= 0.010f * m.physicsAmount * effInt * kotlin.math.abs(yawSig) * proxMacro

                                        // Tier1-G: breath bob — capped at 1mm (was 3mm): whole-model Y
                                        // translation makes her FEET sink/hover against the real
                                        // passthrough floor, a grounding tell stereo vision catches at
                                        // 1-3m (D3). Skinned rigs now breathe through the skeleton
                                        // (IdleLayer); this 1mm remainder keeps non-skinned models alive.
                                        val breathBobM = 0.001f * proxMicro *
                                            kotlin.math.sin(((tSec * 0.25f + seed) * 2.0 * Math.PI).toFloat())
                                        py += breathBobM

                                        // Tier1-J (Gemini #6): vestibular mirror — user's own head-bob
                                        // velocity bleeds 2.5× into her bob. Mirror-neuron sync.
                                        if (m.lastSeenCamPosY == 0f) m.lastSeenCamPosY = camPosY
                                        val camYDelta = camPosY - m.lastSeenCamPosY
                                        m.lastSeenCamPosY = camPosY
                                        m.camYVelSmooth = 0.9f * m.camYVelSmooth + 0.1f * camYDelta
                                        py += (m.camYVelSmooth * 2.5f).coerceIn(-0.003f, 0.003f)

                                        // Tier1-E yaw→pitch coupling DISABLED — this was rotating the
                                        // WORLD quaternion by 0.15 × yawDeg × yawSig on local X axis,
                                        // up to 2.25° world pitch on each yaw peak. For a BCP-OFF
                                        // rig pivoting at pelvis, that's sin(2.25°) × 1m foot height
                                        // = ~4cm foot arc per beat — a direct contribution to the
                                        // residual pendulum the user kept seeing even after every
                                        // other world-rotation source was removed.
                                        // If we want "tilts into the turn" later, apply it at a spine
                                        // joint (waist X), not world, so feet stay planted.

                                        // Tier1-G: breath pitch — 0.3° @ 0.3Hz. NON-SKINNED models only:
                                        // rotating the whole model breathes the feet too. Skinned rigs
                                        // get this as a Waist-X compose in the IdleLayer instead (D3).
                                        val breathPitchDeg = 0.3f * proxMicro *
                                            kotlin.math.sin(((tSec * 0.3f + seed * 1.3f) * 2.0 * Math.PI).toFloat())
                                        if (breathPitchDeg != 0f && stanceGpu?.isSkinned != true) {
                                            val halfPB = Math.toRadians(breathPitchDeg.toDouble()).toFloat() * 0.5f
                                            val spB = kotlin.math.sin(halfPB); val cpB = kotlin.math.cos(halfPB)
                                            val nxB = qx * cpB + qw * spB
                                            val nyB = qy * cpB + qz * spB
                                            val nzB = qz * cpB - qy * spB
                                            val nwB = qw * cpB - qx * spB
                                            qx = nxB; qy = nyB; qz = nzB; qw = nwB
                                        }

                                        // Warmth jitter — micro COM wander (kept, physics-gated).
                                        if (m.physicsAmount > 0.001f) {
                                            val jAmp = 0.002f * m.physicsAmount
                                            px += jAmp * (kotlin.math.sin(tSec * 0.73f).toFloat()
                                                + 0.5f * kotlin.math.sin(tSec * 1.41f + 0.3f).toFloat())
                                            py += jAmp * (kotlin.math.sin(tSec * 1.19f + 0.8f).toFloat()
                                                + 0.5f * kotlin.math.sin(tSec * 0.61f).toFloat())
                                            pz += jAmp * (kotlin.math.sin(tSec * 0.89f + 1.2f).toFloat()
                                                + 0.5f * kotlin.math.sin(tSec * 1.57f + 2f).toFloat())
                                        }

                                        // ── ALIVE: impact kick scheduled off the epoch beat grid ──
                                        // The raised-sine envelope sin(πk)(1-k) peaks at k≈0.36 → ~72ms
                                        // into its 200ms window. Audio-detected beatCounter triggers
                                        // always arrive late (~50ms capture quantization + 180ms min
                                        // interval), so when BPM is locked the kick free-runs the epoch
                                        // grid shifted 72ms EARLY — the envelope peak lands ON the grid
                                        // beat ("her ass is the metronome", same policy as the glute
                                        // tick). beatCounter stays as the no-lock fallback.
                                        // m.lastBeatSeen holds a grid index in epoch mode and a counter
                                        // in fallback mode — one spurious kick on mode switch, absorbed.
                                        if (m.physicsAmount > 0.001f) {
                                            if (hasTempo && snap.epochMs != 0L) {
                                                val kickBeatMs = 60000f / snap.bpm
                                                val kickGridNow = (((snap.nowMs - snap.epochMs).toFloat() + 72f) / kickBeatMs).toLong()
                                                if (kickGridNow != m.lastBeatSeen) {
                                                    if (m.lastBeatSeen != 0L) {
                                                        m.impactKickStartMs = snap.nowMs
                                                        m.impactKickAxis = (kickGridNow % 3).toInt()
                                                    }
                                                    m.lastBeatSeen = kickGridNow
                                                }
                                            } else if (snap.beatCounter != m.lastBeatSeen && snap.beatCounter > 0L) {
                                                // != (not >) so the counter domain is re-adopted on the
                                                // first frame after an epoch→fallback switch — the stale
                                                // grid index in lastBeatSeen can exceed beatCounter by
                                                // thousands and '>' would silence kicks for minutes
                                                // (review finding; same self-healing shape as the glute
                                                // fallback).
                                                m.lastBeatSeen = snap.beatCounter
                                                m.impactKickStartMs = snap.nowMs
                                                m.impactKickAxis = (snap.beatCounter % 3).toInt()
                                            }
                                        }
                                        if (m.impactKickStartMs > 0L) {
                                            val el = (snap.nowMs - m.impactKickStartMs).toFloat()
                                            if (el < 200f) {
                                                val kp = el / 200f
                                                val ks = kotlin.math.sin(Math.PI * kp).toFloat() * (1f - kp)
                                                val kickRad = Math.toRadians(3.0 * m.physicsAmount * effInt * ks * gazeGain * snap.musicalLevel).toFloat()
                                                val half = kickRad * 0.5f
                                                val sh = kotlin.math.sin(half); val ch = kotlin.math.cos(half)
                                                val qxK = qx; val qyK = qy; val qzK = qz; val qwK = qw
                                                when (m.impactKickAxis) {
                                                    0 -> {  // pitch — local X (right-multiply), pivot at pitch pivot
                                                        val nx = qx * ch + qw * sh
                                                        val ny = qy * ch + qz * sh
                                                        val nz = qz * ch - qy * sh
                                                        val nw = qw * ch - qx * sh
                                                        qx = nx; qy = ny; qz = nz; qw = nw
                                                        if (m.pivotEnabled && pivotYPitch != 0f) {
                                                            val sTh = 2f * sh * ch
                                                            val cTh = 1f - 2f * sh * sh
                                                            rotateVecQ(qxK, qyK, qzK, qwK,
                                                                0f, pivotYPitch * (1f - cTh), -pivotYPitch * sTh)
                                                            px += pivotRotScratch[0]
                                                            py += pivotRotScratch[1]
                                                            pz += pivotRotScratch[2]
                                                        }
                                                    }
                                                    1 -> {  // yaw — world Y (no pivot correction needed)
                                                        val nx = ch * qx + sh * qz
                                                        val ny = ch * qy + sh * qw
                                                        val nz = ch * qz - sh * qx
                                                        val nw = ch * qw - sh * qy
                                                        qx = nx; qy = ny; qz = nz; qw = nw
                                                    }
                                                    else -> {  // roll — local Z (right-multiply), pivot at roll pivot
                                                        val nx = qx * ch + qy * sh
                                                        val ny = qy * ch - qx * sh
                                                        val nz = qz * ch + qw * sh
                                                        val nw = qw * ch - qz * sh
                                                        qx = nx; qy = ny; qz = nz; qw = nw
                                                        if (m.pivotEnabled && pivotYRoll != 0f) {
                                                            val sTh = 2f * sh * ch
                                                            val cTh = 1f - 2f * sh * sh
                                                            rotateVecQ(qxK, qyK, qzK, qwK,
                                                                pivotYRoll * sTh, pivotYRoll * (1f - cTh), 0f)
                                                            px += pivotRotScratch[0]
                                                            py += pivotRotScratch[1]
                                                            pz += pivotRotScratch[2]
                                                        }
                                                    }
                                                }
                                            } else {
                                                m.impactKickStartMs = 0L
                                            }
                                        }

                                        // ── Tier1-F: gaze bias post-rotation + Gemini #5 VO-dip ──
                                        // Applied LAST so the entire composed dance pose is rotated as one
                                        // toward/away from the viewer. VO dip is the chin-down "under the
                                        // lashes" glance that sells the saccade as a gaze, not a pan.
                                        if (m.gazeFaceCamHasCapture && m.danceGazeFollow && m.gazeCurrentBiasRad != 0f) {
                                            val hb = m.gazeCurrentBiasRad * 0.5f
                                            val sb = kotlin.math.sin(hb); val cb = kotlin.math.cos(hb)
                                            val nxG = cb * qx + sb * qz
                                            val nyG = cb * qy + sb * qw
                                            val nzG = cb * qz - sb * qx
                                            val nwG = cb * qw - sb * qy
                                            qx = nxG; qy = nyG; qz = nzG; qw = nwG
                                        }
                                        if (gazeVoDipRad != 0f) {
                                            // Negative pitch = chin down. Local-frame right-multiply so
                                            // dip tracks her facing direction after the bias yaw.
                                            val hp = -gazeVoDipRad * 0.5f
                                            val sp = kotlin.math.sin(hp); val cp = kotlin.math.cos(hp)
                                            val nxV = qx * cp + qw * sp
                                            val nyV = qy * cp + qz * sp
                                            val nzV = qz * cp - qy * sp
                                            val nwV = qw * cp - qx * sp
                                            qx = nxV; qy = nyV; qz = nzV; qw = nwV
                                        }

                                        // ── DEFERRED world-Y quat yaw (rudder) ──
                                        // Applied AFTER pitch, roll, bob, kick, gaze-bias, and all their
                                        // pivot corrections. Rotation-only: changes orientation (face
                                        // direction) without touching px/py/pz.
                                        //
                                        // Hard cap ±20° peak — bigger rudder than the previous ±10° so
                                        // the twist actually reads as a pivot/rudder per user request,
                                        // but still bounded so high-intensity yaw doesn't spin the
                                        // torso into a centrifuge. Foot arc at ±20° = 5 cm·sin(20°)
                                        // ≈ 17 mm, near the visible threshold but acceptable trade.
                                        val yawFull = yawMagDegOut.coerceIn(-20f, 20f)
                                        if (kotlin.math.abs(yawFull) > 0.001f) {
                                            val halfY = Math.toRadians(yawFull.toDouble()).toFloat() * 0.5f
                                            val sY = kotlin.math.sin(halfY); val cY = kotlin.math.cos(halfY)
                                            val oqx = qx; val oqy = qy; val oqz = qz; val oqw = qw
                                            qx = cY * oqx + sY * oqz
                                            qy = cY * oqy + sY * oqw
                                            qz = cY * oqz - sY * oqx
                                            qw = cY * oqw - sY * oqy
                                        }

                                        val ql = kotlin.math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw).coerceAtLeast(1e-5f)
                                        val tqx = qx / ql; val tqy = qy / ql; val tqz = qz / ql; val tqw = qw / ql

                                        // Foot anchor removed — world-Y yaw rotates through feet and
                                        // the pelvis-Z sway is off, so there's no longer a drift to
                                        // correct. Previous implementation (subtract XZ drift weighted
                                        // by footAnchorStrength) made sense when feet were pendulum'ing,
                                        // but is redundant now and added a damped "pull-back" that the
                                        // user read as the model "jelly-skating" in place.

                                        if (m.physicsAmount > 0.001f) {
                                            // Inertia lag — body chases target rather than snapping.
                                            // physicsAmount 0..1 maps to follow rate 1..0.25 per frame.
                                            val follow = (1f - m.physicsAmount * 0.75f).coerceIn(0.1f, 1f)
                                            m.posX += (px - m.posX) * follow
                                            m.posY += (py - m.posY) * follow
                                            m.posZ += (pz - m.posZ) * follow
                                            m.rotX += (tqx - m.rotX) * follow
                                            m.rotY += (tqy - m.rotY) * follow
                                            m.rotZ += (tqz - m.rotZ) * follow
                                            m.rotW += (tqw - m.rotW) * follow
                                            val nl = kotlin.math.sqrt(m.rotX*m.rotX + m.rotY*m.rotY + m.rotZ*m.rotZ + m.rotW*m.rotW).coerceAtLeast(1e-5f)
                                            m.rotX /= nl; m.rotY /= nl; m.rotZ /= nl; m.rotW /= nl
                                            // Squash & stretch tied to bob phase. Tier1.5: scale with
                                            // actual bob amplitude, not just gated on > 0. 1mm bob now
                                            // produces 1% squash, not 10%. 7cm bob = full squash.
                                            if (m.danceYMeters > 0f) {
                                                // -cos minimum at lookup 0 = max squash, which is where
                                                // the recentered bob now puts the deepest point. BASE-rate
                                                // lead — this lookup runs at danceYRate, not the
                                                // fill-adjusted yRate (review finding).
                                                val ph = ((ar.phaseAtSnap(snap, m.danceYRate) + m.danceYPhase + leadPhBobBase + phaseSlop) % 1f + 1f) % 1f
                                                val bobSig = -kotlin.math.cos(2.0 * Math.PI * ph).toFloat()
                                                val bobAmpScale = (m.danceYMeters / 0.05f).coerceIn(0f, 1f)
                                                val k = 0.1f * m.physicsAmount * effInt * proxMicro * bobAmpScale
                                                m.scaleMulY = 1f + k * bobSig
                                                m.scaleMulX = 1f - k * bobSig * 0.5f
                                                m.scaleMulZ = 1f - k * bobSig * 0.5f
                                            } else {
                                                m.scaleMulX = 1f; m.scaleMulY = 1f; m.scaleMulZ = 1f
                                            }

                                            // Trailing damped-spring "jiggle" — flesh rings after the
                                            // body moves. 2nd-order spring at ~2.5x beat freq, ζ≈0.18
                                            // (underdamped = fleshy, not gelatinous). Multiplies into
                                            // scale so you see 1-2 lingering ring cycles.
                                            val nowNsJ = System.nanoTime()
                                            val dtJ = if (m.lastDanceNanos == 0L) 1f / 72f else (nowNsJ - m.lastDanceNanos) / 1_000_000_000f
                                            m.lastDanceNanos = nowNsJ
                                            val safeDtJ = dtJ.coerceIn(0.001f, 0.05f)
                                            val bpmHz = if (hasTempo) snap.bpm / 60f else 2f
                                            val omegaJ = 2f * Math.PI.toFloat() * (2.5f * bpmHz)
                                            val zetaJ = 0.18f
                                            // Drive each axis's spring toward its current squash target
                                            val tgtY = m.scaleMulY - 1f
                                            val tgtX = m.scaleMulX - 1f
                                            val tgtZ = m.scaleMulZ - 1f
                                            val aY = omegaJ * omegaJ * (tgtY - m.jiggleY) - 2f * zetaJ * omegaJ * m.jiggleVelY
                                            val aX = omegaJ * omegaJ * (tgtX - m.jiggleX) - 2f * zetaJ * omegaJ * m.jiggleVelX
                                            val aZ = omegaJ * omegaJ * (tgtZ - m.jiggleZ) - 2f * zetaJ * omegaJ * m.jiggleVelZ
                                            m.jiggleVelY += aY * safeDtJ; m.jiggleY += m.jiggleVelY * safeDtJ
                                            m.jiggleVelX += aX * safeDtJ; m.jiggleX += m.jiggleVelX * safeDtJ
                                            m.jiggleVelZ += aZ * safeDtJ; m.jiggleZ += m.jiggleVelZ * safeDtJ
                                            // Jiggle gain scales with high-freq band (hats/snare drive
                                            // micro-jiggle) × proxMicro (closer = more flesh).
                                            val jiggleGain = 0.5f * m.physicsAmount * highGate * proxMicro
                                            m.scaleMulY *= 1f + jiggleGain * m.jiggleY
                                            m.scaleMulX *= 1f + jiggleGain * m.jiggleX
                                            m.scaleMulZ *= 1f + jiggleGain * m.jiggleZ
                                        } else {
                                            m.posX = px; m.posY = py; m.posZ = pz
                                            m.rotX = tqx; m.rotY = tqy; m.rotZ = tqz; m.rotW = tqw
                                            m.scaleMulX = 1f; m.scaleMulY = 1f; m.scaleMulZ = 1f
                                        }
                                        sceneManager.updateModelTransform(m)
                                    } else if (m.animHasA && m.animHasB) {
                                        val t = (rawFill * m.animResponse).coerceIn(0f, 1f)
                                        val s = t * t * (3f - 2f * t)  // smoothstep
                                        val a = m.animPoseA; val b = m.animPoseB
                                        m.posX = a[0] + (b[0] - a[0]) * s
                                        m.posY = a[1] + (b[1] - a[1]) * s
                                        m.posZ = a[2] + (b[2] - a[2]) * s
                                        val qx = a[3] + (b[3] - a[3]) * s
                                        val qy = a[4] + (b[4] - a[4]) * s
                                        val qz = a[5] + (b[5] - a[5]) * s
                                        val qw = a[6] + (b[6] - a[6]) * s
                                        val ql = kotlin.math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw).coerceAtLeast(1e-5f)
                                        m.rotX = qx / ql; m.rotY = qy / ql
                                        m.rotZ = qz / ql; m.rotW = qw / ql
                                        sceneManager.updateModelTransform(m)
                                    }
                                }
                            }

                            // ── D3: always-on idle layer (breath / weight sway / head life) ──
                            // Runs for EVERY skinned model, dancing or not, AFTER the dance
                            // pass (compose-ordering contract — see IdleLayer kdoc). Sits
                            // OUTSIDE the audioReactor gate on its own monotonic time base:
                            // a statue with no audio session still breathes.
                            run {
                                val idleNow = android.os.SystemClock.uptimeMillis()
                                if (monoClockBaseMs == 0L) monoClockBaseMs = idleNow
                                val idleTSec = (idleNow - monoClockBaseMs) / 1000f
                                val arIdle = audioReactor
                                val idleMl = if (arIdle != null && arIdle.enabled) arIdle.musicalLevel else 0f
                                for (i in models.indices) {
                                    val m = models[i]
                                    if (m.isPreview) continue
                                    val idleGpu = gr.getModel(m.gpuModelId)
                                    val idleSkel = idleGpu?.skeleton
                                    if (idleGpu?.isSkinned != true || idleSkel == null) continue
                                    // Mirrors the dance branch's run conditions: true only when
                                    // the dance loop REPLACE-wrote this model's joints this frame.
                                    val danced = arIdle != null && m.animHasBase && (m.danceYawDeg != 0f ||
                                        m.dancePitchDeg != 0f || m.danceYMeters != 0f)
                                    // Dance-stop falling edge: reset the WHOLE skeleton once. The
                                    // dance pass owns knees/thighs/neck/arms and only re-bases them
                                    // while running — without this, a stopped model froze bent-kneed
                                    // while IdleLayer re-bound Root, deleting the root drop that
                                    // balanced the bend → feet hovering cm above the real floor.
                                    if (!danced && m.idleWasDanced) idleSkel.resetAllToBind()
                                    m.idleWasDanced = danced
                                    com.ashairfoil.prism.scene.IdleLayer.apply(
                                        m, idleSkel, danced, idleTSec, idleNow, idleMl)
                                    // D10 — flesh spring bones, the LAST pose writer:
                                    // reacts to dance + idle + whole-model motion. No-op
                                    // on rigs without the Breast/Glute helper bones.
                                    val sps = m.springState ?: com.ashairfoil.prism.scene.SpringBoneLayer.State()
                                        .also { m.springState = it }
                                    com.ashairfoil.prism.scene.SpringBoneLayer.apply(
                                        sps, idleSkel, idleGpu.modelMatrix, idleNow)
                                }
                            }

                            // ── Yeet animation: flying deleted models ──
                            val now = System.nanoTime()
                            val yeetDt = if (lastRenderNanos == 0L) 1f / 72f else (now - lastRenderNanos) / 1_000_000_000f
                            lastRenderNanos = now
                            val safeYeetDt = yeetDt.coerceIn(0.001f, 0.1f)
                            synchronized(sceneManager.yeetingModelsLock) {
                            val yeetIter = yeetingModels.iterator()
                            while (yeetIter.hasNext()) {
                                val ym = yeetIter.next()
                                ym.timer += safeYeetDt
                                ym.velY -= 9.8f * safeYeetDt  // gravity
                                ym.posX += ym.velX * safeYeetDt
                                ym.posY += ym.velY * safeYeetDt
                                ym.posZ += ym.velZ * safeYeetDt
                                ym.spin += 720f * safeYeetDt  // spin wildly
                                ym.scale *= 0.92f  // shrink each frame

                                val gpuModel = gr.getModel(ym.gpuModelId)
                                if (gpuModel != null) {
                                    val s = ym.scale
                                    val angle = Math.toRadians(ym.spin.toDouble())
                                    val cy = kotlin.math.cos(angle).toFloat()
                                    val sy = kotlin.math.sin(angle).toFloat()
                                    val mm = gpuModel.modelMatrix
                                    if (mm.size >= 16) {
                                        mm[0] = cy * s;  mm[1] = 0f;    mm[2] = -sy * s; mm[3] = 0f
                                        mm[4] = 0f;      mm[5] = s;     mm[6] = 0f;      mm[7] = 0f
                                        mm[8] = sy * s;  mm[9] = 0f;    mm[10] = cy * s; mm[11] = 0f
                                        mm[12] = ym.posX; mm[13] = ym.posY; mm[14] = ym.posZ; mm[15] = 1f
                                    } else {
                                        gpuModel.modelMatrix = floatArrayOf(
                                            cy * s, 0f, -sy * s, 0f,
                                            0f, s, 0f, 0f,
                                            sy * s, 0f, cy * s, 0f,
                                            ym.posX, ym.posY, ym.posZ, 1f
                                        )
                                    }
                                }

                                if (ym.timer > 1.5f || ym.scale < 0.01f) {
                                    gr.removeModel(ym.gpuModelId)
                                    yeetIter.remove()
                                }
                            }
                            } // synchronized(yeetingModelsLock)

                            // Shadow map (once, before both eyes)
                            gr.renderShadowMap()

                            // Gizmo state for rendering
                            val selModel = models.getOrNull(selectedModelIndex)
                            val hasGizmoPose = selModel != null
                            if (selModel != null) {
                                gizmoPosBuf[0] = selModel.posX
                                gizmoPosBuf[1] = selModel.posY
                                gizmoPosBuf[2] = selModel.posZ
                                gizmoRotBuf[0] = selModel.rotX
                                gizmoRotBuf[1] = selModel.rotY
                                gizmoRotBuf[2] = selModel.rotZ
                                gizmoRotBuf[3] = selModel.rotW
                            }

                            val ih = inputHandler  // local ref for render state
                            val reactorSnap = reactor
                            val washActive = beatReactorEnabled && reactorSnap != null && beatWashAlpha > 0.005f
                            var washR = 0f; var washG = 0f; var washB = 0f; var washMode = 0
                            if (washActive && reactorSnap != null) {
                                val wc = reactorSnap.getBeatColor()
                                washR = wc[0]; washG = wc[1]; washB = wc[2]
                                washMode = reactorSnap.blendMode.ordinal
                            }

                            // Sync room edit highlight
                            gr.selectedPlaneIndex = if (roomEditMode) selectedPlaneIndex else -1

                            // Left eye: models -> ground/shadow -> gizmo -> laser
                            gr.renderEye(leftTexId, width, height, leftProj, leftView)
                            if (gridVisible || gr.shadowEnabled) gr.renderGrid(leftTexId, width, height, leftProj, leftView, gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(leftProj, leftView)
                            gr.renderPlaneVisualization(leftProj, leftView)
                            if (gizmoVisible && hasGizmoPose)
                                gr.renderGizmo(leftProj, leftView, gizmoPosBuf, gizmoRotBuf, ih.hoveredGizmoAxis)
                            gr.renderEmitter(leftProj, leftView, ih.emitterHovered)
                            // Tier2: front-facing markers on selected / armed models so the
                            // user can tell which side is the front without guessing.
                            val markMode = markAnatomyMode > 0
                            for ((mi, m) in models.withIndex()) {
                                if (m.isPreview) continue
                                val isSel = mi == selectedModelIndex
                                if (!isSel) continue  // show markers only on selected
                                val gmF = gr.getModel(m.gpuModelId) ?: continue
                                val hLocal = 2f * (gmF.boundsCenterY - gmF.boundsMinY)
                                val headYWorld = m.posY + (gmF.boundsMinY + hLocal * 0.92f) * m.scale

                                // ── Floor FORWARD arrow ──
                                // Always on for selected model so user can confirm visually
                                // which direction the rig considers "face forward" (Tripo
                                // convention = local +Z). Three crosses on the floor forming
                                // a yellow→gold line, biggest at the tip. Floor Y = gridHeight
                                // + 5mm so it hovers just above the shadow/grid.
                                run {
                                    val modelYaw = kotlin.math.atan2(
                                        2f * (m.rotW * m.rotY + m.rotX * m.rotZ),
                                        1f - 2f * (m.rotY * m.rotY + m.rotZ * m.rotZ)
                                    )
                                    // Tripo rigs appear to bake face-forward as local +X, not
                                    // +Z (user saw the +Z arrow going perpendicular to her
                                    // face). +X rotated by Y-yaw lands at (cos, -sin) in XZ.
                                    val fwdX = kotlin.math.cos(modelYaw)
                                    val fwdZ = -kotlin.math.sin(modelYaw)
                                    val floorY = (glesRenderer?.gridHeight ?: 0f) + 0.005f
                                    gr.renderFacingMarker(leftProj, leftView,
                                        m.posX, floorY, m.posZ, 0.08f, 1f, 0.9f, 0.15f)
                                    gr.renderFacingMarker(leftProj, leftView,
                                        m.posX + fwdX * 0.3f, floorY, m.posZ + fwdZ * 0.3f,
                                        0.12f, 1f, 0.85f, 0.1f)
                                    gr.renderFacingMarker(leftProj, leftView,
                                        m.posX + fwdX * 0.6f, floorY, m.posZ + fwdZ * 0.6f,
                                        0.22f, 1f, 1f, 0f)
                                }

                                // FACE dot: only rendered when face is explicitly marked.
                                // Positioned on her captured-face side, rotates with her body.
                                if (!m.markedFaceLocalYaw.isNaN()) {
                                    val faceWorldYaw = kotlin.math.atan2(
                                        2f * (m.rotW * m.rotY + m.rotX * m.rotZ),
                                        1f - 2f * (m.rotY * m.rotY + m.rotZ * m.rotZ)
                                    ) + m.markedFaceLocalYaw
                                    val faceX = m.posX + kotlin.math.sin(faceWorldYaw) * 0.35f
                                    val faceZ = m.posZ + kotlin.math.cos(faceWorldYaw) * 0.35f
                                    gr.renderFacingMarker(leftProj, leftView, faceX, headYWorld, faceZ, 0.25f, 1f, 0.2f, 0.6f)
                                }
                                if (m.markedHipFrac >= 0f) {
                                    val hY = m.posY + (gmF.boundsMinY + m.markedHipFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(leftProj, leftView, m.posX, hY, m.posZ, 0.08f, 1f, 0.5f, 0f)
                                }
                                if (m.markedShoulderFrac >= 0f) {
                                    val sY = m.posY + (gmF.boundsMinY + m.markedShoulderFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(leftProj, leftView, m.posX, sY, m.posZ, 0.08f, 0.3f, 0.9f, 1f)
                                }
                                if (m.markedKneeFrac >= 0f) {
                                    val kY = m.posY + (gmF.boundsMinY + m.markedKneeFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(leftProj, leftView, m.posX, kY, m.posZ, 0.08f, 0.8f, 0.3f, 1f)
                                }
                                if (markAnatomyMode > 0) {
                                    val rxm = ih.laserRayDir[0]; val rzm = ih.laserRayDir[2]
                                    val hmag = rxm*rxm + rzm*rzm
                                    if (hmag > 0.0001f) {
                                        val sr = ((m.posX - ih.laserHandPos[0]) * rxm + (m.posZ - ih.laserHandPos[2]) * rzm) / hmag
                                        val projY = (ih.laserHandPos[1] + ih.laserRayDir[1] * sr).coerceIn(
                                            m.posY + gmF.boundsMinY * m.scale,
                                            m.posY + (gmF.boundsMinY + hLocal) * m.scale)
                                        gr.renderFacingMarker(leftProj, leftView, m.posX, projY, m.posZ, 0.22f, 1f, 1f, 0.1f)
                                    }
                                }
                                // Glute L/R markers — render when user is adjusting so
                                // they can SEE where the ass cheeks are set. L = cyan,
                                // R = magenta. Positions are model-local, transformed
                                // to world via the model's full pose (pos + rot + scale).
                                val showGluteMarkers = characterMode ||
                                    markAnatomyMode == 4 || markAnatomyMode == 5 ||
                                    m.gluteBasePush > 0.0001f
                                if (showGluteMarkers) {
                                    val localL = floatArrayOf(
                                        gmF.gluteAPos[0] * m.scale,
                                        gmF.gluteAPos[1] * m.scale,
                                        gmF.gluteAPos[2] * m.scale)
                                    val localR = floatArrayOf(
                                        gmF.gluteBPos[0] * m.scale,
                                        gmF.gluteBPos[1] * m.scale,
                                        gmF.gluteBPos[2] * m.scale)
                                    val qModel = floatArrayOf(m.rotX, m.rotY, m.rotZ, m.rotW)
                                    val worldL = gr.rotateVecByQuat(localL, qModel)
                                    val worldR = gr.rotateVecByQuat(localR, qModel)
                                    val pulse = 0.85f + 0.15f * kotlin.math.sin(
                                        (System.currentTimeMillis() % 1000L) / 1000f * 2f * Math.PI.toFloat())
                                    val sz = 0.06f * pulse
                                    gr.renderFacingMarker(leftProj, leftView,
                                        m.posX + worldL[0], m.posY + worldL[1], m.posZ + worldL[2],
                                        sz, 0.2f, 0.95f, 1f)  // L = cyan
                                    gr.renderFacingMarker(leftProj, leftView,
                                        m.posX + worldR[0], m.posY + worldR[1], m.posZ + worldR[2],
                                        sz, 1f, 0.25f, 0.85f) // R = magenta
                                }
                            }
                            if (ih.laserActive) gr.renderLaser(leftTexId, width, height, leftProj, leftView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance,
                                dotScale = if (markMode) 0.04f else 0.01f,
                                dotColor = if (markMode) MARK_RETICLE_COLOR else LASER_DEFAULT_COLOR)
                            if (washActive) gr.renderColorWash(washR, washG, washB, beatWashAlpha, washMode)
                            gr.renderBloom(leftTexId, width, height)
                            gr.finishEyePass()

                            // Right eye
                            gr.renderEye(rightTexId, width, height, rightProj, rightView)
                            if (gridVisible || gr.shadowEnabled) gr.renderGrid(rightTexId, width, height, rightProj, rightView, gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(rightProj, rightView)
                            gr.renderPlaneVisualization(rightProj, rightView)
                            if (gizmoVisible && hasGizmoPose)
                                gr.renderGizmo(rightProj, rightView, gizmoPosBuf, gizmoRotBuf, ih.hoveredGizmoAxis)
                            gr.renderEmitter(rightProj, rightView, ih.emitterHovered)
                            for ((mi, m) in models.withIndex()) {
                                if (m.isPreview) continue
                                val isSel = mi == selectedModelIndex
                                if (!isSel) continue
                                val gmF = gr.getModel(m.gpuModelId) ?: continue
                                val hLocal = 2f * (gmF.boundsCenterY - gmF.boundsMinY)
                                val headYWorld = m.posY + (gmF.boundsMinY + hLocal * 0.92f) * m.scale

                                // Floor FORWARD arrow (see left-eye block for rationale).
                                run {
                                    val modelYaw = kotlin.math.atan2(
                                        2f * (m.rotW * m.rotY + m.rotX * m.rotZ),
                                        1f - 2f * (m.rotY * m.rotY + m.rotZ * m.rotZ)
                                    )
                                    // Tripo rigs appear to bake face-forward as local +X, not
                                    // +Z (user saw the +Z arrow going perpendicular to her
                                    // face). +X rotated by Y-yaw lands at (cos, -sin) in XZ.
                                    val fwdX = kotlin.math.cos(modelYaw)
                                    val fwdZ = -kotlin.math.sin(modelYaw)
                                    val floorY = (glesRenderer?.gridHeight ?: 0f) + 0.005f
                                    gr.renderFacingMarker(rightProj, rightView,
                                        m.posX, floorY, m.posZ, 0.08f, 1f, 0.9f, 0.15f)
                                    gr.renderFacingMarker(rightProj, rightView,
                                        m.posX + fwdX * 0.3f, floorY, m.posZ + fwdZ * 0.3f,
                                        0.12f, 1f, 0.85f, 0.1f)
                                    gr.renderFacingMarker(rightProj, rightView,
                                        m.posX + fwdX * 0.6f, floorY, m.posZ + fwdZ * 0.6f,
                                        0.22f, 1f, 1f, 0f)
                                }

                                if (!m.markedFaceLocalYaw.isNaN()) {
                                    val faceWorldYaw = kotlin.math.atan2(
                                        2f * (m.rotW * m.rotY + m.rotX * m.rotZ),
                                        1f - 2f * (m.rotY * m.rotY + m.rotZ * m.rotZ)
                                    ) + m.markedFaceLocalYaw
                                    val faceX = m.posX + kotlin.math.sin(faceWorldYaw) * 0.35f
                                    val faceZ = m.posZ + kotlin.math.cos(faceWorldYaw) * 0.35f
                                    gr.renderFacingMarker(rightProj, rightView, faceX, headYWorld, faceZ, 0.25f, 1f, 0.2f, 0.6f)
                                }
                                if (m.markedHipFrac >= 0f) {
                                    val hY = m.posY + (gmF.boundsMinY + m.markedHipFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(rightProj, rightView, m.posX, hY, m.posZ, 0.08f, 1f, 0.5f, 0f)
                                }
                                if (m.markedShoulderFrac >= 0f) {
                                    val sY = m.posY + (gmF.boundsMinY + m.markedShoulderFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(rightProj, rightView, m.posX, sY, m.posZ, 0.08f, 0.3f, 0.9f, 1f)
                                }
                                if (m.markedKneeFrac >= 0f) {
                                    val kY = m.posY + (gmF.boundsMinY + m.markedKneeFrac * hLocal) * m.scale
                                    gr.renderFacingMarker(rightProj, rightView, m.posX, kY, m.posZ, 0.08f, 0.8f, 0.3f, 1f)
                                }
                                if (markAnatomyMode > 0) {
                                    val rxm = ih.laserRayDir[0]; val rzm = ih.laserRayDir[2]
                                    val hmag = rxm*rxm + rzm*rzm
                                    if (hmag > 0.0001f) {
                                        val sr = ((m.posX - ih.laserHandPos[0]) * rxm + (m.posZ - ih.laserHandPos[2]) * rzm) / hmag
                                        val projY = (ih.laserHandPos[1] + ih.laserRayDir[1] * sr).coerceIn(
                                            m.posY + gmF.boundsMinY * m.scale,
                                            m.posY + (gmF.boundsMinY + hLocal) * m.scale)
                                        gr.renderFacingMarker(rightProj, rightView, m.posX, projY, m.posZ, 0.22f, 1f, 1f, 0.1f)
                                    }
                                }
                                // Glute L/R markers (see left-eye block for rationale).
                                val showGluteMarkersR = characterMode ||
                                    markAnatomyMode == 4 || markAnatomyMode == 5 ||
                                    m.gluteBasePush > 0.0001f
                                if (showGluteMarkersR) {
                                    val localL = floatArrayOf(
                                        gmF.gluteAPos[0] * m.scale,
                                        gmF.gluteAPos[1] * m.scale,
                                        gmF.gluteAPos[2] * m.scale)
                                    val localR = floatArrayOf(
                                        gmF.gluteBPos[0] * m.scale,
                                        gmF.gluteBPos[1] * m.scale,
                                        gmF.gluteBPos[2] * m.scale)
                                    val qModel = floatArrayOf(m.rotX, m.rotY, m.rotZ, m.rotW)
                                    val worldL = gr.rotateVecByQuat(localL, qModel)
                                    val worldR = gr.rotateVecByQuat(localR, qModel)
                                    val pulse = 0.85f + 0.15f * kotlin.math.sin(
                                        (System.currentTimeMillis() % 1000L) / 1000f * 2f * Math.PI.toFloat())
                                    val sz = 0.06f * pulse
                                    gr.renderFacingMarker(rightProj, rightView,
                                        m.posX + worldL[0], m.posY + worldL[1], m.posZ + worldL[2],
                                        sz, 0.2f, 0.95f, 1f)
                                    gr.renderFacingMarker(rightProj, rightView,
                                        m.posX + worldR[0], m.posY + worldR[1], m.posZ + worldR[2],
                                        sz, 1f, 0.25f, 0.85f)
                                }
                            }
                            if (ih.laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance,
                                dotScale = if (markMode) 0.04f else 0.01f,
                                dotColor = if (markMode) MARK_RETICLE_COLOR else LASER_DEFAULT_COLOR)
                            if (washActive) gr.renderColorWash(washR, washG, washB, beatWashAlpha, washMode)
                            gr.renderBloom(rightTexId, width, height)
                            gr.finishEyePass()
                        } catch (e: Exception) {
                            Log.e(TAG, "Render error", e)
                        }
                    }
                }

                // Sync compositor quad visibility + pose (always face camera)
                nativeSetUiVisible(menuVisible)
                if (menuVisible) {
                    val gr = glesRenderer
                    if (gr != null) {
                        // Recompute rotation every frame so panel always faces user
                        val dx = camPosX - gr.panelX
                        val dz = camPosZ - gr.panelZ
                        val yaw = kotlin.math.atan2(dx, dz)
                        panelRotX = 0f
                        panelRotY = kotlin.math.sin(yaw * 0.5f)
                        panelRotZ = 0f
                        panelRotW = kotlin.math.cos(yaw * 0.5f)

                        nativeSetUiPose(gr.panelX, gr.panelY, gr.panelZ,
                            panelRotX, panelRotY, panelRotZ, panelRotW)
                        nativeSetUiSize(gr.panelW, gr.panelH)
                    }
                }

                nativeSubmitFrame()

                nativePollInput(inputHandler.inputBuffer)
                inputHandler.handle()

                // ── Poll ALL XR sensors (every few frames to save CPU) ──
                sensorPollFrame++
                if (sensorPollFrame % 3 == 0) {
                    sensorPoller.poll(sensorPollFrame)
                }
                // Thermal auto-downgrade check (every 60 frames ≈ 0.5s @120Hz).
                // We only need to drain events on this cadence — events are
                // coalesced inside the native event loop and the latest levels
                // remain queryable between checks.
                if (sensorPollFrame % 60 == 0) {
                    updateThermalDowngrade()
                }

                // ── Persistent spatial anchors ──
                // Kick off resolve for all stored UUIDs once the anchor manager is ready.
                // Drain pending create/resolve completions every frame (cheap — deque pop).
                pollSpatialAnchors()
                // Build debug string (every 10 frames when HUD visible)
                if (sensorHudVisible && sensorPollFrame % 10 == 0) {
                    sensorDebugStr = sensorPoller.buildDebugString(sensorHub, xrLightEstimateAvailable, xrLightDebugStr)
                }
                // Update roomLux from SensorHub
                sensorHub?.let { roomLux = it.lightLux }
                // Refresh sensor HUD at ~4Hz when visible
                if (sensorHudVisible && menuVisible && sensorPollFrame % 18 == 0) {
                    uiNeedsRefresh = true
                }
            }
            Log.i(TAG, "Render loop stopped")
        }, "ChloeVR-RenderLoop").apply { start() }
    }

    /** Render HUD text panel to bitmap — delegates to UiRenderer */
    internal fun renderUiToBitmap() = uiRenderer.renderUiToBitmap()

    internal fun requestUiRender() {
        if (uiRenderQueued) return
        uiRenderQueued = true
        uiRenderHandler.post {
            try {
                renderUiToBitmap()
            } finally {
                uiRenderQueued = false
            }
        }
    }

    private val loopRunnable = object : Runnable {
        override fun run() {
            audioPlayer?.updateLoop()
            if (loopRunning) loopHandler.postDelayed(this, 120)
        }
    }

    private fun startLoopHandler() {
        if (loopRunning) return
        loopRunning = true
        loopHandler.post(loopRunnable)
    }

    internal fun stopLoopHandler() {
        loopRunning = false  // volatile — immediately visible to handler thread
        // removeCallbacks must run on the handler's own looper thread
        loopHandler.post { loopHandler.removeCallbacks(loopRunnable) }
    }

    private fun getAudioCacheFile(): File = File(cacheDir, "audio_cache.txt")

    private fun loadCachedAudioFiles() {
        val cacheFile = getAudioCacheFile()
        if (!cacheFile.exists()) return
        try {
            val cached = cacheFile.readLines()
                .asSequence()
                .map { File(it) }
                .filter { it.exists() && FilePicker.isAudioFile(it) }
                .sortedBy { it.nameWithoutExtension.lowercase() }
                .toList()
            if (cached.isNotEmpty()) {
                availableAudioFiles = cached
                Log.i(TAG, "Loaded ${cached.size} audio files from cache")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading audio cache: ${e.message}")
        }
    }

    internal fun requestAudioFileScan(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAudioScanStartMs < 1500L && availableAudioFiles.isNotEmpty()) {
            return
        }
        if (audioScanInProgress) {
            audioScanQueued = audioScanQueued || force
            return
        }
        lastAudioScanStartMs = now
        audioScanInProgress = true
        audioScanQueued = false
        Thread {
            try {
                val scanned = FilePicker.listVideoFiles(this)
                    .asSequence()
                    .filter { FilePicker.isAudioFile(it) }
                    .sortedBy { it.nameWithoutExtension.lowercase() }
                    .toList()
                availableAudioFiles = scanned
                try {
                    getAudioCacheFile().writeText(scanned.joinToString("\n") { it.absolutePath })
                } catch (e: Exception) { Log.w(TAG, "Audio cache write failed: ${e.message}") }
                Log.i(TAG, "Scanned ${scanned.size} audio files")
            } catch (e: Exception) {
                Log.w(TAG, "Audio scan failed: ${e.message}")
            } finally {
                audioScanInProgress = false
                uiNeedsRefresh = true
                requestUiRender()
                if (audioScanQueued) requestAudioFileScan(force = true)
            }
        }.start()
    }

    private fun buildModelPanelView(): android.view.View = uiRenderer.buildModelPanelView()

    internal fun showMessage(text: String) = uiRenderer.showMessage(text)

    // ── Lifecycle ──

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorHub?.start()
        // Resume audio player and reactor if they were playing before pause
        val ap = audioPlayer
        if (ap != null && audioPlayerMode) {
            ap.resume()
            val sid = ap.audioSessionId
            if (sid != 0 && beatReactorEnabled) audioReactor?.restart(sid)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        // Safety stop haptics when app goes to background
        if (hapticConnected) {
            val hm3 = hapticManager
            if (hm3 != null && hm3.isDualMotor) hm3.setDualIntensity(0, 0)
            else hm3?.setIntensity(0)
        }
        lastHapticIntensity = -1; lastHapticMotor1 = -1; lastHapticMotor2 = -1
        // Pause audio player so it doesn't keep playing with headset removed
        audioPlayer?.pause()
        audioReactor?.stop()
        sensorHub?.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "onWindowFocusChanged: hasFocus=$hasFocus")
        if (hasFocus) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Samsung Galaxy XR: B button fires both OpenXR action AND KEYCODE_BACK.
        // B handled entirely via OpenXR input polling — suppress Android back event
        // to prevent finish()/onPause() from killing audio and freezing the menu.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false

        // Stop A/B loop handler to prevent Runnable leak
        stopLoopHandler()

        // Auto-save current scene for next launch
        if (models.isNotEmpty()) {
            try {
                saveScene("_autosave")
                Log.i(TAG, "Auto-saved scene (${models.size} models)")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-save failed", e)
            }
        }

        // Persist floor height for next session
        val gr = glesRenderer
        if (gr != null) {
            getSharedPreferences("chloe_vr", MODE_PRIVATE).edit()
                .putFloat("grid_height", gr.gridHeight)
                .apply()
            Log.i(TAG, "Saved grid height: ${gr.gridHeight}")
        }

        // Safety stop: zero vibration before disconnect to prevent runaway motor
        val hmD = hapticManager
        if (hmD != null && hmD.isDualMotor) hmD.setDualIntensity(0, 0)
        else hmD?.setIntensity(0)
        hapticManager?.disconnect()
        hapticManager = null
        audioPlayer?.release(); audioPlayer = null
        audioReactor?.stop()
        // Full teardown: unregister sensors + quit handler thread
        sensorHub?.release()
        sensorHub = null
        renderThread?.join(3000)
        if (renderThread?.isAlive == true) {
            Log.w(TAG, "Render thread still alive after join timeout, interrupting")
            renderThread?.interrupt()
            renderThread?.join(1000)
        }
        // Release the scene-occlusion GL resources before tearing down the
        // EGL context (native shader + VBO live in this context).
        sceneOcclusion.shutdown()
        glesRenderer?.destroy()
        glesRenderer = null
        nativeRendererShutdown()
        uiRenderHandler.removeCallbacksAndMessages(null)
        uiRenderThread.quitSafely()
        super.onDestroy()
    }

    // ── JNI ──

    private external fun nativeRendererInit(activity: Activity): Boolean
    private external fun nativeGetEglContext(): Long
    private external fun nativeGetEglDisplay(): Long
    private external fun nativeGetSwapchainSize(): IntArray
    private external fun nativeWaitFrame(frameData: FloatArray): Boolean
    private external fun nativeForceFoveationOff(): Boolean
    private external fun nativeSubmitFrame()
    private external fun nativePollInput(outState: FloatArray): Boolean
    private external fun nativeRendererShutdown()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeMakeGLContextCurrent(): Boolean
    private external fun nativePollLightEstimate(outData: FloatArray): Boolean
    private external fun nativeSetLightShAmbient(ambient: Boolean)

    // Panel pose JNI
    private external fun nativeSetUiPose(px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float, rw: Float)
    private external fun nativeSetUiSize(w: Float, h: Float)
    private external fun nativeInitUiQuad(width: Int, height: Int): Boolean
    private external fun nativeAcquireUiImage(): Int
    private external fun nativeReleaseUiImage()
    private external fun nativeSetUiVisible(visible: Boolean)

    // Sensor JNI
    private external fun nativePollHandTracking(hand: Int, outData: FloatArray): Boolean
    private external fun nativePollEyeTracking(outData: FloatArray): Boolean
    private external fun nativePollFaceTracking(outData: FloatArray): Boolean
    private external fun nativePollPlanes(outData: FloatArray): Boolean
    private external fun nativePollPerfMetrics(outData: FloatArray): Boolean
    private external fun nativeGetSensorCapabilities(): Int
    private external fun nativeGetPassthroughState(): Int
    private external fun nativeHasFoveation(): Boolean
    private external fun nativeSetFoveationLevel(level: Int)
    private external fun nativeGetFoveationLevel(): Int
    private external fun nativeIsFocused(): Boolean
    private external fun nativeIsUsingStageSpace(): Boolean

    // Display refresh rate (frame-pacing lock)
    private external fun nativeGetAvailableRefreshRates(): FloatArray
    private external fun nativeGetDisplayRefreshRate(): Float
    private external fun nativeRequestDisplayRefreshRate(rateHz: Float): Boolean

    // Eye-tracked foveation
    private external fun nativeHasEyeTrackedFoveation(): Boolean
    private external fun nativeSetEyeTrackedFoveation(enabled: Boolean): Boolean
    private external fun nativeIsEyeTrackedFoveationEnabled(): Boolean

    // Thermal / perf-settings
    private external fun nativeHasPerfSettings(): Boolean
    private external fun nativeConsumeThermalEvent(): Int
    private external fun nativeGetThermalLevel(domain: Int): Int
    private external fun nativeGetRenderScale(): Float
    private external fun nativeSetRenderScale(scale: Float)

    // Public wrappers (internal) so other classes can invoke without triggering
    // Kotlin's `$app_debug` mangling on private external JNI symbols.
    internal fun jniGetAvailableRefreshRates(): FloatArray = nativeGetAvailableRefreshRates()
    internal fun jniGetDisplayRefreshRate(): Float = nativeGetDisplayRefreshRate()
    internal fun jniRequestDisplayRefreshRate(rate: Float): Boolean = nativeRequestDisplayRefreshRate(rate)
    internal fun jniHasEyeTrackedFoveation(): Boolean = nativeHasEyeTrackedFoveation()
    internal fun jniSetEyeTrackedFoveation(enabled: Boolean): Boolean = nativeSetEyeTrackedFoveation(enabled)
    internal fun jniHasPerfSettings(): Boolean = nativeHasPerfSettings()
    internal fun jniConsumeThermalEvent(): Int = nativeConsumeThermalEvent()
    internal fun jniGetThermalLevel(domain: Int): Int = nativeGetThermalLevel(domain)
    internal fun jniGetRenderScale(): Float = nativeGetRenderScale()
    internal fun jniSetRenderScale(scale: Float) = nativeSetRenderScale(scale)
}
