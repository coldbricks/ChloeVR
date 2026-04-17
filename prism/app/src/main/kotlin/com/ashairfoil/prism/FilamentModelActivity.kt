package com.ashairfoil.prism

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ashairfoil.prism.scene.InputHandler
import com.ashairfoil.prism.scene.SceneManager
import com.ashairfoil.prism.scene.SceneOcclusionManager
import com.ashairfoil.prism.scene.UiRenderer
import com.ashairfoil.prism.scene.XrSensorPoller
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
        "BeatReactor", "Foveation", "Tex Quality", "Show Planes", "Room Track")
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
    @Volatile internal var pendingModelLoad: File? = null  // queued for render thread (needs GL context)

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
    @Volatile internal var roomTrackingEnabled = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "onCreate: initializing Filament model viewer")

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
                        // Process pending scene loads
                        val pendingScene = pendingSceneLoad
                        if (pendingScene != null) {
                            pendingSceneLoad = null
                            loadScene(pendingScene)
                            uiNeedsRefresh = true
                            requestUiRender()
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
                                val c = reactor.getBeatColor()
                                val bass = reactor.bass

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

                                    // Shadows soften proportionally
                                    gr.shadowDarkness = beatBaseShadow * (1f - pct * 0.4f * bi)
                                }

                                // Bloom: direct from box fill
                                if (gr.bloomEnabled) {
                                    gr.bloomThreshold = (0.8f - pct * bi * 0.3f).coerceAtLeast(0.2f)
                                    gr.bloomIntensity = 0.3f + pct * bi * 0.5f
                                }

                                // Room wash: direct from box fill
                                beatWashAlpha = if (affectsRoom) {
                                    (intensity * 0.4f).coerceAtMost(0.85f)
                                } else 0f

                                // Haptic output: drives vibrator from audio signal
                                if (hapticEnabled && hapticConnected) {
                                    val hm = hapticManager
                                    if (hm != null && reactor.useVibesEngine) {
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
                                        // Split mode: box A → motor 1, box B → motor 2
                                        val m1 = (pct * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        val m2 = (reactor.box2FillPct * 20f + 0.5f).toInt().coerceIn(0, 20)
                                        if (m1 != lastHapticMotor1 || m2 != lastHapticMotor2) {
                                            lastHapticMotor1 = m1
                                            lastHapticMotor2 = m2
                                            hm.setDualIntensity(m1, m2)
                                        }
                                    } else if (hm != null) {
                                        // Unified: both motors follow box fill directly
                                        val hIntensity = (pct * 20f + 0.5f).toInt().coerceIn(0, 20)
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
                            if (ih.laserActive) gr.renderLaser(leftTexId, width, height, leftProj, leftView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance)
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
                            if (ih.laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance)
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
        runOnUiThread {
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
