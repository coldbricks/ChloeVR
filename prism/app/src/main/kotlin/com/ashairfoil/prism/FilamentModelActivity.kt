package com.ashairfoil.prism

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.ashairfoil.prism.scene.InputHandler
import com.ashairfoil.prism.scene.SceneManager
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
        private const val REQUEST_ADD_MODEL = 1001
    }

    // Rendering
    internal var glesRenderer: GlesModelRenderer? = null
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

    // Legacy sensor compat (SensorHub now owns these)
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    @Volatile internal var roomLux = 200f  // default indoor
    @Volatile internal var autoAmbient = true
    private val lightEstimateBuffer = FloatArray(41)
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

    // ── XR Sensor Poller (hand/eye/face tracking, planes, perf, passthrough) ──
    internal lateinit var sensorPoller: XrSensorPoller

    // Sensor debug HUD
    @Volatile internal var sensorHudVisible = false
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
        "BeatReactor", "Foveation", "Tex Quality", "Show Planes")
    // Slider ranges for continuous params 0-12. Toggles (13-16) have no range.
    internal val PARAM_RANGES = arrayOf(
        0f to 1f, 0.05f to 1f, -5f to 5f, 0.85f to 1.15f, 0f to 3f,
        0f to 10f, 0f to 5f, 0f to 5f, 0f to 360f, 5f to 90f,
        0f to 1f, 0.5f to 5f, 2f to 30f
    )
    // 0=auto (adaptive budget), 1=4096 (original), 2=2048, 3=1024
    internal var textureQuality = 0
    internal var uiNeedsRefresh = false
    private var lastBCloseTime = 0L

    // Draggable panel state
    private var panelPosX = 0f; private var panelPosY = 1.6f; private var panelPosZ = -1.2f
    internal var panelRotX = 0f; internal var panelRotY = 0f; internal var panelRotZ = 0f; internal var panelRotW = 1f
    internal val PANEL_WIDTH = 0.9f   // meters in world space (base size)
    internal val PANEL_HEIGHT = 1.0f
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
    private var climaxAccum = 0f       // 0..1, builds over sustained audio
    private var climaxPeak = 0f        // short-term peak tracker (fast attack, slow decay)
    private var lastBass = 0f          // previous frame bass for slam detection
    private var bassSlam = 0f          // 0..1, spikes on bass transient, fast decay
    private var breathPhase = 0f       // sine phase for idle breathing pulse
    private var hapticLeadBuffer = 0f  // buffered pct sent 1 frame early
    private var edgeSustain = 0f       // time spent near peak (seconds)
    private var lastClimaxTime = 0L    // for dt calculation
    private var bassSlamCooldown = 0f  // prevents slam retriggering too fast
    // Base values captured when BeatReactor turns on — beat modulates FROM these
    internal var beatBaseStored = false
    private var beatBaseAmbient = 1f
    private var beatBaseLight = 2f
    private var beatBaseFill = 0.5f
    private var beatBaseShadow = 0.7f
    private var beatWashAlpha = 0f
    @Volatile internal var foveationLevel = 0  // 0=off, 1=low, 2=med, 3=high
    internal var beatToggleLatch = false
    internal var foveationToggleLatch = false
    internal var planeVisToggleLatch = false

    // ── Audio Player ──
    internal var audioPlayer: AudioPlayer? = null
    @Volatile internal var audioPlayerMode = false
    @Volatile internal var audioPickerMode = false
    internal var availableAudioFiles: List<File> = emptyList()
    internal var audioPickerScrollOffset = 0

    // BeatReactor settings sub-menu
    @Volatile internal var beatSettingsMode = false
    @Volatile internal var beatCursorX = -1f  // laser position on panel (bitmap coords)
    @Volatile internal var beatCursorY = -1f

    // Haptic device (Lovense via BLE)
    internal var hapticManager: com.ashairfoil.prism.haptics.BleDeviceManager? = null
    @Volatile internal var hapticConnected = false
    @Volatile internal var hapticEnabled = false

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

        // Legacy sensor compat (light sensor also available via SensorHub)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Ambient light sensor registered (legacy)")
        }

        // Initialize audio reactor (BeatReactor-style)
        audioReactor = AudioReactor()

        // Load cached GLB file list, then rescan in background
        val cacheFile = File(cacheDir, "glb_cache.txt")
        if (cacheFile.exists()) {
            try {
                val cached = cacheFile.readLines().map { File(it) }.filter { it.exists() }
                if (cached.isNotEmpty()) {
                    availableGlbFiles = cached
                    Log.i(TAG, "Loaded ${cached.size} GLB files from cache")
                }
            } catch (_: Exception) {}
        }
        // Rescan in background and update cache
        Thread {
            val glbFiles = FilePicker.listVideoFiles(this).filter { FilePicker.isModelFile(it) }
            availableGlbFiles = glbFiles
            try { cacheFile.writeText(glbFiles.joinToString("\n") { it.absolutePath }) } catch (_: Exception) {}
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
                    gr.shadowPlanes = planes
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

            // Foveated rendering disabled for now (investigating crash)
            // if (nativeHasFoveation()) {
            //     nativeSetFoveationLevel(2)
            //     foveationLevel = 2
            //     Log.i(TAG, "Foveated rendering enabled (medium)")
            // }

            val renderer = GlesModelRenderer()
            if (!renderer.init()) {
                Log.e(TAG, "Failed to initialize GLES renderer")
                runOnUiThread { showMessage("Failed to initialize renderer.\nPress Back to return.") }
                return@Thread
            }
            glesRenderer = renderer
            Log.i(TAG, "GLES renderer initialized")

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
                runOnUiThread { renderUiToBitmap() }
            }
        }.start()
    }

    private fun loadModel(file: File, offsetIndex: Int = 0) {
        sceneManager.loadModel(file, offsetIndex, detectedFloorY)
    }

    internal fun updateModelTransform(model: SceneManager.PlacedModel) {
        sceneManager.updateModelTransform(model)
    }

    internal fun reloadAllModels() {
        sceneManager.reloadAllModels(textureQuality)
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
                    val leftProj = frameData.copyOfRange(3, 19)
                    val rightProj = frameData.copyOfRange(19, 35)
                    val leftView = frameData.copyOfRange(35, 51)
                    val rightView = frameData.copyOfRange(51, 67)

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
                            runOnUiThread { renderUiToBitmap() }
                        }
                        // Process pending scene loads
                        val pendingScene = pendingSceneLoad
                        if (pendingScene != null) {
                            pendingSceneLoad = null
                            loadScene(pendingScene)
                            uiNeedsRefresh = true
                            runOnUiThread { renderUiToBitmap() }
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

                                    val tcc = floatArrayOf(ccR.coerceIn(0.5f, 1.5f), ccG.coerceIn(0.5f, 1.5f), ccB.coerceIn(0.5f, 1.5f))
                                    for (i in 0..2) smoothAmbientColor[i] += (tcc[i] - smoothAmbientColor[i]) * aI
                                    gr.ambientColor = smoothAmbientColor.copyOf()

                                    // Light direction: use manual azimuth/elevation (menu sliders)
                                    // XR direction estimation is unreliable — don't override user's setting
                                    // Auto-adjust intensity and color from XR only
                                    val dirScale = (dirR + dirG + dirB) / 3f
                                    if (dirScale > 0.01f) {
                                        val targetInt = (dirScale * 3f).coerceIn(0.5f, 5f)
                                        smoothLightIntensity += (targetInt - smoothLightIntensity) * aI
                                        gr.lightIntensity = smoothLightIntensity

                                        val maxDir = maxOf(dirR, dirG, dirB).coerceAtLeast(0.01f)
                                        val tColor = floatArrayOf(dirR / maxDir, dirG / maxDir, dirB / maxDir)
                                        for (i in 0..2) smoothLightColor[i] += (tColor[i] - smoothLightColor[i]) * aI
                                        gr.lightColor = smoothLightColor.copyOf()
                                    }
                                    // Direction set by azimuth/elevation sliders (params 8/9)
                                    gr.updateLightDirFromAngles()

                                    // Smooth SH coefficients
                                    xrSHAvailable = shValid
                                    if (shValid) {
                                        val rawSH = lightEstimateBuffer.copyOfRange(14, 41)
                                        for (i in 0 until 27) smoothSH[i] += (rawSH[i] - smoothSH[i]) * aS
                                        gr.shCoefficients = smoothSH.copyOf()
                                        gr.useSH = true
                                    }

                                    // Debug string for HUD
                                    xrLightDebugStr = "Amb(%.1f,%.1f,%.1f) Dir(%.1f,%.1f,%.1f) %s".format(
                                        ambR, ambG, ambB, dirX, dirY, dirZ,
                                        if (shValid) "SH:L2" else "SH:off")
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
                                    gr.ambientColor = floatArrayOf(1f + warmth * 0.15f, 1f - warmth * 0.02f, 1f - warmth * 0.12f)
                                    gr.lightColor = floatArrayOf(1f + warmth * 0.08f, 0.95f + warmth * 0.02f, 0.9f - warmth * 0.05f)
                                }
                            }

                            // ── BeatReactor + Climax Engine ──
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
                                    lastClimaxTime = System.nanoTime()
                                    climaxAccum = 0f; climaxPeak = 0f; bassSlam = 0f
                                    edgeSustain = 0f; breathPhase = 0f
                                }

                                val now = System.nanoTime()
                                val dt = ((now - lastClimaxTime) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                                lastClimaxTime = now

                                val pct = reactor.boxFillPct
                                val bi = beatIntensity
                                val c = reactor.getBeatColor()
                                val bass = reactor.bass

                                // ── Climax Accumulator ──
                                // Slowly builds when audio is active, decays when quiet.
                                // Creates an escalating intensity curve over the duration of a track.
                                val buildRate = 0.03f  // takes ~33s of sustained audio to reach 1.0
                                val decayRate = 0.008f // slow bleed when quiet
                                if (pct > 0.15f) {
                                    climaxAccum = (climaxAccum + pct * buildRate * dt * bi).coerceAtMost(1f)
                                } else {
                                    climaxAccum = (climaxAccum - decayRate * dt).coerceAtLeast(0f)
                                }

                                // ── Peak Tracker (fast attack, slow release) ──
                                if (pct > climaxPeak) {
                                    climaxPeak = climaxPeak + (pct - climaxPeak) * 0.5f // fast attack
                                } else {
                                    climaxPeak = climaxPeak - (climaxPeak - pct) * 2f * dt // slow decay
                                }
                                climaxPeak = climaxPeak.coerceIn(0f, 1f)

                                // ── Bass Slam Detector ──
                                // Detects sudden bass transients (kick drums, drops)
                                bassSlamCooldown = (bassSlamCooldown - dt).coerceAtLeast(0f)
                                val bassDelta = bass - lastBass
                                if (bassDelta > 0.15f && bass > 0.3f && bassSlamCooldown <= 0f) {
                                    // SLAM! Scale by how much we've built up (louder when climax is higher)
                                    bassSlam = (0.6f + climaxAccum * 0.4f).coerceAtMost(1f)
                                    bassSlamCooldown = 0.08f // 80ms cooldown prevents multi-trigger
                                }
                                bassSlam = (bassSlam * (1f - 12f * dt)).coerceAtLeast(0f) // fast ~80ms decay
                                lastBass = bass

                                // ── Edge Sustain ──
                                // Tracks how long we've been near the peak — tension builder
                                if (pct > 0.7f && climaxAccum > 0.4f) {
                                    edgeSustain = (edgeSustain + dt).coerceAtMost(8f)
                                } else {
                                    edgeSustain = (edgeSustain - dt * 0.5f).coerceAtLeast(0f)
                                }
                                val edgeMult = 1f + edgeSustain * 0.15f // up to 2.2x after 8s of edge

                                // ── Breathing Pulse (idle/low moments) ──
                                breathPhase += dt * 1.2f // ~0.2 Hz breathing cycle
                                val breathVal = if (pct < 0.1f) {
                                    (kotlin.math.sin(breathPhase.toDouble()).toFloat() * 0.5f + 0.5f) * 0.15f
                                } else 0f

                                // ── Composite intensity ──
                                // Base beat + climax buildup escalation + slam spike + breathing
                                val intensity = pct * bi * (1f + climaxAccum * 1.5f) * edgeMult
                                val slamBoost = bassSlam * bi * 3f

                                val affectsModels = reactor.washScope != AudioReactor.WashScope.ROOM
                                val affectsRoom = reactor.washScope != AudioReactor.WashScope.MODELS

                                if (affectsModels) {
                                    // Ambient: warm glow that builds with climax
                                    gr.ambientIntensity = beatBaseAmbient + intensity * 2.5f + breathVal * 0.5f
                                    gr.ambientColor = floatArrayOf(
                                        c[0] * pct * bi * 1.5f + (1f - pct) * 0.3f + breathVal * 0.2f,
                                        c[1] * pct * bi * 1.5f + (1f - pct) * 0.3f + breathVal * 0.1f,
                                        c[2] * pct * bi * 1.5f + (1f - pct) * 0.3f
                                    )

                                    // Main light: punches hard on slam, builds with climax
                                    gr.lightIntensity = beatBaseLight + intensity * 4f + slamBoost * 5f
                                    gr.lightColor = floatArrayOf(
                                        c[0] * pct + (1f - pct) * 1f + bassSlam * 0.3f,
                                        c[1] * pct + (1f - pct) * 0.95f + bassSlam * 0.1f,
                                        c[2] * pct + (1f - pct) * 0.9f
                                    )

                                    // Fill: contrast light follows
                                    gr.fillLightIntensity = beatBaseFill + intensity * 2f + slamBoost * 2f
                                    gr.fillLightColor = floatArrayOf(
                                        c[2] * pct + (1f - pct) * 0.85f,
                                        c[0] * pct + (1f - pct) * 0.9f,
                                        c[1] * pct + (1f - pct) * 1f
                                    )

                                    // Per-model: exposure + EMISSIVE PULSE (models glow from within)
                                    for (placed in models) {
                                        val gpuModel = gr.getModel(placed.gpuModelId)
                                        if (gpuModel != null) {
                                            // Exposure builds with climax
                                            gpuModel.exposure = placed.exposure + intensity * 1f + slamBoost * 0.5f
                                            // Emissive glow: models radiate light on beats
                                            // Stronger as climax builds, spikes on slam
                                            val emGlow = pct * bi * (0.5f + climaxAccum * 1.5f) + bassSlam * 2f + breathVal
                                            gpuModel.emissiveFactor = floatArrayOf(
                                                c[0] * emGlow + breathVal * 0.5f,
                                                c[1] * emGlow + breathVal * 0.3f,
                                                c[2] * emGlow + breathVal * 0.2f
                                            )
                                            // Saturation surge on slam (punchy color)
                                            gpuModel.saturation = placed.saturation + bassSlam * 0.5f
                                        }
                                    }

                                    // Shadows soften as intensity builds (removes harsh edges)
                                    gr.shadowDarkness = beatBaseShadow * (1f - pct * 0.4f * bi)
                                }

                                // ── Beat-synced bloom ──
                                // Threshold drops on slam → bloom FLARES on bass hits
                                // Intensity rises with climax accumulator
                                if (gr.bloomEnabled) {
                                    gr.bloomThreshold = (0.8f - bassSlam * 0.5f - climaxAccum * 0.15f).coerceAtLeast(0.2f)
                                    gr.bloomIntensity = 0.3f + pct * bi * 0.3f + bassSlam * 0.8f + climaxAccum * 0.2f
                                }

                                // ── Room wash: builds with climax, flashes on slam ──
                                beatWashAlpha = if (affectsRoom) {
                                    val baseWash = intensity * 0.35f
                                    val slamFlash = bassSlam * 0.5f
                                    (baseWash + slamFlash + breathVal * 0.1f).coerceAtMost(0.85f)
                                } else 0f

                                // ── Haptic output: LEADS visual by 1 frame ──
                                // Send the CURRENT pct now (visual won't render until next composite)
                                // Plus slam spike for physical punch on bass drops
                                if (hapticEnabled && hapticConnected) {
                                    val hapticPct = (pct * bi * (1f + climaxAccum * 0.8f)
                                        + bassSlam * 0.4f + breathVal * 0.3f).coerceIn(0f, 1f)
                                    val hIntensity = (hapticPct * 20f).toInt().coerceIn(0, 20)
                                    hapticManager?.setIntensity(hIntensity)
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
                                climaxAccum = 0f; climaxPeak = 0f; bassSlam = 0f; edgeSustain = 0f
                                for (placed in models) {
                                    val gpuModel = gr.getModel(placed.gpuModelId)
                                    if (gpuModel != null) {
                                        gpuModel.exposure = placed.exposure
                                        gpuModel.emissiveFactor = floatArrayOf(1f, 1f, 1f)
                                        gpuModel.saturation = placed.saturation
                                    }
                                }
                            }

                            // Audio player A/B loop enforcement + UI refresh
                            audioPlayer?.updateLoop()
                            if (audioPlayerMode && audioPlayer?.isPlaying == true && sensorPollFrame % 5 == 0) {
                                uiNeedsRefresh = true
                            }

                            // Upload pending UI bitmap to compositor quad layer (swapchain texture)
                            val bmp = uiRenderer.pendingUiBitmap
                            if (bmp != null) {
                                uiRenderer.pendingUiBitmap = null
                                if (menuVisible) {
                                    val quadTex = nativeAcquireUiImage()
                                    if (quadTex > 0) {
                                        // Flip bitmap vertically — compositor quad UVs are bottom-up
                                        // Reuse cached flip canvas to avoid allocation every frame
                                        var fb = uiRenderer.uiFlipBitmap
                                        if (fb == null || fb.width != bmp.width || fb.height != bmp.height) {
                                            fb?.recycle()
                                            fb = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                                            uiRenderer.uiFlipBitmap = fb
                                            uiRenderer.uiFlipCanvas = android.graphics.Canvas(fb)
                                            uiRenderer.uiFlipMatrix.setScale(1f, -1f, bmp.width / 2f, bmp.height / 2f)
                                        }
                                        uiRenderer.uiFlipCanvas!!.drawBitmap(bmp, uiRenderer.uiFlipMatrix, null)
                                        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, quadTex)
                                        android.opengl.GLUtils.texSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, fb)
                                        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0)
                                        nativeReleaseUiImage()
                                    }
                                }
                                bmp.recycle()
                            }

                            // ── Yeet animation: flying deleted models ──
                            val yeetDt = 1f / 72f
                            val yeetIter = yeetingModels.iterator()
                            while (yeetIter.hasNext()) {
                                val ym = yeetIter.next()
                                ym.timer += yeetDt
                                ym.velY -= 9.8f * yeetDt  // gravity
                                ym.posX += ym.velX * yeetDt
                                ym.posY += ym.velY * yeetDt
                                ym.posZ += ym.velZ * yeetDt
                                ym.spin += 720f * yeetDt  // spin wildly
                                ym.scale *= 0.92f  // shrink each frame

                                val gpuModel = gr.getModel(ym.gpuModelId)
                                if (gpuModel != null) {
                                    val s = ym.scale
                                    val angle = Math.toRadians(ym.spin.toDouble())
                                    val cy = kotlin.math.cos(angle).toFloat()
                                    val sy = kotlin.math.sin(angle).toFloat()
                                    gpuModel.modelMatrix = floatArrayOf(
                                        cy * s, 0f, -sy * s, 0f,
                                        0f, s, 0f, 0f,
                                        sy * s, 0f, cy * s, 0f,
                                        ym.posX, ym.posY, ym.posZ, 1f
                                    )
                                }

                                if (ym.timer > 1.5f || ym.scale < 0.01f) {
                                    gr.removeModel(ym.gpuModelId)
                                    yeetIter.remove()
                                }
                            }

                            // Shadow map (once, before both eyes)
                            gr.renderShadowMap()

                            // Gizmo state for rendering
                            val selModel = models.getOrNull(selectedModelIndex)
                            val gizmoPos = selModel?.let { floatArrayOf(it.posX, it.posY, it.posZ) }
                            val gizmoRot = selModel?.let { floatArrayOf(it.rotX, it.rotY, it.rotZ, it.rotW) }

                            val ih = inputHandler  // local ref for render state

                            // Left eye: models -> ground/shadow -> gizmo -> laser
                            gr.renderEye(leftTexId, width, height, leftProj, leftView)
                            gr.renderGrid(leftTexId, width, height, leftProj, leftView,
                                gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(leftProj, leftView)
                            gr.renderPlaneVisualization(leftProj, leftView)
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(leftProj, leftView, gizmoPos, gizmoRot, ih.hoveredGizmoAxis)
                            gr.renderEmitter(leftProj, leftView, ih.emitterHovered)
                            if (ih.laserActive) gr.renderLaser(leftTexId, width, height, leftProj, leftView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance)
                            // Color wash: tints ENTIRE view (passthrough + scene)
                            if (beatReactorEnabled && reactor != null && beatWashAlpha > 0.005f) {
                                val wc = reactor.getBeatColor()
                                val bm = reactor.blendMode.ordinal
                                gr.renderColorWash(wc[0], wc[1], wc[2], beatWashAlpha, bm)
                            }
                            gr.renderBloom(leftTexId, width, height)
                            gr.finishEyePass()

                            // Right eye
                            gr.renderEye(rightTexId, width, height, rightProj, rightView)
                            gr.renderGrid(rightTexId, width, height, rightProj, rightView,
                                gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(rightProj, rightView)
                            gr.renderPlaneVisualization(rightProj, rightView)
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(rightProj, rightView, gizmoPos, gizmoRot, ih.hoveredGizmoAxis)
                            gr.renderEmitter(rightProj, rightView, ih.emitterHovered)
                            if (ih.laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                ih.laserHandPos, ih.laserAimRot, ih.hitDistance)
                            if (beatReactorEnabled && reactor != null && beatWashAlpha > 0.005f) {
                                val wc = reactor.getBeatColor()
                                val bm = reactor.blendMode.ordinal
                                gr.renderColorWash(wc[0], wc[1], wc[2], beatWashAlpha, bm)
                            }
                            gr.renderBloom(rightTexId, width, height)
                            gr.finishEyePass()
                            // Menu rendered via compositor quad layer (stereo-correct)

                            android.opengl.GLES30.glFlush()
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

                if (nativePollInput(inputHandler.inputBuffer)) {
                    inputHandler.handle()
                }

                // ── Poll ALL XR sensors (every few frames to save CPU) ──
                sensorPollFrame++
                if (sensorPollFrame % 3 == 0) {
                    sensorPoller.poll(sensorPollFrame)
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

    private fun buildModelPanelView(): android.view.View = uiRenderer.buildModelPanelView()

    internal fun showMessage(text: String) = uiRenderer.showMessage(text)

    // ── Lifecycle ──

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorHub?.start()
        if (lightSensor != null) {
            sensorManager?.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        sensorManager?.unregisterListener(lightListener)
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

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            roomLux = event.values[0]
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false

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

        hapticManager?.disconnect()
        audioPlayer?.release(); audioPlayer = null
        audioReactor?.stop()
        sensorManager?.unregisterListener(lightListener)
        sensorHub?.stop()
        renderThread?.join(2000)
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
    internal external fun nativeSetFoveationLevel(level: Int)
    private external fun nativeGetFoveationLevel(): Int
    private external fun nativeIsFocused(): Boolean
    private external fun nativeIsUsingStageSpace(): Boolean
}
