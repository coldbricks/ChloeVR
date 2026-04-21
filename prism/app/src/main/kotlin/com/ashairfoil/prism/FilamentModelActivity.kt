package com.ashairfoil.prism

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ashairfoil.prism.scene.InputHandler
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
    @Volatile internal var autoAmbient = true
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

    private fun setRgb(dst: FloatArray, r: Float, g: Float, b: Float) {
        if (dst.size < 3) return
        dst[0] = r
        dst[1] = g
        dst[2] = b
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
            sceneOcclusion.enabled = sceneOcclusion.isExtensionSupported &&
                com.ashairfoil.prism.settings.SettingsManager.occlusionEnabled
            // Occlusion piggy-backs on plane detection: when the user toggles
            // "Room Track" in the menu (PARAM index 17), InputHandler flips
            // sensorPoller.planeDetectionEnabled, which drives onRawPlaneBufferUpdated,
            // which feeds sceneOcclusion. No auto-enable here — respect the
            // user's room-tracking choice.

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
        DancePreset("TWERK",        3f,  8, 0.5f,   1.5f,16, 0f,     0.015f, 8, 0.50f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.10f, 0.7f, 0.5f, 0.9f, 0.4f, 0.6f, 0.3f),
        DancePreset("SQUAT PULSE",  8f,  2, 0f,     4f,  4, 0.25f,   0.030f, 4, 0f,    SceneManager.DanceEase.SINE,   0.75f, 0.25f, 0.35f, 0.50f, 0.35f, 0.7f, 0.0f, 0.6f, 0.0f, 1.0f, 0.0f),
        // --- Mixamo-extracted presets (Phase 1) ---
        // Auto-generated by C:\tmp\mixamo_inspect\extract_preset.py from 33 Mixamo FBX
        // clips (31 unique after byte-identical dedup). Extractor refinements applied:
        //   - Euler unwrap on rotation curves (Salsa/Samba full-rotation spin moves)
        //   - BPM-band constraint 60-180 (rejects long-period dance envelopes that
        //     masqueraded as beat frequency on slow-bob clips: House, Snake, Twist)
        // All amplitude fields saturate the runtime schema caps (yawDeg<=15, pitchDeg
        // <=10, bobM<=0.04) for every non-trivial clip - Phase 1 schema ceiling confirmed
        // quantitatively. Phase 2 JointDriveLayer is where the real articulation lives.
        DancePreset("ARMS HIP HOP MIXAMO", 15.00f, 2, 0.270f, 10.00f, 1, 0.077f, 0.0400f, 4, 0.664f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.87f, 0.19f, 0.15f, 1.00f, 0.72f, 0.30f, 0.07f),
        DancePreset("BELLY MIXAMO", 15.00f, 2, 0.347f, 10.00f, 1, 0.393f, 0.0400f, 4, 0.631f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.96f, 0.51f, 0.15f, 1.00f, 0.11f, 1.00f, 0.10f),
        DancePreset("BELLYDANCING MIXAMO", 15.00f, 2, 0.072f, 10.00f, 1, 0.280f, 0.0400f, 4, 0.615f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 0.46f, 0.31f, 1.00f, 0.30f, 0.88f, 0.14f),
        DancePreset("BOOTY HIP HOP MIXAMO", 15.00f, 1, 0.281f, 5.38f, 1, 0.120f, 0.0400f, 4, 0.422f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.31f, 0.71f, 0.09f, 1.00f, 0.31f, 0.64f, 0.30f),
        DancePreset("BREAKDANCE UPROCK MIXAMO", 15.00f, 2, 0.472f, 10.00f, 1, 0.495f, 0.0400f, 4, 0.446f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.60f, 0.29f, 0.08f, 0.32f, 0.75f, 0.14f, 0.05f),
        DancePreset("CHICKEN MIXAMO", 15.00f, 4, 0.801f, 7.58f, 2, 0.873f, 0.0400f, 4, 0.331f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.84f, 0.80f, 0.25f, 1.00f, 0.19f, 1.00f, 0.28f),
        DancePreset("CROSSLEG FREEZE MIXAMO", 15.00f, 1, 0.682f, 10.00f, 1, 0.966f, 0.0400f, 4, 0.574f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 1.00f, 0.71f, 1.00f, 0.37f, 0.77f, 0.25f),
        DancePreset("DANCING 1 MIXAMO", 15.00f, 2, 0.007f, 10.00f, 1, 0.905f, 0.0400f, 4, 0.662f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.96f, 0.87f, 0.07f, 1.00f, 0.28f, 0.03f, 0.12f),
        DancePreset("TWERK MIXAMO", 15.00f, 2, 0.107f, 10.00f, 4, 0.519f, 0.0400f, 4, 0.657f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.41f, 0.84f, 0.14f, 0.30f, 0.06f, 0.12f, 0.06f),
        DancePreset("DANCING MIXAMO", 9.62f, 2, 0.010f, 10.00f, 2, 0.535f, 0.0400f, 4, 0.213f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.28f, 1.00f, 0.13f, 1.00f, 0.24f, 0.44f, 0.12f),
        DancePreset("HIP HOP 1 MIXAMO", 15.00f, 2, 0.839f, 10.00f, 2, 0.004f, 0.0400f, 4, 0.473f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.66f, 0.04f, 0.48f, 1.00f, 0.11f, 0.08f, 0.12f),
        DancePreset("HIP HOP 2 MIXAMO", 15.00f, 1, 0.146f, 10.00f, 2, 0.428f, 0.0400f, 4, 0.588f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.81f, 0.49f, 0.16f, 1.00f, 0.58f, 0.21f, 0.50f),
        DancePreset("HIP HOP 3 MIXAMO", 15.00f, 2, 0.976f, 10.00f, 4, 0.799f, 0.0400f, 4, 0.174f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.54f, 0.61f, 0.20f, 0.72f, 0.23f, 0.24f, 0.08f),
        DancePreset("HIP HOP 4 MIXAMO", 9.39f, 2, 0.184f, 6.25f, 2, 0.906f, 0.0321f, 4, 0.571f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.48f, 1.00f, 0.54f, 1.00f, 0.15f, 0.33f, 0.10f),
        DancePreset("HIP HOP 5 MIXAMO", 15.00f, 1, 0.972f, 10.00f, 2, 0.719f, 0.0400f, 4, 0.651f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.94f, 1.00f, 0.32f, 1.00f, 0.22f, 0.20f, 0.06f),
        DancePreset("HIP HOP 6 MIXAMO", 11.44f, 2, 0.082f, 10.00f, 8, 0.956f, 0.0348f, 4, 0.633f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.01f, 1.00f, 0.26f, 0.14f, 0.10f, 0.50f, 0.87f),
        DancePreset("HIP HOP 7 MIXAMO", 5.14f, 1, 0.878f, 2.92f, 1, 0.474f, 0.0079f, 4, 0.155f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.00f, 0.95f, 0.40f, 1.00f, 0.06f, 1.00f, 0.60f),
        DancePreset("HIP HOP 8 MIXAMO", 15.00f, 1, 0.253f, 10.00f, 1, 0.303f, 0.0400f, 4, 0.581f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.93f, 0.91f, 0.28f, 1.00f, 0.57f, 0.24f, 0.08f),
        DancePreset("HIP HOP 9 MIXAMO", 15.00f, 1, 0.210f, 10.00f, 4, 0.465f, 0.0400f, 4, 0.480f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.00f, 0.81f, 0.42f, 0.14f, 0.04f, 0.14f, 0.10f),
        DancePreset("HIP HOP MIXAMO", 15.00f, 2, 0.027f, 9.65f, 4, 0.124f, 0.0400f, 4, 0.182f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.20f, 1.00f, 0.32f, 0.97f, 0.72f, 1.00f, 0.20f),
        DancePreset("HOUSE MIXAMO", 15.00f, 1, 0.868f, 10.00f, 4, 0.451f, 0.0400f, 4, 0.187f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.90f, 0.21f, 0.06f, 1.00f, 0.32f, 0.34f, 0.30f),
        DancePreset("MACARENA MIXAMO", 15.00f, 1, 0.082f, 10.00f, 4, 0.997f, 0.0400f, 4, 0.582f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.49f, 1.00f, 0.38f, 0.15f, 0.08f, 0.05f, 0.15f),
        DancePreset("RUMBA MIXAMO", 15.00f, 4, 0.992f, 3.56f, 1, 0.119f, 0.0299f, 4, 0.564f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.48f, 0.21f, 0.08f, 1.00f, 0.78f, 1.00f, 0.15f),
        DancePreset("SALSA MIXAMO", 15.00f, 1, 0.257f, 10.00f, 2, 0.944f, 0.0314f, 4, 0.695f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.98f, 1.00f, 0.37f, 0.22f, 0.30f, 0.23f, 0.04f),
        DancePreset("SAMBA 1 MIXAMO", 15.00f, 1, 0.201f, 10.00f, 1, 0.451f, 0.0400f, 4, 0.334f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.98f, 1.00f, 0.07f, 1.00f, 0.43f, 0.14f, 0.40f),
        DancePreset("SAMBA MIXAMO", 15.00f, 1, 0.914f, 10.00f, 2, 0.430f, 0.0400f, 4, 0.025f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.63f, 0.65f, 0.26f, 1.00f, 0.23f, 0.07f, 0.01f),
        DancePreset("SHUFFLING MIXAMO", 15.00f, 2, 0.448f, 10.00f, 4, 0.332f, 0.0400f, 4, 0.072f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.87f, 0.90f, 0.02f, 0.55f, 0.29f, 0.25f, 0.57f),
        DancePreset("SNAKE HIP HOP MIXAMO", 15.00f, 2, 0.289f, 10.00f, 1, 0.240f, 0.0400f, 4, 0.400f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.44f, 0.41f, 0.13f, 1.00f, 0.41f, 0.38f, 0.08f),
        DancePreset("TWIST MIXAMO", 15.00f, 4, 0.681f, 10.00f, 1, 0.189f, 0.0400f, 4, 0.567f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.90f, 0.01f, 0.03f, 1.00f, 0.26f, 0.35f, 0.50f),
        DancePreset("WAVE HIP HOP MIXAMO", 3.81f, 8, 0.321f, 2.99f, 2, 0.975f, 0.0205f, 4, 0.553f, SceneManager.DanceEase.SINE, 0.90f, 0.95f, 0.95f, 0.50f, 0.22f, 0.15f, 0.22f, 1.00f, 0.21f, 0.67f, 0.25f),
        DancePreset("YMCA MIXAMO", 15.00f, 1, 0.183f, 10.00f, 1, 0.705f, 0.0400f, 4, 0.488f, SceneManager.DanceEase.LINEAR, 0.90f, 0.95f, 0.95f, 0.50f, 0.82f, 1.00f, 0.58f, 1.00f, 0.47f, 0.05f, 0.06f),
    )

    // ── Tier1-A: hoisted per-frame scratch — avoid closure/listOf alloc in hot loop ──
    // Audio snapshot reused each frame (read-once vs 6+ @Volatile loads per model).
    private val danceSnapshot = AudioReactor.FrameSnapshot()

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
     *  at dance-arm time. Saccade FSM reactivates automatically.
     *  Tier2: also captures the foot anchor so her heels stay planted while
     *  the body pivots around shoulders/chest. Computed from the base pose
     *  (the stance pose at arm time), not the current swayed pose. */
    internal fun armGazeCapture(m: SceneManager.PlacedModel) {
        val dxC = camPosX - m.posX
        val dzC = camPosZ - m.posZ
        m.gazeFaceCamAnchorRad = kotlin.math.atan2(dxC, dzC)
        m.gazeFaceCamHasCapture = true
        m.gazeCurrentBiasRad = 0f
        m.gazeState = 0
        m.gazeNextCheckMs = 0L

        // Foot anchor — rotate foot-local (0, minY, 0) through the base pose
        // quaternion and add to base position. That's her heel position right
        // now; the dance loop pulls her back toward it each frame.
        val gm = glesRenderer?.getModel(m.gpuModelId)
        if (gm != null && m.animHasBase) {
            val footY = gm.boundsMinY * m.scale
            val bx = m.animBasePose[0]; val bz = m.animBasePose[2]
            val bqx = m.animBasePose[3]; val bqy = m.animBasePose[4]
            val bqz = m.animBasePose[5]; val bqw = m.animBasePose[6]
            rotateVecQ(bqx, bqy, bqz, bqw, 0f, footY, 0f)
            m.footAnchorX = bx + pivotRotScratch[0]
            m.footAnchorZ = bz + pivotRotScratch[2]
            m.footAnchorCaptured = true
        }
    }

    /** Tier1-F: clear gaze capture on dance disarm so the model returns to
     *  its base pose and a future arm re-captures from a fresh heading. */
    internal fun clearGazeCapture(m: SceneManager.PlacedModel) {
        m.gazeFaceCamHasCapture = false
        m.gazeCurrentBiasRad = 0f
        m.gazeState = 0
    }

    /** Shuffle a model's dance parameters — now picks a named style preset so
     *  motion actually looks like a style (wind / bounce / grind / twerk…)
     *  rather than random parameter noise. Called by the SHUFFLE button and
     *  also by IMPROV every N bars. Preserves user-customised character/glute
     *  fields so IMPROV doesn't stomp manual tweaks on each bar boundary. */
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
        m.jointLayer = p.jointLayer
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
        m.jointLayer = p.jointLayer
        m.currentPresetName = p.name
        android.util.Log.i(TAG, "Dance preset (direct): ${p.name}")
    }

    internal val dancePresetCount: Int get() = dancePresets.size
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

        val warn = newLevel >= 25 // WARNING_EXT
        val impaired = newLevel >= 75 // IMPAIRED_EXT

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
                // Restore foveation to user-selected level
                if (savedFoveationLevel != foveationLevel) setFoveationLevelSafe(savedFoveationLevel)
            }
            1 -> {
                // Stage 1: disable effects (here: bloom) and bump foveation up
                // to high so GPU is freed immediately.
                savedFoveationLevel = foveationLevel
                gr?.bloomEnabled = false
                if (foveationLevel < 3) setFoveationLevelSafe(3)
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

                                    // Light direction: use manual azimuth/elevation (menu sliders)
                                    // XR direction estimation is unreliable — don't override user's setting
                                    // Auto-adjust intensity and color from XR only
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
                                    // Direction set by azimuth/elevation sliders (params 8/9)
                                    gr.updateLightDirFromAngles()

                                    // Smooth SH coefficients
                                    xrSHAvailable = shValid
                                    if (shValid) {
                                        System.arraycopy(lightEstimateBuffer, 14, rawSHBuf, 0, 27)
                                        for (i in 0 until 27) smoothSH[i] += (rawSHBuf[i] - smoothSH[i]) * aS
                                        System.arraycopy(smoothSH, 0, gr.shCoefficients, 0, 27)
                                        gr.useSH = true
                                    }

                                    // Debug string for HUD (only allocate when visible)
                                    if (sensorHudVisible) {
                                        xrLightDebugStr = "Amb(%.1f,%.1f,%.1f) Dir(%.1f,%.1f,%.1f) %s".format(
                                            ambR, ambG, ambB, dirX, dirY, dirZ,
                                            if (shValid) "SH:L2" else "SH:off")
                                    }
                                } else {
                                    // Fallback: lux sensor
                                    xrLightEstimateAvailable = false
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
                                val tSecFrame = snap.nowMs / 1000f
                                val phaseSlop = 0.05f * kotlin.math.sin((tSecFrame * 0.1f * 2.0 * Math.PI).toFloat())
                                for (i in models.indices) {
                                    val m = models[i]
                                    if (m.isPreview) continue
                                    if (inputHandler.grabbing && i == selectedModelIndex) continue

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
                                            if (useShaker) {
                                                val shakeRate = 8 // 8 subs/beat = 1/32 note
                                                val subBeatMs = beatPeriodMsG / shakeRate
                                                val subBeatNow = (snap.nowMs / subBeatMs).toLong()
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
                                                val subBeatNow = (snap.nowMs / subBeatMs).toLong()
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
                                                val beatNow = (snap.nowMs / beatPeriodMsG).toLong()
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
                                        val fillActive = choreoActive && snap.phase4bar > 0.75f
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

                                        val b = m.animBasePose
                                        var px = b[0]; var py = b[1]; var pz = b[2]
                                        var qx = b[3]; var qy = b[4]; var qz = b[5]; var qw = b[6]

                                        // Tier1-A: fbmMod + easeFor hoisted to class level — no per-frame
                                        // closure or listOf allocation (was ~720 alloc/sec at 4 models × 120Hz).
                                        val tSec = snap.nowMs / 1000f
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
                                        if (stanceGpu?.isSkinned == true && stanceSkel != null) {
                                            if (m.pelvisJointIdx == Int.MIN_VALUE) {
                                                m.pelvisJointIdx = stanceSkel.indexOf("Pelvis")
                                                m.waistJointIdx = stanceSkel.indexOf("Waist")
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
                                                val pelvisPh = ((ar.phaseAtSnap(snap, yRateS) + m.danceYPhase + phaseSlop) % 1f + 1f) % 1f
                                                val kSkewS = 0.5f - 0.2f * m.physicsAmount
                                                val p01 = if (pelvisPh < kSkewS) pelvisPh / kSkewS else 1f - (pelvisPh - kSkewS) / (1f - kSkewS)
                                                p01 * 4f * snap.musicalLevel
                                            } else 0f
                                            val pelvisThrustDeg = yawThrustDeg + bobThrustDeg

                                            // Pelvis gets a SMALL Z sway back for visible hip motion.
                                            // At 5° peak (40% of the base 12° sway signal), the
                                            // asymmetric L/R foot drift is ~7mm — imperceptible.
                                            // The full sway magnitude stays at the waist so upper
                                            // body reads dramatic while hips rotate subtly.
                                            val pelvisSwayZ = hipSwayDeg * 0.4f
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
                                            // Upper arm drop + KINETIC CHAIN arm sway. Z axis confirmed
                                            // as the abduction axis on this rig. Sway adds a phase-
                                            // lagged (15% behind pelvis) oscillation so arms drag and
                                            // swing THROUGH the hip motion rather than staying static.
                                            // L side gets -(drop - sway) and R gets +(drop + sway) so
                                            // positive sway lifts L / dips R = natural gait contra.
                                            val armDropZ = 60f
                                            val armSwayAmp = if (yawDrivenStance) {
                                                val armLagPh = ((yawPh - 0.15f) % 1f + 1f) % 1f
                                                val armLagSine = kotlin.math.sin(2f * Math.PI.toFloat() * armLagPh)
                                                // Bumped 20° → 35° per user: "can we bring back
                                                // the sexy... arm movements". Bigger swing-through
                                                // on the back-half of each cycle; upper arms now
                                                // visibly swoop through the drop range.
                                                armLagSine * 35f * gyrateScale * snap.musicalLevel
                                            } else 0f
                                            if (m.upperArmLJointIdx >= 0) stanceSkel.setJointEulerZ(m.upperArmLJointIdx, -(armDropZ - armSwayAmp))
                                            if (m.upperArmRJointIdx >= 0) stanceSkel.setJointEulerZ(m.upperArmRJointIdx, armDropZ + armSwayAmp)
                                            // Forearm elbow with RANDOM STRAIGHTEN drift.
                                            // Each arm has its own slow fBm signal (uncorrelated
                                            // phases via seed offset). When signal peaks past ~0.3,
                                            // the arm extends — elbow straightens from 45° bend
                                            // down toward 0°. Smoothstep makes the transition feel
                                            // gradual ("she's thinking about it"). L and R
                                            // independent so she can have one arm out while the
                                            // other rests — natural variety, not metronomic.
                                            val elbowBendX = 45f
                                            val lArmNoise = fbmMod(tSec, seed, 7.7f)
                                            val rArmNoise = fbmMod(tSec, seed, 9.1f)
                                            val lStraighten = kotlin.math.max(0f,
                                                (lArmNoise - 0.3f) / 0.4f).coerceIn(0f, 1f)
                                            val rStraighten = kotlin.math.max(0f,
                                                (rArmNoise - 0.3f) / 0.4f).coerceIn(0f, 1f)
                                            // At full straighten, elbow bend drops to 5° (slightly
                                            // bent still — fully straight looks unnatural).
                                            val lElbowDeg = elbowBendX * (1f - lStraighten) + 5f * lStraighten
                                            val rElbowDeg = elbowBendX * (1f - rStraighten) + 5f * rStraighten
                                            if (m.forearmLJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmLJointIdx, lElbowDeg)
                                            if (m.forearmRJointIdx >= 0) stanceSkel.setJointEulerX(m.forearmRJointIdx, rElbowDeg)
                                        }

                                        val fbmYaw = fbmMod(tSec, seed, 0f)
                                        val fbmPitch = fbmMod(tSec, seed, 1.7f)
                                        val fbmBob = fbmMod(tSec, seed, 3.1f)

                                        // Anticipation: lead yaw phase by ~45ms so motion feels "danced"
                                        // instead of "on the grid". Scales with physicsAmount so clean
                                        // motion at 0 and full swagger at 1.
                                        val yawCycleMs = if (hasTempo) (60000f / snap.bpm) * 4f / yawRate.coerceAtLeast(1) else 500f
                                        val leadPh = -(45f * m.physicsAmount) / yawCycleMs

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
                                        val pivotYPitch = if (m.pivotEnabled) (pvMinY + fPitch * pvHeight) * m.scale else 0f
                                        val pivotYRoll = if (m.pivotEnabled) (pvMinY + fRoll * pvHeight) * m.scale else 0f
                                        val pivotYCtrRoll = if (m.pivotEnabled) (pvMinY + fCtr * pvHeight) * m.scale else 0f

                                        var yawSig = 0f
                                        // YAW is now applied as a SPINE01 Y-joint rotation rather than a
                                        // world-space quaternion twist. Why:
                                        //   The previous impl rotated the whole model's quaternion by
                                        //   yaw*sin(t). On a Bottom-Center-Pivot-OFF rig (model origin
                                        //   at pelvis, feet ~1m below) that world rotation pivots
                                        //   everything — including the feet — around pelvis height.
                                        //   Feet sweep arcs of radius ≈ foot-to-pelvis-distance × sin(θ)
                                        //   = 1m × sin(15°) = 26cm. Add bob's vertical drop and the
                                        //   combined motion reads as "feet bobbing across the floor" /
                                        //   pendulum.
                                        //
                                        //   Applying yaw at Spine01 instead (Spine01 sits above the
                                        //   pelvis in the hierarchy; thighs / legs are siblings of the
                                        //   subtree, not children) rotates ONLY the torso / head / arms.
                                        //   Hips don't yaw, thighs don't yaw, feet stay planted.
                                        //
                                        //   We still compute yawSig here so downstream blocks that use
                                        //   it (counter-roll, arm sway) keep their beat shaping. The
                                        //   actual joint write moves to the spine-twist block below.
                                        if (m.danceYawDeg != 0f) {
                                            val ph = ((ar.phaseAtSnap(snap, yawRate) + m.danceYawPhase - 0.09f + leadPh + phaseSlop) % 1f + 1f) % 1f
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
                                                // Spine01 carries ALL the torso twist (0.3 + 0.15 that
                                                // used to be spread across both spines). Spine02 carries
                                                // ONLY the counter-roll Z. Each joint = one axis =
                                                // zero gimbal stack.
                                                if (m.spine01JointIdx >= 0) skelYaw.setJointEulerY(m.spine01JointIdx, -yawMagDeg * 0.45f)
                                                if (m.spine02JointIdx >= 0) skelYaw.setJointEulerZ(m.spine02JointIdx, counterRollDeg)
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
                                        // PITCH around LOCAL X (right-multiply q * qX) — so pitch tilts
                                        // the model in its OWN forward-back axis regardless of base facing.
                                        // Phase-shear -0.04 (lower back between hips & shoulders).
                                        // Tier2-pivot: if pivotEnabled, pitch pivots at preset-specified
                                        // height (e.g. 0.95 head for TWERK → hips pop, head stays fixed).
                                        if (m.dancePitchDeg != 0f) {
                                            val ph = ((ar.phaseAtSnap(snap, pitchRate) + m.dancePitchPhase - 0.04f + phaseSlop) % 1f + 1f) % 1f
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
                                            val ph = ((ar.phaseAtSnap(snap, yRate) + m.danceYPhase + phaseSlop) % 1f + 1f) % 1f
                                            val kSkew = 0.5f - 0.2f * m.physicsAmount  // 0.5 = symmetric, 0.3 = fast-down
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

                                                    // Recompute the SMALL pelvis Z sway here so we can
                                                    // apply the matching Z counter on the thighs in
                                                    // squat mode. Must match the stance block exactly
                                                    // or pelvis rotation leaks into thighs during bob.
                                                    val pelvisSwayZBob = if (m.danceYawDeg > 0.001f) {
                                                        val yawPh = ((ar.phaseAtSnap(snap, yawRate) + m.danceYawPhase + phaseSlop) % 1f + 1f) % 1f
                                                        val raw1 = kotlin.math.sin(2f * Math.PI.toFloat() * yawPh)
                                                        val a = kotlin.math.abs(raw1).toDouble()
                                                        val popped = Math.pow(a, 0.55).toFloat().let { if (raw1 >= 0f) it else -it }
                                                        val gScale = (m.danceYawDeg / 15f).coerceIn(0f, 1f)
                                                        popped * 12f * gScale * snap.musicalLevel * 0.4f
                                                    } else 0f

                                                    val kneeDeg = -squat * 40f
                                                    val thighDeg = squat * 20f
                                                    skel.setJointEulerX(m.kneeLJointIdx, kneeDeg)
                                                    skel.setJointEulerX(m.kneeRJointIdx, kneeDeg)
                                                    if (m.thighLJointIdx >= 0) {
                                                        skel.setJointEulerXYZ(m.thighLJointIdx, thighDeg, 0f, -pelvisSwayZBob)
                                                    }
                                                    if (m.thighRJointIdx >= 0) {
                                                        skel.setJointEulerXYZ(m.thighRJointIdx, thighDeg, 0f, -pelvisSwayZBob)
                                                    }
                                                    // Root Y drop for dance-squat only. Arch no longer drops
                                                    // root (arch is upper-body lordosis, doesn't need leg
                                                    // compensation). 4cm is tolerable for deep squat.
                                                    if (m.rootJointIdx >= 0) {
                                                        skel.resetJointToBind(m.rootJointIdx)
                                                        val dropMeters = squat * 0.04f
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

                                        // ── Phase 2: JointDriveLayer articulation pass ──
                                        // Runs AFTER all Tier 4 rigid-body joint writes so the layer's spine/
                                        // clavicle/arm/head drives land on top. Per-joint ownership model:
                                        // Tier 4 keeps pelvis/waist/thigh/calf (biomechanics-coded); the layer
                                        // owns the upper body where Mixamo-extracted articulation lives.
                                        // Null layer = pure Tier 4 behaviour (all 38 hand-tuned presets today).
                                        val layer = m.jointLayer
                                        if (layer != null) {
                                            val gpuL = glesRenderer?.getModel(m.gpuModelId)
                                            val skelL = gpuL?.skeleton
                                            if (gpuL?.isSkinned == true && skelL != null) {
                                                // phaseAt(1) = one cycle per measure. JointDrive.rateCyclesPerMeasure
                                                // scales from there (rate=4 → one cycle per beat in 4/4).
                                                val measurePhase = ar.phaseAt(1)
                                                layer.evaluate(measurePhase, skelL, effInt * ampGain)
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

                                        // Tier1-G: breath bob — 3mm @ 0.25Hz, per-model seed decorrelation.
                                        val breathBobM = 0.003f * proxMicro *
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

                                        // Tier1-G: breath pitch — 0.3° @ 0.3Hz, always on (local-frame).
                                        val breathPitchDeg = 0.3f * proxMicro *
                                            kotlin.math.sin(((tSec * 0.3f + seed * 1.3f) * 2.0 * Math.PI).toFloat())
                                        if (breathPitchDeg != 0f) {
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

                                        // ── ALIVE: impact kick on each detected beat ──
                                        // Skip beat capture during freeze — the held-gaze moment is more
                                        // powerful than a syncopated twitch.
                                        if (snap.beatCounter > m.lastBeatSeen && m.physicsAmount > 0.001f) {
                                            m.lastBeatSeen = snap.beatCounter
                                            m.impactKickStartMs = snap.nowMs
                                            m.impactKickAxis = (snap.beatCounter % 3).toInt()
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

                                        val ql = kotlin.math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw).coerceAtLeast(1e-5f)
                                        val tqx = qx / ql; val tqy = qy / ql; val tqz = qz / ql; val tqw = qw / ql

                                        // ── Tier2-footanchor: keep the heels planted ──
                                        // After every rotation + pivot correction lands, compute where her
                                        // feet actually are in world and subtract the drift from (px, pz).
                                        // Result: torso pivots around fixed heels, not "dancing on ice."
                                        // Y is left alone so bob + breath + squash still work freely.
                                        if (m.footAnchorCaptured && m.footAnchorStrength > 0.001f) {
                                            val footLocalY = pvMinY * m.scale
                                            rotateVecQ(tqx, tqy, tqz, tqw, 0f, footLocalY, 0f)
                                            val footWorldX = px + pivotRotScratch[0]
                                            val footWorldZ = pz + pivotRotScratch[2]
                                            val driftX = footWorldX - m.footAnchorX
                                            val driftZ = footWorldZ - m.footAnchorZ
                                            px -= driftX * m.footAnchorStrength
                                            pz -= driftZ * m.footAnchorStrength
                                        }

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
                                                val ph = ((ar.phaseAtSnap(snap, m.danceYRate) + m.danceYPhase + phaseSlop) % 1f + 1f) % 1f
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
    private external fun nativeSubmitFrame()
    private external fun nativePollInput(outState: FloatArray): Boolean
    private external fun nativeRendererShutdown()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeMakeGLContextCurrent(): Boolean
    private external fun nativePollLightEstimate(outData: FloatArray): Boolean

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
