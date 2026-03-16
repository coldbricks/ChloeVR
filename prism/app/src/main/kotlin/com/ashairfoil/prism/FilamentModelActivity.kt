package com.ashairfoil.prism

import android.app.Activity
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
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
    private var glesRenderer: GlesModelRenderer? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    // Models
    data class PlacedModel(
        val file: File,
        val asset: Any?,
        var gpuModelId: Int = -1,
        var scale: Float = 1f,
        val baseScale: Float = 1f,
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
        var saturation: Float = 1f
    )
    private val models = mutableListOf<PlacedModel>()
    private var selectedModelIndex = -1

    // Controller state
    private val inputBuffer = FloatArray(41)
    private val STICK_DEADZONE = 0.15f

    // Controller edge detection
    private var lastMenuState = false
    private var lastAState = false
    private var lastBState = false
    private var lastYState = false
    private var inputLogCount = 0

    // Grab state — object locks to laser ray at grab point
    private var grabbing = false
    private var grabDistance = 2f
    private var grabOffset = floatArrayOf(0f, 0f, 0f) // pivot offset from hit point
    private var grabStartAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var grabStartModelRot = floatArrayOf(0f, 0f, 0f, 1f)

    // ── Sensor Hub (ALL Android hardware sensors) ──
    private var sensorHub: SensorHub? = null

    // Legacy sensor compat (SensorHub now owns these)
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    @Volatile private var roomLux = 200f  // default indoor
    @Volatile private var autoAmbient = true
    private val lightEstimateBuffer = FloatArray(41)
    @Volatile private var xrLightEstimateAvailable = false
    @Volatile private var xrSHAvailable = false
    @Volatile private var xrLightDebugStr = ""

    // Temporal smoothing for XR light estimation (prevents "moving light" jitter)
    private var smoothAmbientIntensity = 1f
    private var smoothAmbientColor = floatArrayOf(1f, 1f, 1f)
    private var smoothLightIntensity = 2f
    private var smoothLightDir = floatArrayOf(0f, 1f, 0f)
    private var smoothLightColor = floatArrayOf(1f, 1f, 1f)
    private var smoothSH = FloatArray(27)
    private var lightSmoothed = false  // first frame gets instant values

    // ── XR Sensor buffers ──
    private val handTrackingBufferL = FloatArray(209) // 1 + 26×8
    private val handTrackingBufferR = FloatArray(209)
    private val eyeTrackingBuffer = FloatArray(25)
    private val faceTrackingBuffer = FloatArray(69) // 1 + 68
    private val planeBuffer = FloatArray(322) // 2 + 32×10
    private val perfMetricsBuffer = FloatArray(5)

    // ── XR Sensor state ──
    @Volatile private var xrSensorCaps = 0 // bitmask
    @Volatile private var handTrackingActive = booleanArrayOf(false, false)
    @Volatile private var eyeTrackingActive = false
    @Volatile private var faceTrackingActive = false
    @Volatile private var detectedPlaneCount = 0
    @Volatile private var gpuFrameTimeMs = 0f
    @Volatile private var cpuFrameTimeMs = 0f
    @Volatile private var displayRefreshRate = 0f
    @Volatile private var droppedFrames = 0f
    @Volatile private var passthroughState = 0

    // Combined eye gaze (for gaze cursor)
    @Volatile private var gazeOriginX = 0f; @Volatile private var gazeOriginY = 0f; @Volatile private var gazeOriginZ = 0f
    @Volatile private var gazeRotX = 0f; @Volatile private var gazeRotY = 0f
    @Volatile private var gazeRotZ = 0f; @Volatile private var gazeRotW = 1f

    // Sensor debug HUD
    @Volatile private var sensorHudVisible = false
    @Volatile private var sensorDebugStr = ""
    private var sensorPollFrame = 0

    // Laser / selection state
    private var laserHandPos = floatArrayOf(0f, 0f, 0f)
    private var laserAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var laserActive = false
    private var hoveredModelIndex = -1
    private var hitDistance = -1f
    private var lastRightTriggerState = false
    // Light emitter grab
    private var emitterHovered = false
    private var emitterGrabbed = false
    private var emitterGrabDist = 2f

    // Grid state
    @Volatile var gridVisible = false

    // Gizmo state
    var gizmoVisible = true
    private var hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
    private var gizmoDragging = false
    private var gizmoDragAxis = GlesModelRenderer.GIZMO_AXIS_NONE
    private var gizmoDragStartHandPos = floatArrayOf(0f, 0f, 0f)
    private var gizmoDragStartModelPos = floatArrayOf(0f, 0f, 0f)

    // UI state
    @Volatile private var menuVisible = false
    private var handsLocked = false
    private var uiTextureId = 0
    @Volatile private var pendingUiBitmap: android.graphics.Bitmap? = null
    private var uiFlipBitmap: android.graphics.Bitmap? = null
    private var uiFlipCanvas: android.graphics.Canvas? = null
    private val uiFlipMatrix = android.graphics.Matrix()
    private var selectedParam = 0
    private val PARAM_NAMES = arrayOf("Metallic", "Roughness", "Exposure",
        "Contrast", "Saturation",
        "Light Intensity", "Fill Intensity", "Ambient", "Light Azimuth",
        "Light Height", "Shadow Dark", "Shadow Soft", "Shadow Spread",
        "BeatReactor", "Foveation", "Tex Quality", "Show Planes")
    // Slider ranges for continuous params 0-12. Toggles (13-16) have no range.
    private val PARAM_RANGES = arrayOf(
        0f to 1f, 0.05f to 1f, -5f to 5f, 0.85f to 1.15f, 0f to 3f,
        0f to 10f, 0f to 5f, 0f to 5f, 0f to 360f, 5f to 90f,
        0f to 1f, 0.5f to 5f, 2f to 30f
    )
    // 0=auto (adaptive budget), 1=4096 (original), 2=2048, 3=1024
    private var textureQuality = 0
    private var lastXState = false
    private var lastRightStickClick = false
    private var lastLeftStickClick = false
    private var uiNeedsRefresh = false
    private var lastBCloseTime = 0L
    private var hoveredMenuParam = -1
    private var lastHoveredMenuParam = -1

    // Draggable panel state
    private var panelPosX = 0f; private var panelPosY = 1.6f; private var panelPosZ = -1.2f
    private var panelRotX = 0f; private var panelRotY = 0f; private var panelRotZ = 0f; private var panelRotW = 1f
    private val PANEL_WIDTH = 0.9f   // meters in world space (base size)
    private val PANEL_HEIGHT = 1.0f
    private var panelScale = 1.0f    // zoom factor (0.5..2.0)
    private var draggingPanel = false
    private var panelGrabDist = 1.0f // distance from hand at grab time

    // Action button hover state: -1=none, 100=back, 101=add object, 102=exit app, 200=title bar
    private var hoveredActionButton = -1
    private var lastHoveredActionButton = -1

    // GLB picker sub-menu (shown when ADD MODEL is tapped)
    @Volatile private var glbPickerMode = false
    private var availableGlbFiles: List<File> = emptyList()
    private var hoveredGlbIndex = -1
    private var lastHoveredGlbIndex = -1
    private var glbPickerScrollOffset = 0
    @Volatile private var pendingModelLoad: File? = null  // queued for render thread (needs GL context)

    // Scene save/load
    @Volatile private var scenePickerMode = false  // true = showing load scene list
    private var scenePickerIsSave = false
    private var savedSceneFiles: List<File> = emptyList()
    private var hoveredSceneIndex = -1
    private var lastHoveredSceneIndex = -1
    @Volatile private var pendingSceneLoad: File? = null  // queued for render thread

    // Save name editor
    @Volatile private var saveNameMode = false
    private val SAVE_NAME_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_- "
    private var saveNameChars = CharArray(20) { if (it < 5) "Scene"[it] else ' ' }
    private var saveNameLen = 5
    private var saveNameCursor = 0
    private var hoveredSaveButton = -1  // 0=SAVE, 1=BACK
    private var saveNameStickCooldown = 0

    // Audio-reactive lighting (BeatReactor)
    private var audioReactor: AudioReactor? = null
    @Volatile private var beatReactorEnabled = false
    @Volatile private var beatIntensity = 1.0f

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
    private var beatBaseStored = false
    private var beatBaseAmbient = 1f
    private var beatBaseLight = 2f
    private var beatBaseFill = 0.5f
    private var beatBaseShadow = 0.7f
    private var beatWashAlpha = 0f
    @Volatile private var foveationLevel = 0  // 0=off, 1=low, 2=med, 3=high
    private var beatToggleLatch = false
    private var foveationToggleLatch = false
    private var planeVisToggleLatch = false
    private var lastLaserBx = 0f      // last laser bitmap X on menu panel
    private var sliderDragging = -1   // param index being slider-dragged, -1 if none

    // ── Audio Player ──
    private var audioPlayer: AudioPlayer? = null
    @Volatile private var audioPlayerMode = false
    @Volatile private var audioPickerMode = false
    private var availableAudioFiles: List<File> = emptyList()
    private var hoveredAudioButton = -1
    private var hoveredAudioFileIndex = -1
    private var audioPickerScrollOffset = 0
    private var audioSeekDragging = false

    // BeatReactor settings sub-menu
    @Volatile private var beatSettingsMode = false
    private var beatDraggingSlider = -1
    private var beatSliderLaserX = 0f
    private var beatDragCorner = -1  // -1=none, 0=TL, 1=TR, 2=BL, 3=BR, 4=move whole box
    @Volatile private var beatCursorX = -1f  // laser position on panel (bitmap coords)
    @Volatile private var beatCursorY = -1f

    // Haptic device (Lovense via BLE)
    private var hapticManager: com.ashairfoil.prism.haptics.BleDeviceManager? = null
    @Volatile private var hapticConnected = false
    @Volatile private var hapticEnabled = false

    // Floor detection: snap grid once at startup, track floor Y for model placement
    @Volatile private var floorSnappedOnce = false  // true after first grid snap to detected floor
    @Volatile private var detectedFloorY = Float.MIN_VALUE

    // Yeet animation (model delete with fly-off)
    data class YeetingModel(
        val gpuModelId: Int,
        var posX: Float, var posY: Float, var posZ: Float,
        var velX: Float, var velY: Float, var velZ: Float,
        var scale: Float,
        var spin: Float = 0f,
        var timer: Float = 0f
    )
    private val yeetingModels = mutableListOf<YeetingModel>()

    // Head/camera position (extracted from view matrix each frame)
    private var camPosX = 0f; private var camPosY = 1.6f; private var camPosZ = 0f
    private var camFwdX = 0f; private var camFwdY = 0f; private var camFwdZ = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val renderer = glesRenderer ?: return
        try {
            val bytes = file.readBytes()
            Log.i(TAG, "Loading GLB: ${file.name} (${bytes.size} bytes)")

            val gpuId = renderer.loadGlb(bytes)
            if (gpuId < 0) {
                Log.e(TAG, "Failed to load GLB: ${file.name}")
                runOnUiThread { showMessage("Failed to load 3D model: ${file.name}") }
                return
            }

            val gpuModel = renderer.getModel(gpuId) ?: return

            val autoScale = 0.75f
            // Offset new models so they don't overlap
            val offsetX = if (models.isEmpty()) 0f else offsetIndex * 1.0f

            val placed = PlacedModel(
                file = file,
                asset = null,
                gpuModelId = gpuId,
                scale = autoScale,
                baseScale = autoScale,
                posX = offsetX,
                posZ = -1f,
                metallic = gpuModel.metallic,
                roughness = gpuModel.roughness
            )
            models.add(placed)
            selectedModelIndex = models.size - 1

            // Auto-snap to detected floor if available
            if (detectedFloorY != Float.MIN_VALUE) {
                val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                placed.posY += (detectedFloorY - worldMinY)
            } else if (renderer.gridHeight != 0f) {
                val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                placed.posY += (renderer.gridHeight - worldMinY)
            }

            updateModelTransform(placed)

            Log.i(TAG, "Model loaded: ${file.name}, scale=$autoScale, gpuId=$gpuId, floorY=${detectedFloorY}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${file.name}", e)
            runOnUiThread { showMessage("Error: ${e.message}") }
        }
    }

    private fun updateModelTransform(model: PlacedModel) {
        val renderer = glesRenderer ?: return
        val gpuModel = renderer.getModel(model.gpuModelId) ?: return

        val s = model.scale
        val x = model.rotX; val y = model.rotY; val z = model.rotZ; val w = model.rotW

        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2

        gpuModel.modelMatrix = floatArrayOf(
            (1f - (yy + zz)) * s, (xy + wz) * s, (xz - wy) * s, 0f,
            (xy - wz) * s, (1f - (xx + zz)) * s, (yz + wx) * s, 0f,
            (xz + wy) * s, (yz - wx) * s, (1f - (xx + yy)) * s, 0f,
            model.posX, model.posY, model.posZ, 1f
        )
    }

    private fun reloadAllModels() {
        val renderer = glesRenderer ?: return
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
        for ((file, snap, _) in snapshots) {
            if (!file.exists()) continue
            try {
                val bytes = file.readBytes()
                val gpuId = renderer.loadGlb(bytes)
                if (gpuId < 0) continue
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
            }
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
        Log.i(TAG, "Reloaded ${models.size} models (texQuality=$textureQuality)")
    }

    // ── Scene Save/Load ──

    private fun getScenesDir(): File {
        val dir = File(filesDir, "scenes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun refreshSceneList() {
        savedSceneFiles = getScenesDir().listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun saveScene(name: String) {
        try {
            val renderer = glesRenderer ?: return
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
                modelsArr.put(obj)
            }
            scene.put("models", modelsArr)

            val file = File(getScenesDir(), "$name.json")
            file.writeText(scene.toString(2))
            Log.i(TAG, "Scene saved: ${file.absolutePath} (${models.size} models)")
            runOnUiThread { showMessage("Scene saved: $name") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scene", e)
            runOnUiThread { showMessage("Save failed: ${e.message}") }
        }
    }

    private fun loadScene(sceneFile: File) {
        // This must be called on the render thread (needs GL context for loadModel)
        try {
            val renderer = glesRenderer ?: return
            val json = org.json.JSONObject(sceneFile.readText())

            // Clear existing models
            for (m in models) {
                renderer.removeModel(m.gpuModelId)
            }
            models.clear()
            selectedModelIndex = -1

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
                autoAmbient = lighting.optBoolean("autoAmbient", true)
                gridVisible = lighting.optBoolean("gridVisible", true)
                renderer.gridHeight = lighting.optDouble("gridHeight", 0.0).toFloat()
            }

            // Load models
            val modelsArr = json.optJSONArray("models")
            if (modelsArr != null) {
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.getJSONObject(i)
                    val file = File(obj.getString("path"))
                    if (!file.exists()) {
                        Log.w(TAG, "Scene model not found: ${file.absolutePath}")
                        continue
                    }
                    val bytes = file.readBytes()
                    val gpuId = renderer.loadGlb(bytes)
                    if (gpuId < 0) continue

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
                        saturation = obj.optDouble("saturation", 1.0).toFloat()
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
            Log.i(TAG, "Scene loaded: ${sceneFile.name} (${models.size} models)")
            runOnUiThread { showMessage("Scene loaded: ${sceneFile.nameWithoutExtension}") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scene", e)
            runOnUiThread { showMessage("Load failed: ${e.message}") }
        }
    }

    // BeatReactor slider definitions: name, get, set, min, max, unit
    private data class BeatSlider(val name: String, val unit: String, val min: Float, val max: Float,
                                   val get: () -> Float, val set: (Float) -> Unit)
    private val beatSliders by lazy { arrayOf(
        BeatSlider("GAIN", "x", 0.5f, 10f,
            { audioReactor?.sensitivity ?: 3f }, { audioReactor?.sensitivity = it }),
        BeatSlider("BOX LEFT", "Hz", 20f, 2000f,
            { val r = audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxLeft.toDouble()).toFloat() else 20f },
            { audioReactor?.boxLeft = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) }),
        BeatSlider("BOX RIGHT", "Hz", 100f, 20000f,
            { val r = audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxRight.toDouble()).toFloat() else 300f },
            { audioReactor?.boxRight = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) }),
        BeatSlider("BOX BOTTOM", "%", 0f, 80f,
            { (audioReactor?.boxBottom ?: 5f) * 100f }, { audioReactor?.boxBottom = it / 100f }),
        BeatSlider("BOX TOP", "%", 20f, 100f,
            { (audioReactor?.boxTop ?: 85f) * 100f }, { audioReactor?.boxTop = it / 100f }),
        BeatSlider("ATTACK", "ms", 1f, 200f,
            { audioReactor?.attackMs ?: 20f }, { audioReactor?.attackMs = it }),
        BeatSlider("RELEASE", "ms", 10f, 2000f,
            { audioReactor?.releaseMs ?: 150f }, { audioReactor?.releaseMs = it }),
        BeatSlider("EXPAND", "x", 0.3f, 4f,
            { audioReactor?.dynRange ?: 2f }, { audioReactor?.dynRange = it }),
        BeatSlider("SMOOTH", "%", 0f, 100f,
            { (audioReactor?.smootherAmount ?: 0.3f) * 100f }, { audioReactor?.smootherAmount = it / 100f }),
        BeatSlider("ZOOM-H", "x", 1f, 8f,
            { audioReactor?.specZoom ?: 1f }, { audioReactor?.specZoom = it }),
        BeatSlider("ZOOM-V", "x", 0.5f, 20f,
            { audioReactor?.specVZoom ?: 1f }, { audioReactor?.specVZoom = it }),
        BeatSlider("COLOR", "\u00B0", 0f, 360f,
            { audioReactor?.beatHue ?: 330f }, { audioReactor?.beatHue = it }),
        BeatSlider("MIX", "%", 0f, 100f,
            { beatIntensity * 50f }, { beatIntensity = it / 50f }),
    ) }
    private var beatLockedSlider = -1  // locked slider index while trigger held

    private fun applyBeatSlider(idx: Int, normalizedX: Float) {
        val slider = beatSliders.getOrNull(idx) ?: return
        val value = slider.min + normalizedX * (slider.max - slider.min)
        slider.set(value)
    }

    private fun startRenderLoop() {
        running = true
        renderThread = Thread({
            val frameData = FloatArray(69)

            Log.i(TAG, "Render loop started")
            nativeMakeGLContextCurrent()

            // Init XR compositor quad for stereo-correct menu panel
            if (nativeInitUiQuad(1024, 1024)) {
                Log.i(TAG, "Compositor UI quad initialized (1024x1024)")
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
                            val bmp = pendingUiBitmap
                            if (bmp != null) {
                                pendingUiBitmap = null
                                if (menuVisible) {
                                    val quadTex = nativeAcquireUiImage()
                                    if (quadTex > 0) {
                                        // Flip bitmap vertically — compositor quad UVs are bottom-up
                                        // Reuse cached flip canvas to avoid allocation every frame
                                        var fb = uiFlipBitmap
                                        if (fb == null || fb.width != bmp.width || fb.height != bmp.height) {
                                            fb?.recycle()
                                            fb = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
                                            uiFlipBitmap = fb
                                            uiFlipCanvas = android.graphics.Canvas(fb)
                                            uiFlipMatrix.setScale(1f, -1f, bmp.width / 2f, bmp.height / 2f)
                                        }
                                        uiFlipCanvas!!.drawBitmap(bmp, uiFlipMatrix, null)
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

                            // Left eye: models → ground/shadow → gizmo → laser
                            gr.renderEye(leftTexId, width, height, leftProj, leftView)
                            gr.renderGrid(leftTexId, width, height, leftProj, leftView,
                                gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(leftProj, leftView)
                            gr.renderPlaneVisualization(leftProj, leftView)
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(leftProj, leftView, gizmoPos, gizmoRot, hoveredGizmoAxis)
                            gr.renderEmitter(leftProj, leftView, emitterHovered)
                            if (laserActive) gr.renderLaser(leftTexId, width, height, leftProj, leftView,
                                laserHandPos, laserAimRot, hitDistance)
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
                                gr.renderGizmo(rightProj, rightView, gizmoPos, gizmoRot, hoveredGizmoAxis)
                            gr.renderEmitter(rightProj, rightView, emitterHovered)
                            if (laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                laserHandPos, laserAimRot, hitDistance)
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

                if (nativePollInput(inputBuffer)) {
                    handleInput()
                }

                // ── Poll ALL XR sensors (every few frames to save CPU) ──
                sensorPollFrame++
                if (sensorPollFrame % 3 == 0) {
                    pollXrSensors()
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

    // ── Ray-Object Intersection ──

    private fun raySphereIntersect(
        rayOrigin: FloatArray, rayDir: FloatArray,
        sphereCenter: FloatArray, sphereRadius: Float
    ): Float {
        val ocX = rayOrigin[0] - sphereCenter[0]
        val ocY = rayOrigin[1] - sphereCenter[1]
        val ocZ = rayOrigin[2] - sphereCenter[2]

        // c < 0 means ray origin is inside the sphere — skip so user can deselect
        val c = ocX * ocX + ocY * ocY + ocZ * ocZ - sphereRadius * sphereRadius
        if (c < 0f) return -1f

        val a = rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2]
        if (a < 1e-8f) return -1f  // zero-length ray guard
        val b = 2f * (ocX * rayDir[0] + ocY * rayDir[1] + ocZ * rayDir[2])

        val disc = b * b - 4f * a * c
        if (disc < 0f) return -1f

        val sqrtDisc = kotlin.math.sqrt(disc)
        val t1 = (-b - sqrtDisc) / (2f * a)
        val t2 = (-b + sqrtDisc) / (2f * a)

        return when {
            t1 > 0.01f -> t1
            t2 > 0.01f -> t2
            else -> -1f
        }
    }

    // ── Controller Input ──

    private fun handleInput() {
        val leftThumbX = inputBuffer[0]
        val leftThumbY = inputBuffer[2]
        val rightThumbX = inputBuffer[1]
        val rightThumbY = inputBuffer[3]
        val leftTrigger = inputBuffer[4]
        val rightTrigger = inputBuffer[5]
        val leftSqueeze = inputBuffer[6]
        val rightSqueeze = inputBuffer[7]
        val aButton = inputBuffer[8] > 0.5f
        val bButton = inputBuffer[9] > 0.5f
        val xButton = inputBuffer[10] > 0.5f
        val yButton = inputBuffer[11] > 0.5f
        val menuButton = inputBuffer[12] > 0.5f
        val leftHandPosX = inputBuffer[15]
        val leftHandPosY = inputBuffer[17]
        val leftHandPosZ = inputBuffer[19]
        val rightHandPosX = inputBuffer[16]
        val rightHandPosY = inputBuffer[18]
        val rightHandPosZ = inputBuffer[20]
        val leftHandValid = inputBuffer[29] > 0.5f
        val rightHandValid = inputBuffer[30] > 0.5f
        val leftAimRotX = inputBuffer[31]; val leftAimRotY = inputBuffer[33]
        val leftAimRotZ = inputBuffer[35]; val leftAimRotW = inputBuffer[37]
        val rightAimRotX = inputBuffer[32]; val rightAimRotY = inputBuffer[34]
        val rightAimRotZ = inputBuffer[36]; val rightAimRotW = inputBuffer[38]
        val leftAimValid = inputBuffer[39] > 0.5f
        val rightAimValid = inputBuffer[40] > 0.5f

        if (inputLogCount < 5 && (menuButton || bButton || aButton)) {
            Log.i(TAG, "Input: menu=$menuButton b=$bButton a=$aButton squeeze=${inputBuffer[6]},${inputBuffer[7]}")
            inputLogCount++
        }

        // Menu button toggles sensor debug HUD
        if (menuButton && !lastMenuState) {
            sensorHudVisible = !sensorHudVisible
            Log.i(TAG, "Sensor HUD: ${if (sensorHudVisible) "ON" else "OFF"}")
            if (sensorHudVisible) {
                sensorDebugStr = buildSensorDebugString()
                uiNeedsRefresh = true
            }
            uiNeedsRefresh = true
        }
        lastMenuState = menuButton

        // ── Laser pointer + ray intersection + gizmo hover ──
        val renderer = glesRenderer
        if (rightHandValid && rightAimValid && renderer != null) {
            laserActive = true
            laserHandPos = floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
            laserAimRot = floatArrayOf(rightAimRotX, rightAimRotY, rightAimRotZ, rightAimRotW)

            val rayDir = renderer.quatForward(laserAimRot)

            // ── Menu panel hit test ──
            // Panel rendered by renderUiOverlay — position tracked in renderer.panelX/Y/Z
            hoveredMenuParam = -1
            hoveredActionButton = -1
            var laserOnPanel = false
            if (menuVisible && renderer != null) {
                val pcx = renderer.panelX
                val pcy = renderer.panelY
                val pcz = renderer.panelZ
                val panelHW = renderer.panelW * 0.5f
                val panelHH = renderer.panelH * 0.5f

                // Billboard axes — must match renderer exactly
                // Forward = panel → camera (same as renderer: panelPos - camPos then negate = camPos - panelPos...
                // renderer uses fwd = panel - cam, right = cross(fwd, up), up = -cross(fwd, right))
                var fwdX = pcx - camPosX; var fwdY = pcy - camPosY; var fwdZ = pcz - camPosZ
                val fLen = kotlin.math.sqrt(fwdX*fwdX + fwdY*fwdY + fwdZ*fwdZ).coerceAtLeast(0.001f)
                fwdX /= fLen; fwdY /= fLen; fwdZ /= fLen

                // Right = cross(fwd, worldUp) — matches renderer
                var rx = fwdY*0f - fwdZ*1f
                var ry = fwdZ*0f - fwdX*0f
                var rz = fwdX*1f - fwdY*0f
                val rLen = kotlin.math.sqrt(rx*rx + ry*ry + rz*rz).coerceAtLeast(0.001f)
                rx /= rLen; ry /= rLen; rz /= rLen

                // Up = cross(fwd, right) then negate — matches renderer's -bup
                val bupX = fwdY*rz - fwdZ*ry
                val bupY = fwdZ*rx - fwdX*rz
                val bupZ = fwdX*ry - fwdY*rx
                val ux = -bupX; val uy = -bupY; val uz = -bupZ

                // Normal for ray-plane intersection (panel faces camera)
                val nx = -fwdX; val ny = -fwdY; val nz = -fwdZ

                val denom = rayDir[0]*nx + rayDir[1]*ny + rayDir[2]*nz
                if (kotlin.math.abs(denom) > 0.01f) {
                    val t = ((pcx - laserHandPos[0])*nx + (pcy - laserHandPos[1])*ny + (pcz - laserHandPos[2])*nz) / denom
                    if (t > 0f && t < 8f) {
                        val wx = laserHandPos[0] + rayDir[0] * t
                        val wy = laserHandPos[1] + rayDir[1] * t
                        val wz = laserHandPos[2] + rayDir[2] * t

                        // Project onto panel's local right/up axes
                        val dx = wx - pcx; val dy = wy - pcy; val dz = wz - pcz
                        val hx = dx*rx + dy*ry + dz*rz
                        val hy = dx*ux + dy*uy + dz*uz

                        if (hx in -panelHW..panelHW && hy in -panelHH..panelHH) {
                            laserOnPanel = true
                            hitDistance = t
                            val u = (hx + panelHW) / (panelHW * 2f)
                            val v = 1f - (hy + panelHH) / (panelHH * 2f)
                            val bx = u * 1024f
                            val by = v * 1024f

                            // Title bar drag zone: top ~80px
                            if (by < 85f) {
                                hoveredActionButton = 200 // title bar hover
                                val rg = inputBuffer[7]
                                if (rg > 0.7f && !draggingPanel) {
                                    draggingPanel = true
                                    panelGrabDist = t // remember how far the panel was
                                }
                            }

                            if (audioPlayerMode) {
                                hoveredAudioButton = -1
                                hoveredAudioFileIndex = -1
                                lastLaserBx = bx

                                if (audioPickerMode) {
                                    // File list hover
                                    val maxVis = 10; val rowH = 72f; val startY = 140f
                                    if (by in startY..(startY + maxVis * rowH)) {
                                        val vi = ((by - startY) / rowH).toInt()
                                        val idx = audioPickerScrollOffset + vi
                                        if (idx < availableAudioFiles.size) hoveredAudioFileIndex = idx
                                    }
                                    if (by > (1024 - 80f)) hoveredAudioButton = 50 // BACK
                                } else {
                                    // Transport buttons: y=245..300
                                    if (by in 245f..300f) {
                                        val btnW = 130f; val gap = 12f
                                        for (i in 0..3) {
                                            val bxl = 60f + i * (btnW + gap)
                                            if (bx in bxl..(bxl + btnW)) { hoveredAudioButton = i; break }
                                        }
                                    }
                                    // Speed -/+: y=303..331
                                    if (by in 303f..331f) {
                                        if (bx in 260f..320f) hoveredAudioButton = 10
                                        if (bx in 330f..390f) hoveredAudioButton = 11
                                    }
                                    // A/B buttons: y=355..383
                                    if (by in 355f..383f) {
                                        for (i in 0..2) {
                                            val bxl = 220f + i * 100f
                                            if (bx in bxl..(bxl + 90f)) { hoveredAudioButton = 20 + i; break }
                                        }
                                    }
                                    // Repeat: y=405..433
                                    if (by in 405f..433f && bx in 180f..280f) hoveredAudioButton = 30
                                    // EQ presets: y=455..483
                                    if (by in 455f..483f) {
                                        for (i in 0..3) {
                                            val bxl = 130f + i * 120f
                                            if (bx in bxl..(bxl + 110f)) { hoveredAudioButton = 40 + i; break }
                                        }
                                    }
                                    // Progress bar drag: y=210..220
                                    if (by in 200f..230f && bx in 60f..964f) {
                                        hoveredAudioButton = 60 // seekbar
                                    }
                                    // BROWSE / BACK: bottom
                                    if (by > (1024 - 80f)) {
                                        val halfW = (1024 - 70f) / 2f
                                        if (bx < 30f + halfW) hoveredAudioButton = 51
                                        else hoveredAudioButton = 52
                                    }
                                }
                                uiNeedsRefresh = true
                            } else if (beatSettingsMode) {
                                val prevDragging = beatDraggingSlider
                                beatDraggingSlider = -1
                                beatSliderLaserX = 0f
                                hoveredActionButton = -1
                                beatCursorX = bx; beatCursorY = by  // track for visible cursor

                                // Layout: spectrum 140..390, sliders 418..786, buttons 920+
                                val specTopHit = 155f; val specBotHit = 435f  // specTop + specH
                                val specLeftHit = 40f; val specRightHit = 984f  // uiW(1024) - 40
                                val sliderAreaTopHit = 465f  // after bigger spectrum
                                val sliderRowH = 32f  // tighter to fit

                                if (by > 920f) {
                                    val quarter = (1024f - 100f) / 4f
                                    if (bx < 30f + quarter) hoveredActionButton = 127 // BOOM
                                    else if (bx < 40f + quarter * 2f) hoveredActionButton = 128 // VIBES
                                    else if (bx < 50f + quarter * 3f) hoveredActionButton = 112 // OFF
                                    else hoveredActionButton = 111 // BACK
                                } else if (by in 108f..135f) {
                                    // Rolloff mode buttons
                                    if (bx in 300f..420f) hoveredActionButton = 113
                                    else if (bx in 430f..550f) hoveredActionButton = 114
                                    else if (bx in 560f..680f) hoveredActionButton = 115
                                    else if (bx in 690f..810f) hoveredActionButton = 116
                                } else if (by in 130f..150f) {
                                    // Color mode buttons (left)
                                    if (bx in 50f..135f) hoveredActionButton = 117
                                    else if (bx in 141f..226f) hoveredActionButton = 118
                                    else if (bx in 232f..317f) hoveredActionButton = 119
                                    // Scope buttons (middle)
                                    else if (bx in 350f..435f) hoveredActionButton = 120
                                    else if (bx in 441f..526f) hoveredActionButton = 121
                                    else if (bx in 532f..617f) hoveredActionButton = 122
                                    // Blend mode buttons (right)
                                    else if (bx in 640f..708f) hoveredActionButton = 123
                                    else if (bx in 712f..780f) hoveredActionButton = 124
                                    else if (bx in 784f..852f) hoveredActionButton = 125
                                    else if (bx in 856f..924f) hoveredActionButton = 126
                                } else if (by in specTopHit..specBotHit && bx in specLeftHit..specRightHit) {
                                    // Laser is on the spectrum area — corner/box dragging
                                    val reactor = audioReactor
                                    if (reactor != null) {
                                        // Map screen position to spectrum coordinates (through zoom)
                                        val screenNormX = ((bx - specLeftHit) / (specRightHit - specLeftHit)).coerceIn(0f, 1f)
                                        val hz = reactor.specZoom.coerceAtLeast(1f)
                                        val vw = 1f / hz
                                        val vc = reactor.specViewCenter.coerceIn(vw / 2f, 1f - vw / 2f)
                                        val vL = vc - vw / 2f
                                        val normX = vL + screenNormX * vw  // map to actual spectrum position
                                        val screenY = (1f - (by - specTopHit) / (specBotHit - specTopHit)).coerceIn(0f, 1f)
                                        // Convert screen Y to raw box coordinates (undo vZoom * expand)
                                        val vzex = (reactor.specVZoom * reactor.dynRange).coerceAtLeast(0.01f)
                                        val normY = (screenY / vzex).coerceIn(0f, 1f)

                                        // Display-space box positions (for hit testing)
                                        val dispBoxTop = (reactor.boxTop * vzex).coerceAtMost(1f)
                                        val dispBoxBot = (reactor.boxBottom * vzex).coerceAtMost(1f)

                                        if (rightTrigger > 0.5f) {
                                            if (beatDragCorner < 0) {
                                                // Compare in display space
                                                val corners = arrayOf(
                                                    floatArrayOf(reactor.boxLeft, dispBoxTop),
                                                    floatArrayOf(reactor.boxRight, dispBoxTop),
                                                    floatArrayOf(reactor.boxLeft, dispBoxBot),
                                                    floatArrayOf(reactor.boxRight, dispBoxBot)
                                                )
                                                var minDist = Float.MAX_VALUE
                                                var nearestCorner = -1
                                                for (ci in corners.indices) {
                                                    val d = (normX - corners[ci][0]) * (normX - corners[ci][0]) +
                                                            (screenY - corners[ci][1]) * (screenY - corners[ci][1])
                                                    if (d < minDist) { minDist = d; nearestCorner = ci }
                                                }
                                                beatDragCorner = if (minDist < 0.015f) nearestCorner
                                                    else if (normX in reactor.boxLeft..reactor.boxRight && screenY in dispBoxBot..dispBoxTop) 4
                                                    else -1
                                            }
                                            // Apply drag (normY is in raw box space)
                                            when (beatDragCorner) {
                                                0 -> { reactor.boxLeft = normX.coerceIn(0f, reactor.boxRight - 0.02f); reactor.boxTop = normY.coerceIn(reactor.boxBottom + 0.01f, 1f) }
                                                1 -> { reactor.boxRight = normX.coerceIn(reactor.boxLeft + 0.02f, 1f); reactor.boxTop = normY.coerceIn(reactor.boxBottom + 0.01f, 1f) }
                                                2 -> { reactor.boxLeft = normX.coerceIn(0f, reactor.boxRight - 0.02f); reactor.boxBottom = normY.coerceIn(0f, reactor.boxTop - 0.01f) }
                                                3 -> { reactor.boxRight = normX.coerceIn(reactor.boxLeft + 0.02f, 1f); reactor.boxBottom = normY.coerceIn(0f, reactor.boxTop - 0.01f) }
                                                4 -> {
                                                    val bw = reactor.boxRight - reactor.boxLeft
                                                    val bh = reactor.boxTop - reactor.boxBottom
                                                    val newL = (normX - bw / 2f).coerceIn(0f, 1f - bw)
                                                    val newB = (normY - bh / 2f).coerceIn(0f, 1f - bh)
                                                    reactor.boxLeft = newL; reactor.boxRight = newL + bw
                                                    reactor.boxBottom = newB; reactor.boxTop = newB + bh
                                                }
                                            }
                                            uiNeedsRefresh = true
                                        } else {
                                            beatDragCorner = -1
                                        }
                                    }
                                } else if (by >= sliderAreaTopHit && by < sliderAreaTopHit + sliderRowH * beatSliders.size) {
                                    // Slider area
                                    val sliderIdx = ((by - sliderAreaTopHit) / sliderRowH).toInt().coerceIn(0, beatSliders.size - 1)
                                    beatDraggingSlider = sliderIdx
                                    beatSliderLaserX = ((bx - 260f) / (984f - 260f)).coerceIn(0f, 1f)
                                    if (rightTrigger > 0.5f) {
                                        // LOCK: first click locks to this slider, stays locked while held
                                        if (beatLockedSlider < 0) beatLockedSlider = sliderIdx
                                        applyBeatSlider(beatLockedSlider, beatSliderLaserX)
                                        beatDraggingSlider = beatLockedSlider
                                        uiNeedsRefresh = true
                                    } else {
                                        beatLockedSlider = -1  // release unlocks
                                    }
                                }
                            } else if (saveNameMode) {
                                hoveredSaveButton = -1
                                hoveredSceneIndex = -1
                                // SAVE button: ~200..260
                                if (by in 190f..270f) hoveredSaveButton = 0
                                // Existing scenes: ~280..880
                                if (by in 280f..880f) {
                                    val maxVisible = 10
                                    val frac = (by - 280f) / (880f - 280f)
                                    val idx = (frac * maxVisible).toInt()
                                    if (idx < savedSceneFiles.size) hoveredSceneIndex = idx
                                }
                                // BACK button at bottom
                                if (by > 920f) hoveredSaveButton = 1
                            } else if (glbPickerMode) {
                                // GLB picker: back button at bottom
                                if (by > 920f) {
                                    hoveredActionButton = 103 // BACK from GLB picker
                                }
                                // GLB file rows: ~120..900 in bitmap Y
                                if (by in 120f..900f) {
                                    val maxVisible = 13
                                    val frac = (by - 120f) / (900f - 120f)
                                    val idx = glbPickerScrollOffset + (frac * maxVisible).toInt()
                                    if (idx < availableGlbFiles.size) {
                                        hoveredGlbIndex = idx
                                    }
                                }
                            } else if (scenePickerMode) {
                                // Scene picker: back button at bottom
                                if (by > 920f) {
                                    hoveredActionButton = 106 // BACK from scene picker
                                }
                                // Scene file rows: ~120..900
                                if (by in 120f..900f) {
                                    val maxVisible = 13
                                    val frac = (by - 120f) / (900f - 120f)
                                    val idx = (frac * maxVisible).toInt()
                                    if (idx < savedSceneFiles.size) {
                                        hoveredSceneIndex = idx
                                    }
                                }
                            } else {
                                // Action buttons: 3 rows at bottom
                                // Row 1 (~810-860): SAVE / LOAD
                                if (by in 800f..860f) {
                                    if (bx < 520f) hoveredActionButton = 104      // SAVE SCENE
                                    else hoveredActionButton = 105                 // LOAD SCENE
                                }
                                // Row 2 (~865-920): ADD / DELETE / RESET
                                if (by in 860f..925f) {
                                    if (bx < 360f) hoveredActionButton = 101      // ADD MODEL
                                    else if (bx < 690f) hoveredActionButton = 107 // DELETE
                                    else hoveredActionButton = 108                 // RESET
                                }
                                // Row 3 (~925-990): AUDIO / FILE MENU / EXIT
                                if (by > 925f) {
                                    if (bx < 360f) hoveredActionButton = 109      // AUDIO
                                    else if (bx < 690f) hoveredActionButton = 100 // FILE MENU
                                    else hoveredActionButton = 102                 // EXIT
                                }

                                // Param rows with section headers (MODEL@0, LIGHTING@5, SYSTEM@13)
                                if (by in 160f..850f) {
                                    val adjustedBy = by - 160f
                                    var acc = 0f; var idx = -1
                                    for (p in 0 until PARAM_NAMES.size) {
                                        if (p == 0 || p == 5 || p == 13) acc += 10f // section header
                                        if (adjustedBy >= acc && adjustedBy < acc + 38f) { idx = p; break }
                                        acc += 38f
                                    }
                                    if (idx >= 0) hoveredMenuParam = idx
                                    lastLaserBx = bx
                                }
                            }
                        }
                    }
                }

                // Drag update — full 3D: place panel along laser ray at grab distance
                if (draggingPanel && renderer != null) {
                    val rg = inputBuffer[7]
                    if (rg > 0.5f) {
                        // Right stick Y to push/pull distance
                        val stickY = inputBuffer[3]
                        if (kotlin.math.abs(stickY) > STICK_DEADZONE) {
                            panelGrabDist += stickY * 0.03f
                            panelGrabDist = panelGrabDist.coerceIn(0.3f, 5f)
                        }
                        // Left stick Y to zoom panel size
                        val leftStickY = inputBuffer[2]
                        if (kotlin.math.abs(leftStickY) > STICK_DEADZONE) {
                            panelScale += leftStickY * 0.02f
                            panelScale = panelScale.coerceIn(0.5f, 2.0f)
                            renderer.panelW = PANEL_WIDTH * panelScale
                            renderer.panelH = PANEL_HEIGHT * panelScale
                        }
                        renderer.panelX = laserHandPos[0] + rayDir[0] * panelGrabDist
                        renderer.panelY = laserHandPos[1] + rayDir[1] * panelGrabDist
                        renderer.panelZ = laserHandPos[2] + rayDir[2] * panelGrabDist
                    } else {
                        draggingPanel = false
                    }
                }

                // Refresh only when hover state actually changes
                if (hoveredMenuParam != lastHoveredMenuParam || hoveredActionButton != lastHoveredActionButton
                    || (glbPickerMode && hoveredGlbIndex != lastHoveredGlbIndex)
                    || (scenePickerMode && hoveredSceneIndex != lastHoveredSceneIndex)) {
                    lastHoveredMenuParam = hoveredMenuParam
                    lastHoveredActionButton = hoveredActionButton
                    lastHoveredGlbIndex = hoveredGlbIndex
                    lastHoveredSceneIndex = hoveredSceneIndex
                    uiNeedsRefresh = true
                }

                // When menu is up, block ALL model/gizmo interaction
                hoveredModelIndex = -1
                hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            }

            // Test gizmo + model only when menu is NOT up
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            val selModel = models.getOrNull(selectedModelIndex)
            if (!menuVisible && gizmoVisible && selModel != null && !gizmoDragging) {
                val gPos = floatArrayOf(selModel.posX, selModel.posY, selModel.posZ)
                val gRot = floatArrayOf(selModel.rotX, selModel.rotY, selModel.rotZ, selModel.rotW)
                val (axis, _) = renderer.testGizmoHit(laserHandPos, rayDir, gPos, gRot)
                hoveredGizmoAxis = axis
            }

            // Test emitter hit
            emitterHovered = false
            if (!menuVisible && !emitterGrabbed) {
                val eDist = renderer.testEmitterHit(laserHandPos, rayDir)
                if (eDist > 0f) emitterHovered = true
            }

            // Emitter grab: grip on hovered emitter → drag along ray, delta-based
            if (emitterHovered && rightSqueeze > 0.5f && !emitterGrabbed) {
                emitterGrabbed = true
                val dx = renderer.emitterPos[0] - laserHandPos[0]
                val dy = renderer.emitterPos[1] - laserHandPos[1]
                val dz = renderer.emitterPos[2] - laserHandPos[2]
                emitterGrabDist = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz).coerceAtLeast(0.1f)
            }
            if (emitterGrabbed) {
                if (rightSqueeze < 0.3f) {
                    emitterGrabbed = false
                } else {
                    // Stick Y adjusts distance along ray
                    val rightThumbY = inputBuffer[3]
                    if (kotlin.math.abs(rightThumbY) > 0.15f) {
                        emitterGrabDist += rightThumbY * 0.02f
                        emitterGrabDist = emitterGrabDist.coerceIn(0.3f, 5f)
                    }
                    // Place emitter along the laser ray at grab distance — no snap
                    renderer.emitterPos = floatArrayOf(
                        laserHandPos[0] + rayDir[0] * emitterGrabDist,
                        laserHandPos[1] + rayDir[1] * emitterGrabDist,
                        laserHandPos[2] + rayDir[2] * emitterGrabDist
                    )
                    // Scene center for light direction
                    var scX = 0f; var scY = 0f; var scZ = 0f
                    if (models.isNotEmpty()) {
                        for (m in models) { scX += m.posX; scY += m.posY; scZ += m.posZ }
                        scX /= models.size; scY /= models.size; scZ /= models.size
                    }
                    renderer.updateLightFromEmitter(scX, scY, scZ)
                }
            }

            // Test model intersection (only if not hovering gizmo/emitter and menu is closed)
            if (!menuVisible && hoveredGizmoAxis == GlesModelRenderer.GIZMO_AXIS_NONE && !emitterHovered && !emitterGrabbed) {
                var nearestDist = Float.MAX_VALUE
                var nearestIdx = -1
                for ((i, placed) in models.withIndex()) {
                    val gpuModel = renderer.getModel(placed.gpuModelId) ?: continue
                    val m = gpuModel.modelMatrix
                    val worldCx = m[0]*gpuModel.boundsCenterX + m[4]*gpuModel.boundsCenterY + m[8]*gpuModel.boundsCenterZ + m[12]
                    val worldCy = m[1]*gpuModel.boundsCenterX + m[5]*gpuModel.boundsCenterY + m[9]*gpuModel.boundsCenterZ + m[13]
                    val worldCz = m[2]*gpuModel.boundsCenterX + m[6]*gpuModel.boundsCenterY + m[10]*gpuModel.boundsCenterZ + m[14]
                    val center = floatArrayOf(worldCx, worldCy, worldCz)
                    val sx = kotlin.math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
                    val worldR = gpuModel.boundsRadius * sx
                    var dist = raySphereIntersect(laserHandPos, rayDir, center, worldR)
                    // Inside bounding sphere: allow selecting by aiming at the center (small core radius)
                    if (dist < 0f) {
                        val coreR = (worldR * 0.15f).coerceIn(0.05f, 0.15f)
                        dist = raySphereIntersect(laserHandPos, rayDir, center, coreR)
                    }
                    if (dist in 0.01f..nearestDist) { nearestDist = dist; nearestIdx = i }
                }
                hoveredModelIndex = nearestIdx
                hitDistance = if (nearestIdx >= 0) nearestDist else -1f
            } else {
                hoveredModelIndex = -1
                hitDistance = -1f
            }

            // Highlight
            for ((i, placed) in models.withIndex()) {
                renderer.getModel(placed.gpuModelId)?.selected = (i == selectedModelIndex) || (i == hoveredModelIndex)
            }

            // ── Gizmo drag with grip ──
            val rightGripForGizmo = rightSqueeze > 0.5f
            if (gizmoDragging) {
                if (rightGripForGizmo && selModel != null) {
                    val worldAxis = renderer.getGizmoWorldAxis(gizmoDragAxis,
                        floatArrayOf(selModel.rotX, selModel.rotY, selModel.rotZ, selModel.rotW))
                    val dx = rightHandPosX - gizmoDragStartHandPos[0]
                    val dy = rightHandPosY - gizmoDragStartHandPos[1]
                    val dz = rightHandPosZ - gizmoDragStartHandPos[2]
                    val proj = dx*worldAxis[0] + dy*worldAxis[1] + dz*worldAxis[2]
                    // 3x sensitivity so you don't need full arm sweeps
                    val scaled = proj * 3f
                    selModel.posX = gizmoDragStartModelPos[0] + worldAxis[0] * scaled
                    selModel.posY = gizmoDragStartModelPos[1] + worldAxis[1] * scaled
                    selModel.posZ = gizmoDragStartModelPos[2] + worldAxis[2] * scaled
                    updateModelTransform(selModel)
                } else {
                    gizmoDragging = false
                }
            } else if (rightGripForGizmo && hoveredGizmoAxis >= 0 && selModel != null) {
                // Start gizmo drag
                gizmoDragging = true
                gizmoDragAxis = hoveredGizmoAxis
                gizmoDragStartHandPos = floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
                gizmoDragStartModelPos = floatArrayOf(selModel.posX, selModel.posY, selModel.posZ)
            }
        } else {
            laserActive = false
            hoveredModelIndex = -1
            hitDistance = -1f
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            gizmoDragging = false
        }

        // Trigger press (edge-detected) = select/deselect or menu select
        val rightTriggerPressed = rightTrigger > 0.5f
        if (rightTriggerPressed && !lastRightTriggerState && !grabbing) {
            // ── Audio Player trigger handling ──
            if (menuVisible && audioPlayerMode && audioPickerMode) {
                when {
                    hoveredAudioFileIndex >= 0 && hoveredAudioFileIndex < availableAudioFiles.size -> {
                        val file = availableAudioFiles[hoveredAudioFileIndex]
                        if (audioPlayer == null) audioPlayer = AudioPlayer(this)
                        audioPlayer?.play(file)
                        // Connect BeatReactor to this audio session
                        val reactor = audioReactor
                        val sid = audioPlayer?.audioSessionId ?: 0
                        if (reactor != null && sid != 0) {
                            reactor.restart(sid)
                            if (!beatReactorEnabled) {
                                beatReactorEnabled = true
                                reactor.enabled = true
                            }
                        }
                        audioPickerMode = false
                        Log.i(TAG, "Audio: playing ${file.name}, session=$sid")
                    }
                    hoveredAudioButton == 50 -> audioPickerMode = false // BACK
                }
                uiNeedsRefresh = true
            } else if (menuVisible && audioPlayerMode && !audioPickerMode) {
                val ap = audioPlayer
                when (hoveredAudioButton) {
                    0 -> ap?.seekBy(-10000) // |<< skip back 10s
                    1 -> ap?.togglePlayPause() // PLAY/PAUSE
                    2 -> ap?.seekBy(10000)  // >>| skip fwd 10s
                    3 -> { ap?.stop(); audioReactor?.restart(0) } // STOP + back to system audio
                    10 -> ap?.cycleSpeed(false) // speed -
                    11 -> ap?.cycleSpeed(true)  // speed +
                    20 -> ap?.setLoopA()
                    21 -> ap?.setLoopB()
                    22 -> ap?.clearLoop()
                    30 -> ap?.cycleRepeat()
                    in 40..43 -> audioPlayer?.setEqPresetByIndex(hoveredAudioButton - 40)
                    51 -> { // BROWSE
                        availableAudioFiles = FilePicker.listVideoFiles(this)
                            .filter { FilePicker.isAudioFile(it) }
                            .sortedBy { it.nameWithoutExtension.lowercase() }
                        audioPickerMode = true
                        audioPickerScrollOffset = 0
                        hoveredAudioFileIndex = -1
                    }
                    52 -> audioPlayerMode = false // BACK to main menu
                    60 -> { // Seekbar click
                        val dur = ap?.durationMs ?: 0
                        if (dur > 0) {
                            val t = ((lastLaserBx - 60f) / (1024f - 120f)).coerceIn(0f, 1f)
                            ap?.seekTo((t * dur).toLong())
                            audioSeekDragging = true
                        }
                    }
                }
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 113) {
                audioReactor?.rolloff = AudioReactor.Rolloff.INSTANT
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 114) {
                audioReactor?.rolloff = AudioReactor.Rolloff.HARD_KNEE
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 115) {
                audioReactor?.rolloff = AudioReactor.Rolloff.SOFT_KNEE
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 116) {
                audioReactor?.rolloff = AudioReactor.Rolloff.THROB
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 117) {
                audioReactor?.colorMode = AudioReactor.ColorMode.FIXED
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 118) {
                audioReactor?.colorMode = AudioReactor.ColorMode.CYCLE
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 119) {
                audioReactor?.colorMode = AudioReactor.ColorMode.FLASH
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 127) {
                // BOOM: snap box to detected high-variance zone
                audioReactor?.lockBoom()
                Log.i(TAG, "BOOM: locked box to ${audioReactor?.boomLeft}-${audioReactor?.boomRight}")
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton in 120..122) {
                audioReactor?.washScope = AudioReactor.WashScope.entries[hoveredActionButton - 120]
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton in 123..126) {
                audioReactor?.blendMode = AudioReactor.BlendMode.entries[hoveredActionButton - 123]
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 111) {
                beatSettingsMode = false
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 112) {
                // OFF button in beat settings
                beatReactorEnabled = false
                audioReactor?.enabled = false
                beatSettingsMode = false
                beatBaseStored = false
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 128) {
                // VIBES: toggle haptic device
                if (hapticConnected) {
                    hapticManager?.disconnect()
                    hapticConnected = false
                    hapticEnabled = false
                } else {
                    if (hapticManager == null) {
                        hapticManager = com.ashairfoil.prism.haptics.BleDeviceManager(this)
                        hapticManager?.onConnectionStateChanged = { state ->
                            when (state) {
                                com.ashairfoil.prism.haptics.ConnectionState.Ready -> {
                                    hapticConnected = true
                                    Log.i(TAG, "Haptic device connected: ${hapticManager?.connectedDeviceName}")
                                    uiNeedsRefresh = true
                                }
                                com.ashairfoil.prism.haptics.ConnectionState.Disconnected -> {
                                    hapticConnected = false
                                    hapticEnabled = false
                                    Log.i(TAG, "Haptic device disconnected")
                                    uiNeedsRefresh = true
                                }
                                else -> {}
                            }
                        }
                    }
                    Log.i(TAG, "Starting haptic BLE scan...")
                    runOnUiThread { hapticManager?.startScan() }
                    hapticEnabled = true
                }
                uiNeedsRefresh = true
            } else if (menuVisible && saveNameMode && hoveredSaveButton == 0) {
                // SAVE with the edited name
                val name = String(saveNameChars, 0, saveNameLen).trim()
                if (name.isNotEmpty()) {
                    Log.i(TAG, "Saving scene as: '$name'")
                    saveScene(name)
                    saveNameMode = false
                }
                uiNeedsRefresh = true
            } else if (menuVisible && saveNameMode && hoveredSaveButton == 1) {
                // BACK from save name editor
                saveNameMode = false
                uiNeedsRefresh = true
            } else if (menuVisible && saveNameMode && hoveredSceneIndex >= 0 && hoveredSceneIndex < savedSceneFiles.size) {
                // Overwrite existing scene
                val existingFile = savedSceneFiles[hoveredSceneIndex]
                val name = existingFile.nameWithoutExtension
                Log.i(TAG, "Overwriting scene: '$name'")
                saveScene(name)
                saveNameMode = false
                uiNeedsRefresh = true
            } else if (menuVisible && glbPickerMode && hoveredActionButton == 103) {
                // BACK from GLB picker → return to main menu
                glbPickerMode = false
                hoveredGlbIndex = -1
                uiNeedsRefresh = true
            } else if (menuVisible && glbPickerMode && hoveredGlbIndex >= 0 && hoveredGlbIndex < availableGlbFiles.size) {
                // Queue GLB for loading on render thread (needs GL context)
                val file = availableGlbFiles[hoveredGlbIndex]
                Log.i(TAG, "GLB picker: queuing ${file.name} for load")
                pendingModelLoad = file
                glbPickerMode = false
                hoveredGlbIndex = -1
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredActionButton == 109) {
                // Open audio player
                Log.i(TAG, "Action: Audio player")
                audioPlayerMode = true
                hoveredAudioButton = -1
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredActionButton == 100) {
                // Back to file menu
                Log.i(TAG, "Action: Return to file menu")
                finish()
                return
            } else if (menuVisible && hoveredActionButton == 102) {
                // Exit app entirely
                Log.i(TAG, "Action: Exit app")
                running = false
                runOnUiThread { finishAffinity() }
                return
            } else if (menuVisible && hoveredActionButton == 104) {
                // Open save name editor
                Log.i(TAG, "Action: Save scene — opening name editor")
                saveNameMode = true
                hoveredSaveButton = -1
                // Default name from first model or "Scene"
                val defaultName = models.firstOrNull()?.file?.nameWithoutExtension?.take(15) ?: "Scene"
                saveNameChars = CharArray(20) { ' ' }
                for (i in defaultName.indices) saveNameChars[i] = defaultName[i]
                saveNameLen = defaultName.length
                saveNameCursor = saveNameLen.coerceAtMost(19)
                saveNameStickCooldown = 0
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredActionButton == 107) {
                // DELETE selected model — yeet it off screen
                val model = models.getOrNull(selectedModelIndex)
                if (model != null) {
                    Log.i(TAG, "Action: Yeet model ${model.file.name}")
                    // Launch it away from camera with random spin
                    val dx = model.posX - camPosX
                    val dz = model.posZ - camPosZ
                    val dist = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
                    val speed = 8f
                    yeetingModels.add(YeetingModel(
                        gpuModelId = model.gpuModelId,
                        posX = model.posX, posY = model.posY, posZ = model.posZ,
                        velX = (dx / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        velY = 3f + Math.random().toFloat() * 2f,
                        velZ = (dz / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        scale = model.scale
                    ))
                    models.removeAt(selectedModelIndex)
                    selectedModelIndex = if (models.isNotEmpty()) 0 else -1
                    uiNeedsRefresh = true
                }
            } else if (menuVisible && hoveredActionButton == 108) {
                // RESET all per-model material params to defaults
                val model = models.getOrNull(selectedModelIndex)
                val gr = glesRenderer
                if (model != null && gr != null) {
                    val gpuModel = gr.getModel(model.gpuModelId)
                    model.metallic = 0f; model.roughness = 0.9f; model.exposure = 0f
                    model.contrast = 1f; model.saturation = 1f
                    gpuModel?.metallic = 0f; gpuModel?.roughness = 0.9f; gpuModel?.exposure = 0f
                    gpuModel?.contrast = 1f; gpuModel?.saturation = 1f
                    Log.i(TAG, "Action: Reset material for ${model.file.name}")
                    uiNeedsRefresh = true
                }
            } else if (menuVisible && hoveredActionButton == 105) {
                // Open scene load picker
                Log.i(TAG, "Action: Load scene — opening scene picker")
                refreshSceneList()
                scenePickerMode = true
                hoveredSceneIndex = -1
                uiNeedsRefresh = true
            } else if (menuVisible && scenePickerMode && hoveredActionButton == 106) {
                // BACK from scene picker
                scenePickerMode = false
                hoveredSceneIndex = -1
                uiNeedsRefresh = true
            } else if (menuVisible && scenePickerMode && hoveredSceneIndex >= 0 && hoveredSceneIndex < savedSceneFiles.size) {
                // Queue scene for loading on render thread
                pendingSceneLoad = savedSceneFiles[hoveredSceneIndex]
                scenePickerMode = false
                hoveredSceneIndex = -1
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredActionButton == 101) {
                // Add object — show GLB picker on the same menu panel
                Log.i(TAG, "Action: Add object — opening GLB picker")
                glbPickerMode = true
                hoveredGlbIndex = -1
                glbPickerScrollOffset = 0
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredMenuParam in 0..12) {
                // Slider click: set value from laser X position
                selectedParam = hoveredMenuParam
                val sliderLeft = 240f; val sliderRight = 904f
                if (lastLaserBx in sliderLeft..sliderRight) {
                    val t = ((lastLaserBx - sliderLeft) / (sliderRight - sliderLeft)).coerceIn(0f, 1f)
                    val range = PARAM_RANGES[hoveredMenuParam]
                    setParamValue(hoveredMenuParam, range.first + t * (range.second - range.first))
                    sliderDragging = hoveredMenuParam
                }
                uiNeedsRefresh = true
            } else if (menuVisible && hoveredMenuParam >= 13) {
                // Select the param the laser is pointing at
                selectedParam = hoveredMenuParam
                // Toggle params: trigger press directly toggles
                if (hoveredMenuParam == 13) {
                    if (!beatReactorEnabled) {
                        // Turn on
                        beatReactorEnabled = true
                        val reactor = audioReactor
                        if (reactor != null) {
                            reactor.enabled = true
                            if (!reactor.isActive) reactor.start()
                        }
                        Log.i(TAG, "BeatReactor ON")
                    } else {
                        // Already on — open settings panel
                        beatSettingsMode = true
                        Log.i(TAG, "BeatReactor settings opened")
                    }
                } else if (hoveredMenuParam == 14) {
                    foveationLevel = (foveationLevel + 1) % 4
                    nativeSetFoveationLevel(foveationLevel)
                    Log.i(TAG, "Foveation: $foveationLevel")
                } else if (hoveredMenuParam == 15) {
                    textureQuality = (textureQuality + 1) % 4
                    Log.i(TAG, "Tex quality: $textureQuality (${arrayOf("Auto","4096","2048","1024")[textureQuality]})")
                    reloadAllModels()
                } else if (hoveredMenuParam == 16) {
                    val gr = glesRenderer
                    if (gr != null) {
                        gr.showPlaneVisualization = !gr.showPlaneVisualization
                        Log.i(TAG, "Plane visualization: ${gr.showPlaneVisualization}")
                    }
                }
                uiNeedsRefresh = true
            } else if (hoveredModelIndex >= 0 && hoveredModelIndex != selectedModelIndex) {
                // Select a different model
                selectedModelIndex = hoveredModelIndex
                uiNeedsRefresh = true
            } else if (hoveredModelIndex == selectedModelIndex && selectedModelIndex >= 0) {
                // Clicking already-selected model = deselect
                selectedModelIndex = -1
                if (renderer != null) {
                    for (placed in models) {
                        renderer.getModel(placed.gpuModelId)?.selected = false
                    }
                }
                uiNeedsRefresh = true
            } else {
                // Pointing at nothing = deselect
                selectedModelIndex = -1
                if (renderer != null) {
                    for (placed in models) {
                        renderer.getModel(placed.gpuModelId)?.selected = false
                    }
                }
                uiNeedsRefresh = true
            }
        }
        // Slider drag: while trigger held and dragging a slider, update continuously
        if (rightTriggerPressed && sliderDragging in 0..12 && menuVisible) {
            val sliderLeft = 240f; val sliderRight = 904f
            if (lastLaserBx in sliderLeft..sliderRight) {
                val t = ((lastLaserBx - sliderLeft) / (sliderRight - sliderLeft)).coerceIn(0f, 1f)
                val range = PARAM_RANGES[sliderDragging]
                setParamValue(sliderDragging, range.first + t * (range.second - range.first))
                uiNeedsRefresh = true
            }
        }
        if (!rightTriggerPressed) sliderDragging = -1
        lastRightTriggerState = rightTriggerPressed

        // Right stick click = toggle grid
        val rightStickClick = inputBuffer[14] > 0.5f
        if (rightStickClick && !lastRightStickClick && !menuVisible) {
            gridVisible = !gridVisible
            uiNeedsRefresh = true
        }
        // (lastRightStickClick updated in menu section below)

        // Grid height: grip + stick Y with nothing selected → adjust grid Y
        if (selectedModelIndex < 0 && rightHandValid && rightSqueeze > 0.5f && !emitterGrabbed && renderer != null) {
            if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                renderer.gridHeight += rightThumbY * 0.02f
                renderer.gridHeight = renderer.gridHeight.coerceIn(-3f, 3f)
                uiNeedsRefresh = true
            }
        }

        // X = toggle gizmo
        if (xButton && !lastXState) {
            gizmoVisible = !gizmoVisible
            uiNeedsRefresh = true
        }
        lastXState = xButton

        // B = toggle menu panel (or close sub-pickers first)
        if (bButton && !lastBState) {
            if (audioPickerMode) {
                audioPickerMode = false
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (audioPlayerMode) {
                audioPlayerMode = false
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (glbPickerMode) {
                glbPickerMode = false
                hoveredGlbIndex = -1
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (beatSettingsMode) {
                beatSettingsMode = false
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (saveNameMode) {
                saveNameMode = false
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (scenePickerMode) {
                scenePickerMode = false
                hoveredSceneIndex = -1
                uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            menuVisible = !menuVisible
            if (menuVisible && renderer != null) {
                // Apply current scale
                renderer.panelW = PANEL_WIDTH * panelScale
                renderer.panelH = PANEL_HEIGHT * panelScale
                // Place panel 1.2m in front of the user's head
                renderer.panelX = camPosX + camFwdX * 1.2f
                renderer.panelY = camPosY + camFwdY * 1.2f
                renderer.panelZ = camPosZ + camFwdZ * 1.2f

                // Compute Y-axis rotation so quad faces the camera
                // Default quad faces -Z. Rotate around Y so it faces back toward user.
                val yaw = kotlin.math.atan2(-camFwdX, -camFwdZ)
                panelRotX = 0f
                panelRotY = kotlin.math.sin(yaw * 0.5f)
                panelRotZ = 0f
                panelRotW = kotlin.math.cos(yaw * 0.5f)
            }
            uiNeedsRefresh = true
        }
        lastBState = bButton

        // When menu is visible: param controls (works with or without model selected)
        if (menuVisible) {
            // Save name editor: stick edits the name
            if (saveNameMode) {
                if (saveNameStickCooldown > 0) saveNameStickCooldown--
                if (saveNameStickCooldown == 0) {
                    // Right stick Y = cycle character at cursor
                    if (rightThumbY > 0.4f) {
                        val curChar = saveNameChars[saveNameCursor]
                        val idx = SAVE_NAME_CHARS.indexOf(curChar)
                        saveNameChars[saveNameCursor] = if (idx < 0) 'A' else SAVE_NAME_CHARS[(idx + 1) % SAVE_NAME_CHARS.length]
                        if (saveNameCursor >= saveNameLen) saveNameLen = saveNameCursor + 1
                        saveNameStickCooldown = 6  // ~80ms debounce at 72fps
                        uiNeedsRefresh = true
                    } else if (rightThumbY < -0.4f) {
                        val curChar = saveNameChars[saveNameCursor]
                        val idx = SAVE_NAME_CHARS.indexOf(curChar)
                        saveNameChars[saveNameCursor] = if (idx < 0) 'Z' else SAVE_NAME_CHARS[(idx - 1 + SAVE_NAME_CHARS.length) % SAVE_NAME_CHARS.length]
                        if (saveNameCursor >= saveNameLen) saveNameLen = saveNameCursor + 1
                        saveNameStickCooldown = 6
                        uiNeedsRefresh = true
                    }
                    // Right stick X = move cursor
                    if (rightThumbX > 0.5f) {
                        saveNameCursor = (saveNameCursor + 1).coerceAtMost(19)
                        saveNameStickCooldown = 8
                        uiNeedsRefresh = true
                    } else if (rightThumbX < -0.5f) {
                        saveNameCursor = (saveNameCursor - 1).coerceAtLeast(0)
                        saveNameStickCooldown = 8
                        uiNeedsRefresh = true
                    }
                    // A button = delete character at cursor (backspace)
                    if (aButton && !lastAState && saveNameLen > 0) {
                        if (saveNameCursor < saveNameLen) {
                            for (i in saveNameCursor until 19) saveNameChars[i] = saveNameChars[i + 1]
                            saveNameChars[19] = ' '
                            saveNameLen = (saveNameLen - 1).coerceAtLeast(0)
                        }
                        uiNeedsRefresh = true
                    }
                    lastAState = aButton
                }
                lastRightStickClick = rightStickClick
                if (uiNeedsRefresh) {
                    uiNeedsRefresh = false
                    runOnUiThread { renderUiToBitmap() }
                }
                return
            }

            // GLB picker mode: stick scrolls the file list
            if (audioPickerMode) {
                if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                    val maxVisible = 10
                    val maxScroll = (availableAudioFiles.size - maxVisible).coerceAtLeast(0)
                    if (rightThumbY < -STICK_DEADZONE && audioPickerScrollOffset < maxScroll) {
                        audioPickerScrollOffset++; uiNeedsRefresh = true
                    } else if (rightThumbY > STICK_DEADZONE && audioPickerScrollOffset > 0) {
                        audioPickerScrollOffset--; uiNeedsRefresh = true
                    }
                }
                lastRightStickClick = rightStickClick
                if (uiNeedsRefresh) { uiNeedsRefresh = false; runOnUiThread { renderUiToBitmap() } }
                lastRightStickClick = rightStickClick
                return
            }
            if (glbPickerMode) {
                if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                    val maxVisible = 13
                    val maxScroll = (availableGlbFiles.size - maxVisible).coerceAtLeast(0)
                    if (rightThumbY < -STICK_DEADZONE && glbPickerScrollOffset < maxScroll) {
                        glbPickerScrollOffset++
                        uiNeedsRefresh = true
                    } else if (rightThumbY > STICK_DEADZONE && glbPickerScrollOffset > 0) {
                        glbPickerScrollOffset--
                        uiNeedsRefresh = true
                    }
                }
                lastRightStickClick = rightStickClick
                if (uiNeedsRefresh) {
                    uiNeedsRefresh = false
                    runOnUiThread { renderUiToBitmap() }
                }
                return
            }

            val model = models.getOrNull(selectedModelIndex)

            // Right stick click = cycle parameter (reuse rightStickClick from above)
            if (rightStickClick && !lastRightStickClick) {
                selectedParam = (selectedParam + 1) % PARAM_NAMES.size
                uiNeedsRefresh = true
            }

            // Right thumbstick up/down = adjust selected parameter
            if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE && renderer != null) {
                val delta = rightThumbY * 0.015f
                val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
                when (selectedParam) {
                    // Per-model params (skip if no model selected)
                    0 -> if (model != null) {
                        model.metallic = (model.metallic + delta).coerceIn(0f, 1f)
                        gpuModel?.metallic = model.metallic
                    }
                    1 -> if (model != null) {
                        model.roughness = (model.roughness + delta).coerceIn(0.05f, 1f)
                        gpuModel?.roughness = model.roughness
                    }
                    2 -> if (model != null) {
                        model.exposure = (model.exposure + delta * 3f).coerceIn(-5f, 5f)
                        gpuModel?.exposure = model.exposure
                    }
                    3 -> if (model != null) {
                        model.contrast = (model.contrast + delta * 0.2f).coerceIn(0.85f, 1.15f)
                        gpuModel?.contrast = model.contrast
                    }
                    4 -> if (model != null) {
                        model.saturation = (model.saturation + delta * 2f).coerceIn(0f, 3f)
                        gpuModel?.saturation = model.saturation
                    }
                    // Global params (always work)
                    5 -> renderer.lightIntensity = (renderer.lightIntensity + delta * 2f).coerceIn(0f, 10f)
                    6 -> renderer.fillLightIntensity = (renderer.fillLightIntensity + delta).coerceIn(0f, 5f)
                    7 -> {
                        autoAmbient = false
                        renderer.ambientIntensity = (renderer.ambientIntensity + delta).coerceIn(0f, 5f)
                    }
                    8 -> {
                        renderer.lightAngleDeg = (renderer.lightAngleDeg + delta * 20f) % 360f
                        if (renderer.lightAngleDeg < 0f) renderer.lightAngleDeg += 360f
                        renderer.updateLightDirFromAngles()
                    }
                    9 -> {
                        renderer.lightElevDeg = (renderer.lightElevDeg + delta * 10f).coerceIn(5f, 90f)
                        renderer.updateLightDirFromAngles()
                    }
                    10 -> renderer.shadowDarkness = (renderer.shadowDarkness + delta).coerceIn(0f, 1f)
                    11 -> renderer.shadowSoftness = (renderer.shadowSoftness + delta * 5f).coerceIn(0.5f, 5f)
                    12 -> renderer.shadowSpread = (renderer.shadowSpread + delta * 10f).coerceIn(2f, 30f)
                    13 -> {
                        // BeatReactor toggle (one-shot on stick push past threshold)
                        if (!beatToggleLatch && kotlin.math.abs(rightThumbY) > 0.5f) {
                            beatToggleLatch = true
                            beatReactorEnabled = !beatReactorEnabled
                            Log.i(TAG, "BeatReactor toggled: $beatReactorEnabled")
                            val reactor = audioReactor
                            if (beatReactorEnabled && reactor != null) {
                                reactor.enabled = true
                                val started = if (!reactor.isActive) reactor.start() else true
                                Log.i(TAG, "BeatReactor started=$started, isActive=${reactor.isActive}")
                            } else {
                                reactor?.enabled = false
                            }
                        }
                        if (kotlin.math.abs(rightThumbY) < 0.3f) beatToggleLatch = false
                    }
                    14 -> {
                        // Foveation level toggle (one-shot)
                        if (!foveationToggleLatch && kotlin.math.abs(rightThumbY) > 0.5f) {
                            foveationToggleLatch = true
                            foveationLevel = if (rightThumbY > 0) {
                                (foveationLevel + 1).coerceAtMost(3)
                            } else {
                                (foveationLevel - 1).coerceAtLeast(0)
                            }
                            nativeSetFoveationLevel(foveationLevel)
                        }
                        if (kotlin.math.abs(rightThumbY) < 0.3f) foveationToggleLatch = false
                    }
                    15 -> {
                        // Tex Quality toggle (one-shot)
                        if (!foveationToggleLatch && kotlin.math.abs(rightThumbY) > 0.5f) {
                            foveationToggleLatch = true
                            textureQuality = if (rightThumbY > 0) {
                                (textureQuality + 1) % 4
                            } else {
                                (textureQuality - 1 + 4) % 4
                            }
                            reloadAllModels()
                        }
                        if (kotlin.math.abs(rightThumbY) < 0.3f) foveationToggleLatch = false
                    }
                    16 -> {
                        // Show Planes toggle (one-shot)
                        if (!planeVisToggleLatch && kotlin.math.abs(rightThumbY) > 0.5f) {
                            planeVisToggleLatch = true
                            val gr = glesRenderer
                            if (gr != null) {
                                gr.showPlaneVisualization = !gr.showPlaneVisualization
                                Log.i(TAG, "Plane visualization: ${gr.showPlaneVisualization}")
                            }
                        }
                        if (kotlin.math.abs(rightThumbY) < 0.3f) planeVisToggleLatch = false
                    }
                }
                uiNeedsRefresh = true
            }

            // A button = reset selected parameter
            if (aButton && !lastAState && renderer != null) {
                val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
                when (selectedParam) {
                    0 -> if (model != null) { model.metallic = 0f; gpuModel?.metallic = 0f }
                    1 -> if (model != null) { model.roughness = 0.9f; gpuModel?.roughness = 0.9f }
                    2 -> if (model != null) { model.exposure = 0f; gpuModel?.exposure = 0f }
                    3 -> if (model != null) { model.contrast = 1f; gpuModel?.contrast = 1f }
                    4 -> if (model != null) { model.saturation = 1f; gpuModel?.saturation = 1f }
                    5 -> renderer.lightIntensity = 2.0f
                    6 -> renderer.fillLightIntensity = 0.5f
                    7 -> { autoAmbient = true; renderer.ambientIntensity = 1.0f }
                    8 -> { renderer.lightAngleDeg = 0f; renderer.updateLightDirFromAngles() }
                    9 -> { renderer.lightElevDeg = 60f; renderer.updateLightDirFromAngles() }
                    10 -> renderer.shadowDarkness = 0.7f
                    11 -> renderer.shadowSoftness = 2.0f
                    12 -> renderer.shadowSpread = 8f
                    13 -> {
                        beatReactorEnabled = false
                        audioReactor?.enabled = false
                    }
                    14 -> { foveationLevel = 0; nativeSetFoveationLevel(0) }
                    15 -> { textureQuality = 0; reloadAllModels() }
                    16 -> { glesRenderer?.showPlaneVisualization = false }
                }
                uiNeedsRefresh = true
            }
            lastAState = aButton

            if (uiNeedsRefresh) {
                uiNeedsRefresh = false
                runOnUiThread { renderUiToBitmap() }
            }
            lastRightStickClick = rightStickClick
            return
        }
        lastRightStickClick = rightStickClick

        if (handsLocked) return

        // ── Model manipulation (skip if gizmo is being dragged) ──
        if (gizmoDragging) return

        val leftGripHeld = leftSqueeze > 0.5f && leftHandValid
        val rightGripHeld = rightSqueeze > 0.5f && rightHandValid
        val isGrabbing = leftGripHeld || rightGripHeld

        // Auto-select hovered model on grip (no trigger needed)
        if (isGrabbing && selectedModelIndex !in models.indices && hoveredModelIndex >= 0) {
            selectedModelIndex = hoveredModelIndex
            if (renderer != null) {
                for ((i, placed) in models.withIndex()) {
                    renderer.getModel(placed.gpuModelId)?.selected = (i == selectedModelIndex)
                }
            }
        }
        if (selectedModelIndex !in models.indices) return
        val selected = models[selectedModelIndex]

        val grabHandPos = when {
            rightGripHeld -> floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
            leftGripHeld -> floatArrayOf(leftHandPosX, leftHandPosY, leftHandPosZ)
            else -> null
        }
        val grabAimRot = when {
            rightGripHeld && rightAimValid -> floatArrayOf(rightAimRotX, rightAimRotY, rightAimRotZ, rightAimRotW)
            leftGripHeld && leftAimValid -> floatArrayOf(leftAimRotX, leftAimRotY, leftAimRotZ, leftAimRotW)
            else -> null
        }
        val grabThumbX = when {
            rightGripHeld -> rightThumbX
            leftGripHeld -> leftThumbX
            else -> 0f
        }
        val grabThumbY = when {
            rightGripHeld -> rightThumbY
            leftGripHeld -> leftThumbY
            else -> 0f
        }

        if (isGrabbing && grabHandPos != null && grabAimRot != null) {
            if (!grabbing) {
                // Grab start — lock to where the laser actually hits, not the pivot
                grabbing = true
                val fwdInit = glesRenderer?.quatForward(grabAimRot) ?: floatArrayOf(0f, 0f, -1f)
                // Use laser hit distance if available, else distance to object center
                if (hitDistance > 0f) {
                    grabDistance = hitDistance
                } else {
                    val dx = selected.posX - grabHandPos[0]
                    val dy = selected.posY - grabHandPos[1]
                    val dz = selected.posZ - grabHandPos[2]
                    grabDistance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                }
                if (grabDistance < 0.1f) grabDistance = 0.5f
                // Compute offset in controller-local space so it rotates with hand
                val hitX = grabHandPos[0] + fwdInit[0] * grabDistance
                val hitY = grabHandPos[1] + fwdInit[1] * grabDistance
                val hitZ = grabHandPos[2] + fwdInit[2] * grabDistance
                val worldOff = floatArrayOf(selected.posX - hitX, selected.posY - hitY, selected.posZ - hitZ)
                // Transform to controller-local space (inverse quat = conjugate for unit quat)
                val invAim = floatArrayOf(-grabAimRot[0], -grabAimRot[1], -grabAimRot[2], grabAimRot[3])
                grabOffset = glesRenderer?.rotateVecByQuat(worldOff, invAim) ?: worldOff
                grabStartAimRot = grabAimRot.copyOf()
                grabStartModelRot = floatArrayOf(selected.rotX, selected.rotY, selected.rotZ, selected.rotW)
            }

            // Object follows the laser ray, offset from grab point to pivot preserved
            val fwd = glesRenderer?.quatForward(grabAimRot) ?: floatArrayOf(0f, 0f, -1f)

            // Stick Y = push/pull (adjust distance along ray)
            if (kotlin.math.abs(grabThumbY) > STICK_DEADZONE) {
                grabDistance += grabThumbY * 0.05f
                grabDistance = grabDistance.coerceIn(0.05f, 30f)
            }

            // Stick X = scale
            if (kotlin.math.abs(grabThumbX) > STICK_DEADZONE) {
                selected.scale = (selected.scale + grabThumbX * 0.03f).coerceIn(0.05f, 10f)
            }

            // Position = hit point + offset rotated by current aim
            val rotOff = glesRenderer?.rotateVecByQuat(grabOffset, grabAimRot) ?: grabOffset
            selected.posX = grabHandPos[0] + fwd[0] * grabDistance + rotOff[0]
            selected.posY = grabHandPos[1] + fwd[1] * grabDistance + rotOff[1]
            selected.posZ = grabHandPos[2] + fwd[2] * grabDistance + rotOff[2]

            // Grip = move + rotate (ShapesXR style — no trigger needed)
            run {
                val isx = -grabStartAimRot[0].toDouble()
                val isy = -grabStartAimRot[1].toDouble()
                val isz = -grabStartAimRot[2].toDouble()
                val isw = grabStartAimRot[3].toDouble()
                val cx = grabAimRot[0].toDouble()
                val cy = grabAimRot[1].toDouble()
                val cz = grabAimRot[2].toDouble()
                val cw = grabAimRot[3].toDouble()

                val relW = (cw*isw - cx*isx - cy*isy - cz*isz).toFloat()
                val relX = (cw*isx + cx*isw + cy*isz - cz*isy).toFloat()
                val relY = (cw*isy - cx*isz + cy*isw + cz*isx).toFloat()
                val relZ = (cw*isz + cx*isy - cy*isx + cz*isw).toFloat()

                val sr = grabStartModelRot
                selected.rotX = relW * sr[0] + relX * sr[3] + relY * sr[2] - relZ * sr[1]
                selected.rotY = relW * sr[1] - relX * sr[2] + relY * sr[3] + relZ * sr[0]
                selected.rotZ = relW * sr[2] + relX * sr[1] - relY * sr[0] + relZ * sr[3]
                selected.rotW = relW * sr[3] - relX * sr[0] - relY * sr[1] - relZ * sr[2]
            }

            updateModelTransform(selected)
        } else {
            grabbing = false
        }

        // Free thumbstick = rotate/height
        if (!isGrabbing && !menuVisible) {
            val hAxis = if (kotlin.math.abs(rightThumbX) > kotlin.math.abs(leftThumbX))
                rightThumbX else leftThumbX
            if (kotlin.math.abs(hAxis) > STICK_DEADZONE) {
                val angle = -hAxis * 0.07f
                val halfAngle = angle / 2f
                val sinH = kotlin.math.sin(halfAngle)
                val cosH = kotlin.math.cos(halfAngle)

                val dx = 0f; val dy = sinH; val dz = 0f; val dw = cosH

                val cr = selected.rotX; val ci = selected.rotY
                val cj = selected.rotZ; val ck = selected.rotW
                selected.rotW = (dw * ck - dx * cr - dy * ci - dz * cj)
                selected.rotX = (dw * cr + dx * ck + dy * cj - dz * ci)
                selected.rotY = (dw * ci - dx * cj + dy * ck + dz * cr)
                selected.rotZ = (dw * cj + dx * ci - dy * cr + dz * ck)
                updateModelTransform(selected)
            }

            val vAxis = leftThumbY
            if (kotlin.math.abs(vAxis) > STICK_DEADZONE) {
                selected.posY += vAxis * 0.06f
                updateModelTransform(selected)
            }
        }

        // Left stick click = toggle light emitter visibility
        val leftStickClick = inputBuffer[13] > 0.5f
        if (leftStickClick && !lastLeftStickClick) {
            val gr = glesRenderer
            if (gr != null) {
                gr.emitterVisible = !gr.emitterVisible
                Log.i(TAG, "Light emitter ${if (gr.emitterVisible) "ON" else "OFF"}")
            }
        }
        lastLeftStickClick = leftStickClick

        // Y = cycle selected model
        if (yButton && !lastYState && models.size > 1) {
            selectedModelIndex = (selectedModelIndex + 1) % models.size
            if (menuVisible) uiNeedsRefresh = true
        }
        lastYState = yButton

        if (aButton && !lastAState && !menuVisible) {
            // Snap selected model to nearest detected surface (or grid floor)
            val model = models.getOrNull(selectedModelIndex)
            val gr = glesRenderer
            if (model != null && gr != null) {
                val gpuModel = gr.getModel(model.gpuModelId)
                if (gpuModel != null) {
                    // Find nearest horizontal plane to snap to
                    var snapY = gr.gridHeight
                    val planes = gr.shadowPlanes
                    if (planes.isNotEmpty()) {
                        var bestDist = Float.MAX_VALUE
                        for (p in planes) {
                            if (p.label != 1 && p.label != 4) continue // only floor/table
                            val dx = model.posX - p.posX
                            val dz = model.posZ - p.posZ
                            val hDist = kotlin.math.sqrt(dx*dx + dz*dz)
                            // Check if model is within the plane's extents (with margin)
                            if (hDist < maxOf(p.extentX, p.extentY) * 1.5f && hDist < bestDist) {
                                bestDist = hDist
                                snapY = p.posY
                            }
                        }
                    }
                    // Model's lowest point in local space = boundsMinY
                    // In world space: posY + boundsMinY * scale
                    // Snap so lowest point = snapY
                    val worldMinY = model.posY + gpuModel.boundsMinY * model.scale
                    model.posY += (snapY - worldMinY)
                    updateModelTransform(model)
                    Log.i(TAG, "Snap to surface: posY=${model.posY} (minY=${gpuModel.boundsMinY}, snapY=$snapY)")
                }
            }
        }
        lastAState = aButton

        if (uiNeedsRefresh) {
            uiNeedsRefresh = false
            runOnUiThread { renderUiToBitmap() }
        }
    }

    /** Set an absolute value for slider param 0-12 */
    private fun setParamValue(idx: Int, value: Float) {
        val renderer = glesRenderer ?: return
        val model = models.getOrNull(selectedModelIndex)
        val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
        when (idx) {
            0 -> if (model != null) { model.metallic = value; gpuModel?.metallic = value }
            1 -> if (model != null) { model.roughness = value; gpuModel?.roughness = value }
            2 -> if (model != null) { model.exposure = value; gpuModel?.exposure = value }
            3 -> if (model != null) { model.contrast = value; gpuModel?.contrast = value }
            4 -> if (model != null) { model.saturation = value; gpuModel?.saturation = value }
            5 -> renderer.lightIntensity = value
            6 -> renderer.fillLightIntensity = value
            7 -> { autoAmbient = false; renderer.ambientIntensity = value }
            8 -> { renderer.lightAngleDeg = value; renderer.updateLightDirFromAngles() }
            9 -> { renderer.lightElevDeg = value; renderer.updateLightDirFromAngles() }
            10 -> renderer.shadowDarkness = value
            11 -> renderer.shadowSoftness = value
            12 -> renderer.shadowSpread = value
        }
    }

    /** Render HUD text panel to bitmap */
    private fun renderUiToBitmap() {
        val uiW = 1024
        val uiH = 1024
        val bitmap = android.graphics.Bitmap.createBitmap(uiW, uiH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        // ═══ ChloeVibes Neon Theme ═══
        // Background: dark with subtle purple gradient
        val bgPaint = android.graphics.Paint()
        val bgGrad = android.graphics.LinearGradient(
            0f, 0f, 0f, uiH.toFloat(),
            intArrayOf(0xE8100818.toInt(), 0xE80A0A14.toInt(), 0xE8120818.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP)
        bgPaint.shader = bgGrad
        canvas.drawRect(0f, 0f, uiW.toFloat(), uiH.toFloat(), bgPaint)

        // Neon border glow
        val borderPink = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFEC4899.toInt()
            maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.OUTER)
            isAntiAlias = true
        }
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, borderPink)
        val borderSolid = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f; color = 0xAAEC4899.toInt()
            isAntiAlias = true
        }
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, borderSolid)

        // ── Title bar (drag zone) — illuminates on hover ──
        val titleHovered = hoveredActionButton == 200
        val titleBg = android.graphics.Paint().apply {
            color = if (titleHovered) 0x80EC4899.toInt() else 0x40EC4899.toInt()
        }
        canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, titleBg)
        if (titleHovered) {
            val glow = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f
                color = 0xFFEC4899.toInt()
                maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.OUTER)
                isAntiAlias = true
            }
            canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, glow)
        }

        val titlePaint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 48f
            color = if (titleHovered) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            isFakeBoldText = true
            maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.SOLID)
        }
        canvas.drawText("ChloeVR", 50f, 62f, titlePaint)
        val dragHint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 24f
            color = if (titleHovered) 0xFFEC4899.toInt() else 0x60FFFFFF.toInt()
        }
        canvas.drawText(if (draggingPanel) "dragging..." else "grip to drag", uiW - 280f, 56f, dragHint)

        // ═══ BeatReactor Settings ═══
        if (beatSettingsMode) {
            val reactor = audioReactor
            val p = android.graphics.Paint().apply { isAntiAlias = true }

            // Auto-refresh spectrum every 3 frames while settings panel is open
            if (reactor != null && reactor.isActive && sensorPollFrame % 3 == 0) {
                uiNeedsRefresh = true
            }

            // Header
            p.textSize = 34f; p.color = 0xFFEC4899.toInt(); p.isFakeBoldText = true
            canvas.drawText("BEATREACTOR", 50f, 105f, p)

            // FILL display top right
            val fillPct = reactor?.boxFillPct ?: 0f
            p.textSize = 38f; p.color = 0xFFFFFFFF.toInt()
            canvas.drawText("FILL: %.0f%%".format(fillPct * 100), 550f, 105f, p)
            p.isFakeBoldText = false

            // Roll-off mode buttons
            val curMode = reactor?.rolloff ?: AudioReactor.Rolloff.SOFT_KNEE
            val modes = arrayOf("INSTANT" to AudioReactor.Rolloff.INSTANT, "HARD" to AudioReactor.Rolloff.HARD_KNEE, "SOFT" to AudioReactor.Rolloff.SOFT_KNEE, "THROB" to AudioReactor.Rolloff.THROB)
            val modeHoverIds = arrayOf(113, 114, 115, 116)
            var mx = 300f
            for ((mi, pair) in modes.withIndex()) {
                val (label, modeVal) = pair
                val isActive = curMode == modeVal
                val isHover = hoveredActionButton == modeHoverIds[mi]
                val mw = 120f
                p.color = when {
                    isActive -> 0xFFEC4899.toInt()
                    isHover -> 0x60EC4899.toInt()
                    else -> 0x20EC4899.toInt()
                }
                canvas.drawRoundRect(mx, 108f, mx + mw, 133f, 6f, 6f, p)
                p.textSize = 20f; p.isFakeBoldText = isActive
                p.color = if (isActive || isHover) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt()
                p.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText(label, mx + mw / 2f, 126f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
                mx += mw + 10f
            }

            // Color mode buttons (right side of header)
            val curColorMode = reactor?.colorMode ?: AudioReactor.ColorMode.CYCLE
            val colorModes = arrayOf("FIXED" to AudioReactor.ColorMode.FIXED, "CYCLE" to AudioReactor.ColorMode.CYCLE, "FLASH" to AudioReactor.ColorMode.FLASH)
            val colorHoverIds = arrayOf(117, 118, 119)
            p.textSize = 16f
            var cx = 50f
            for ((ci, pair) in colorModes.withIndex()) {
                val (clabel, cmode) = pair
                val isActive = curColorMode == cmode
                val isHover = hoveredActionButton == colorHoverIds[ci]
                val cw = 85f
                p.color = when {
                    isActive -> 0xFF10B981.toInt()
                    isHover -> 0x6010B981.toInt()
                    else -> 0x2010B981.toInt()
                }
                canvas.drawRoundRect(cx, 130f, cx + cw, 148f, 4f, 4f, p)
                p.color = if (isActive || isHover) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
                p.isFakeBoldText = isActive
                p.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText(clabel, cx + cw / 2f, 145f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
                cx += cw + 6f
            }

            // Scope buttons (what the beat affects)
            val curScope = reactor?.washScope ?: AudioReactor.WashScope.BOTH
            val scopes = arrayOf("GLBs" to AudioReactor.WashScope.MODELS, "ROOM" to AudioReactor.WashScope.ROOM, "BOTH" to AudioReactor.WashScope.BOTH)
            val scopeIds = arrayOf(120, 121, 122)
            cx = 350f
            for ((si, spair) in scopes.withIndex()) {
                val (slabel, smode) = spair
                val isAct = curScope == smode
                val isHov = hoveredActionButton == scopeIds[si]
                val sw = 85f
                p.color = if (isAct) 0xFF8B5CF6.toInt() else if (isHov) 0x608B5CF6.toInt() else 0x208B5CF6.toInt()
                canvas.drawRoundRect(cx, 130f, cx + sw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
                p.isFakeBoldText = isAct; p.textSize = 16f
                p.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText(slabel, cx + sw / 2f, 145f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
                cx += sw + 6f
            }

            // Blend mode buttons
            val curBlend = reactor?.blendMode ?: AudioReactor.BlendMode.ADD
            val blends = arrayOf("NORM" to AudioReactor.BlendMode.NORMAL, "ADD" to AudioReactor.BlendMode.ADD, "MULT" to AudioReactor.BlendMode.MULTIPLY, "SCRN" to AudioReactor.BlendMode.SCREEN)
            val blendIds = arrayOf(123, 124, 125, 126)
            cx += 20f
            for ((bi2, bpair) in blends.withIndex()) {
                val (blabel, bmode) = bpair
                val isAct = curBlend == bmode
                val isHov = hoveredActionButton == blendIds[bi2]
                val bw = 68f
                p.color = if (isAct) 0xFFFF9500.toInt() else if (isHov) 0x60FF9500.toInt() else 0x20FF9500.toInt()
                canvas.drawRoundRect(cx, 130f, cx + bw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
                p.isFakeBoldText = isAct; p.textSize = 14f
                p.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText(blabel, cx + bw / 2f, 145f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
                cx += bw + 4f
            }

            // ── SPECTRUM ANALYZER ──
            val specLeft = 40f; val specRight = uiW - 80f; val specTop = 155f; val specH = 280f
            val specBot = specTop + specH

            // Background
            p.color = 0xFF080812.toInt()
            canvas.drawRoundRect(specLeft, specTop, specRight, specBot, 8f, 8f, p)

            // Grid lines with amplitude labels
            p.color = 0x30FFFFFF.toInt(); p.strokeWidth = 1f
            for (pct in arrayOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                val gy = specBot - pct * specH
                canvas.drawLine(specLeft, gy, specRight, gy, p)
            }
            p.color = 0x50FFFFFF.toInt(); p.textSize = 14f
            canvas.drawText("25%", specLeft + 2f, specBot - 0.25f * specH - 2f, p)
            canvas.drawText("50%", specLeft + 2f, specBot - 0.5f * specH - 2f, p)
            canvas.drawText("75%", specLeft + 2f, specBot - 0.75f * specH - 2f, p)

            // Spectrum bars — horizontal zoom centered on box
            val bins = reactor?.spectrumBins ?: FloatArray(64)
            val specW = specRight - specLeft
            val hZoom = reactor?.specZoom?.coerceAtLeast(1f) ?: 1f
            val viewWidth = 1f / hZoom
            // Lazy scroll: only move view when box edge is within 15% of display edge
            val r = reactor
            if (r != null && hZoom > 1f) {
                val margin = 0.15f * viewWidth
                if (r.boxLeft < r.specViewCenter - viewWidth / 2f + margin)
                    r.specViewCenter = r.boxLeft + viewWidth / 2f - margin
                if (r.boxRight > r.specViewCenter + viewWidth / 2f - margin)
                    r.specViewCenter = r.boxRight - viewWidth / 2f + margin
                r.specViewCenter = r.specViewCenter.coerceIn(viewWidth / 2f, 1f - viewWidth / 2f)
            }
            val center = reactor?.specViewCenter ?: 0.5f
            val visLeft = (center - viewWidth / 2f).coerceIn(0f, 1f - viewWidth)
            val visRight = (visLeft + viewWidth).coerceAtMost(1f)
            // Draw DISTINCT bars with clear gaps
            val visRange = (visRight - visLeft).coerceAtLeast(0.001f)
            val vZoom = reactor?.specVZoom ?: 1f
            val expand = reactor?.dynRange ?: 1f
            val binScreenW = specW / (visRange * 64f)  // width of one bin on screen
            val gap = 2f.coerceAtMost(binScreenW * 0.3f)  // gap between bars (proportional)
            val barW = (binScreenW - gap).coerceAtLeast(2f)

            for (i in 0 until 64) {
                val binLeft = i.toFloat() / 64f
                val binRight = (i + 1).toFloat() / 64f
                if (binRight < visLeft || binLeft > visRight) continue

                // Bar position: center of bin mapped to screen
                val screenX = specLeft + ((binLeft - visLeft) / visRange) * specW

                // Bars: vZoom amplifies, expand amplifies, clamp at top
                val rawLevel = bins[i] * vZoom * expand
                if (rawLevel < 0.003f) continue
                val barH = (rawLevel * specH).coerceAtMost(specH)

                // Color: bass(pink) → mid(cyan) → high(blue)
                val frac = i / 64f
                val cr: Int; val cg: Int; val cb: Int
                when {
                    frac < 0.3f -> { cr = 0xFF; cg = 0x40; cb = 0x80 }
                    frac < 0.6f -> {
                        val t = (frac - 0.3f) / 0.3f
                        cr = (255 * (1f - t)).toInt().coerceIn(16, 255)
                        cg = (64 + 176 * t).toInt().coerceIn(64, 240)
                        cb = (128 + 32 * t).toInt().coerceIn(128, 160)
                    }
                    else -> {
                        val t = (frac - 0.6f) / 0.4f
                        cr = (16 + 96 * t).toInt().coerceIn(16, 112)
                        cg = (240 * (1f - t)).toInt().coerceIn(64, 240)
                        cb = 0xF6
                    }
                }

                // Solid bar with hard edges
                val level = rawLevel.coerceAtMost(1f)
                p.color = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                canvas.drawRect(screenX, specBot - barH, screenX + barW, specBot, p)

                // Bright cap at top
                if (barH > 4f) {
                    p.color = (0xDD shl 24) or 0xFFFFFF
                    canvas.drawRect(screenX, specBot - barH, screenX + barW, specBot - barH + 2f, p)
                }
            }

            // Bounding box — mapped to visible range
            if (reactor != null) {
                val visRange = visRight - visLeft
                val bxL = specLeft + ((reactor.boxLeft - visLeft) / visRange).coerceIn(0f, 1f) * specW
                val bxR = specLeft + ((reactor.boxRight - visLeft) / visRange).coerceIn(0f, 1f) * specW
                // Box edges in same space as bars (vZoom + expand applied)
                val vz = reactor.specVZoom
                val ex = reactor.dynRange
                val bxT = specBot - (reactor.boxTop * vz * ex).coerceAtMost(1f) * specH
                val bxB = specBot - (reactor.boxBottom * vz * ex).coerceAtMost(1f) * specH

                // Fill
                p.color = 0x18FFFF00.toInt()
                canvas.drawRect(bxL, bxT, bxR, bxB, p)

                // All 4 edges
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f
                p.color = 0xFFFFFF00.toInt()
                canvas.drawRect(bxL, bxT, bxR, bxB, p)
                p.style = android.graphics.Paint.Style.FILL

                // Corner markers at all 4 corners
                val cm = 8f
                p.color = 0xFFFFFF00.toInt()
                canvas.drawRect(bxL - 1f, bxT - 1f, bxL + cm, bxT + cm, p)
                canvas.drawRect(bxR - cm, bxT - 1f, bxR + 1f, bxT + cm, p)
                canvas.drawRect(bxL - 1f, bxB - cm, bxL + cm, bxB + 1f, p)
                canvas.drawRect(bxR - cm, bxB - cm, bxR + 1f, bxB + 1f, p)

                // Threshold line (STROBE mode — shows where the trigger level is)
                if (curMode == AudioReactor.Rolloff.INSTANT) {
                    val threshY = specBot - reactor.threshold * specH
                    p.color = 0xFFFF0000.toInt(); p.strokeWidth = 2f
                    canvas.drawLine(specLeft, threshY, specRight, threshY, p)
                    p.textSize = 16f; canvas.drawText("TRIGGER", specRight - 80f, threshY - 4f, p)
                }

                // ── Output meter (right side, wider and more prominent) ──
                val meterX = specRight + 12f; val meterW = 28f
                // Meter background
                p.color = 0xFF0A0A14.toInt()
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                // Meter grid
                p.color = 0x20FFFFFF.toInt(); p.strokeWidth = 1f
                for (pct2 in arrayOf(0.25f, 0.5f, 0.75f)) {
                    val my = specBot - pct2 * specH
                    canvas.drawLine(meterX, my, meterX + meterW, my, p)
                }
                // Meter fill (gradient: red at top, pink at bottom)
                val meterFillH = reactor.boxFillPct * specH
                if (meterFillH > 0f) {
                    val meterFillTop = specBot - meterFillH
                    // Hot pink fill
                    p.color = 0xFFFF4090.toInt()
                    canvas.drawRoundRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, specBot, 3f, 3f, p)
                    // Bright cap at top of fill
                    if (meterFillH > 4f) {
                        p.color = 0xFFFFFFFF.toInt()
                        canvas.drawRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, meterFillTop + 3f, p)
                    }
                }
                // Meter border
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.5f
                p.color = 0x80FFFFFF.toInt()
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                p.style = android.graphics.Paint.Style.FILL

                // Output % label above meter
                p.color = 0xFFFFFFFF.toInt(); p.textSize = 22f; p.isFakeBoldText = true
                p.textAlign = android.graphics.Paint.Align.CENTER
                canvas.drawText("%.0f%%".format(reactor.boxFillPct * 100), meterX + meterW / 2f, specTop - 8f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
            }

            // Frequency labels (mapped to visible range)
            p.color = 0xFF6B7280.toInt(); p.textSize = 16f
            p.textAlign = android.graphics.Paint.Align.LEFT
            fun binToHz(norm: Float): String {
                val hz = 20f * Math.pow(1000.0, norm.toDouble()).toFloat()
                return if (hz >= 1000f) "%.1fk".format(hz / 1000f) else "%.0f".format(hz)
            }
            canvas.drawText(binToHz(visLeft), specLeft, specBot + 16f, p)
            canvas.drawText(binToHz(visLeft + (visRight - visLeft) * 0.33f), specLeft + specW * 0.33f, specBot + 16f, p)
            canvas.drawText(binToHz(visLeft + (visRight - visLeft) * 0.66f), specLeft + specW * 0.66f, specBot + 16f, p)
            p.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.drawText(binToHz(visRight), specRight, specBot + 16f, p)
            p.textAlign = android.graphics.Paint.Align.LEFT
            p.textAlign = android.graphics.Paint.Align.LEFT

            // ── SLIDERS (below spectrum) ──
            val sliderAreaTop = specBot + 20f
            val trackLeft = 260f; val trackRight = uiW - 40f; val trackH = 24f
            val labelP = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFD1D5DB.toInt() }
            val valP = android.graphics.Paint().apply { isAntiAlias = true; textSize = 20f; color = 0xFF9CA3AF.toInt() }
            var sy = sliderAreaTop

            for ((i, slider) in beatSliders.withIndex()) {
                val isHovered = i == beatDraggingSlider
                val value = slider.get()
                val norm = ((value - slider.min) / (slider.max - slider.min)).coerceIn(0f, 1f)

                canvas.drawText(slider.name, 40f, sy + 13f, labelP)
                val valStr = when (slider.unit) {
                    "x" -> "%.1fx".format(value)
                    "Hz" -> "%.0f Hz".format(value)
                    "ms" -> "%.0f ms".format(value)
                    else -> "%.0f%%".format(value)
                }
                val vw = valP.measureText(valStr)
                canvas.drawText(valStr, trackLeft - vw - 6f, sy + 13f, valP)

                // Track background
                p.color = 0xFF1E1E28.toInt()
                canvas.drawRoundRect(trackLeft, sy, trackRight, sy + trackH, 4f, 4f, p)

                if (slider.name == "COLOR") {
                    // Rainbow hue bar
                    val segments = 12
                    val segW = (trackRight - trackLeft) / segments
                    for (s in 0 until segments) {
                        val hue = (s.toFloat() / segments) * 360f
                        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        p.color = rgb
                        canvas.drawRect(trackLeft + s * segW, sy, trackLeft + (s + 1) * segW, sy + trackH, p)
                    }
                } else {
                    // Normal fill
                    val fillEnd2 = trackLeft + norm * (trackRight - trackLeft)
                    p.color = if (isHovered) 0xFFFF6BB5.toInt() else 0xFFEC4899.toInt()
                    canvas.drawRoundRect(trackLeft, sy, fillEnd2, sy + trackH, 4f, 4f, p)
                }

                // Knob
                val fillEnd = trackLeft + norm * (trackRight - trackLeft)
                if (slider.name == "COLOR") {
                    // Color knob shows the selected color
                    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(value, 1f, 1f))
                    p.color = rgb
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.color = 0xFFFFFFFF.toInt()
                    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2f
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.style = android.graphics.Paint.Style.FILL
                } else {
                    p.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                }

                sy += 32f
            }

            // ── Buttons: BOOM / VIBES / OFF / BACK ──
            val btnY = uiH - 75f; val btnH = 50f
            val quarterW = (uiW - 100f) / 4f
            p.textSize = 22f; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true

            // BOOM button
            val boomReady = reactor?.boomReady ?: false
            p.color = if (hoveredActionButton == 127) 0x80FF9500.toInt()
                else if (boomReady) 0x40FF9500.toInt() else 0x15FF9500.toInt()
            canvas.drawRoundRect(30f, btnY, 30f + quarterW, btnY + btnH, 10f, 10f, p)
            p.color = if (boomReady) 0xFFFF9500.toInt() else 0xFF555555.toInt()
            canvas.drawText("\uD83D\uDCA5 BOOM", 30f + quarterW / 2f, btnY + 33f, p)

            // VIBES button
            val vibesColor = if (hapticConnected) 0xFF8B5CF6.toInt() else 0xFFEC4899.toInt()
            val vibesLabel = if (hapticConnected) "VIBES: ON"
                else if (hapticEnabled) "Scanning..."
                else "VIBES"
            p.color = if (hoveredActionButton == 128) 0x80EC4899.toInt()
                else if (hapticConnected) 0x408B5CF6.toInt() else 0x20EC4899.toInt()
            canvas.drawRoundRect(40f + quarterW, btnY, 40f + quarterW * 2f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 128) 0xFFFFFFFF.toInt() else vibesColor
            canvas.drawText(vibesLabel, 40f + quarterW * 1.5f, btnY + 33f, p)

            // OFF button
            p.color = if (hoveredActionButton == 112) 0x80F04858.toInt() else 0x20F04858.toInt()
            canvas.drawRoundRect(50f + quarterW * 2f, btnY, 50f + quarterW * 3f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 112) 0xFFFFFFFF.toInt() else 0xFFF04858.toInt()
            canvas.drawText("OFF", 50f + quarterW * 2.5f, btnY + 33f, p)

            // BACK button
            p.color = if (hoveredActionButton == 111) 0x80EC4899.toInt() else 0x20EC4899.toInt()
            canvas.drawRoundRect(60f + quarterW * 3f, btnY, uiW - 30f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 111) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            canvas.drawText("\u25C0 BACK", 60f + quarterW * 3f + (uiW - 90f - quarterW * 3f) / 2f, btnY + 33f, p)
            p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false

            // ── Laser cursor (bright, visible crosshair) ──
            if (beatCursorX > 0f && beatCursorY > 0f) {
                val cp = android.graphics.Paint().apply {
                    isAntiAlias = true; color = 0xFFFFFFFF.toInt(); strokeWidth = 2f
                    style = android.graphics.Paint.Style.STROKE
                }
                canvas.drawCircle(beatCursorX, beatCursorY, 12f, cp)
                cp.strokeWidth = 1f; cp.color = 0x80FFFFFF.toInt()
                canvas.drawLine(beatCursorX - 20f, beatCursorY, beatCursorX + 20f, beatCursorY, cp)
                canvas.drawLine(beatCursorX, beatCursorY - 20f, beatCursorX, beatCursorY + 20f, cp)
                // Bright dot at center
                cp.style = android.graphics.Paint.Style.FILL; cp.color = 0xFFEC4899.toInt()
                canvas.drawCircle(beatCursorX, beatCursorY, 3f, cp)
            }

            pendingUiBitmap = bitmap
            return
        }

        // ═══ Audio Player ═══
        if (audioPlayerMode) {
            val ap = audioPlayer
            val p = android.graphics.Paint().apply { isAntiAlias = true }

            // Audio file picker sub-mode
            if (audioPickerMode) {
                p.textSize = 42f; p.color = 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
                canvas.drawText("Select Audio File", 60f, 112f, p)
                canvas.drawLine(60f, 120f, uiW - 60f, 120f,
                    android.graphics.Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f })

                val files = availableAudioFiles
                if (files.isEmpty()) {
                    p.textSize = 32f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false
                    canvas.drawText("No audio files found", 60f, 200f, p)
                } else {
                    val maxVis = 10; val rowH = 72f; val startY = 140f
                    for (vi in 0 until maxVis) {
                        val idx = audioPickerScrollOffset + vi
                        if (idx >= files.size) break
                        val file = files[idx]
                        val ry = startY + vi * rowH
                        val isHov = idx == hoveredAudioFileIndex

                        if (isHov) {
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                                android.graphics.Paint().apply { color = 0x208B5CF6.toInt(); isAntiAlias = true })
                        }
                        if (vi % 2 == 0) {
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                                android.graphics.Paint().apply { color = 0x08FFFFFF.toInt(); isAntiAlias = true })
                        }
                        val name = file.nameWithoutExtension
                        val display = if (name.length > 28) name.take(26) + ".." else name
                        p.textSize = if (isHov) 36f else 34f
                        p.color = if (isHov) 0xFF8B5CF6.toInt() else 0xFFE8EAF0.toInt()
                        p.isFakeBoldText = isHov
                        canvas.drawText(display, 50f, ry + 32f, p)

                        // Extension + size
                        p.textSize = 18f; p.color = 0xFF505868.toInt(); p.isFakeBoldText = false
                        val ext = file.extension.uppercase()
                        val sizeMB = file.length() / 1048576f
                        val info = "$ext  %.1f MB".format(sizeMB)
                        canvas.drawText(info, 50f, ry + 52f, p)
                    }
                    if (files.size > maxVis) {
                        val pg = audioPickerScrollOffset / maxVis + 1
                        val total = (files.size + maxVis - 1) / maxVis
                        p.textSize = 20f; p.color = 0xFF505868.toInt()
                        p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = false
                        canvas.drawText("Page $pg of $total  (${files.size} files)",
                            uiW / 2f, startY + maxVis * rowH + 16f, p)
                        p.textAlign = android.graphics.Paint.Align.LEFT
                    }
                }
                // BACK button
                val btnY = uiH - 80f
                val isBack = hoveredAudioButton == 50
                canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + 60f, 12f, 12f,
                    android.graphics.Paint().apply { color = if (isBack) 0x608B5CF6.toInt() else 0x188B5CF6.toInt() })
                p.textSize = 32f; p.textAlign = android.graphics.Paint.Align.CENTER
                p.color = if (isBack) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
                canvas.drawText("\u25C0 BACK", uiW / 2f, btnY + 40f, p)
                p.textAlign = android.graphics.Paint.Align.LEFT

                pendingUiBitmap = bitmap
                return
            }

            // ── Audio Player Main Panel ──
            p.textSize = 38f; p.color = 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
            canvas.drawText("AUDIO PLAYER", 60f, 108f, p)
            canvas.drawLine(60f, 116f, uiW - 60f, 116f,
                android.graphics.Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f })

            // Now playing
            val fileName = ap?.currentFile?.nameWithoutExtension ?: "No track loaded"
            val displayName = if (fileName.length > 36) fileName.take(34) + ".." else fileName
            p.textSize = 30f; p.color = 0xFFD0D0E0.toInt(); p.isFakeBoldText = false
            canvas.drawText(displayName, 60f, 160f, p)

            // Time + progress bar
            val posMs = ap?.currentPositionMs ?: 0
            val durMs = ap?.durationMs ?: 0
            fun fmtTime(ms: Long): String {
                val s = ms / 1000; val m = s / 60
                return "%d:%02d".format(m, s % 60)
            }
            p.textSize = 24f; p.color = 0xFF9CA3AF.toInt()
            canvas.drawText("${fmtTime(posMs)} / ${fmtTime(durMs)}", 60f, 195f, p)

            // Progress bar
            val barLeft = 60f; val barRight = uiW - 60f; val barY = 210f; val barH = 10f
            canvas.drawRoundRect(barLeft, barY, barRight, barY + barH, 5f, 5f,
                android.graphics.Paint().apply { color = 0xFF0E0E1C.toInt() })
            if (durMs > 0) {
                val prog = (posMs.toFloat() / durMs).coerceIn(0f, 1f)
                val fillR = barLeft + (barRight - barLeft) * prog
                canvas.drawRoundRect(barLeft, barY, fillR, barY + barH, 5f, 5f,
                    android.graphics.Paint().apply {
                        shader = android.graphics.LinearGradient(barLeft, 0f, fillR, 0f,
                            0xFF2A1048.toInt(), 0xFF8B5CF6.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    })
                // A/B markers
                if (ap != null && ap.hasLoop()) {
                    val aX = barLeft + (barRight - barLeft) * (ap.loopA.toFloat() / durMs)
                    val bX = barLeft + (barRight - barLeft) * (ap.loopB.toFloat() / durMs)
                    canvas.drawRect(aX - 1f, barY - 4f, aX + 1f, barY + barH + 4f,
                        android.graphics.Paint().apply { color = 0xFF10B981.toInt() })
                    canvas.drawRect(bX - 1f, barY - 4f, bX + 1f, barY + barH + 4f,
                        android.graphics.Paint().apply { color = 0xFFF04858.toInt() })
                    canvas.drawRect(aX, barY, bX, barY + 2f,
                        android.graphics.Paint().apply { color = 0x3010B981.toInt() })
                }
                // Thumb
                canvas.drawCircle(fillR, barY + barH / 2f, 7f,
                    android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true })
            }

            // Transport buttons
            val txY = 245f; val btnW = 130f; val btnH = 55f; val gap = 12f
            val transportBtns = arrayOf(
                "|<<" to 0, (if (ap?.isPlaying == true) "PAUSE" else "PLAY") to 1, ">>|" to 2, "STOP" to 3
            )
            for ((i, btn) in transportBtns.withIndex()) {
                val bx = 60f + i * (btnW + gap)
                val isHov = hoveredAudioButton == btn.second
                val col = when (i) {
                    1 -> 0xFF8B5CF6.toInt()  // play/pause = purple
                    3 -> 0xFFF04858.toInt()  // stop = red
                    else -> 0xFF3B82F6.toInt() // skip = blue
                }
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + btnH, 10f, 10f,
                    android.graphics.Paint().apply {
                        color = if (isHov) (col and 0x00FFFFFF) or 0x60000000
                        else (col and 0x00FFFFFF) or 0x18000000
                    })
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + btnH, 10f, 10f,
                    android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = if (isHov) 2f else 1f
                        color = if (isHov) col else (col and 0x00FFFFFF) or 0x50000000; isAntiAlias = true
                    })
                p.textSize = 22f; p.textAlign = android.graphics.Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = true
                canvas.drawText(btn.first, bx + btnW / 2f, txY + 37f, p)
            }

            // Speed
            val spY = 325f
            p.textAlign = android.graphics.Paint.Align.LEFT
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false
            canvas.drawText("SPEED", 60f, spY, p)
            val spLabel = AudioPlayer.SPEED_LABELS.getOrElse(ap?.speedIndex ?: 2) { "1.0x" }
            p.color = 0xFF30D8D0.toInt(); p.isFakeBoldText = true
            canvas.drawText(spLabel, 160f, spY, p)
            // Speed buttons
            val isSpDown = hoveredAudioButton == 10; val isSpUp = hoveredAudioButton == 11
            p.textSize = 28f; p.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawRoundRect(260f, spY - 22f, 320f, spY + 6f, 8f, 8f,
                android.graphics.Paint().apply { color = if (isSpDown) 0x403B82F6.toInt() else 0x103B82F6.toInt() })
            p.color = if (isSpDown) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt()
            canvas.drawText("-", 290f, spY, p)
            canvas.drawRoundRect(330f, spY - 22f, 390f, spY + 6f, 8f, 8f,
                android.graphics.Paint().apply { color = if (isSpUp) 0x403B82F6.toInt() else 0x103B82F6.toInt() })
            p.color = if (isSpUp) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt()
            canvas.drawText("+", 360f, spY, p)

            // A/B Loop
            val abY = 375f
            p.textAlign = android.graphics.Paint.Align.LEFT
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false
            canvas.drawText("A/B LOOP", 60f, abY, p)
            val abBtns = arrayOf("Set A" to 20, "Set B" to 21, "Clear" to 22)
            for ((i, ab) in abBtns.withIndex()) {
                val bx = 220f + i * 100f
                val isHov = hoveredAudioButton == ab.second
                val col = when (i) { 0 -> 0xFF10B981.toInt(); 1 -> 0xFFF04858.toInt(); else -> 0xFF6B7280.toInt() }
                canvas.drawRoundRect(bx, abY - 20f, bx + 90f, abY + 8f, 8f, 8f,
                    android.graphics.Paint().apply { color = if (isHov) (col and 0x00FFFFFF) or 0x40000000 else 0x10404050.toInt() })
                p.textSize = 20f; p.textAlign = android.graphics.Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = isHov
                canvas.drawText(ab.first, bx + 45f, abY, p)
            }
            if (ap != null && ap.hasLoop()) {
                p.textSize = 18f; p.color = 0xFF505868.toInt(); p.textAlign = android.graphics.Paint.Align.LEFT; p.isFakeBoldText = false
                canvas.drawText("A=${fmtTime(ap.loopA)}  B=${fmtTime(ap.loopB)}", 540f, abY, p)
            }

            // Repeat
            val rpY = 425f
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false; p.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText("REPEAT", 60f, rpY, p)
            val rpLabel = ap?.repeatMode?.label ?: "OFF"
            val isRpHov = hoveredAudioButton == 30
            canvas.drawRoundRect(180f, rpY - 20f, 280f, rpY + 8f, 8f, 8f,
                android.graphics.Paint().apply { color = if (isRpHov) 0x40EC4899.toInt() else 0x10EC4899.toInt() })
            p.textSize = 22f; p.textAlign = android.graphics.Paint.Align.CENTER
            p.color = if (rpLabel != "OFF") 0xFFEC4899.toInt() else 0xFF505868.toInt(); p.isFakeBoldText = rpLabel != "OFF"
            canvas.drawText(rpLabel, 230f, rpY, p)

            // EQ
            val eqY = 475f
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false; p.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText("EQ", 60f, eqY, p)
            val eqPresets = AudioPlayer.EqPreset.entries
            for ((i, eq) in eqPresets.withIndex()) {
                val bx = 130f + i * 120f
                val isHov = hoveredAudioButton == 40 + i
                val isCurrent = ap?.eqPreset == eq
                val col = if (isCurrent) 0xFFFF9500.toInt() else 0xFF505868.toInt()
                canvas.drawRoundRect(bx, eqY - 20f, bx + 110f, eqY + 8f, 8f, 8f,
                    android.graphics.Paint().apply {
                        color = if (isHov) (col and 0x00FFFFFF) or 0x40000000
                        else if (isCurrent) 0x20FF9500.toInt() else 0x10404050.toInt()
                    })
                p.textSize = 18f; p.textAlign = android.graphics.Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = isCurrent
                canvas.drawText(eq.label, bx + 55f, eqY, p)
            }

            // Bottom buttons: BROWSE / BACK
            val bbY = uiH - 80f
            val halfW = (uiW - 70f) / 2f
            val isBrowse = hoveredAudioButton == 51; val isBack = hoveredAudioButton == 52
            canvas.drawRoundRect(30f, bbY, 30f + halfW, bbY + 60f, 12f, 12f,
                android.graphics.Paint().apply { color = if (isBrowse) 0x608B5CF6.toInt() else 0x188B5CF6.toInt() })
            p.textSize = 28f; p.textAlign = android.graphics.Paint.Align.CENTER
            p.color = if (isBrowse) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
            canvas.drawText("BROWSE", 30f + halfW / 2f, bbY + 40f, p)

            canvas.drawRoundRect(40f + halfW, bbY, uiW - 30f, bbY + 60f, 12f, 12f,
                android.graphics.Paint().apply { color = if (isBack) 0x60EC4899.toInt() else 0x18EC4899.toInt() })
            p.color = if (isBack) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            canvas.drawText("BACK", 40f + halfW + (uiW - 70f - halfW) / 2f, bbY + 40f, p)
            p.textAlign = android.graphics.Paint.Align.LEFT

            pendingUiBitmap = bitmap
            return
        }

        // ═══ Save Name Editor ═══
        if (saveNameMode) {
            val headerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 36f; color = 0xFF8B5CF6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Save Scene", 50f, 110f, headerPaint)

            // Name display with cursor
            val nameY = 155f
            val charW = 38f
            val nameStartX = 50f
            for (i in 0 until 20) {
                val ch = if (i < saveNameLen) saveNameChars[i] else ' '
                val isCursor = i == saveNameCursor
                // Cursor highlight
                if (isCursor) {
                    val cursorBg = android.graphics.Paint().apply { color = 0xFF8B5CF6.toInt() }
                    canvas.drawRoundRect(nameStartX + i * charW - 2f, nameY - 32f,
                        nameStartX + i * charW + charW - 4f, nameY + 6f, 4f, 4f, cursorBg)
                }
                val charPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 36f
                    color = if (isCursor) 0xFFFFFFFF.toInt() else if (i < saveNameLen) 0xFFF3F4F6.toInt() else 0xFF3A3A42.toInt()
                    isFakeBoldText = isCursor
                }
                canvas.drawText(ch.toString(), nameStartX + i * charW, nameY, charPaint)
            }

            // Underline
            val underPaint = android.graphics.Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f }
            canvas.drawLine(nameStartX, nameY + 8f, nameStartX + 20 * charW, nameY + 8f, underPaint)

            // Hint
            val hintPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 20f; color = 0xFF58585F.toInt()
            }
            canvas.drawText("Stick \u2195:letter  \u2194:cursor  A:backspace", 50f, nameY + 30f, hintPaint)

            // SAVE button
            val saveBtnY = 200f
            val isSaveHovered = hoveredSaveButton == 0
            val saveBg = android.graphics.Paint().apply {
                color = if (isSaveHovered) 0x808B5CF6.toInt() else 0x308B5CF6.toInt()
            }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, saveBg)
            val saveBorder = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = if (isSaveHovered) 3f else 1.5f
                color = 0xFF8B5CF6.toInt(); isAntiAlias = true
            }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, saveBorder)
            val saveTxt = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isSaveHovered) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); isFakeBoldText = true
            }
            val displayName = String(saveNameChars, 0, saveNameLen).trim().ifEmpty { "untitled" }
            canvas.drawText("SAVE \"$displayName\"", uiW / 2f, saveBtnY + 34f, saveTxt)

            // Existing scenes (overwrite targets)
            refreshSceneList()
            val scenes = savedSceneFiles
            if (scenes.isNotEmpty()) {
                val secHeader = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 24f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("Or overwrite existing:", 50f, 290f, secHeader)

                val rowH = 55f
                val startY = 310f
                val normalPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 30f; color = 0xFFF3F4F6.toInt()
                }
                val hoverPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 32f; color = 0xFF8B5CF6.toInt(); isFakeBoldText = true
                }
                val datePaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 22f; color = 0xFF6B7280.toInt()
                }
                val hoverBg = android.graphics.Paint().apply { color = 0x208B5CF6.toInt() }

                for (vi in 0 until minOf(10, scenes.size)) {
                    val scene = scenes[vi]
                    val ry = startY + vi * rowH
                    val isHovered = vi == hoveredSceneIndex

                    if (isHovered) {
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, hoverBg)
                    }
                    canvas.drawText(scene.nameWithoutExtension, 50f, ry + 30f, if (isHovered) hoverPaint else normalPaint)

                    val dateStr = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(scene.lastModified()))
                    val dw = datePaint.measureText(dateStr)
                    canvas.drawText(dateStr, uiW - 60f - dw, ry + 30f, datePaint)
                }
            }

            // BACK button
            val btnY = uiH - 80f
            val btnH = 60f
            val isBackHovered = hoveredSaveButton == 1
            val backBg = android.graphics.Paint().apply {
                color = if (isBackHovered) 0x708B5CF6.toInt() else 0x208B5CF6.toInt()
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f, backBg)
            val backText = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, btnY + 40f, backText)

            pendingUiBitmap = bitmap
            return
        }

        // ═══ GLB Picker Sub-Menu ═══
        if (glbPickerMode) {
            // Header with neon accent
            val headerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 42f; color = 0xFF10B981.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Select a 3D Model", 70f, 112f, headerPaint)
            // Subtle underline glow
            val lineGlow = android.graphics.Paint().apply {
                color = 0x4010B981.toInt(); strokeWidth = 2f; isAntiAlias = true
                maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawLine(70f, 120f, uiW - 70f, 120f, lineGlow)

            val files = availableGlbFiles
            if (files.isEmpty()) {
                val emptyPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 34f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("No .glb files found on device", 70f, 200f, emptyPaint)
            } else {
                val maxVisible = 10
                val rowH = 76f
                val startY = 140f
                val loadedPaths = models.map { it.file.absolutePath }.toSet()

                for (vi in 0 until maxVisible) {
                    val idx = glbPickerScrollOffset + vi
                    if (idx >= files.size) break
                    val file = files[idx]
                    val ry = startY + vi * rowH
                    val isHovered = idx == hoveredGlbIndex
                    val isLoaded = file.absolutePath in loadedPaths

                    // Row background
                    val rowBg = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = when {
                            isHovered && isLoaded -> 0x3010B981.toInt()
                            isHovered -> 0x2010B981.toInt()
                            isLoaded -> 0x14EC4899.toInt()
                            vi % 2 == 0 -> 0x08FFFFFF.toInt()
                            else -> 0x00000000.toInt()
                        }
                    }
                    canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, rowBg)

                    // Hover glow border
                    if (isHovered) {
                        canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                            android.graphics.Paint().apply {
                                style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f
                                color = 0x6010B981.toInt(); isAntiAlias = true
                                maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.OUTER)
                            })
                    }

                    // Loaded indicator dot
                    if (isLoaded) {
                        canvas.drawCircle(46f, ry + 30f, 4f,
                            android.graphics.Paint().apply { color = 0xFFEC4899.toInt(); isAntiAlias = true })
                        canvas.drawCircle(46f, ry + 30f, 6f,
                            android.graphics.Paint().apply {
                                color = 0x40EC4899.toInt(); isAntiAlias = true
                                maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                            })
                    }

                    // File name
                    val label = file.nameWithoutExtension
                    val displayName = if (label.length > 26) label.take(24) + ".." else label
                    val namePaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = if (isHovered) 38f else 36f
                        color = when {
                            isHovered -> 0xFF10B981.toInt()
                            isLoaded -> 0xFFD0C0E0.toInt()
                            else -> 0xFFE8EAF0.toInt()
                        }
                        isFakeBoldText = isHovered
                    }
                    canvas.drawText(displayName, if (isLoaded) 62f else 50f, ry + 34f, namePaint)

                    // File size badge
                    val sizeMB = file.length() / 1048576f
                    val sizeStr = if (sizeMB >= 10f) "%.0f MB".format(sizeMB) else "%.1f MB".format(sizeMB)
                    val badgeColor = when {
                        sizeMB > 50f -> 0xFFF04858.toInt()   // red for huge
                        sizeMB > 10f -> 0xFFFF9500.toInt()   // orange for large
                        else -> 0xFF6B7280.toInt()            // gray for normal
                    }
                    val sizePaint = android.graphics.Paint().apply {
                        isAntiAlias = true; textSize = 22f; color = badgeColor
                    }
                    val sw = sizePaint.measureText(sizeStr)
                    // Badge pill
                    val bx = uiW - 60f - sw - 16f
                    canvas.drawRoundRect(bx, ry + 18f, uiW - 50f, ry + 42f, 12f, 12f,
                        android.graphics.Paint().apply { color = (badgeColor and 0x00FFFFFF) or 0x18000000; isAntiAlias = true })
                    canvas.drawText(sizeStr, bx + 8f, ry + 37f, sizePaint)

                    // Extension badge
                    val ext = file.extension.uppercase()
                    val extPaint = android.graphics.Paint().apply {
                        isAntiAlias = true; textSize = 16f; color = 0xFF8B5CF6.toInt()
                        letterSpacing = 0.05f
                    }
                    canvas.drawText(ext, if (isLoaded) 62f else 50f, ry + 54f, extPaint)
                }

                // Page indicator
                if (files.size > maxVisible) {
                    val page = glbPickerScrollOffset / maxVisible + 1
                    val totalPages = (files.size + maxVisible - 1) / maxVisible
                    val pagePaint = android.graphics.Paint().apply {
                        isAntiAlias = true; textSize = 22f; color = 0xFF505868.toInt()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText("Page $page of $totalPages  (${files.size} files)", uiW / 2f,
                        startY + maxVisible * rowH + 16f, pagePaint)
                }
            }

            // BACK button
            val btnY = uiH - 80f
            val btnH = 60f
            val isBackHovered = hoveredActionButton == 103
            if (isBackHovered) {
                canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f,
                    android.graphics.Paint().apply {
                        color = 0xFFEC4899.toInt(); isAntiAlias = true
                        maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.OUTER)
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f
                    })
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f,
                android.graphics.Paint().apply {
                    color = if (isBackHovered) 0x60EC4899.toInt() else 0x18EC4899.toInt(); isAntiAlias = true
                })
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f,
                android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = if (isBackHovered) 2f else 1f
                    color = if (isBackHovered) 0xFFEC4899.toInt() else 0x50EC4899.toInt()
                    isAntiAlias = true
                })
            val backText = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 32f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
                isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, btnY + 40f, backText)

            pendingUiBitmap = bitmap
            return
        }

        // ═══ Scene Picker Sub-Menu ═══
        if (scenePickerMode) {
            val headerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 38f; color = 0xFF3B82F6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Load Scene", 50f, 115f, headerPaint)

            val scenes = savedSceneFiles
            if (scenes.isEmpty()) {
                val emptyPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 32f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("No saved scenes", 50f, 200f, emptyPaint)
            } else {
                val maxVisible = 13
                val rowH = 60f
                val startY = 130f
                val normalPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 34f; color = 0xFFF3F4F6.toInt()
                }
                val hoverPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 36f; color = 0xFF3B82F6.toInt(); isFakeBoldText = true
                }
                val datePaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 24f; color = 0xFF6B7280.toInt()
                }
                val hoverBg = android.graphics.Paint().apply { color = 0x203B82F6.toInt() }

                for (vi in 0 until minOf(maxVisible, scenes.size)) {
                    val scene = scenes[vi]
                    val ry = startY + vi * rowH
                    val isHovered = vi == hoveredSceneIndex

                    if (isHovered) {
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, hoverBg)
                    }

                    canvas.drawText(scene.nameWithoutExtension, 50f, ry + 34f, if (isHovered) hoverPaint else normalPaint)

                    val dateStr = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(scene.lastModified()))
                    val dw = datePaint.measureText(dateStr)
                    canvas.drawText(dateStr, uiW - 60f - dw, ry + 34f, datePaint)
                }
            }

            // BACK button
            val btnY = uiH - 80f
            val btnH = 60f
            val isBackHovered = hoveredActionButton == 106
            val backBg = android.graphics.Paint().apply {
                color = if (isBackHovered) 0x703B82F6.toInt() else 0x203B82F6.toInt()
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f, backBg)
            val backBorder = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = if (isBackHovered) 3f else 1.5f
                color = if (isBackHovered) 0xFF3B82F6.toInt() else 0x603B82F6.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f, backBorder)
            val backText = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, btnY + 40f, backText)

            pendingUiBitmap = bitmap
            return
        }

        // ── Status line ──
        val renderer = glesRenderer
        val dim = android.graphics.Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF6B7280.toInt() }
        val teal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF10B981.toInt() }

        var y = 110f
        val luxStr = when {
            !autoAmbient -> "Manual"
            xrLightEstimateAvailable && xrSHAvailable -> "XR+SH"
            xrLightEstimateAvailable -> "XR Light"
            else -> "%.0f lux".format(roomLux)
        }
        val gridStr = if (gridVisible) "ON" else "OFF"
        canvas.drawText("${models.size} mdl  |  Grid: $gridStr  |  $luxStr", 50f, y, dim)
        y += 28f

        val model = models.getOrNull(selectedModelIndex)
        val modelName = model?.file?.nameWithoutExtension ?: "No selection"
        val namePaint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 30f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        canvas.drawText(modelName, 50f, y, namePaint)
        y += 12f

        // ── Separator ──
        val sepPaint = android.graphics.Paint().apply { color = 0x30EC4899.toInt(); strokeWidth = 1f }
        canvas.drawLine(40f, y, uiW - 40f, y, sepPaint)
        y += 14f

        // ── Parameters (start ~260) ──
        val noModel = "---"
        val params = arrayOf(
            "Metallic" to (if (model != null) "%.0f%%".format(model.metallic * 100) else noModel),
            "Roughness" to (if (model != null) "%.0f%%".format(model.roughness * 100) else noModel),
            "Exposure" to (if (model != null) "%+.1f EV".format(model.exposure) else noModel),
            "Contrast" to (if (model != null) "%.0f%%".format(model.contrast * 100) else noModel),
            "Saturation" to (if (model != null) "%.0f%%".format(model.saturation * 100) else noModel),
            "Light" to "%.1f".format(renderer?.lightIntensity ?: 2f),
            "Fill" to "%.1f".format(renderer?.fillLightIntensity ?: 0.5f),
            "Ambient" to "%.1f%s".format(renderer?.ambientIntensity ?: 1f, if (autoAmbient) " auto" else ""),
            "Azimuth" to "%.0f\u00B0".format(renderer?.lightAngleDeg ?: 0f),
            "Elevation" to "%.0f\u00B0".format(renderer?.lightElevDeg ?: 60f),
            "Shadow" to "%.0f%%".format((renderer?.shadowDarkness ?: 0.7f) * 100),
            "Softness" to "%.1f".format(renderer?.shadowSoftness ?: 2f),
            "Spread" to "%.1fm".format(renderer?.shadowSpread ?: 8f),
            "BeatReactor" to if (beatReactorEnabled) {
                val r = audioReactor
                if (r != null) "ON %.0f%%".format((r.boxFillPct) * 100) else "ON"
            } else "OFF",
            "Foveation" to arrayOf("OFF", "LOW", "MED", "HIGH")[foveationLevel],
            "Tex Quality" to arrayOf("Auto", "4096", "2048", "1024")[textureQuality],
            "Show Planes" to if (glesRenderer?.showPlaneVisualization == true) "ON" else "OFF",
        )

        val rowH = 38f
        val normal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFFB0B8C4.toInt() }
        val highlight = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 30f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        val disabled = android.graphics.Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF2A2A32.toInt() }
        val valuePaint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFD0D0D0.toInt() }
        val valueHighlight = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }

        val paramRanges = PARAM_RANGES
        fun getParamValue(idx: Int): Float = when (idx) {
            0 -> model?.metallic ?: 0f
            1 -> model?.roughness ?: 0.9f
            2 -> model?.exposure ?: 0f
            3 -> model?.contrast ?: 1f
            4 -> model?.saturation ?: 1f
            5 -> renderer?.lightIntensity ?: 2f
            6 -> renderer?.fillLightIntensity ?: 0.5f
            7 -> renderer?.ambientIntensity ?: 1f
            8 -> renderer?.lightAngleDeg ?: 0f
            9 -> renderer?.lightElevDeg ?: 60f
            10 -> renderer?.shadowDarkness ?: 0.7f
            11 -> renderer?.shadowSoftness ?: 2f
            12 -> renderer?.shadowSpread ?: 8f
            else -> 0f
        }

        // Section dividers
        val sectionLabelPaint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 16f; color = 0xFF6B5080.toInt()
            letterSpacing = 0.15f; isFakeBoldText = true
        }
        val sections = mapOf(0 to "MODEL", 5 to "LIGHTING", 13 to "SYSTEM")

        for ((i, param) in params.withIndex()) {
            // Section header
            if (i in sections) {
                canvas.drawText(sections[i]!!, 56f, y + 2f, sectionLabelPaint)
                canvas.drawLine(56f + sectionLabelPaint.measureText(sections[i]!!) + 8f, y - 2f,
                    uiW - 40f, y - 2f, android.graphics.Paint().apply { color = 0x18EC4899.toInt(); strokeWidth = 1f })
                y += 10f
            }

            val rowTop = y - 4f
            val rowBot = y + rowH - 14f

            val isSelected = i == selectedParam
            val isHovered = i == hoveredMenuParam

            // Row background
            if (isSelected) {
                // Neon glow bg for selected row
                val glowPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; color = 0x2030D8D0.toInt()
                    maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f, glowPaint)
                // Solid bg
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f,
                    android.graphics.Paint().apply { color = 0x2818C8C0.toInt() })
                // Left accent — neon pink with glow
                val accentGlow = android.graphics.Paint().apply {
                    color = 0xFFEC4899.toInt(); isAntiAlias = true
                    maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f, accentGlow)
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f,
                    android.graphics.Paint().apply { color = 0xFFEC4899.toInt() })
            } else if (isHovered) {
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f,
                    android.graphics.Paint().apply { color = 0x14EC4899.toInt() })
            }

            val isPerModel = i <= 4
            val isDead = isPerModel && model == null
            val labelP = when {
                isDead -> disabled
                isSelected -> highlight
                isHovered -> android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 29f; color = 0xFFD8D0E0.toInt()
                }
                else -> normal
            }
            val arrow = if (isSelected) "\u25B6 " else "  "
            canvas.drawText("$arrow${param.first}", 44f, y + 14f, labelP)

            // Slider bar for continuous params (0-12)
            if (i <= 12) {
                val sliderLeft = 240f
                val sliderRight = uiW - 120f
                val sliderY = y + 6f
                val sliderH = 8f
                val range = paramRanges[i]
                val value = getParamValue(i)
                val t = ((value - range.first) / (range.second - range.first)).coerceIn(0f, 1f)
                val fillRight = sliderLeft + (sliderRight - sliderLeft) * t

                // Track: subtle rounded groove
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f,
                    android.graphics.Paint().apply { color = 0xFF0E0E1C.toInt(); isAntiAlias = true })
                // Track inner border
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f,
                    android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.5f
                        color = 0xFF202035.toInt(); isAntiAlias = true
                    })

                if (!isDead) {
                    // Fill: gradient from dark to neon
                    val fillPaint = android.graphics.Paint().apply { isAntiAlias = true }
                    if (isSelected) {
                        fillPaint.shader = android.graphics.LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF0A4040.toInt(), 0xFF30D8D0.toInt(),
                            android.graphics.Shader.TileMode.CLAMP)
                    } else if (isHovered) {
                        fillPaint.shader = android.graphics.LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF1A1028.toInt(), 0xFF9060B0.toInt(),
                            android.graphics.Shader.TileMode.CLAMP)
                    } else {
                        fillPaint.shader = android.graphics.LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF141424.toInt(), 0xFF3A4858.toInt(),
                            android.graphics.Shader.TileMode.CLAMP)
                    }
                    canvas.drawRoundRect(sliderLeft, sliderY, fillRight, sliderY + sliderH, 4f, 4f, fillPaint)

                    // Glow on fill end (selected only)
                    if (isSelected && t > 0.02f) {
                        val glowP = android.graphics.Paint().apply {
                            isAntiAlias = true; color = 0x6030D8D0.toInt()
                            maskFilter = android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.drawCircle(fillRight, sliderY + sliderH / 2f, 6f, glowP)
                    }

                    // Thumb
                    val thumbX = fillRight.coerceIn(sliderLeft + 4f, sliderRight - 4f)
                    val thumbR = if (isSelected) 6f else 4f
                    if (isSelected) {
                        // Outer glow ring
                        canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR + 3f,
                            android.graphics.Paint().apply {
                                isAntiAlias = true; color = 0x4030D8D0.toInt()
                                maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                            })
                    }
                    // Solid thumb
                    canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR,
                        android.graphics.Paint().apply {
                            isAntiAlias = true
                            color = if (isSelected) 0xFFFFFFFF.toInt()
                                else if (isHovered) 0xFFC0B0D0.toInt()
                                else 0xFF707888.toInt()
                        })

                    // Value text right of slider
                    val vp = if (isSelected) valueHighlight else valuePaint
                    val valStr = param.second
                    canvas.drawText(valStr, sliderRight + 10f, y + 16f, vp)
                } else {
                    // Disabled: just show dashes
                    canvas.drawText("---", sliderRight + 10f, y + 16f, disabled)
                }
            } else {
                // Toggle params (13-16): show value as a pill/badge
                val valStr = param.second
                val vp = if (isSelected) valueHighlight else valuePaint
                val badgeLeft = uiW - 150f
                val badgeW = vp.measureText(valStr) + 24f
                val badgeY = y + 1f
                val badgeH = 20f
                val isOn = valStr.startsWith("ON") || valStr == "HIGH" || valStr == "MED" || valStr == "LOW"
                        || valStr == "4096" || valStr == "2048" || valStr == "1024"
                val badgeBg = if (isOn) {
                    if (isSelected) 0x4030D8D0.toInt() else 0x2010B981.toInt()
                } else {
                    0x18404050.toInt()
                }
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f,
                    android.graphics.Paint().apply { color = badgeBg; isAntiAlias = true })
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f,
                    android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.8f
                        color = if (isOn && isSelected) 0x6030D8D0.toInt()
                            else if (isOn) 0x3010B981.toInt()
                            else 0x20505060.toInt()
                        isAntiAlias = true
                    })
                val textP = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 18f; textAlign = android.graphics.Paint.Align.CENTER
                    color = if (isOn) {
                        if (isSelected) 0xFF30D8D0.toInt() else 0xFF10B981.toInt()
                    } else 0xFF606068.toInt()
                    isFakeBoldText = isSelected
                }
                canvas.drawText(valStr, badgeLeft + badgeW / 2f, badgeY + 15f, textP)
            }

            y += rowH
        }

        // Controls hint (compact)
        val hint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 14f; color = 0xFF404048.toInt() }
        canvas.drawText("Stick:Adjust  A:Reset  B:Close  X:Gizmo  Y:Next  Grip:Grab", 40f, y + 4f, hint)

        // ── Action buttons (2 rows) ──
        val btnGap = 8f
        val btnH = 48f

        fun drawButton(bx1: Float, bx2: Float, by: Float, label: String, hovered: Boolean, normalColor: Int) {
            if (hovered) {
                val glow = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = normalColor
                    maskFilter = android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.OUTER)
                    isAntiAlias = true
                }
                canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, glow)
            }
            val bg = android.graphics.Paint().apply {
                color = if (hovered) (normalColor and 0x00FFFFFF) or 0x70000000
                else (normalColor and 0x00FFFFFF) or 0x20000000
            }
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, bg)
            val border = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = if (hovered) 3f else 1.5f
                color = if (hovered) normalColor else (normalColor and 0x00FFFFFF) or 0x60000000.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, border)
            val text = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 24f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (hovered) 0xFFFFFFFF.toInt() else normalColor; isFakeBoldText = true
            }
            canvas.drawText(label, (bx1 + bx2) / 2f, by + 35f, text)
        }

        // Row 1: SAVE / LOAD
        val row1Y = uiH - 170f
        val btn2W = (uiW - 60f - btnGap) / 2f
        drawButton(30f, 30f + btn2W, row1Y, "SAVE SCENE", hoveredActionButton == 104, 0xFF8B5CF6.toInt())
        drawButton(30f + btn2W + btnGap, uiW - 30f, row1Y, "LOAD SCENE", hoveredActionButton == 105, 0xFF3B82F6.toInt())

        // Row 2: ADD / DELETE / RESET
        val row2Y = row1Y + btnH + btnGap
        val btn3W = (uiW - 60f - btnGap * 2f) / 3f
        val r2b1 = 30f; val r2b2 = r2b1 + btn3W + btnGap; val r2b3 = r2b2 + btn3W + btnGap
        drawButton(r2b1, r2b1 + btn3W, row2Y, "+ ADD", hoveredActionButton == 101, 0xFF10B981.toInt())
        drawButton(r2b2, r2b2 + btn3W, row2Y, "DELETE", hoveredActionButton == 107, 0xFFF04858.toInt())
        drawButton(r2b3, r2b3 + btn3W, row2Y, "RESET", hoveredActionButton == 108, 0xFFFF9500.toInt())

        // Row 3: AUDIO / FILE MENU / EXIT
        val row3Y = row2Y + btnH + btnGap
        drawButton(r2b1, r2b1 + btn3W, row3Y, "AUDIO", hoveredActionButton == 109, 0xFF8B5CF6.toInt())
        drawButton(r2b2, r2b2 + btn3W, row3Y, "FILE MENU", hoveredActionButton == 100, 0xFFEC4899.toInt())
        drawButton(r2b3, r2b3 + btn3W, row3Y, "EXIT", hoveredActionButton == 102, 0xFFF04858.toInt())

        // ── Sensor debug HUD overlay ──
        if (sensorHudVisible && sensorDebugStr.isNotEmpty()) {
            y = row1Y - 20f
            val sensorPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 20f; color = 0xFF10B981.toInt()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val sensorBg = android.graphics.Paint().apply { color = 0xC0080810.toInt() }
            canvas.drawRoundRect(20f, y - 260f, uiW - 20f, y, 8f, 8f, sensorBg)
            var sy = y - 240f
            for (line in sensorDebugStr.lines()) {
                if (sy > y - 10f) break
                canvas.drawText(line, 30f, sy, sensorPaint)
                sy += 22f
            }
        }

        pendingUiBitmap = bitmap
    }

    private fun buildModelPanelView(): android.view.View {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        layout.addView(TextView(this).apply {
            text = "3D Model Viewer (Filament)"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        })

        layout.addView(TextView(this).apply {
            text = "${models.size} model${if (models.size != 1) "s" else ""} in scene"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 16)
        })

        for ((i, model) in models.withIndex()) {
            val isSelected = i == selectedModelIndex
            layout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (isSelected) 0xFF1565C0.toInt() else 0xFF1E1E1E.toInt())
                setPadding(24, 16, 24, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 2, 0, 2)
                layoutParams = lp

                addView(TextView(this@FilamentModelActivity).apply {
                    text = "${model.file.name} (${String.format("%.2f", model.scale)}x)"
                    textSize = 14f
                    setTextColor(0xFFE0E0E0.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(Button(this@FilamentModelActivity).apply {
                    text = if (isSelected) "Selected" else "Select"
                    textSize = 12f
                    setOnClickListener {
                        selectedModelIndex = i
                        renderUiToBitmap()
                    }
                })
            })
        }

        if (selectedModelIndex in models.indices) {
            val model = models[selectedModelIndex]

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Model Size"))
            layout.addView(makeSlider("Scale", 5, 500, (model.scale * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.scale = value / 100f
                    updateModelTransform(it)
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("PBR Material"))

            layout.addView(makeSlider("Metallic", 0, 100, (model.metallic * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.metallic = value / 100f
                    glesRenderer?.getModel(it.gpuModelId)?.metallic = it.metallic
                }
            })

            layout.addView(makeSlider("Roughness", 0, 100, (model.roughness * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.roughness = value / 100f
                    glesRenderer?.getModel(it.gpuModelId)?.roughness = it.roughness
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Lighting"))

            layout.addView(makeSlider("Exposure", -500, 500, (model.exposure * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.exposure = value / 100f
                    glesRenderer?.getModel(it.gpuModelId)?.exposure = it.exposure
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Hands"))
            layout.addView(Button(this).apply {
                text = if (handsLocked) "Unlock Hands" else "Lock Hands (walk around)"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (handsLocked) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                setOnClickListener {
                    handsLocked = !handsLocked
                    text = if (handsLocked) "Unlock Hands" else "Lock Hands (walk around)"
                    setBackgroundColor(if (handsLocked) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                    if (handsLocked) {
                        menuVisible = false
                        setContentView(android.view.View(this@FilamentModelActivity))
                    }
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(Button(this).apply {
                text = "Reset Position"
                textSize = 14f
                setBackgroundColor(0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    models.getOrNull(selectedModelIndex)?.let {
                        it.posX = 0f; it.posY = 0f; it.posZ = -1f
                        it.rotX = 0f; it.rotY = 0f; it.rotZ = 0f; it.rotW = 1f
                        updateModelTransform(it)
                    }
                }
            })
        }

        layout.addView(makeSpacer(16))
        layout.addView(TextView(this).apply {
            text = "Controls:\n" +
                    "  Laser pointer = aim from right hand\n" +
                    "  Trigger = Select model\n" +
                    "  Grip = Move selected model\n" +
                    "  Grip + Trigger = Move + rotate\n" +
                    "  Grip + Stick L/R = Scale\n" +
                    "  Grip + Stick Fwd/Back = Push/pull\n" +
                    "  Free stick L/R = Spin model\n" +
                    "  Free stick Up/Down = Lift/lower\n" +
                    "  Y = Cycle models\n" +
                    "  X = Toggle grid\n" +
                    "  B = Close panel / Exit"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        })

        layout.addView(makeSpacer(16))
        layout.addView(Button(this).apply {
            text = "Back to Files"
            textSize = 16f
            setBackgroundColor(0xFF8B0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                running = false
                finish()
            }
        })

        scrollView.addView(layout)
        return scrollView
    }

    // ── UI Helpers ──

    private fun showMessage(text: String) {
        setContentView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
    }

    private fun makeSpacer(height: Int): android.view.View {
        return android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 8, 0, 4)
        }
    }

    private fun makeSlider(label: String, min: Int, max: Int, initial: Int,
                           onChange: (Int) -> Unit): LinearLayout {
        val range = max - min
        val valueLabel = TextView(this).apply {
            text = "$label: $initial"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 2, 0, 2)
            layoutParams = lp
            addView(valueLabel)
            addView(SeekBar(this@FilamentModelActivity).apply {
                this.max = range
                progress = (initial - min).coerceIn(0, range)
                setPadding(0, 4, 0, 4)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueLabel.text = "$label: $value"
                        onChange(value)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── XR Sensor Polling ──
    // ═══════════════════════════════════════════════════════════════

    private fun pollXrSensors() {
        // Get capabilities once
        if (xrSensorCaps == 0) {
            xrSensorCaps = nativeGetSensorCapabilities()
            Log.i(TAG, "XR Sensor capabilities: 0x${xrSensorCaps.toString(16)}")
            if (xrSensorCaps and (1 shl 0) != 0) Log.i(TAG, "  + Hand tracking")
            if (xrSensorCaps and (1 shl 1) != 0) Log.i(TAG, "  + Eye tracking")
            if (xrSensorCaps and (1 shl 2) != 0) Log.i(TAG, "  + Face tracking")
            if (xrSensorCaps and (1 shl 3) != 0) Log.i(TAG, "  + Plane detection")
            if (xrSensorCaps and (1 shl 4) != 0) Log.i(TAG, "  + Refresh rate control")
            if (xrSensorCaps and (1 shl 5) != 0) Log.i(TAG, "  + Performance metrics")
            if (xrSensorCaps and (1 shl 6) != 0) Log.i(TAG, "  + Passthrough state")
        }

        // Hand tracking (both hands)
        if (xrSensorCaps and (1 shl 0) != 0) {
            handTrackingActive[0] = nativePollHandTracking(0, handTrackingBufferL) &&
                    handTrackingBufferL[0] > 0.5f
            handTrackingActive[1] = nativePollHandTracking(1, handTrackingBufferR) &&
                    handTrackingBufferR[0] > 0.5f
        }

        // Eye tracking
        if (xrSensorCaps and (1 shl 1) != 0) {
            if (nativePollEyeTracking(eyeTrackingBuffer) && eyeTrackingBuffer[0] > 0.5f) {
                eyeTrackingActive = true
                // Combined gaze: indices 17-24
                if (eyeTrackingBuffer[17] > 0.5f) {
                    gazeOriginX = eyeTrackingBuffer[18]; gazeOriginY = eyeTrackingBuffer[19]; gazeOriginZ = eyeTrackingBuffer[20]
                    gazeRotX = eyeTrackingBuffer[21]; gazeRotY = eyeTrackingBuffer[22]
                    gazeRotZ = eyeTrackingBuffer[23]; gazeRotW = eyeTrackingBuffer[24]
                }
            } else {
                eyeTrackingActive = false
            }
        }

        // Face tracking
        if (xrSensorCaps and (1 shl 2) != 0) {
            faceTrackingActive = nativePollFaceTracking(faceTrackingBuffer) &&
                    faceTrackingBuffer[0] > 0.5f
        }

        // Plane detection (less frequently - every 30 frames)
        if (xrSensorCaps and (1 shl 3) != 0 && sensorPollFrame % 30 == 0) {
            if (nativePollPlanes(planeBuffer) && planeBuffer[0] > 0.5f) {
                detectedPlaneCount = planeBuffer[1].toInt()

                // Parse planes for shadow receiving
                val gr = glesRenderer
                if (gr != null && detectedPlaneCount > 0) {
                    val planes = mutableListOf<GlesModelRenderer.ShadowPlane>()
                    for (i in 0 until minOf(detectedPlaneCount, 32)) {
                        val off = 2 + i * 10
                        planes.add(GlesModelRenderer.ShadowPlane(
                            posX = planeBuffer[off], posY = planeBuffer[off+1], posZ = planeBuffer[off+2],
                            rotX = planeBuffer[off+3], rotY = planeBuffer[off+4],
                            rotZ = planeBuffer[off+5], rotW = planeBuffer[off+6],
                            extentX = planeBuffer[off+7], extentY = planeBuffer[off+8],
                            label = planeBuffer[off+9].toInt()
                        ))
                    }
                    gr.shadowPlanes = planes
                }

                // Find the LOWEST horizontal surface — don't trust labels
                // (runtime labels bed as "floor", real floor may have any label)
                var lowestHorizY = Float.MAX_VALUE
                for (i in 0 until minOf(detectedPlaneCount, 32)) {
                    val off = 2 + i * 10
                    val posY = planeBuffer[off + 1]
                    val qx = planeBuffer[off + 3]; val qy = planeBuffer[off + 4]
                    val qz = planeBuffer[off + 5]; val qw = planeBuffer[off + 6]
                    // Plane normal Y = transform local up (0,1,0) by quaternion
                    val normalY = 1f - 2f * (qx * qx + qz * qz)
                    // Horizontal if normal Y > 0.7 (allows ~45° tilt)
                    if (normalY > 0.7f && posY < lowestHorizY) {
                        lowestHorizY = posY
                    }
                }
                if (lowestHorizY < Float.MAX_VALUE) {
                    val gr = glesRenderer
                    if (gr != null) {
                        if (!floorSnappedOnce) {
                            gr.gridHeight = lowestHorizY
                            detectedFloorY = lowestHorizY
                            floorSnappedOnce = true
                            Log.i(TAG, "Grid snap to lowest horizontal surface: $lowestHorizY")
                        } else if (lowestHorizY < detectedFloorY - 0.05f) {
                            gr.gridHeight = lowestHorizY
                            detectedFloorY = lowestHorizY
                            Log.i(TAG, "Grid re-snap to lower surface: $lowestHorizY")
                        }
                    }
                }
            }
        }

        // Performance metrics (every 15 frames)
        if (xrSensorCaps and (1 shl 5) != 0 && sensorPollFrame % 15 == 0) {
            if (nativePollPerfMetrics(perfMetricsBuffer) && perfMetricsBuffer[0] > 0.5f) {
                gpuFrameTimeMs = perfMetricsBuffer[1]
                cpuFrameTimeMs = perfMetricsBuffer[2]
                droppedFrames = perfMetricsBuffer[3]
                displayRefreshRate = perfMetricsBuffer[4]
            }
        }

        // Passthrough state
        if (xrSensorCaps and (1 shl 6) != 0 && sensorPollFrame % 60 == 0) {
            passthroughState = nativeGetPassthroughState()
        }

        // Build debug string (every 10 frames)
        if (sensorHudVisible && sensorPollFrame % 10 == 0) {
            sensorDebugStr = buildSensorDebugString()
        }
    }

    private fun buildSensorDebugString(): String {
        val sb = StringBuilder()

        // Android hardware sensors
        sensorHub?.let { hub ->
            sb.appendLine(hub.getDebugString())
            sb.appendLine()
        }

        // XR extensions
        sb.appendLine("=== XR SENSORS (caps=0x${xrSensorCaps.toString(16)}) ===")

        // XR Light
        if (xrLightEstimateAvailable)
            sb.appendLine("XR Light: $xrLightDebugStr")

        // Hand tracking
        if (handTrackingActive[0] || handTrackingActive[1]) {
            val lPalm = if (handTrackingActive[0]) {
                val px = handTrackingBufferL[1]; val py = handTrackingBufferL[2]; val pz = handTrackingBufferL[3]
                "(%.2f,%.2f,%.2f)".format(px, py, pz)
            } else "inactive"
            val rPalm = if (handTrackingActive[1]) {
                val px = handTrackingBufferR[1]; val py = handTrackingBufferR[2]; val pz = handTrackingBufferR[3]
                "(%.2f,%.2f,%.2f)".format(px, py, pz)
            } else "inactive"
            sb.appendLine("Hands: L=$lPalm R=$rPalm")

            // Pinch distance (thumb tip to index tip)
            if (handTrackingActive[1]) {
                val thumbOff = 1 + 5 * 8 // thumb tip = joint 5
                val indexOff = 1 + 10 * 8 // index tip = joint 10
                val dx = handTrackingBufferR[thumbOff] - handTrackingBufferR[indexOff]
                val dy = handTrackingBufferR[thumbOff+1] - handTrackingBufferR[indexOff+1]
                val dz = handTrackingBufferR[thumbOff+2] - handTrackingBufferR[indexOff+2]
                val pinchDist = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
                sb.appendLine("R Pinch: %.3f m %s".format(pinchDist, if (pinchDist < 0.02f) "PINCHED" else ""))
            }
        } else if (xrSensorCaps and (1 shl 0) != 0) {
            sb.appendLine("Hands: not tracked (controllers active?)")
        }

        // Eye tracking
        if (eyeTrackingActive) {
            sb.appendLine("Gaze: (%.3f,%.3f,%.3f) rot(%.2f,%.2f,%.2f,%.2f)".format(
                gazeOriginX, gazeOriginY, gazeOriginZ,
                gazeRotX, gazeRotY, gazeRotZ, gazeRotW))
        } else if (xrSensorCaps and (1 shl 1) != 0) {
            sb.appendLine("Eyes: not tracked (need permission?)")
        }

        // Face tracking
        if (faceTrackingActive) {
            // Show a few key blend shapes
            val jaw = faceTrackingBuffer[1]
            val smileL = faceTrackingBuffer[2]
            val smileR = faceTrackingBuffer[3]
            val browL = faceTrackingBuffer[4]
            sb.appendLine("Face: jaw=%.2f smile(%.2f,%.2f) brow=%.2f".format(jaw, smileL, smileR, browL))
        } else if (xrSensorCaps and (1 shl 2) != 0) {
            sb.appendLine("Face: not tracked (need permission?)")
        }

        // Planes
        if (detectedPlaneCount > 0) {
            sb.appendLine("Planes: $detectedPlaneCount detected")
            val count = minOf(detectedPlaneCount, 5)
            for (i in 0 until count) {
                val off = 2 + i * 10
                val labels = arrayOf("unknown", "floor", "ceiling", "wall", "table")
                val lbl = planeBuffer[off + 9].toInt().coerceIn(0, 4)
                sb.appendLine("  ${labels[lbl]}: (%.1f,%.1f,%.1f) %.1fx%.1f m".format(
                    planeBuffer[off], planeBuffer[off+1], planeBuffer[off+2],
                    planeBuffer[off+7]*2, planeBuffer[off+8]*2))
            }
        } else if (xrSensorCaps and (1 shl 3) != 0) {
            sb.appendLine("Planes: scanning...")
        }

        // Performance
        if (displayRefreshRate > 0) {
            sb.appendLine("Perf: GPU=%.1fms CPU=%.1fms @%.0fHz drop=%.0f".format(
                gpuFrameTimeMs, cpuFrameTimeMs, displayRefreshRate, droppedFrames))
        }

        // Passthrough
        val ptState = when(passthroughState) { 0 -> "disabled"; 1 -> "initializing"; 2 -> "enabled"; else -> "unknown" }
        sb.appendLine("Passthrough: $ptState")
        sb.appendLine("Blend: ${if (passthroughState == 2) "ALPHA_BLEND (MR)" else "OPAQUE (VR)"}")

        return sb.toString().trimEnd()
    }

    // ── Lifecycle ──

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
    private external fun nativeSetFoveationLevel(level: Int)
    private external fun nativeGetFoveationLevel(): Int
}
