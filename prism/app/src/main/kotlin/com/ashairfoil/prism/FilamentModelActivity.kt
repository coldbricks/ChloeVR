package com.ashairfoil.prism

import android.app.Activity
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
    }

    // Rendering
    private var glesRenderer: GlesModelRenderer? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    // Models
    data class PlacedModel(
        val file: File,
        val asset: Any?,
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
        var exposure: Float = 0f  // EV offset
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

    // Grab state
    private var grabbing = false
    private var grabStartHandPos = floatArrayOf(0f, 0f, 0f)
    private var grabStartModelPos = floatArrayOf(0f, 0f, 0f)
    private var grabStartAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var grabStartModelRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var pushOffsetZ = 0f

    // UI state
    @Volatile private var menuVisible = false
    private var handsLocked = false
    private var uiTextureId = 0
    @Volatile private var pendingUiBitmap: android.graphics.Bitmap? = null
    private var selectedParam = 0  // 0=Metallic, 1=Roughness, 2=Exposure
    private val PARAM_NAMES = arrayOf("Metallic", "Roughness", "Exposure")
    private var lastXState = false
    private var lastRightStickClick = false
    private var uiNeedsRefresh = false
    private var lastBCloseTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: initializing Filament model viewer")

        showMessage("Initializing 3D renderer...")

        // Initialize on a background thread (OpenXR init + Filament)
        Thread {
            // 1. Initialize native OpenXR renderer
            if (!nativeRendererInit(this)) {
                Log.e(TAG, "Failed to initialize OpenXR renderer")
                runOnUiThread { showMessage("Failed to initialize OpenXR renderer.\nPress Back to return.") }
                return@Thread
            }
            Log.i(TAG, "OpenXR renderer initialized")

            // 2. Create GLES renderer (renders directly to OpenXR swapchain textures)
            // EGL context is already current from nativeRendererInit
            val renderer = GlesModelRenderer()
            if (!renderer.init()) {
                Log.e(TAG, "Failed to initialize GLES renderer")
                runOnUiThread { showMessage("Failed to initialize renderer.\nPress Back to return.") }
                return@Thread
            }
            glesRenderer = renderer
            Log.i(TAG, "GLES renderer initialized")

            // 3. Load the model
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

            // 4. Start render loop
            startRenderLoop()

            // 5. Show UI quad panel
            if (models.isNotEmpty()) {
                menuVisible = true
                runOnUiThread { renderUiToBitmap() }
            }
        }.start()
    }

    private fun loadModel(file: File) {
        val renderer = glesRenderer ?: return
        try {
            val bytes = file.readBytes()
            Log.i(TAG, "Loading GLB: ${file.name} (${bytes.size} bytes)")

            if (!renderer.loadGlb(bytes)) {
                Log.e(TAG, "Failed to load GLB: ${file.name}")
                runOnUiThread { showMessage("Failed to load 3D model: ${file.name}") }
                return
            }

            val autoScale = 0.75f

            val placed = PlacedModel(
                file = file,
                asset = null,
                scale = autoScale,
                baseScale = autoScale,
                posZ = -2f,
                metallic = renderer.metallic,
                roughness = renderer.roughness
            )
            models.add(placed)
            selectedModelIndex = models.size - 1
            updateModelTransform(placed)

            Log.i(TAG, "Model loaded: ${file.name}, scale=$autoScale")
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${file.name}", e)
            runOnUiThread { showMessage("Error: ${e.message}") }
        }
    }

    private fun updateModelTransform(model: PlacedModel) {
        val renderer = glesRenderer ?: return

        val s = model.scale
        val x = model.rotX; val y = model.rotY; val z = model.rotZ; val w = model.rotW

        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2

        renderer.modelMatrix = floatArrayOf(
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
            // Make EGL context current on this thread for our own GL calls (texture creation, blit)
            nativeMakeGLContextCurrent()
            while (running) {
                // Block until OpenXR wants a frame
                if (!nativeWaitFrame(frameData)) {
                    // Session not ready yet, keep trying
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
                            // Upload pending UI bitmap to a regular GL texture
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

                            gr.renderEye(leftTexId, width, height, leftProj, leftView)
                            if (menuVisible && uiTextureId != 0) gr.renderUiOverlay(leftTexId, width, height, uiTextureId, leftProj, leftView)

                            gr.renderEye(rightTexId, width, height, rightProj, rightView)
                            if (menuVisible && uiTextureId != 0) gr.renderUiOverlay(rightTexId, width, height, uiTextureId, rightProj, rightView)

                            android.opengl.GLES30.glFlush()
                        } catch (e: Exception) {
                            Log.e(TAG, "Render error", e)
                        }
                    }
                }

                // Submit frame to compositor
                nativeSubmitFrame()

                // Poll input (non-blocking, process on this thread)
                if (nativePollInput(inputBuffer)) {
                    handleInput()
                }
            }
            Log.i(TAG, "Render loop stopped")
        }, "ChloeVR-RenderLoop").apply { start() }
    }

    // ── Controller Input ──

    private fun handleInput() {
        // Parse input buffer (same layout as OpenXRInput)
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

        // Log first few input polls to verify
        if (inputLogCount < 5 && (menuButton || bButton || aButton)) {
            Log.i(TAG, "Input: menu=$menuButton b=$bButton a=$aButton squeeze=${inputBuffer[6]},${inputBuffer[7]}")
            inputLogCount++
        }

        lastMenuState = menuButton

        // B = open panel / close panel / exit
        if (bButton && !lastBState) {
            if (menuVisible) {
                menuVisible = false
            } else if (System.currentTimeMillis() - lastBCloseTime < 1000) {
                // Double-B (within 1s of closing panel) = exit
                running = false
                runOnUiThread { finish() }
            } else {
                menuVisible = true
                uiNeedsRefresh = true
            }
            if (!menuVisible) lastBCloseTime = System.currentTimeMillis()
        }
        lastBState = bButton

        // When menu is visible: one-handed right controller operation
        if (menuVisible && selectedModelIndex in models.indices) {
            val model = models[selectedModelIndex]

            // Right stick click = cycle parameter
            val rightStickClick = inputBuffer[14] > 0.5f
            if (rightStickClick && !lastRightStickClick) {
                selectedParam = (selectedParam + 1) % PARAM_NAMES.size
                uiNeedsRefresh = true
            }
            lastRightStickClick = rightStickClick

            // Right thumbstick up/down = adjust selected parameter
            if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                val delta = rightThumbY * 0.015f
                when (selectedParam) {
                    0 -> { model.metallic = (model.metallic + delta).coerceIn(0f, 1f); glesRenderer?.metallic = model.metallic }
                    1 -> { model.roughness = (model.roughness + delta).coerceIn(0.05f, 1f); glesRenderer?.roughness = model.roughness }
                    2 -> { model.exposure = (model.exposure + delta * 3f).coerceIn(-5f, 5f); glesRenderer?.exposure = model.exposure }
                }
                uiNeedsRefresh = true
            }

            // A button = reset selected parameter
            if (aButton && !lastAState) {
                when (selectedParam) {
                    0 -> { model.metallic = 0f; glesRenderer?.metallic = 0f }
                    1 -> { model.roughness = 0.9f; glesRenderer?.roughness = 0.9f }
                    2 -> { model.exposure = 0f; glesRenderer?.exposure = 0f }
                }
                uiNeedsRefresh = true
            }
            lastAState = aButton

            if (uiNeedsRefresh) {
                uiNeedsRefresh = false
                runOnUiThread { renderUiToBitmap() }
            }
            return
        }

        if (handsLocked) return

        // ── Model manipulation ──
        if (selectedModelIndex !in models.indices) return
        val selected = models[selectedModelIndex]

        val leftGripHeld = leftSqueeze > 0.5f && leftHandValid
        val rightGripHeld = rightSqueeze > 0.5f && rightHandValid
        val isGrabbing = leftGripHeld || rightGripHeld

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

        if (isGrabbing && grabHandPos != null) {
            if (!grabbing) {
                // Grab start
                grabbing = true
                grabStartHandPos = grabHandPos.copyOf()
                grabStartModelPos = floatArrayOf(selected.posX, selected.posY, selected.posZ)
                grabStartAimRot = grabAimRot?.copyOf() ?: floatArrayOf(0f, 0f, 0f, 1f)
                grabStartModelRot = floatArrayOf(selected.rotX, selected.rotY, selected.rotZ, selected.rotW)
                pushOffsetZ = 0f
            } else {
                // Hand delta
                val dX = grabHandPos[0] - grabStartHandPos[0]
                val dY = grabHandPos[1] - grabStartHandPos[1]
                val dZ = grabHandPos[2] - grabStartHandPos[2]

                // Push/pull with thumbstick
                if (kotlin.math.abs(grabThumbY) > STICK_DEADZONE) {
                    pushOffsetZ -= grabThumbY * 0.02f
                    pushOffsetZ = pushOffsetZ.coerceIn(-10f, 10f)
                }

                // Scale with thumbstick
                if (kotlin.math.abs(grabThumbX) > STICK_DEADZONE) {
                    selected.scale = (selected.scale + grabThumbX * 0.03f).coerceIn(0.05f, 10f)
                }

                // Position
                selected.posX = grabStartModelPos[0] + dX
                selected.posY = grabStartModelPos[1] + dY
                selected.posZ = grabStartModelPos[2] + dZ + pushOffsetZ

                // Rotation (grip + trigger)
                val triggerHeld = if (rightGripHeld) rightTrigger > 0.5f else leftTrigger > 0.5f
                if (triggerHeld && grabAimRot != null) {
                    // Relative rotation: current * inverse(start)
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

                    // Apply to original model rotation
                    val sr = grabStartModelRot
                    selected.rotX = relW * sr[0] + relX * sr[3] + relY * sr[2] - relZ * sr[1]
                    selected.rotY = relW * sr[1] - relX * sr[2] + relY * sr[3] + relZ * sr[0]
                    selected.rotZ = relW * sr[2] + relX * sr[1] - relY * sr[0] + relZ * sr[3]
                    selected.rotW = relW * sr[3] - relX * sr[0] - relY * sr[1] - relZ * sr[2]
                }

                updateModelTransform(selected)
            }
        } else {
            grabbing = false
        }

        // Free thumbstick = rotate/height
        if (!isGrabbing && !menuVisible) {
            val hAxis = if (kotlin.math.abs(rightThumbX) > kotlin.math.abs(leftThumbX))
                rightThumbX else leftThumbX
            if (kotlin.math.abs(hAxis) > STICK_DEADZONE) {
                // Rotate around Y
                val angle = -hAxis * 0.035f // radians per frame
                val halfAngle = angle / 2f
                val sinH = kotlin.math.sin(halfAngle)
                val cosH = kotlin.math.cos(halfAngle)

                // Y-axis quaternion delta
                val dx = 0f; val dy = sinH; val dz = 0f; val dw = cosH

                // Multiply: delta * current
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
                selected.posY += vAxis * 0.03f
                updateModelTransform(selected)
            }
        }

        // Y = cycle selected model
        if (yButton && !lastYState && models.size > 1) {
            selectedModelIndex = (selectedModelIndex + 1) % models.size
            if (menuVisible) runOnUiThread { renderUiToBitmap() }
        }
        lastYState = yButton

        // A = toggle animation (if asset supports it)
        if (aButton && !lastAState) {
            // Filament gltfio animation: would need Animator from asset
            // TODO: implement animation control
        }
        lastAState = aButton
    }

    /** Render a simple HUD text panel to bitmap (no Android Views needed) */
    private fun renderUiToBitmap() {
        val uiW = 1024
        val uiH = 768
        val bitmap = android.graphics.Bitmap.createBitmap(uiW, uiH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(0xD0101010.toInt())

        val title = android.graphics.Paint().apply { isAntiAlias = true; textSize = 56f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
        val normal = android.graphics.Paint().apply { isAntiAlias = true; textSize = 44f; color = 0xFFDDDDDD.toInt() }
        val highlight = android.graphics.Paint().apply { isAntiAlias = true; textSize = 48f; color = 0xFF00CCFF.toInt(); isFakeBoldText = true }
        val dim = android.graphics.Paint().apply { isAntiAlias = true; textSize = 36f; color = 0xFF777777.toInt() }

        var y = 80f
        canvas.drawText("3D Model Viewer", 50f, y, title)
        y += 90f

        val model = models.getOrNull(selectedModelIndex)
        if (model != null) {
            canvas.drawText(model.file.name, 50f, y, dim)
            y += 80f

            val params = arrayOf(
                "Metallic" to "%.0f%%".format(model.metallic * 100),
                "Roughness" to "%.0f%%".format(model.roughness * 100),
                "Exposure" to "%+.1f EV".format(model.exposure)
            )
            for ((i, param) in params.withIndex()) {
                val p = if (i == selectedParam) highlight else normal
                val arrow = if (i == selectedParam) ">" else " "
                canvas.drawText("$arrow ${param.first}:  ${param.second}", 50f, y, p)
                y += 70f
            }

            y += 40f
            canvas.drawText("[X] Cycle param   [Stick] Adjust", 50f, y, dim)
            y += 50f
            canvas.drawText("[A] Reset param   [B] Close panel", 50f, y, dim)
            y += 50f
            canvas.drawText("[Grip] Move   [Grip+Trigger] Rotate", 50f, y, dim)
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

        // Model list
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

        // Material controls (THE WHOLE POINT)
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
                    glesRenderer?.metallic = it.metallic
                    glesRenderer?.roughness = it.roughness
                }
            })

            layout.addView(makeSlider("Roughness", 0, 100, (model.roughness * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.roughness = value / 100f
                    glesRenderer?.metallic = it.metallic
                    glesRenderer?.roughness = it.roughness
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Lighting"))

            layout.addView(makeSlider("Exposure", -500, 500, (model.exposure * 100).toInt()) { value ->
                models.getOrNull(selectedModelIndex)?.let {
                    it.exposure = value / 100f
                    glesRenderer?.exposure = it.exposure
                }
            })

            // Lock hands button
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

        // Controls help
        layout.addView(makeSpacer(16))
        layout.addView(TextView(this).apply {
            text = "Controls:\n" +
                    "  Grip = Move model\n" +
                    "  Grip + Trigger = Move + rotate\n" +
                    "  Grip + Stick L/R = Scale\n" +
                    "  Grip + Stick Fwd/Back = Push/pull\n" +
                    "  Free stick L/R = Spin model\n" +
                    "  Free stick Up/Down = Lift/lower\n" +
                    "  Y = Cycle models\n" +
                    "  Menu = This panel\n" +
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

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        running = false
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
