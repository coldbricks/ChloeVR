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
        var posZ: Float = -2f,
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

    // Grid state
    @Volatile var gridVisible = true

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
    private var selectedParam = 0
    private val PARAM_NAMES = arrayOf("Metallic", "Roughness", "Exposure",
        "Contrast", "Saturation",
        "Light Intensity", "Fill Intensity", "Ambient", "Light Azimuth",
        "Light Height", "Shadow Dark", "Shadow Soft", "Shadow Spread",
        "BeatReactor", "Foveation")
    private var lastXState = false
    private var lastRightStickClick = false
    private var uiNeedsRefresh = false
    private var lastBCloseTime = 0L
    private var hoveredMenuParam = -1
    private var lastHoveredMenuParam = -1

    // Draggable panel state
    private var panelPosX = 0f; private var panelPosY = 1.6f; private var panelPosZ = -1.2f
    private var panelRotX = 0f; private var panelRotY = 0f; private var panelRotZ = 0f; private var panelRotW = 1f
    private val PANEL_WIDTH = 1.1f  // meters in world space — big enough to read easily
    private val PANEL_HEIGHT = 1.2f
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
    // Base values captured when BeatReactor turns on — beat modulates FROM these
    private var beatBaseStored = false
    private var beatBaseAmbient = 1f
    private var beatBaseLight = 2f
    private var beatBaseFill = 0.5f
    private var beatBaseShadow = 0.7f
    @Volatile private var foveationLevel = 0  // 0=off, 1=low, 2=med, 3=high
    private var beatToggleLatch = false
    private var foveationToggleLatch = false

    // BeatReactor settings sub-menu
    @Volatile private var beatSettingsMode = false
    private var beatDraggingSlider = -1  // which slider the laser is on (-1 = none)
    private var beatSliderLaserX = 0f    // 0..1 position on slider track

    // Auto-floor: lock grid to detected XR floor plane
    @Volatile private var autoFloorEnabled = false  // disabled — causes jitter, user snaps manually with A
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

            // Restore persistent floor height
            val savedGridHeight = getSharedPreferences("chloe_vr", MODE_PRIVATE)
                .getFloat("grid_height", 0f)
            if (savedGridHeight != 0f) {
                renderer.gridHeight = savedGridHeight
                Log.i(TAG, "Restored grid height: $savedGridHeight")
            }

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

            startRenderLoop()

            if (models.isNotEmpty()) {
                menuVisible = true
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
                posZ = -2f,
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
                        posZ = obj.optDouble("posZ", -2.0).toFloat(),
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
        BeatSlider("INPUT GAIN", "x", 0.2f, 5f,
            { audioReactor?.sensitivity ?: 1.5f }, { audioReactor?.sensitivity = it }),
        BeatSlider("THRESHOLD", "%", 0f, 0.5f,
            { audioReactor?.bassConfig?.gateThreshold ?: 0.3f },
            { audioReactor?.let { r -> r.bassConfig.gateThreshold = it; r.midConfig.gateThreshold = it; r.highConfig.gateThreshold = it } }),
        BeatSlider("ATTACK", "ms", 5f, 200f,
            { (audioReactor?.bassConfig?.attack ?: 0.4f) * 500f },
            { val v = it / 500f; audioReactor?.let { r -> r.bassConfig.attack = v; r.midConfig.attack = v; r.highConfig.attack = v } }),
        BeatSlider("RELEASE", "ms", 10f, 500f,
            { (audioReactor?.bassConfig?.decay ?: 0.08f) * 5000f },
            { val v = it / 5000f; audioReactor?.let { r -> r.bassConfig.decay = v; r.midConfig.decay = v; r.highConfig.decay = v } }),
        BeatSlider("LOW CUT", "Hz", 10f, 200f,
            { audioReactor?.bassConfig?.freqLow ?: 20f }, { audioReactor?.bassConfig?.freqLow = it }),
        BeatSlider("HIGH CUT", "Hz", 80f, 2000f,
            { audioReactor?.bassConfig?.freqHigh ?: 150f }, { audioReactor?.bassConfig?.freqHigh = it }),
        BeatSlider("OUTPUT MIX", "%", 0f, 100f,
            { beatIntensity * 50f }, { beatIntensity = it / 50f }),
        BeatSlider("OUTPUT FLOOR", "%", 0f, 50f,
            { (audioReactor?.bassConfig?.floor ?: 0f) * 100f },
            { val v = it / 100f; audioReactor?.let { r -> r.bassConfig.floor = v; r.midConfig.floor = v; r.highConfig.floor = v } }),
    ) }

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

                                    // Temporal smoothing: EMA to prevent jittery "moving light"
                                    // α = 0.08 for intensity/color (smooth), 0.04 for direction (very smooth)
                                    val aI = if (lightSmoothed) 0.08f else 1f  // instant on first frame
                                    val aD = if (lightSmoothed) 0.04f else 1f
                                    val aS = if (lightSmoothed) 0.06f else 1f
                                    lightSmoothed = true

                                    // Smooth ambient
                                    val targetAmbInt = ((ambR + ambG + ambB) / 3f * 2f).coerceIn(0.1f, 3f)
                                    smoothAmbientIntensity += (targetAmbInt - smoothAmbientIntensity) * aI
                                    gr.ambientIntensity = smoothAmbientIntensity

                                    val tcc = floatArrayOf(ccR.coerceIn(0.5f, 1.5f), ccG.coerceIn(0.5f, 1.5f), ccB.coerceIn(0.5f, 1.5f))
                                    for (i in 0..2) smoothAmbientColor[i] += (tcc[i] - smoothAmbientColor[i]) * aI
                                    gr.ambientColor = smoothAmbientColor.copyOf()

                                    // Smooth directional light
                                    val dirScale = (dirR + dirG + dirB) / 3f
                                    if (dirScale > 0.01f) {
                                        var ldx = dirX; var ldy = dirY; var ldz = dirZ
                                        if (ldy < 0f) { ldx = -ldx; ldy = -ldy; ldz = -ldz }
                                        if (ldy > 0.1f) {
                                            // Smooth direction (slerp-like via EMA + renormalize)
                                            smoothLightDir[0] += (ldx - smoothLightDir[0]) * aD
                                            smoothLightDir[1] += (ldy - smoothLightDir[1]) * aD
                                            smoothLightDir[2] += (ldz - smoothLightDir[2]) * aD
                                            val len = kotlin.math.sqrt(smoothLightDir[0]*smoothLightDir[0] +
                                                smoothLightDir[1]*smoothLightDir[1] + smoothLightDir[2]*smoothLightDir[2])
                                                .coerceAtLeast(0.001f)
                                            gr.lightDir = floatArrayOf(smoothLightDir[0]/len, smoothLightDir[1]/len, smoothLightDir[2]/len)
                                        }
                                        val targetInt = (dirScale * 3f).coerceIn(0.5f, 5f)
                                        smoothLightIntensity += (targetInt - smoothLightIntensity) * aI
                                        gr.lightIntensity = smoothLightIntensity

                                        val maxDir = maxOf(dirR, dirG, dirB).coerceAtLeast(0.01f)
                                        val tColor = floatArrayOf(dirR / maxDir, dirG / maxDir, dirB / maxDir)
                                        for (i in 0..2) smoothLightColor[i] += (tColor[i] - smoothLightColor[i]) * aI
                                        gr.lightColor = smoothLightColor.copyOf()
                                    }

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

                            // ── BeatReactor: audio-reactive lighting ──
                            // Like BCC BeatReactor: each band maps to a RANGE on a parameter
                            // Base value is preserved, beat oscillates between base and base+range
                            val reactor = audioReactor
                            if (reactor != null && beatReactorEnabled) {
                                reactor.update()
                                if (menuVisible && sensorPollFrame % 5 == 0) uiNeedsRefresh = true

                                val bi = beatIntensity
                                val b = reactor.bass   // 0..1
                                val m = reactor.mid    // 0..1
                                val h = reactor.high   // 0..1

                                // Store base values on first beat frame (so we modulate FROM them)
                                if (!beatBaseStored) {
                                    beatBaseAmbient = gr.ambientIntensity
                                    beatBaseLight = gr.lightIntensity
                                    beatBaseFill = gr.fillLightIntensity
                                    beatBaseShadow = gr.shadowDarkness
                                    beatBaseStored = true
                                }

                                // Ambient: bass drives between base and base+range
                                // Range scales with beatIntensity (bi)
                                gr.ambientIntensity = beatBaseAmbient + b * bi * 0.8f

                                // Main light: mid band drives it
                                gr.lightIntensity = beatBaseLight + m * bi * 1.2f

                                // Fill light: high frequencies add sparkle
                                gr.fillLightIntensity = beatBaseFill + h * bi * 0.6f

                                // Shadow: bass deepens shadows on the beat (tight range)
                                gr.shadowDarkness = (beatBaseShadow + b * bi * 0.15f).coerceAtMost(1f)

                                // Color: subtle warm shift on bass, cool on highs
                                // Modulate the existing color, don't replace it
                                val baseR = gr.ambientColor[0]
                                val baseG = gr.ambientColor[1]
                                val baseB = gr.ambientColor[2]
                                gr.ambientColor = floatArrayOf(
                                    baseR + b * bi * 0.15f,   // bass warms red
                                    baseG + m * bi * 0.08f,   // mid tints green
                                    baseB + h * bi * 0.12f    // high cools blue
                                )

                                // Per-model exposure: gentle pulse (not blinding)
                                val beatExp = b * bi * 0.3f
                                for (placed in models) {
                                    val gpuModel = gr.getModel(placed.gpuModelId)
                                    if (gpuModel != null) {
                                        gpuModel.exposure = placed.exposure + beatExp
                                    }
                                }
                            } else if (beatBaseStored) {
                                // Reactor turned off — restore base values
                                gr.ambientIntensity = beatBaseAmbient
                                gr.lightIntensity = beatBaseLight
                                gr.fillLightIntensity = beatBaseFill
                                gr.shadowDarkness = beatBaseShadow
                                beatBaseStored = false
                                for (placed in models) {
                                    val gpuModel = gr.getModel(placed.gpuModelId)
                                    if (gpuModel != null) gpuModel.exposure = placed.exposure
                                }
                            }

                            // Upload pending UI bitmap to compositor quad layer (swapchain texture)
                            val bmp = pendingUiBitmap
                            if (bmp != null) {
                                pendingUiBitmap = null
                                if (menuVisible) {
                                    val quadTex = nativeAcquireUiImage()
                                    if (quadTex > 0) {
                                        // Flip bitmap vertically — compositor quad UVs are bottom-up
                                        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, bmp.width / 2f, bmp.height / 2f) }
                                        val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, false)
                                        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, quadTex)
                                        android.opengl.GLUtils.texSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, flipped)
                                        android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0)
                                        nativeReleaseUiImage()
                                        flipped.recycle()
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
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(leftProj, leftView, gizmoPos, gizmoRot, hoveredGizmoAxis)
                            if (laserActive) gr.renderLaser(leftTexId, width, height, leftProj, leftView,
                                laserHandPos, laserAimRot, hitDistance)
                            gr.finishEyePass()
                            // Menu rendered via compositor quad layer (stereo-correct)

                            // Right eye
                            gr.renderEye(rightTexId, width, height, rightProj, rightView)
                            gr.renderGrid(rightTexId, width, height, rightProj, rightView,
                                gridAlpha = if (gridVisible) 0.3f else 0f)
                            gr.renderShadowPlanes(rightProj, rightView)
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(rightProj, rightView, gizmoPos, gizmoRot, hoveredGizmoAxis)
                            if (laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                laserHandPos, laserAimRot, hitDistance)
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

        val a = rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2]
        val b = 2f * (ocX * rayDir[0] + ocY * rayDir[1] + ocZ * rayDir[2])
        val c = ocX * ocX + ocY * ocY + ocZ * ocZ - sphereRadius * sphereRadius

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

                            if (beatSettingsMode) {
                                beatDraggingSlider = -1
                                beatSliderLaserX = 0f
                                hoveredActionButton = -1
                                if (by > 920f) {
                                    if (bx < 520f) hoveredActionButton = 112 // OFF
                                    else hoveredActionButton = 111 // BACK
                                } else if (by in 120f..880f) {
                                    val sliderIdx = ((by - 120f) / 95f).toInt().coerceIn(0, 7)
                                    beatDraggingSlider = sliderIdx
                                    // Slider track is x: 300..950 in bitmap coords
                                    beatSliderLaserX = ((bx - 300f) / 650f).coerceIn(0f, 1f)
                                    // Apply value immediately as laser moves over slider
                                    applyBeatSlider(sliderIdx, beatSliderLaserX)
                                    uiNeedsRefresh = true
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
                                // Row 3 (~925-990): FILE MENU / EXIT
                                if (by > 925f) {
                                    if (bx < 520f) hoveredActionButton = 100      // FILE MENU
                                    else hoveredActionButton = 102                 // EXIT
                                }

                                // Param rows: ~195..765 in bitmap Y (15 params at 38px)
                                if (by in 195f..765f) {
                                    val frac = (by - 195f) / (770f - 195f)
                                    val idx = (frac * PARAM_NAMES.size).toInt().coerceIn(0, PARAM_NAMES.size - 1)
                                    hoveredMenuParam = idx
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

            // Test model intersection (only if not hovering gizmo and menu is closed)
            if (!menuVisible && hoveredGizmoAxis == GlesModelRenderer.GIZMO_AXIS_NONE) {
                var nearestDist = Float.MAX_VALUE
                var nearestIdx = -1
                for ((i, placed) in models.withIndex()) {
                    val gpuModel = renderer.getModel(placed.gpuModelId) ?: continue
                    val m = gpuModel.modelMatrix
                    val worldCx = m[0]*gpuModel.boundsCenterX + m[4]*gpuModel.boundsCenterY + m[8]*gpuModel.boundsCenterZ + m[12]
                    val worldCy = m[1]*gpuModel.boundsCenterX + m[5]*gpuModel.boundsCenterY + m[9]*gpuModel.boundsCenterZ + m[13]
                    val worldCz = m[2]*gpuModel.boundsCenterX + m[6]*gpuModel.boundsCenterY + m[10]*gpuModel.boundsCenterZ + m[14]
                    val sx = kotlin.math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
                    val dist = raySphereIntersect(laserHandPos, rayDir,
                        floatArrayOf(worldCx, worldCy, worldCz), gpuModel.boundsRadius * sx)
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
            if (menuVisible && beatSettingsMode && hoveredActionButton == 111) {
                beatSettingsMode = false
                uiNeedsRefresh = true
            } else if (menuVisible && beatSettingsMode && hoveredActionButton == 112) {
                // OFF button in beat settings
                beatReactorEnabled = false
                audioReactor?.enabled = false
                beatSettingsMode = false
                beatBaseStored = false
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
            } else if (menuVisible && hoveredMenuParam >= 0) {
                // Select the param the laser is pointing at
                Log.i(TAG, "Param selected: $hoveredMenuParam / ${PARAM_NAMES.getOrNull(hoveredMenuParam)}")
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
                }
                uiNeedsRefresh = true
            } else if (hoveredModelIndex >= 0) {
                selectedModelIndex = hoveredModelIndex
                uiNeedsRefresh = true
            } else {
                // Deselect when pointing at nothing
                selectedModelIndex = -1
                if (renderer != null) {
                    for (placed in models) {
                        renderer.getModel(placed.gpuModelId)?.selected = false
                    }
                }
                uiNeedsRefresh = true
            }
        }
        lastRightTriggerState = rightTriggerPressed

        // Right stick click = toggle grid
        val rightStickClick = inputBuffer[14] > 0.5f
        if (rightStickClick && !lastRightStickClick && !menuVisible) {
            gridVisible = !gridVisible
            uiNeedsRefresh = true
        }
        // (lastRightStickClick updated in menu section below)

        // Grid height: grip + stick Y with nothing selected → adjust grid Y
        if (selectedModelIndex < 0 && rightHandValid && rightSqueeze > 0.5f && renderer != null) {
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

        // ═══ BeatReactor Settings (Slider Panel) ═══
        if (beatSettingsMode) {
            val headerP = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 36f; color = 0xFFEC4899.toInt(); isFakeBoldText = true
            }
            canvas.drawText("BEATREACTOR", 50f, 108f, headerP)

            // Level meters (top right)
            val reactor = audioReactor
            val meterP = android.graphics.Paint().apply { isAntiAlias = true }
            if (reactor != null) {
                val meterX = 700f; val meterW = 60f; val meterH = 70f
                // Bass meter
                meterP.color = 0xFFEC4899.toInt()
                canvas.drawRect(meterX, 108f - reactor.bass * meterH, meterX + meterW, 108f, meterP)
                meterP.color = 0x40FFFFFF.toInt()
                canvas.drawRect(meterX, 108f - meterH, meterX + meterW, 108f, meterP)
                // Mid meter
                meterP.color = 0xFF10B981.toInt()
                canvas.drawRect(meterX + 70f, 108f - reactor.mid * meterH, meterX + 70f + meterW, 108f, meterP)
                meterP.color = 0x40FFFFFF.toInt()
                canvas.drawRect(meterX + 70f, 108f - meterH, meterX + 70f + meterW, 108f, meterP)
                // High meter
                meterP.color = 0xFF3B82F6.toInt()
                canvas.drawRect(meterX + 140f, 108f - reactor.high * meterH, meterX + 140f + meterW, 108f, meterP)
                meterP.color = 0x40FFFFFF.toInt()
                canvas.drawRect(meterX + 140f, 108f - meterH, meterX + 140f + meterW, 108f, meterP)
            }

            // Sliders
            val labelP = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f; color = 0xFFD1D5DB.toInt() }
            val valueP = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFF9CA3AF.toInt() }
            val trackBg = android.graphics.Paint().apply { color = 0xFF1E1E28.toInt() }
            val trackFill = android.graphics.Paint().apply { color = 0xFFEC4899.toInt() }
            val trackHover = android.graphics.Paint().apply { color = 0xFFFF6BB5.toInt() }
            val knobP = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }

            val trackLeft = 300f; val trackRight = 950f; val trackH = 20f
            var sy = 135f

            for ((i, slider) in beatSliders.withIndex()) {
                val isHovered = i == beatDraggingSlider
                val value = slider.get()
                val norm = ((value - slider.min) / (slider.max - slider.min)).coerceIn(0f, 1f)

                // Label
                canvas.drawText(slider.name, 40f, sy + 16f, labelP)

                // Value
                val valStr = when {
                    slider.unit == "x" -> "%.1f${slider.unit}".format(value)
                    slider.unit == "Hz" -> "%.0f ${slider.unit}".format(value)
                    slider.unit == "ms" -> "%.0f ${slider.unit}".format(value)
                    else -> "%.0f${slider.unit}".format(value)
                }
                canvas.drawText(valStr, 160f, sy + 16f, valueP)

                // Track background
                canvas.drawRoundRect(trackLeft, sy, trackRight, sy + trackH, 4f, 4f, trackBg)

                // Track fill
                val fillEnd = trackLeft + norm * (trackRight - trackLeft)
                canvas.drawRoundRect(trackLeft, sy, fillEnd, sy + trackH, 4f, 4f,
                    if (isHovered) trackHover else trackFill)

                // Knob
                canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 14f else 10f, knobP)

                // Threshold line (for threshold slider)
                if (i == 1 && reactor != null) {
                    val threshX = trackLeft + norm * (trackRight - trackLeft)
                    val threshP = android.graphics.Paint().apply { color = 0xFFFFFF00.toInt(); strokeWidth = 2f }
                    canvas.drawLine(threshX, sy - 5f, threshX, sy + trackH + 5f, threshP)
                }

                sy += 95f
            }

            // OFF button and BACK button at bottom
            val btnY = uiH - 80f; val btnH = 55f
            val isOffHovered = hoveredActionButton == 112
            val isBackHovered = hoveredActionButton == 111
            val halfW = (uiW - 70f) / 2f

            val offBg = android.graphics.Paint().apply {
                color = if (isOffHovered) 0x80F04858.toInt() else 0x20F04858.toInt()
            }
            canvas.drawRoundRect(30f, btnY, 30f + halfW, btnY + btnH, 10f, 10f, offBg)
            val offTxt = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isOffHovered) 0xFFFFFFFF.toInt() else 0xFFF04858.toInt(); isFakeBoldText = true
            }
            canvas.drawText("TURN OFF", 30f + halfW / 2f, btnY + 36f, offTxt)

            val backBg = android.graphics.Paint().apply {
                color = if (isBackHovered) 0x80EC4899.toInt() else 0x20EC4899.toInt()
            }
            canvas.drawRoundRect(40f + halfW, btnY, uiW - 30f, btnY + btnH, 10f, 10f, backBg)
            val backTxt = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 26f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt(); isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", 40f + halfW + (uiW - 70f - halfW) / 2f, btnY + 36f, backTxt)

            // Auto-refresh for live meters
            if (sensorPollFrame % 5 == 0) pendingUiBitmap = bitmap

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
            val headerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 38f; color = 0xFF10B981.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Select a 3D Model", 50f, 115f, headerPaint)

            val files = availableGlbFiles
            if (files.isEmpty()) {
                val emptyPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 32f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("No .glb files found on device", 50f, 200f, emptyPaint)
            } else {
                val maxVisible = 13
                val rowH = 60f
                val startY = 130f
                val normalPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 34f; color = 0xFFF3F4F6.toInt()
                }
                val hoverPaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 36f; color = 0xFF10B981.toInt(); isFakeBoldText = true
                }
                val sizePaint = android.graphics.Paint().apply {
                    isAntiAlias = true; textSize = 26f; color = 0xFF6B7280.toInt()
                }
                val hoverBg = android.graphics.Paint().apply { color = 0x2010B981.toInt() }
                val loadedBg = android.graphics.Paint().apply { color = 0x20EC4899.toInt() }

                val loadedPaths = models.map { it.file.absolutePath }.toSet()

                for (vi in 0 until maxVisible) {
                    val idx = glbPickerScrollOffset + vi
                    if (idx >= files.size) break
                    val file = files[idx]
                    val ry = startY + vi * rowH
                    val isHovered = idx == hoveredGlbIndex
                    val isLoaded = file.absolutePath in loadedPaths

                    if (isHovered) {
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, hoverBg)
                    }
                    if (isLoaded) {
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, loadedBg)
                    }

                    val label = file.nameWithoutExtension
                    val displayName = if (label.length > 32) label.take(30) + ".." else label
                    canvas.drawText(displayName, 50f, ry + 34f, if (isHovered) hoverPaint else normalPaint)

                    val sizeStr = "%.1f MB".format(file.length() / 1048576f)
                    val sw = sizePaint.measureText(sizeStr)
                    canvas.drawText(sizeStr, uiW - 60f - sw, ry + 34f, sizePaint)
                }

                // Scroll indicator
                if (files.size > maxVisible) {
                    val scrollPaint = android.graphics.Paint().apply {
                        isAntiAlias = true; textSize = 24f; color = 0xFF6B7280.toInt()
                    }
                    val shown = "${glbPickerScrollOffset + 1}-${minOf(glbPickerScrollOffset + maxVisible, files.size)} of ${files.size}"
                    canvas.drawText(shown, 50f, startY + maxVisible * rowH + 20f, scrollPaint)
                }
            }

            // BACK button at bottom
            val btnY = uiH - 80f
            val btnH = 60f
            val isBackHovered = hoveredActionButton == 103
            val backBg = android.graphics.Paint().apply {
                color = if (isBackHovered) 0x70EC4899.toInt() else 0x20EC4899.toInt()
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f, backBg)
            val backBorder = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = if (isBackHovered) 3f else 1.5f
                color = if (isBackHovered) 0xFFEC4899.toInt() else 0x60EC4899.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(30f, btnY, uiW - 30f, btnY + btnH, 12f, 12f, backBorder)
            val backText = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER
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

        var y = 118f
        val luxStr = when {
            !autoAmbient -> "Manual"
            xrLightEstimateAvailable && xrSHAvailable -> "XR+SH"
            xrLightEstimateAvailable -> "XR Light"
            else -> "%.0f lux".format(roomLux)
        }
        val gridStr = if (gridVisible) "ON" else "OFF"
        canvas.drawText("${models.size} models  |  Grid: $gridStr  |  Light: $luxStr", 50f, y, dim)
        y += 32f

        val model = models.getOrNull(selectedModelIndex)
        val modelName = model?.file?.nameWithoutExtension ?: "No selection"
        val namePaint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 32f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        canvas.drawText(modelName, 50f, y, namePaint)
        y += 16f

        // ── Separator ──
        val sepPaint = android.graphics.Paint().apply { color = 0x30EC4899.toInt(); strokeWidth = 1f }
        canvas.drawLine(40f, y, uiW - 40f, y, sepPaint)
        y += 22f

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
                if (r != null) "ON ${r.statusString()}" else "ON"
            } else "OFF",
            "Foveation" to arrayOf("OFF", "LOW", "MED", "HIGH")[foveationLevel],
        )

        val rowH = 38f  // 15 params
        val normal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 32f; color = 0xFFF3F4F6.toInt() }
        val highlight = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 34f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        val disabled = android.graphics.Paint().apply { isAntiAlias = true; textSize = 32f; color = 0xFF3A3A42.toInt() }
        val valuePaint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF9CA3AF.toInt() }
        val hoverBg = android.graphics.Paint().apply { color = 0x20EC4899.toInt() }
        val selectedBg = android.graphics.Paint().apply { color = 0x3030D8D0.toInt() }
        val selectedBar = android.graphics.Paint().apply { color = 0xFFEC4899.toInt() }

        for ((i, param) in params.withIndex()) {
            val rowTop = y - 10f
            val rowBot = y + rowH - 16f

            if (i == selectedParam) {
                canvas.drawRoundRect(24f, rowTop, uiW - 24f, rowBot, 8f, 8f, selectedBg)
                canvas.drawRect(24f, rowTop, 30f, rowBot, selectedBar) // pink accent bar
            }
            if (i == hoveredMenuParam && hoveredMenuParam != selectedParam) {
                canvas.drawRoundRect(24f, rowTop, uiW - 24f, rowBot, 8f, 8f, hoverBg)
            }

            val isSelected = i == selectedParam
            val isHovered = i == hoveredMenuParam
            val isPerModel = i <= 4
            val labelP = when {
                isPerModel && model == null -> disabled
                isSelected || isHovered -> highlight
                else -> normal
            }
            val arrow = if (isSelected) "\u25B6 " else "  "
            canvas.drawText("$arrow${param.first}", 50f, y + 30f, labelP)

            // Value right-aligned
            val valStr = param.second
            val valW = valuePaint.measureText(valStr)
            canvas.drawText(valStr, uiW - 60f - valW, y + 30f, valuePaint)

            y += rowH
        }

        // ── Separator ──
        y += 8f
        canvas.drawLine(40f, y, uiW - 40f, y, sepPaint)
        y += 16f

        // Controls hint (single compact line)
        val hint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 16f; color = 0xFF404048.toInt() }
        canvas.drawText("Stick:Adjust  A:Reset  B:Close  X:Gizmo  Y:Next  Grip:Grab", 40f, y, hint)

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
        val row1Y = uiH - 180f
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

        // Row 3: FILE MENU / EXIT
        val row3Y = row2Y + btnH + btnGap
        drawButton(30f, 30f + btn2W, row3Y, "FILE MENU", hoveredActionButton == 100, 0xFFEC4899.toInt())
        drawButton(30f + btn2W + btnGap, uiW - 30f, row3Y, "EXIT", hoveredActionButton == 102, 0xFFF04858.toInt())

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
                        it.posX = 0f; it.posY = 0f; it.posZ = -2f
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

                // Auto-floor: find the floor plane and lock grid to its Y position
                if (autoFloorEnabled) {
                    for (i in 0 until minOf(detectedPlaneCount, 32)) {
                        val off = 2 + i * 10
                        val label = planeBuffer[off + 9].toInt()
                        if (label == 1) { // 1 = floor
                            val floorY = planeBuffer[off + 1]
                            // Very slow smoothing + dead zone to prevent earthquake
                            detectedFloorY = if (detectedFloorY == Float.MIN_VALUE) floorY
                                else detectedFloorY + (floorY - detectedFloorY) * 0.005f
                            val gr = glesRenderer
                            if (gr != null && kotlin.math.abs(gr.gridHeight - detectedFloorY) > 0.01f) {
                                gr.gridHeight = detectedFloorY
                            }
                            break
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

        // Persist floor height for next session
        val gr = glesRenderer
        if (gr != null) {
            getSharedPreferences("chloe_vr", MODE_PRIVATE).edit()
                .putFloat("grid_height", gr.gridHeight)
                .apply()
            Log.i(TAG, "Saved grid height: ${gr.gridHeight}")
        }

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
