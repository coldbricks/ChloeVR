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
        var exposure: Float = 0f
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
        "Light Intensity", "Fill Intensity", "Ambient", "Light Azimuth",
        "Light Height", "Shadow Dark", "Shadow Soft")
    private var lastXState = false
    private var lastRightStickClick = false
    private var uiNeedsRefresh = false
    private var lastBCloseTime = 0L
    private var hoveredMenuParam = -1
    private var lastHoveredMenuParam = -1

    // Draggable panel state
    private var panelPosX = 0f; private var panelPosY = 1.6f; private var panelPosZ = -1.2f
    private var panelRotX = 0f; private var panelRotY = 0f; private var panelRotZ = 0f; private var panelRotW = 1f
    private val PANEL_WIDTH = 0.85f  // meters in world space — big enough to hit easily
    private val PANEL_HEIGHT = 0.95f
    private var draggingPanel = false
    private var panelGrabDist = 1.0f // distance from hand at grab time

    // Action button hover state: -1=none, 100=back, 101=add object, 102=exit app, 200=title bar
    private var hoveredActionButton = -1
    private var lastHoveredActionButton = -1

    // Head/camera position (extracted from view matrix each frame)
    private var camPosX = 0f; private var camPosY = 1.6f; private var camPosZ = 0f
    private var camFwdX = 0f; private var camFwdY = 0f; private var camFwdZ = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing Filament model viewer")

        showMessage("Initializing 3D renderer...")

        // Check scene understanding permission for XR light estimation
        // (Don't requestPermissions here — it restarts the activity and kills OpenXR)
        // Permission granted via system Settings > Apps > ChloeVR > Permissions
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

        Thread {
            if (!nativeRendererInit(this)) {
                Log.e(TAG, "Failed to initialize OpenXR renderer")
                runOnUiThread { showMessage("Failed to initialize OpenXR renderer.\nPress Back to return.") }
                return@Thread
            }
            Log.i(TAG, "OpenXR renderer initialized")

            val renderer = GlesModelRenderer()
            if (!renderer.init()) {
                Log.e(TAG, "Failed to initialize GLES renderer")
                runOnUiThread { showMessage("Failed to initialize renderer.\nPress Back to return.") }
                return@Thread
            }
            glesRenderer = renderer
            Log.i(TAG, "GLES renderer initialized")

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
            updateModelTransform(placed)

            Log.i(TAG, "Model loaded: ${file.name}, scale=$autoScale, gpuId=$gpuId")
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

                                    // Set ambient from real room
                                    val ambScale = (ambR + ambG + ambB) / 3f
                                    gr.ambientIntensity = (ambScale * 2f).coerceIn(0.1f, 3f)
                                    gr.ambientColor = floatArrayOf(
                                        (ccR).coerceIn(0.5f, 1.5f),
                                        (ccG).coerceIn(0.5f, 1.5f),
                                        (ccB).coerceIn(0.5f, 1.5f)
                                    )

                                    // Set directional light from real room
                                    // XR gives "direction toward light" — ensure Y is positive (from above)
                                    // If Y is negative, the convention might be "direction OF light" → negate
                                    val dirScale = (dirR + dirG + dirB) / 3f
                                    if (dirScale > 0.01f) {
                                        var ldx = dirX; var ldy = dirY; var ldz = dirZ
                                        // If light direction points down, negate (convention mismatch)
                                        if (ldy < 0f) { ldx = -ldx; ldy = -ldy; ldz = -ldz }
                                        // Only use XR direction if it makes sense (light from above)
                                        if (ldy > 0.1f) {
                                            gr.lightDir = floatArrayOf(ldx, ldy, ldz)
                                        }
                                        gr.lightIntensity = (dirScale * 3f).coerceIn(0.5f, 5f)
                                        val maxDir = maxOf(dirR, dirG, dirB).coerceAtLeast(0.01f)
                                        gr.lightColor = floatArrayOf(dirR / maxDir, dirG / maxDir, dirB / maxDir)
                                    }

                                    // Pass SH coefficients to renderer
                                    xrSHAvailable = shValid
                                    if (shValid) {
                                        gr.shCoefficients = lightEstimateBuffer.copyOfRange(14, 41)
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

                            // Action buttons: bottom ~100px (3 buttons)
                            if (by > 895f) {
                                if (bx < 340f) hoveredActionButton = 100      // FILE MENU
                                else if (bx < 680f) hoveredActionButton = 101 // ADD MODEL
                                else hoveredActionButton = 102                 // EXIT
                            }

                            // Param rows: ~195..770 in bitmap Y
                            if (by in 195f..770f) {
                                val frac = (by - 195f) / (770f - 195f)
                                val idx = (frac * PARAM_NAMES.size).toInt().coerceIn(0, PARAM_NAMES.size - 1)
                                hoveredMenuParam = idx
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
                if (hoveredMenuParam != lastHoveredMenuParam || hoveredActionButton != lastHoveredActionButton) {
                    lastHoveredMenuParam = hoveredMenuParam
                    lastHoveredActionButton = hoveredActionButton
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
            if (menuVisible && hoveredActionButton == 100) {
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
            } else if (menuVisible && hoveredActionButton == 101) {
                // Add object — open file picker
                Log.i(TAG, "Action: Add object")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("model/gltf-binary", "application/octet-stream"))
                }
                startActivityForResult(intent, REQUEST_ADD_MODEL)
            } else if (menuVisible && hoveredMenuParam >= 0) {
                // Select the param the laser is pointing at
                selectedParam = hoveredMenuParam
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

        // B = toggle menu panel — always spawns in front of user, facing them
        if (bButton && !lastBState) {
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
                    // Global params (always work)
                    3 -> renderer.lightIntensity = (renderer.lightIntensity + delta * 2f).coerceIn(0f, 10f)
                    4 -> renderer.fillLightIntensity = (renderer.fillLightIntensity + delta).coerceIn(0f, 5f)
                    5 -> {
                        autoAmbient = false
                        renderer.ambientIntensity = (renderer.ambientIntensity + delta).coerceIn(0f, 5f)
                    }
                    6 -> {
                        renderer.lightAngleDeg = (renderer.lightAngleDeg + delta * 20f) % 360f
                        if (renderer.lightAngleDeg < 0f) renderer.lightAngleDeg += 360f
                        renderer.updateLightDirFromAngles()
                    }
                    7 -> {
                        renderer.lightElevDeg = (renderer.lightElevDeg + delta * 10f).coerceIn(5f, 90f)
                        renderer.updateLightDirFromAngles()
                    }
                    8 -> renderer.shadowDarkness = (renderer.shadowDarkness + delta).coerceIn(0f, 1f)
                    9 -> renderer.shadowSoftness = (renderer.shadowSoftness + delta * 5f).coerceIn(0.5f, 5f)
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
                    3 -> renderer.lightIntensity = 2.0f
                    4 -> renderer.fillLightIntensity = 0.5f
                    5 -> { autoAmbient = true; renderer.ambientIntensity = 1.0f }
                    6 -> { renderer.lightAngleDeg = 0f; renderer.updateLightDirFromAngles() }
                    7 -> { renderer.lightElevDeg = 60f; renderer.updateLightDirFromAngles() }
                    8 -> renderer.shadowDarkness = 0.7f
                    9 -> renderer.shadowSoftness = 2.0f
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
            // Snap selected model to grid floor
            val model = models.getOrNull(selectedModelIndex)
            val gr = glesRenderer
            if (model != null && gr != null) {
                val gpuModel = gr.getModel(model.gpuModelId)
                if (gpuModel != null) {
                    // Model's lowest point in local space = boundsMinY
                    // In world space: posY + boundsMinY * scale
                    // Snap so lowest point = gridHeight
                    val worldMinY = model.posY + gpuModel.boundsMinY * model.scale
                    model.posY += (gr.gridHeight - worldMinY)
                    updateModelTransform(model)
                    Log.i(TAG, "Snap to ground: posY=${model.posY} (minY=${gpuModel.boundsMinY}, grid=${gr.gridHeight})")
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
            "Light" to "%.1f".format(renderer?.lightIntensity ?: 2f),
            "Fill" to "%.1f".format(renderer?.fillLightIntensity ?: 0.5f),
            "Ambient" to "%.1f%s".format(renderer?.ambientIntensity ?: 1f, if (autoAmbient) " auto" else ""),
            "Azimuth" to "%.0f\u00B0".format(renderer?.lightAngleDeg ?: 0f),
            "Elevation" to "%.0f\u00B0".format(renderer?.lightElevDeg ?: 60f),
            "Shadow" to "%.0f%%".format((renderer?.shadowDarkness ?: 0.7f) * 100),
            "Softness" to "%.1f".format(renderer?.shadowSoftness ?: 2f),
        )

        val rowH = 55f
        val normal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 40f; color = 0xFFF3F4F6.toInt() }
        val highlight = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 42f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        val disabled = android.graphics.Paint().apply { isAntiAlias = true; textSize = 40f; color = 0xFF3A3A42.toInt() }
        val valuePaint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 36f; color = 0xFF9CA3AF.toInt() }
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
            val isPerModel = i <= 2
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

        // ── Controls hint ──
        val hint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f; color = 0xFF58585F.toInt() }
        canvas.drawText("Trigger:Select  Stick:Adjust  A:Reset  B:Close  X:Gizmo  Y:Next", 40f, y, hint)
        y += 28f
        canvas.drawText("Grip:Grab  R-Click:Grid  Menu:Sensors${if (sensorHudVisible) " [ON]" else ""}", 40f, y, hint)

        // ── Action buttons (3 across, y ~900) ──
        val btnY = uiH - 100f
        val btnH = 70f
        val btnGap = 12f
        val btnW = (uiW - 60f - btnGap * 2f) / 3f // 3 equal-width buttons

        fun drawButton(x1: Float, x2: Float, label: String, hovered: Boolean,
                       normalColor: Int, hoverColor: Int) {
            // Neon glow on hover
            if (hovered) {
                val glow = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f
                    color = normalColor
                    maskFilter = android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.OUTER)
                    isAntiAlias = true
                }
                canvas.drawRoundRect(x1, btnY, x2, btnY + btnH, 12f, 12f, glow)
            }
            val bg = android.graphics.Paint().apply {
                color = if (hovered) (normalColor and 0x00FFFFFF) or 0x70000000
                else (normalColor and 0x00FFFFFF) or 0x20000000
            }
            canvas.drawRoundRect(x1, btnY, x2, btnY + btnH, 12f, 12f, bg)
            val border = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = if (hovered) 3f else 1.5f
                color = if (hovered) normalColor else (normalColor and 0x00FFFFFF) or 0x60000000.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(x1, btnY, x2, btnY + btnH, 12f, 12f, border)
            val text = android.graphics.Paint().apply {
                isAntiAlias = true; textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER
                color = if (hovered) 0xFFFFFFFF.toInt() else normalColor
                isFakeBoldText = true
                if (hovered) maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.SOLID)
            }
            canvas.drawText(label, (x1 + x2) / 2f, btnY + 44f, text)
        }

        val b1x = 30f
        val b2x = b1x + btnW + btnGap
        val b3x = b2x + btnW + btnGap

        drawButton(b1x, b1x + btnW, "FILE MENU", hoveredActionButton == 100,
            0xFFEC4899.toInt(), 0xFFFF6BB5.toInt()) // pink
        drawButton(b2x, b2x + btnW, "+ ADD MODEL", hoveredActionButton == 101,
            0xFF10B981.toInt(), 0xFF34D399.toInt()) // green
        drawButton(b3x, b3x + btnW, "EXIT", hoveredActionButton == 102,
            0xFFF04858.toInt(), 0xFFFF6B6B.toInt()) // red

        // ── Sensor debug HUD overlay ──
        if (sensorHudVisible && sensorDebugStr.isNotEmpty()) {
            y = btnY - 20f
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
}
