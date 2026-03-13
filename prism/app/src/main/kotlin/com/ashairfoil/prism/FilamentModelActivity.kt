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

    // Ambient light sensor
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    @Volatile private var roomLux = 200f  // default indoor
    @Volatile private var autoAmbient = true

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing Filament model viewer")

        showMessage("Initializing 3D renderer...")

        // Register ambient light sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Ambient light sensor registered")
        } else {
            Log.i(TAG, "No ambient light sensor available, using default lighting")
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
                    val width = frameData[67].toInt()
                    val height = frameData[68].toInt()

                    val gr = glesRenderer
                    if (gr != null) {
                        try {
                            // Update ambient from room light sensor
                            if (autoAmbient) {
                                // Environment-adaptive lighting from room sensor
                                val lux = roomLux
                                // Ambient intensity: dark room=low, bright room=high
                                gr.ambientIntensity = when {
                                    lux <= 0f -> 0.15f
                                    lux < 100f -> 0.15f + (lux / 100f) * 0.55f  // 0.15-0.7
                                    lux < 400f -> 0.7f + ((lux - 100f) / 300f) * 0.5f  // 0.7-1.2
                                    lux < 1500f -> 1.2f + ((lux - 400f) / 1100f) * 0.6f // 1.2-1.8
                                    else -> (1.8f + kotlin.math.ln(lux / 1500f) * 0.3f).coerceAtMost(2.5f)
                                }
                                // Primary light: reduce in bright rooms (real light provides fill)
                                gr.lightIntensity = when {
                                    lux < 50f -> 2.5f   // dark: boost virtual light
                                    lux < 300f -> 2.0f   // normal
                                    lux < 1000f -> 1.6f  // bright: real light helps
                                    else -> 1.2f          // very bright: real light dominates
                                }
                                // Color temperature: warm in low light, neutral-cool in bright
                                val warmth = (1f - (lux / 800f).coerceIn(0f, 1f))
                                gr.ambientColor = floatArrayOf(
                                    1f + warmth * 0.15f,    // warm red boost in low light
                                    1f - warmth * 0.02f,    // slight green reduction
                                    1f - warmth * 0.12f     // cool blue reduction in low light
                                )
                                // Light color also shifts warm in low light
                                gr.lightColor = floatArrayOf(
                                    1f + warmth * 0.08f,
                                    0.95f + warmth * 0.02f,
                                    0.9f - warmth * 0.05f
                                )
                            }

                            // Upload pending UI bitmap
                            val bmp = pendingUiBitmap
                            if (bmp != null) {
                                pendingUiBitmap = null
                                if (uiTextureId == 0) {
                                    val buf = intArrayOf(0)
                                    android.opengl.GLES30.glGenTextures(1, buf, 0)
                                    uiTextureId = buf[0]
                                }
                                android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, uiTextureId)
                                android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MIN_FILTER, android.opengl.GLES30.GL_LINEAR)
                                android.opengl.GLES30.glTexParameteri(android.opengl.GLES30.GL_TEXTURE_2D, android.opengl.GLES30.GL_TEXTURE_MAG_FILTER, android.opengl.GLES30.GL_LINEAR)
                                android.opengl.GLUtils.texImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                                android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, 0)
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
                            if (menuVisible && uiTextureId != 0) gr.renderUiOverlay(leftTexId, width, height, uiTextureId, leftProj, leftView)

                            // Right eye
                            gr.renderEye(rightTexId, width, height, rightProj, rightView)
                            gr.renderGrid(rightTexId, width, height, rightProj, rightView,
                                gridAlpha = if (gridVisible) 0.3f else 0f)
                            if (gizmoVisible && gizmoPos != null && gizmoRot != null)
                                gr.renderGizmo(rightProj, rightView, gizmoPos, gizmoRot, hoveredGizmoAxis)
                            if (laserActive) gr.renderLaser(rightTexId, width, height, rightProj, rightView,
                                laserHandPos, laserAimRot, hitDistance)
                            gr.finishEyePass()
                            if (menuVisible && uiTextureId != 0) gr.renderUiOverlay(rightTexId, width, height, uiTextureId, rightProj, rightView)

                            android.opengl.GLES30.glFlush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Render error", e)
                        }
                    }
                }

                nativeSubmitFrame()

                if (nativePollInput(inputBuffer)) {
                    handleInput()
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

        lastMenuState = menuButton

        // ── Laser pointer + ray intersection + gizmo hover ──
        val renderer = glesRenderer
        if (rightHandValid && rightAimValid && renderer != null) {
            laserActive = true
            laserHandPos = floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
            laserAimRot = floatArrayOf(rightAimRotX, rightAimRotY, rightAimRotZ, rightAimRotW)

            val rayDir = renderer.quatForward(laserAimRot)

            // ── Menu panel hit test (when visible) ──
            hoveredMenuParam = -1
            if (menuVisible) {
                // Panel: 0.8m wide × 0.9m tall, center at (0, 0, -1.0), normal = +Z
                // Ray-plane intersection: plane Z = -1.0
                val panelCZ = -1.0f
                val panelHW = 0.4f   // half-width (X)
                val panelHH = 0.45f  // half-height (Y)

                // Only if ray has a meaningful Z component toward the panel
                if (kotlin.math.abs(rayDir[2]) > 0.01f) {
                    val t = (panelCZ - laserHandPos[2]) / rayDir[2]
                    if (t > 0f && t < 8f) {
                        val hx = laserHandPos[0] + rayDir[0] * t
                        val hy = laserHandPos[1] + rayDir[1] * t

                        if (hx in -panelHW..panelHW && hy in -panelHH..panelHH) {
                            hitDistance = t
                            // Map panel Y to param index
                            // Params: start at bitmap Y~260, 10 rows × 55px → end ~810
                            val paramTopY = panelHH - (255f / 1024f) * (panelHH * 2f)
                            val paramBotY = panelHH - (815f / 1024f) * (panelHH * 2f)
                            if (hy in paramBotY..paramTopY) {
                                val frac = (paramTopY - hy) / (paramTopY - paramBotY)
                                val idx = (frac * PARAM_NAMES.size).toInt().coerceIn(0, PARAM_NAMES.size - 1)
                                hoveredMenuParam = idx
                            }
                        }
                    }
                }
                // Refresh UI when hover changes
                if (hoveredMenuParam != lastHoveredMenuParam) {
                    lastHoveredMenuParam = hoveredMenuParam
                    uiNeedsRefresh = true
                }
            }

            // Test gizmo first (priority over model hover)
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            val selModel = models.getOrNull(selectedModelIndex)
            if (gizmoVisible && selModel != null && !gizmoDragging) {
                val gPos = floatArrayOf(selModel.posX, selModel.posY, selModel.posZ)
                val gRot = floatArrayOf(selModel.rotX, selModel.rotY, selModel.rotZ, selModel.rotW)
                val (axis, _) = renderer.testGizmoHit(laserHandPos, rayDir, gPos, gRot)
                hoveredGizmoAxis = axis
            }

            // Test model intersection (only if not hovering gizmo)
            if (hoveredGizmoAxis == GlesModelRenderer.GIZMO_AXIS_NONE) {
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
                    selModel.posX = gizmoDragStartModelPos[0] + worldAxis[0] * proj
                    selModel.posY = gizmoDragStartModelPos[1] + worldAxis[1] * proj
                    selModel.posZ = gizmoDragStartModelPos[2] + worldAxis[2] * proj
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
            if (menuVisible && hoveredMenuParam >= 0) {
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

        // B = open panel / close panel / exit
        if (bButton && !lastBState) {
            if (menuVisible) {
                menuVisible = false
            } else if (System.currentTimeMillis() - lastBCloseTime < 1000) {
                running = false
                runOnUiThread { finish() }
            } else {
                menuVisible = true
                uiNeedsRefresh = true
            }
            if (!menuVisible) lastBCloseTime = System.currentTimeMillis()
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
                grabDistance = grabDistance.coerceIn(0.2f, 20f)
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

        if (aButton && !lastAState) {
            // Reserved for future use (animation)
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
        val uiH = 1024  // taller to fit more params
        val bitmap = android.graphics.Bitmap.createBitmap(uiW, uiH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xD0101010.toInt())

        val title = android.graphics.Paint().apply { isAntiAlias = true; textSize = 56f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
        val normal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 44f; color = 0xFFDDDDDD.toInt() }
        val highlight = android.graphics.Paint().apply { isAntiAlias = true; textSize = 48f; color = 0xFF00CCFF.toInt(); isFakeBoldText = true }
        val dim = android.graphics.Paint().apply { isAntiAlias = true; textSize = 36f; color = 0xFF777777.toInt() }

        var y = 80f
        canvas.drawText("3D Model Viewer", 50f, y, title)
        y += 60f

        val renderer = glesRenderer
        // Model count + grid state
        val luxStr = if (autoAmbient) "Auto (%.0f lux)".format(roomLux) else "Manual"
        val gridStr = if (gridVisible) "ON (Y=%.1f)".format(renderer?.gridHeight ?: 0f) else "OFF"
        val gizmoStr = if (gizmoVisible) "ON" else "OFF"
        canvas.drawText("${models.size} model${if (models.size != 1) "s" else ""}  |  Grid: $gridStr  |  Gizmo: $gizmoStr", 50f, y, dim)
        y += 70f

        val model = models.getOrNull(selectedModelIndex)
        val modelName = model?.file?.name ?: "No model selected"
        canvas.drawText(modelName, 50f, y, dim)
        y += 70f

        val noModel = "---"
        val params = arrayOf(
            "Metallic" to (if (model != null) "%.0f%%".format(model.metallic * 100) else noModel),
            "Roughness" to (if (model != null) "%.0f%%".format(model.roughness * 100) else noModel),
            "Exposure" to (if (model != null) "%+.1f EV".format(model.exposure) else noModel),
            "Light Intensity" to "%.1f".format(renderer?.lightIntensity ?: 2f),
            "Fill Intensity" to "%.1f".format(renderer?.fillLightIntensity ?: 0.5f),
            "Ambient" to "%.1f%s".format(renderer?.ambientIntensity ?: 1f,
                if (autoAmbient) " (auto)" else ""),
            "Light Azimuth" to "%.0f°".format(renderer?.lightAngleDeg ?: 0f),
            "Light Height" to "%.0f°".format(renderer?.lightElevDeg ?: 60f),
            "Shadow Dark" to "%.0f%%".format((renderer?.shadowDarkness ?: 0.7f) * 100),
            "Shadow Soft" to "%.1f".format(renderer?.shadowSoftness ?: 2f),
        )
        val hoverBg = android.graphics.Paint().apply { color = 0x40FFFFFF.toInt() }
        val selectedBg = android.graphics.Paint().apply { color = 0x3000CCFF.toInt() }
        val disabled = android.graphics.Paint().apply { isAntiAlias = true; textSize = 44f; color = 0xFF555555.toInt() }
        for ((i, param) in params.withIndex()) {
            if (i == hoveredMenuParam && hoveredMenuParam != selectedParam) {
                canvas.drawRect(20f, y - 48f, uiW - 20f, y + 14f, hoverBg)
            }
            if (i == selectedParam) {
                canvas.drawRect(20f, y - 48f, uiW - 20f, y + 14f, selectedBg)
            }
            val isHovered = i == hoveredMenuParam
            val isSelected = i == selectedParam
            val isPerModel = i <= 2
            val p = when {
                isPerModel && model == null -> disabled
                isSelected || isHovered -> highlight
                else -> normal
            }
            val arrow = if (isSelected) ">" else if (isHovered) "-" else " "
            canvas.drawText("$arrow ${param.first}:  ${param.second}", 50f, y, p)
            y += 55f
        }

        y += 20f
        canvas.drawText("[Laser+Trigger] Select   [R Stick] Adjust", 50f, y, dim)
        y += 40f
        canvas.drawText("[A] Reset   [B] Close   [X] Gizmo", 50f, y, dim)
        y += 40f
        canvas.drawText("[Grip] Grab   [R Stick Click] Grid   [Y] Next", 50f, y, dim)

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
}
