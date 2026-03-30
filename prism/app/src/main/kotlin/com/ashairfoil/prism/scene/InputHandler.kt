package com.ashairfoil.prism.scene

import android.os.SystemClock
import android.util.Log
import com.ashairfoil.prism.AudioReactor
import com.ashairfoil.prism.FilamentModelActivity
import com.ashairfoil.prism.GlesModelRenderer

/**
 * Handles all VR controller input: laser pointer, model grab/rotate/scale,
 * gizmo drag, menu panel interaction, and parameter adjustment.
 *
 * Extracted from FilamentModelActivity to reduce that class's size.
 * Accesses shared state on the activity via `internal` fields.
 */
class InputHandler(private val activity: FilamentModelActivity) {

    companion object {
        private const val TAG = "ChloeVR-Input"
    }

    // ── Controller state ──
    val inputBuffer = FloatArray(41)
    val STICK_DEADZONE = 0.15f

    // Edge detection
    private var lastMenuState = false
    private var lastAState = false
    private var lastBState = false
    private var lastYState = false
    private var inputLogCount = 0
    private var lastRightTriggerState = false
    private var lastXState = false
    private var lastRightStickClick = false
    private var lastLeftStickClick = false

    // Grab state
    private var grabbing = false
    private var grabDistance = 2f
    private var grabOffset = floatArrayOf(0f, 0f, 0f)
    private var grabStartAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var grabStartModelRot = floatArrayOf(0f, 0f, 0f, 1f)

    // Laser / selection (preallocated, written in-place)
    val laserHandPos = floatArrayOf(0f, 0f, 0f)
    val laserAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    // Scratch buffers for per-frame math (avoid alloc in hot path)
    private val scratchPanelRot = FloatArray(4)
    private val scratchAxisX = floatArrayOf(1f, 0f, 0f)
    private val scratchAxisY = floatArrayOf(0f, 1f, 0f)
    private val scratchAxisZ = floatArrayOf(0f, 0f, 1f)
    private val scratchRight = FloatArray(3)
    private val scratchUp = FloatArray(3)
    private val scratchNormal = FloatArray(3)
    var laserActive = false
    var hoveredModelIndex = -1
    var hitDistance = -1f

    // Emitter grab
    var emitterHovered = false
    var emitterGrabbed = false
    private var emitterGrabDist = 2f

    // Gizmo state
    var hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
    private var gizmoDragging = false
    private var gizmoDragAxis = GlesModelRenderer.GIZMO_AXIS_NONE
    private val gizmoDragStartHandPos = floatArrayOf(0f, 0f, 0f)
    private val gizmoDragStartModelPos = floatArrayOf(0f, 0f, 0f)
    private val scratchGizmoPos = FloatArray(3)
    private val scratchGizmoRot = FloatArray(4)

    // Pre-allocated scratch buffers for grab path (avoids per-frame floatArrayOf)
    private val scratchGrabHandPos = FloatArray(3)
    private val scratchGrabAimRot = FloatArray(4)
    private val scratchGrabFwd = FloatArray(3)
    private val scratchWorldOff = FloatArray(3)
    private val scratchInvAim = FloatArray(4)
    // Pre-allocated corner buffers for beat drag (avoids per-trigger-frame alloc)
    private val beatCornerBuf = Array(8) { FloatArray(2) }
    private var beatCornerCount = 0

    // Panel drag
    var draggingPanel = false
    private var panelGrabDist = 1.0f
    private var blockGripInteractionsUntilRelease = false

    // Menu hover
    var hoveredMenuParam = -1
    private var lastHoveredMenuParam = -1
    var hoveredActionButton = -1
    private var lastHoveredActionButton = -1

    // GLB picker hover
    var hoveredGlbIndex = -1
    private var lastHoveredGlbIndex = -1

    // Scene picker hover
    var hoveredSceneIndex = -1
    private var lastHoveredSceneIndex = -1

    // Audio hover
    var hoveredAudioButton = -1
    var hoveredAudioFileIndex = -1
    private var lastHoveredAudioButton = -1
    private var lastHoveredAudioFileIndex = -1
    private var audioSeekDragging = false
    private var lastAudioSeekTargetMs = Long.MIN_VALUE
    private var lastCursorRefreshMs = 0L

    // Beat settings drag
    var beatDraggingSlider = -1
    var beatSliderLaserX = 0f
    private var beatDragCorner = -1
    private var beatLockedSlider = -1

    // Save name hover
    var hoveredSaveButton = -1
    private var lastHoveredSaveButton = -1

    // Lighting preset hover
    var hoveredLightingPresetIndex = -1
    private var lastHoveredLightingPresetIndex = -1

    // Virtual keyboard hover
    var hoveredKeyboardKey = -1
    private var lastHoveredKeyboardKey = -1

    // Slider dragging
    private var sliderDragging = -1
    private var lastLaserBx = 0f

    // ── BeatSlider definitions ──

    data class BeatSlider(
        val name: String, val unit: String, val min: Float, val max: Float,
        val get: () -> Float, val set: (Float) -> Unit,
        val logScale: Boolean = false
    )

    val beatSliders by lazy {
        arrayOf(
            BeatSlider("GAIN", "x", 0.5f, 10f,
                { activity.audioReactor?.sensitivity ?: 3f },
                { activity.audioReactor?.sensitivity = it },
                logScale = true),
            BeatSlider("BOX LEFT", "Hz", 20f, 2000f,
                { val r = activity.audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxLeft.toDouble()).toFloat() else 20f },
                { activity.audioReactor?.boxLeft = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) },
                logScale = true),
            BeatSlider("BOX RIGHT", "Hz", 100f, 20000f,
                { val r = activity.audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxRight.toDouble()).toFloat() else 300f },
                { activity.audioReactor?.boxRight = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) },
                logScale = true),
            BeatSlider("BOX BOTTOM", "%", 0f, 80f,
                { (activity.audioReactor?.boxBottom ?: 5f) * 100f },
                { activity.audioReactor?.boxBottom = it / 100f }),
            BeatSlider("BOX TOP", "%", 20f, 100f,
                { (activity.audioReactor?.boxTop ?: 85f) * 100f },
                { activity.audioReactor?.boxTop = it / 100f }),
            BeatSlider("ATTACK", "ms", 1f, 200f,
                { activity.audioReactor?.attackMs ?: 20f },
                { activity.audioReactor?.attackMs = it }),
            BeatSlider("RELEASE", "ms", 10f, 2000f,
                { activity.audioReactor?.releaseMs ?: 150f },
                { activity.audioReactor?.releaseMs = it },
                logScale = true),
            BeatSlider("EXPAND", "x", 0.3f, 4f,
                { activity.audioReactor?.dynRange ?: 2f },
                { activity.audioReactor?.dynRange = it }),
            BeatSlider("SMOOTH", "%", 0f, 100f,
                { (activity.audioReactor?.smootherAmount ?: 0.3f) * 100f },
                { activity.audioReactor?.smootherAmount = it / 100f }),
            BeatSlider("ZOOM-H", "x", 1f, 8f,
                { activity.audioReactor?.specZoom ?: 1f },
                { activity.audioReactor?.specZoom = it }),
            BeatSlider("ZOOM-V", "x", 0.5f, 20f,
                { activity.audioReactor?.specVZoom ?: 1f },
                { activity.audioReactor?.specVZoom = it }),
            BeatSlider("COLOR", "\u00B0", 0f, 360f,
                { activity.audioReactor?.beatHue ?: 330f },
                { activity.audioReactor?.beatHue = it }),
            BeatSlider("MIX", "%", 0f, 100f,
                { activity.beatIntensity * 50f },
                { activity.beatIntensity = it / 50f }),
        )
    }

    // ── Ray-Object Intersection ──

    fun raySphereIntersect(
        rayOrigin: FloatArray, rayDir: FloatArray,
        sphereCenter: FloatArray, sphereRadius: Float
    ): Float {
        val ocX = rayOrigin[0] - sphereCenter[0]
        val ocY = rayOrigin[1] - sphereCenter[1]
        val ocZ = rayOrigin[2] - sphereCenter[2]

        // c < 0 means ray origin is inside the sphere -- skip so user can deselect
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

    /** Set an absolute value for slider param 0-12 */
    fun setParamValue(idx: Int, value: Float) {
        val renderer = activity.glesRenderer ?: return
        val model = activity.sceneManager.models.getOrNull(activity.sceneManager.selectedModelIndex)
        val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
        when (idx) {
            0 -> if (model != null) { model.metallic = value; gpuModel?.metallic = value }
            1 -> if (model != null) { model.roughness = value; gpuModel?.roughness = value }
            2 -> if (model != null) { model.exposure = value; gpuModel?.exposure = value }
            3 -> if (model != null) { model.contrast = value; gpuModel?.contrast = value }
            4 -> if (model != null) { model.saturation = value; gpuModel?.saturation = value }
            5 -> renderer.lightIntensity = value
            6 -> renderer.fillLightIntensity = value
            7 -> { activity.autoAmbient = false; renderer.ambientIntensity = value }
            8 -> { renderer.lightAngleDeg = value; renderer.updateLightDirFromAngles() }
            9 -> { renderer.lightElevDeg = value; renderer.updateLightDirFromAngles() }
            10 -> renderer.shadowDarkness = value
            11 -> renderer.shadowSoftness = value
            12 -> renderer.shadowSpread = value
        }
    }

    private fun applyBeatSlider(idx: Int, normalizedX: Float) {
        val slider = beatSliders.getOrNull(idx) ?: return
        val value = if (slider.logScale && slider.min > 0f) {
            // Log interpolation: left side of slider gets more resolution for low values
            val logMin = kotlin.math.ln(slider.min)
            val logMax = kotlin.math.ln(slider.max)
            kotlin.math.exp(logMin + normalizedX * (logMax - logMin))
        } else {
            slider.min + normalizedX * (slider.max - slider.min)
        }
        slider.set(value)
    }

    /** Convert a slider's current value to normalized 0..1 position, respecting logScale */
    fun sliderNorm(slider: BeatSlider): Float {
        val value = slider.get()
        return if (slider.logScale && slider.min > 0f) {
            val logMin = kotlin.math.ln(slider.min)
            val logMax = kotlin.math.ln(slider.max)
            ((kotlin.math.ln(value.coerceIn(slider.min, slider.max)) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
        } else {
            ((value - slider.min) / (slider.max - slider.min)).coerceIn(0f, 1f)
        }
    }

    // ── Convenience accessors ──
    private inline val models get() = activity.sceneManager.models
    private inline var selectedModelIndex
        get() = activity.sceneManager.selectedModelIndex
        set(value) { activity.sceneManager.selectedModelIndex = value }
    private inline val yeetingModels get() = activity.sceneManager.yeetingModels

    // ══════════════════════════════════════════════════════════
    //  handle()  —  the main input processing loop
    // ══════════════════════════════════════════════════════════

    fun handle() {
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
        if (blockGripInteractionsUntilRelease && leftSqueeze < 0.3f && rightSqueeze < 0.3f) {
            blockGripInteractionsUntilRelease = false
        }
        if (inputLogCount < 5 && (menuButton || bButton || aButton)) {
            Log.i(TAG, "Input: menu=$menuButton b=$bButton a=$aButton squeeze=${inputBuffer[6]},${inputBuffer[7]}")
            inputLogCount++
        }

        // Menu button toggles sensor debug HUD
        if (menuButton && !lastMenuState) {
            activity.sensorHudVisible = !activity.sensorHudVisible
            Log.i(TAG, "Sensor HUD: ${if (activity.sensorHudVisible) "ON" else "OFF"}")
            if (activity.sensorHudVisible) {
                activity.sensorDebugStr = activity.sensorPoller.buildDebugString(activity.sensorHub, activity.xrLightEstimateAvailable, activity.xrLightDebugStr)
                activity.uiNeedsRefresh = true
            }
            activity.uiNeedsRefresh = true
        }
        lastMenuState = menuButton

        // ── Laser pointer + ray intersection + gizmo hover ──
        val renderer = activity.glesRenderer
        if (rightHandValid && rightAimValid && renderer != null) {
            laserActive = true
            laserHandPos[0] = rightHandPosX; laserHandPos[1] = rightHandPosY; laserHandPos[2] = rightHandPosZ
            laserAimRot[0] = rightAimRotX; laserAimRot[1] = rightAimRotY; laserAimRot[2] = rightAimRotZ; laserAimRot[3] = rightAimRotW

            val rayDir = renderer.quatForward(laserAimRot)

            // ── Menu panel hit test ──
            hoveredMenuParam = -1
            hoveredActionButton = -1
            var laserOnPanel = false
            var panelRayT = -1f
            var panelRayNearUi = false
            activity.beatCursorX = -1f
            activity.beatCursorY = -1f
            if (activity.menuVisible) {
                val pcx = renderer.panelX
                val pcy = renderer.panelY
                val pcz = renderer.panelZ
                val panelHW = (renderer.panelW * 0.5f).coerceAtLeast(0.001f)
                val panelHH = (renderer.panelH * 0.5f).coerceAtLeast(0.001f)
                scratchPanelRot[0] = activity.panelRotX; scratchPanelRot[1] = activity.panelRotY
                scratchPanelRot[2] = activity.panelRotZ; scratchPanelRot[3] = activity.panelRotW
                // Match compositor panel orientation exactly (XR quad uses this pose).
                renderer.rotateVecByQuat(scratchAxisX, scratchPanelRot, scratchRight)
                renderer.rotateVecByQuat(scratchAxisY, scratchPanelRot, scratchUp)
                renderer.rotateVecByQuat(scratchAxisZ, scratchPanelRot, scratchNormal)
                val rx = scratchRight[0]; val ry = scratchRight[1]; val rz = scratchRight[2]
                val ux = scratchUp[0]; val uy = scratchUp[1]; val uz = scratchUp[2]
                val nx = scratchNormal[0]; val ny = scratchNormal[1]; val nz = scratchNormal[2]

                val denom = rayDir[0]*nx + rayDir[1]*ny + rayDir[2]*nz
                if (kotlin.math.abs(denom) > 0.01f) {
                    val t = ((pcx - laserHandPos[0])*nx + (pcy - laserHandPos[1])*ny + (pcz - laserHandPos[2])*nz) / denom
                    if (t > 0f && t < 8f) {
                        panelRayT = t
                        val wx = laserHandPos[0] + rayDir[0] * t
                        val wy = laserHandPos[1] + rayDir[1] * t
                        val wz = laserHandPos[2] + rayDir[2] * t

                        // Project onto panel's local right/up axes
                        val dx = wx - pcx; val dy = wy - pcy; val dz = wz - pcz
                        val hx = dx*rx + dy*ry + dz*rz
                        val hy = dx*ux + dy*uy + dz*uz
                        panelRayNearUi = hx in (-panelHW * 1.5f)..(panelHW * 1.5f) &&
                            hy in (-panelHH * 1.5f)..(panelHH * 1.5f)

                        if (hx in -panelHW..panelHW && hy in -panelHH..panelHH) {
                            laserOnPanel = true
                            hitDistance = t
                            val u = (hx + panelHW) / (panelHW * 2f)
                            val v = 1f - (hy + panelHH) / (panelHH * 2f)
                            val bx = u * 1024f
                            val by = v * 1280f
                            activity.beatCursorX = bx
                            activity.beatCursorY = by

                            // Title bar drag zone: top ~80px
                            if (by < 85f) {
                                hoveredActionButton = 200 // title bar hover
                                val rg = inputBuffer[7]
                                if (rg > 0.7f && !draggingPanel) {
                                    draggingPanel = true
                                    panelGrabDist = t
                                }
                            }

                            if (activity.audioPlayerMode) {
                                hoveredAudioButton = -1
                                hoveredAudioFileIndex = -1
                                lastLaserBx = bx

                                if (activity.audioPickerMode) {
                                    // File list hover
                                    val maxVis = 10; val rowH = 72f; val startY = 140f
                                    if (by in startY..(startY + maxVis * rowH)) {
                                        val vi = ((by - startY) / rowH).toInt()
                                        val idx = activity.audioPickerScrollOffset + vi
                                        if (idx < activity.availableAudioFiles.size) hoveredAudioFileIndex = idx
                                    }
                                    if (by > (1280 - 80f)) hoveredAudioButton = 50 // BACK
                                } else {
                                    // Transport buttons: y=245..300 (matches UiRenderer txY=245, txBtnH=55)
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
                                    // BROWSE / BACK: bottom (UiRenderer bbY = uiH - 80 = 1200)
                                    if (by > 1200f) {
                                        val halfW = (1024 - 70f) / 2f
                                        if (bx < 30f + halfW) hoveredAudioButton = 51
                                        else hoveredAudioButton = 52
                                    }
                                }
                                // Refresh every frame for smooth cursor tracking
                                activity.uiNeedsRefresh = true
                            } else if (activity.beatSettingsMode) {
                                val prevDragging = beatDraggingSlider
                                beatDraggingSlider = -1
                                beatSliderLaserX = 0f
                                hoveredActionButton = -1

                                // Layout: spectrum 140..390, sliders 418..786, buttons 920+
                                val specTopHit = 155f; val specBotHit = 435f
                                val specLeftHit = 40f; val specRightHit = 984f
                                val sliderAreaTopHit = 465f
                                val sliderRowH = 32f

                                if (by > 1205f) {
                                    // Match UiRenderer: fifthW = (1024-130)/5, buttons at 30,40+fw,50+fw*2,...
                                    val fifth = (1024f - 130f) / 5f
                                    if (bx < 30f + fifth + 5f) hoveredActionButton = 127 // BOOM
                                    else if (bx < 40f + fifth * 2f + 5f) hoveredActionButton = 128 // VIBES
                                    else if (bx < 50f + fifth * 3f + 5f) hoveredActionButton = 129 // SPLIT (dual motor toggle)
                                    else if (bx < 60f + fifth * 4f + 5f) hoveredActionButton = 112 // OFF
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
                                    // Laser is on the spectrum area -- corner/box dragging
                                    val reactor = activity.audioReactor
                                    if (reactor != null) {
                                        // Map screen position to spectrum coordinates (through zoom)
                                        val screenNormX = ((bx - specLeftHit) / (specRightHit - specLeftHit)).coerceIn(0f, 1f)
                                        val hz = reactor.specZoom.coerceAtLeast(1f)
                                        val vw = 1f / hz
                                        val vc = reactor.specViewCenter.coerceIn(vw / 2f, 1f - vw / 2f)
                                        val vL = vc - vw / 2f
                                        val normX = vL + screenNormX * vw
                                        val screenY = (1f - (by - specTopHit) / (specBotHit - specTopHit)).coerceIn(0f, 1f)
                                        val vzex = (reactor.specVZoom * reactor.dynRange).coerceAtLeast(0.01f)
                                        val normY = (screenY / vzex).coerceIn(0f, 1f)

                                        // Display-space box positions (for hit testing)
                                        val dispBoxTop = (reactor.boxTop * vzex).coerceAtMost(1f)
                                        val dispBoxBot = (reactor.boxBottom * vzex).coerceAtMost(1f)
                                        val hasSplit = activity.hapticDualMotorSplit
                                        val disp2Top = if (hasSplit) (reactor.box2Top * vzex).coerceAtMost(1f) else 0f
                                        val disp2Bot = if (hasSplit) (reactor.box2Bottom * vzex).coerceAtMost(1f) else 0f

                                        if (rightTrigger > 0.5f) {
                                            if (beatDragCorner < 0) {
                                                // Box A corners: 0-3, body: 4
                                                // Box B corners: 5-8, body: 9
                                                // Use pre-allocated corner buffers
                                                beatCornerBuf[0][0] = reactor.boxLeft;  beatCornerBuf[0][1] = dispBoxTop   // 0
                                                beatCornerBuf[1][0] = reactor.boxRight; beatCornerBuf[1][1] = dispBoxTop   // 1
                                                beatCornerBuf[2][0] = reactor.boxLeft;  beatCornerBuf[2][1] = dispBoxBot   // 2
                                                beatCornerBuf[3][0] = reactor.boxRight; beatCornerBuf[3][1] = dispBoxBot   // 3
                                                beatCornerCount = 4
                                                if (hasSplit) {
                                                    beatCornerBuf[4][0] = reactor.box2Left;  beatCornerBuf[4][1] = disp2Top  // 5
                                                    beatCornerBuf[5][0] = reactor.box2Right; beatCornerBuf[5][1] = disp2Top  // 6
                                                    beatCornerBuf[6][0] = reactor.box2Left;  beatCornerBuf[6][1] = disp2Bot  // 7
                                                    beatCornerBuf[7][0] = reactor.box2Right; beatCornerBuf[7][1] = disp2Bot  // 8
                                                    beatCornerCount = 8
                                                }
                                                var minDist = Float.MAX_VALUE
                                                var nearestCorner = -1
                                                for (ci in 0 until beatCornerCount) {
                                                    val d = (normX - beatCornerBuf[ci][0]) * (normX - beatCornerBuf[ci][0]) +
                                                            (screenY - beatCornerBuf[ci][1]) * (screenY - beatCornerBuf[ci][1])
                                                    if (d < minDist) { minDist = d; nearestCorner = ci }
                                                }
                                                beatDragCorner = if (minDist < 0.015f) {
                                                    if (nearestCorner < 4) nearestCorner  // Box A corner
                                                    else nearestCorner + 1  // Box B corners: 5,6,7,8 -> stored as 5,6,7,8
                                                } else if (hasSplit && normX in reactor.box2Left..reactor.box2Right && screenY in disp2Bot..disp2Top) 9
                                                else if (normX in reactor.boxLeft..reactor.boxRight && screenY in dispBoxBot..dispBoxTop) 4
                                                else -1
                                            }
                                            // Apply drag (normY is in raw box space)
                                            when (beatDragCorner) {
                                                // Box A
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
                                                // Box B
                                                5 -> { reactor.box2Left = normX.coerceIn(0f, reactor.box2Right - 0.02f); reactor.box2Top = normY.coerceIn(reactor.box2Bottom + 0.01f, 1f) }
                                                6 -> { reactor.box2Right = normX.coerceIn(reactor.box2Left + 0.02f, 1f); reactor.box2Top = normY.coerceIn(reactor.box2Bottom + 0.01f, 1f) }
                                                7 -> { reactor.box2Left = normX.coerceIn(0f, reactor.box2Right - 0.02f); reactor.box2Bottom = normY.coerceIn(0f, reactor.box2Top - 0.01f) }
                                                8 -> { reactor.box2Right = normX.coerceIn(reactor.box2Left + 0.02f, 1f); reactor.box2Bottom = normY.coerceIn(0f, reactor.box2Top - 0.01f) }
                                                9 -> {
                                                    val bw = reactor.box2Right - reactor.box2Left
                                                    val bh = reactor.box2Top - reactor.box2Bottom
                                                    val newL = (normX - bw / 2f).coerceIn(0f, 1f - bw)
                                                    val newB = (normY - bh / 2f).coerceIn(0f, 1f - bh)
                                                    reactor.box2Left = newL; reactor.box2Right = newL + bw
                                                    reactor.box2Bottom = newB; reactor.box2Top = newB + bh
                                                }
                                            }
                                            activity.uiNeedsRefresh = true
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
                                        if (beatLockedSlider < 0) beatLockedSlider = sliderIdx
                                        applyBeatSlider(beatLockedSlider, beatSliderLaserX)
                                        beatDraggingSlider = beatLockedSlider
                                        activity.uiNeedsRefresh = true
                                    } else {
                                        beatLockedSlider = -1
                                    }
                                }
                            } else if (activity.lightingPresetMode) {
                                hoveredLightingPresetIndex = -1
                                hoveredActionButton = -1
                                val presetCount = com.ashairfoil.prism.settings.LightingPresets.getAllPresets().size
                                // Preset rows: startY=140, rowH=65, max 12 visible
                                if (by in 140f..920f) {
                                    val maxVisible = minOf(12, presetCount)
                                    if (maxVisible > 0) {
                                        val idx = ((by - 140f) / 65f).toInt()
                                        if (idx < presetCount) hoveredLightingPresetIndex = idx
                                    }
                                }
                                if (by in 1130f..1180f) hoveredActionButton = 132 // SAVE CURRENT
                                if (by > 1200f) {
                                    if (bx < 520f) hoveredActionButton = 133 // SET DEFAULT
                                    else hoveredActionButton = 131 // BACK
                                }
                            } else if (activity.saveNameMode) {
                                hoveredSaveButton = -1
                                hoveredSceneIndex = -1
                                hoveredKeyboardKey = -1
                                // Keyboard: y=260 to ~544 (5 rows x 52+6)
                                if (by in 260f..550f) {
                                    hoveredKeyboardKey = activity.uiRenderer.keyboard.hitTest(bx, by)
                                }
                                // SAVE button at saveBtnY=555, height 50
                                if (by in 555f..605f) hoveredSaveButton = 0
                                // Scene overwrite list at startY=650, rowH=50
                                if (by in 650f..1150f) {
                                    val maxVisible = 10
                                    val idx = ((by - 650f) / 50f).toInt()
                                    if (idx < activity.savedSceneFiles.size) hoveredSceneIndex = idx
                                }
                                if (by > 1200f) hoveredSaveButton = 1 // BACK at uiH-80=1200
                            } else if (activity.glbPickerMode) {
                                if (by > 1200f) {
                                    hoveredActionButton = 103 // BACK at uiH-80=1200
                                }
                                // UiRenderer: GLB rows at startY=140, rowH=76, 13 visible
                                if (by in 140f..1128f) {
                                    val maxVisible = 13
                                    val frac = (by - 140f) / (1128f - 140f)
                                    val idx = activity.glbPickerScrollOffset + (frac * maxVisible).toInt()
                                    if (idx < activity.availableGlbFiles.size) {
                                        hoveredGlbIndex = idx
                                    }
                                }
                            } else if (activity.scenePickerMode) {
                                if (by > 1200f) {
                                    hoveredActionButton = 106 // BACK at uiH-80=1200
                                }
                                // UiRenderer: scene rows at startY=130, rowH=60, 13 visible
                                if (by in 130f..910f) {
                                    val maxVisible = 13
                                    val frac = (by - 130f) / (910f - 130f)
                                    val idx = (frac * maxVisible).toInt()
                                    if (idx < activity.savedSceneFiles.size) {
                                        hoveredSceneIndex = idx
                                    }
                                }
                            } else {
                                // Action buttons: 4 rows at bottom (UiRenderer: row1Y=1054, btnH=48, btnGap=8)
                                // btn2W=478, btn3W=316; gap midpoints: row1=512, rows2-4=350/674
                                if (by in 1054f..1102f) {
                                    if (bx < 512f) hoveredActionButton = 104
                                    else hoveredActionButton = 105
                                }
                                if (by in 1110f..1158f) {
                                    if (bx < 350f) hoveredActionButton = 101
                                    else if (bx < 674f) hoveredActionButton = 107
                                    else hoveredActionButton = 108
                                }
                                if (by in 1166f..1214f) {
                                    if (bx < 350f) hoveredActionButton = 109
                                    else if (bx < 674f) hoveredActionButton = 100
                                    else hoveredActionButton = 102
                                }
                                if (by > 1222f) {
                                    if (bx < 350f) hoveredActionButton = 130
                                    else if (bx < 674f) hoveredActionButton = 134
                                    else hoveredActionButton = 135
                                }

                                // Param rows with section headers (rowH=46px, 10px section pad)
                                val paramRowHit = 46f
                                val sectionPadHit = 10f
                                if (by in 164f..1100f) {
                                    // Lock hover to dragged slider while trigger is held
                                    if (sliderDragging >= 0 && rightTrigger > 0.5f) {
                                        hoveredMenuParam = sliderDragging
                                    } else {
                                        val adjustedBy = by - 164f
                                        var acc = 0f; var idx = -1
                                        for (p in 0 until activity.PARAM_NAMES.size) {
                                            if (p == 0 || p == 5 || p == 13) acc += sectionPadHit
                                            if (adjustedBy >= acc && adjustedBy < acc + paramRowHit) { idx = p; break }
                                            acc += paramRowHit
                                        }
                                        if (idx >= 0) hoveredMenuParam = idx
                                    }
                                    lastLaserBx = bx
                                }
                            }
                        }
                    }
                }
                if (!laserOnPanel && panelRayT > 0f && panelRayNearUi) {
                    // Prevent visual/menu click-through when ray is on (or very near) the menu plane.
                    hitDistance = panelRayT
                }

                // Drag update -- full 3D: place panel along laser ray at grab distance
                if (draggingPanel) {
                    val rg = inputBuffer[7]
                    if (rg > 0.5f) {
                        val stickY = inputBuffer[3]
                        if (kotlin.math.abs(stickY) > STICK_DEADZONE) {
                            panelGrabDist += stickY * 0.03f
                            panelGrabDist = panelGrabDist.coerceIn(0.3f, 5f)
                        }
                        val leftStickY = inputBuffer[2]
                        if (kotlin.math.abs(leftStickY) > STICK_DEADZONE) {
                            activity.panelScale += leftStickY * 0.02f
                            activity.panelScale = activity.panelScale.coerceIn(0.5f, 2.0f)
                            renderer.panelW = activity.PANEL_WIDTH * activity.panelScale
                            renderer.panelH = activity.PANEL_HEIGHT * activity.panelScale
                        }
                        renderer.panelX = laserHandPos[0] + rayDir[0] * panelGrabDist
                        renderer.panelY = laserHandPos[1] + rayDir[1] * panelGrabDist
                        renderer.panelZ = laserHandPos[2] + rayDir[2] * panelGrabDist
                    } else {
                        draggingPanel = false
                    }
                }

                // Always refresh when laser is on panel — cursor must track smoothly
                if (laserOnPanel) {
                    activity.uiNeedsRefresh = true
                }
                // Track hover state changes for interaction logic
                lastHoveredMenuParam = hoveredMenuParam
                lastHoveredActionButton = hoveredActionButton
                lastHoveredGlbIndex = hoveredGlbIndex
                lastHoveredSceneIndex = hoveredSceneIndex
                lastHoveredSaveButton = hoveredSaveButton
                lastHoveredAudioButton = hoveredAudioButton
                lastHoveredAudioFileIndex = hoveredAudioFileIndex
                lastHoveredLightingPresetIndex = hoveredLightingPresetIndex
                lastHoveredKeyboardKey = hoveredKeyboardKey

                // When menu is up and laser is on the panel, block model interaction
                // But allow model hover when laser points away from the panel
                if (laserOnPanel) {
                    hoveredModelIndex = -1
                    hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
                }
            }

            // Test gizmo + model only when menu is NOT up
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            val selModel = models.getOrNull(selectedModelIndex)
            if (!activity.menuVisible && activity.gizmoVisible && selModel != null && !gizmoDragging) {
                scratchGizmoPos[0] = selModel.posX; scratchGizmoPos[1] = selModel.posY; scratchGizmoPos[2] = selModel.posZ
                scratchGizmoRot[0] = selModel.rotX; scratchGizmoRot[1] = selModel.rotY; scratchGizmoRot[2] = selModel.rotZ; scratchGizmoRot[3] = selModel.rotW
                val (axis, _) = renderer.testGizmoHit(laserHandPos, rayDir, scratchGizmoPos, scratchGizmoRot)
                hoveredGizmoAxis = axis
            }

            // Test emitter hit
            emitterHovered = false
            if (!activity.menuVisible && !emitterGrabbed) {
                val eDist = renderer.testEmitterHit(laserHandPos, rayDir)
                if (eDist > 0f) emitterHovered = true
            }

            // Emitter grab: grip on hovered emitter -> drag along ray, delta-based
            if (emitterHovered && rightSqueeze > 0.5f && !emitterGrabbed && !blockGripInteractionsUntilRelease) {
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
                    val rightThumbY = inputBuffer[3]
                    if (kotlin.math.abs(rightThumbY) > 0.15f) {
                        emitterGrabDist += rightThumbY * 0.02f
                        emitterGrabDist = emitterGrabDist.coerceIn(0.3f, 5f)
                    }
                    renderer.emitterPos[0] = laserHandPos[0] + rayDir[0] * emitterGrabDist
                    renderer.emitterPos[1] = laserHandPos[1] + rayDir[1] * emitterGrabDist
                    renderer.emitterPos[2] = laserHandPos[2] + rayDir[2] * emitterGrabDist
                    var scX = 0f; var scY = 0f; var scZ = 0f
                    if (models.isNotEmpty()) {
                        for (m in models) { scX += m.posX; scY += m.posY; scZ += m.posZ }
                        scX /= models.size; scY /= models.size; scZ /= models.size
                    }
                    renderer.updateLightFromEmitter(scX, scY, scZ)
                }
            }

            // Test model intersection (only if not hovering gizmo/emitter and menu is closed)
            if (!activity.menuVisible && hoveredGizmoAxis == GlesModelRenderer.GIZMO_AXIS_NONE && !emitterHovered && !emitterGrabbed) {
                var nearestDist = Float.MAX_VALUE
                var nearestIdx = -1
                for ((i, placed) in models.withIndex()) {
                    val gpuModel = renderer.getModel(placed.gpuModelId) ?: continue
                    val m = gpuModel.modelMatrix
                    val worldCx = m[0]*gpuModel.boundsCenterX + m[4]*gpuModel.boundsCenterY + m[8]*gpuModel.boundsCenterZ + m[12]
                    val worldCy = m[1]*gpuModel.boundsCenterX + m[5]*gpuModel.boundsCenterY + m[9]*gpuModel.boundsCenterZ + m[13]
                    val worldCz = m[2]*gpuModel.boundsCenterX + m[6]*gpuModel.boundsCenterY + m[10]*gpuModel.boundsCenterZ + m[14]
                    scratchGizmoPos[0] = worldCx; scratchGizmoPos[1] = worldCy; scratchGizmoPos[2] = worldCz
                    val sx = kotlin.math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
                    val worldR = gpuModel.boundsRadius * sx
                    var dist = raySphereIntersect(laserHandPos, rayDir, scratchGizmoPos, worldR)
                    if (dist < 0f) {
                        val coreR = (worldR * 0.15f).coerceIn(0.05f, 0.15f)
                        dist = raySphereIntersect(laserHandPos, rayDir, scratchGizmoPos, coreR)
                    }
                    if (dist in 0.01f..nearestDist) { nearestDist = dist; nearestIdx = i }
                }
                hoveredModelIndex = nearestIdx
                hitDistance = if (nearestIdx >= 0) nearestDist else -1f
            } else {
                hoveredModelIndex = -1
                // Don't reset hitDistance if laser hit the menu panel
                if (!laserOnPanel) hitDistance = -1f
            }

            // Highlight
            for ((i, placed) in models.withIndex()) {
                renderer.getModel(placed.gpuModelId)?.selected = (i == selectedModelIndex) || (i == hoveredModelIndex)
            }

            // ── Gizmo drag with grip ──
            val rightGripForGizmo = rightSqueeze > 0.5f
            if (gizmoDragging) {
                if (rightGripForGizmo && selModel != null) {
                    scratchGizmoRot[0] = selModel.rotX; scratchGizmoRot[1] = selModel.rotY; scratchGizmoRot[2] = selModel.rotZ; scratchGizmoRot[3] = selModel.rotW
                    val worldAxis = renderer.getGizmoWorldAxis(gizmoDragAxis, scratchGizmoRot)
                    val dx = rightHandPosX - gizmoDragStartHandPos[0]
                    val dy = rightHandPosY - gizmoDragStartHandPos[1]
                    val dz = rightHandPosZ - gizmoDragStartHandPos[2]
                    val proj = dx*worldAxis[0] + dy*worldAxis[1] + dz*worldAxis[2]
                    val scaled = proj * 3f
                    selModel.posX = gizmoDragStartModelPos[0] + worldAxis[0] * scaled
                    selModel.posY = gizmoDragStartModelPos[1] + worldAxis[1] * scaled
                    selModel.posZ = gizmoDragStartModelPos[2] + worldAxis[2] * scaled
                    activity.updateModelTransform(selModel)
                } else {
                    gizmoDragging = false
                }
            } else if (rightGripForGizmo && !blockGripInteractionsUntilRelease && hoveredGizmoAxis >= 0 && selModel != null) {
                // Start gizmo drag
                gizmoDragging = true
                gizmoDragAxis = hoveredGizmoAxis
                gizmoDragStartHandPos[0] = rightHandPosX; gizmoDragStartHandPos[1] = rightHandPosY; gizmoDragStartHandPos[2] = rightHandPosZ
                gizmoDragStartModelPos[0] = selModel.posX; gizmoDragStartModelPos[1] = selModel.posY; gizmoDragStartModelPos[2] = selModel.posZ
            }
        } else {
            laserActive = false
            hoveredModelIndex = -1
            hitDistance = -1f
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            gizmoDragging = false
            activity.beatCursorX = -1f
            activity.beatCursorY = -1f
        }

        // Trigger press (edge-detected) = select/deselect or menu select
        val rightTriggerPressed = rightTrigger > 0.5f
        if (rightTriggerPressed && !lastRightTriggerState && !grabbing) {
            // ── Audio Player trigger handling ──
            if (activity.menuVisible && activity.audioPlayerMode && activity.audioPickerMode) {
                when {
                    hoveredAudioFileIndex >= 0 && hoveredAudioFileIndex < activity.availableAudioFiles.size -> {
                        val fileIndex = hoveredAudioFileIndex
                        val files = activity.availableAudioFiles
                        // ExoPlayer must be created and accessed on the main thread
                        activity.runOnUiThread {
                            if (activity.audioPlayer == null) {
                                activity.audioPlayer = com.ashairfoil.prism.AudioPlayer(activity)
                                // Wire auto-restart AudioReactor on track change (playlist advance)
                                activity.audioPlayer?.onTrackChanged = { _ ->
                                    val reactor = activity.audioReactor
                                    val sid = activity.audioPlayer?.audioSessionId ?: 0
                                    if (reactor != null && sid != 0) reactor.restart(sid)
                                    activity.uiNeedsRefresh = true
                                }
                                // Surface audio errors to user
                                activity.audioPlayer?.onError = { msg ->
                                    activity.runOnUiThread { activity.sceneManager.showMessage(msg) }
                                }
                            }
                            activity.audioPlayer?.playFromPlaylist(files, fileIndex)
                            val reactor = activity.audioReactor
                            val sid = activity.audioPlayer?.audioSessionId ?: 0
                            if (reactor != null && sid != 0) {
                                reactor.restart(sid)
                                if (!activity.beatReactorEnabled) {
                                    activity.beatReactorEnabled = true
                                    reactor.enabled = true
                                }
                            }
                        }
                        activity.audioPickerMode = false
                        Log.i(TAG, "Audio: playing ${files[fileIndex].name} [${fileIndex + 1}/${files.size}]")
                    }
                    hoveredAudioButton == 50 -> activity.audioPickerMode = false
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.audioPlayerMode && !activity.audioPickerMode) {
                val btn = hoveredAudioButton
                val laserBx = lastLaserBx
                val applyAudioAction = btn in 0..3 || btn in 10..11 || btn in 20..22 || btn == 30 || btn in 40..43 || btn == 60
                if (applyAudioAction) {
                    // Stop loop handler + clear loop immediately to prevent freeze
                    if (btn in 0..3) { activity.stopLoopHandler(); activity.audioPlayer?.clearLoop() }
                    // ExoPlayer must be accessed on the main thread
                    activity.runOnUiThread {
                        val ap = activity.audioPlayer
                        when (btn) {
                            0 -> ap?.playPrevious()
                            1 -> ap?.togglePlayPause()
                            2 -> ap?.playNext()
                            3 -> { ap?.stop(); activity.audioReactor?.restart(0) }
                            10 -> ap?.cycleSpeed(false)
                            11 -> ap?.cycleSpeed(true)
                            20 -> ap?.setLoopA()
                            21 -> ap?.setLoopB()
                            22 -> ap?.clearLoop()
                            30 -> ap?.cycleRepeat()
                            in 40..43 -> ap?.setEqPresetByIndex(btn - 40)
                            60 -> {
                                val dur = ap?.durationMs ?: 0
                                if (dur > 0) {
                                    val t = ((laserBx - 60f) / (1024f - 120f)).coerceIn(0f, 1f)
                                    ap?.seekTo((t * dur).toLong())
                                }
                            }
                        }
                    }
                }
                when (btn) {
                    51 -> {
                        activity.audioPickerMode = true
                        activity.audioPickerScrollOffset = 0
                        hoveredAudioFileIndex = -1
                        activity.requestAudioFileScan()
                        activity.uiNeedsRefresh = true
                        activity.requestUiRender()
                    }
                    52 -> activity.audioPlayerMode = false
                    60 -> {
                        audioSeekDragging = true
                        lastAudioSeekTargetMs = Long.MIN_VALUE
                    }
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 113) {
                activity.audioReactor?.rolloff = AudioReactor.Rolloff.INSTANT
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 114) {
                activity.audioReactor?.rolloff = AudioReactor.Rolloff.HARD_KNEE
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 115) {
                activity.audioReactor?.rolloff = AudioReactor.Rolloff.SOFT_KNEE
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 116) {
                activity.audioReactor?.rolloff = AudioReactor.Rolloff.THROB
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 117) {
                activity.audioReactor?.colorMode = AudioReactor.ColorMode.FIXED
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 118) {
                activity.audioReactor?.colorMode = AudioReactor.ColorMode.CYCLE
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 119) {
                activity.audioReactor?.colorMode = AudioReactor.ColorMode.FLASH
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 127) {
                activity.audioReactor?.lockBoom()
                Log.i(TAG, "BOOM: locked box to ${activity.audioReactor?.boomLeft}-${activity.audioReactor?.boomRight}")
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton in 120..122) {
                activity.audioReactor?.washScope = AudioReactor.WashScope.entries[hoveredActionButton - 120]
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton in 123..126) {
                activity.audioReactor?.blendMode = AudioReactor.BlendMode.entries[hoveredActionButton - 123]
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 111) {
                activity.beatSettingsMode = false
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 112) {
                activity.beatReactorEnabled = false
                activity.audioReactor?.enabled = false
                activity.beatSettingsMode = false
                activity.beatBaseStored = false
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 128) {
                // VIBES: toggle haptic device
                if (activity.hapticConnected) {
                    // Safety stop: zero vibration before disconnect
                    val hmStop = activity.hapticManager
                    if (hmStop != null && hmStop.isDualMotor) hmStop.setDualIntensity(0, 0)
                    else hmStop?.setIntensity(0)
                    activity.hapticManager?.disconnect()
                    activity.hapticConnected = false
                    activity.hapticEnabled = false
                } else {
                    if (activity.hapticManager == null) {
                        activity.hapticManager = com.ashairfoil.prism.haptics.BleDeviceManager(activity)
                        activity.hapticManager?.onConnectionStateChanged = { state ->
                            when (state) {
                                com.ashairfoil.prism.haptics.ConnectionState.Ready -> {
                                    activity.hapticConnected = true
                                    activity.hapticEnabled = true
                                    Log.i(TAG, "Haptic device connected: ${activity.hapticManager?.connectedDeviceName}")
                                    activity.uiNeedsRefresh = true
                                }
                                com.ashairfoil.prism.haptics.ConnectionState.Disconnected -> {
                                    activity.hapticConnected = false
                                    activity.hapticEnabled = false
                                    Log.i(TAG, "Haptic device disconnected")
                                    activity.uiNeedsRefresh = true
                                }
                                else -> {}
                            }
                        }
                        activity.hapticManager?.onScanStopped = {
                            if (!activity.hapticConnected) {
                                activity.hapticEnabled = false
                                activity.showMessage("No Lovense device found")
                                activity.uiNeedsRefresh = true
                            }
                        }
                    }
                    val hm = activity.hapticManager
                    if (hm != null && !hm.hasBlePermissions()) {
                        Log.w(TAG, "BLE permissions missing: ${hm.getMissingPermissions()}")
                        activity.showMessage("BLE permissions needed.\nGrant in Settings > Apps > ChloeVR")
                        activity.hapticEnabled = false
                    } else {
                        Log.i(TAG, "Starting haptic BLE scan...")
                        val scanStarted = hm?.startScan() ?: false
                        if (scanStarted) {
                            activity.hapticEnabled = true
                        } else {
                            Log.w(TAG, "BLE scan failed to start — BT off or scanner unavailable")
                            activity.showMessage("BLE scan failed — check Bluetooth is on")
                            activity.hapticEnabled = false
                        }
                    }
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.beatSettingsMode && hoveredActionButton == 129) {
                // SPLIT: toggle dual motor independent bass/treble
                activity.hapticDualMotorSplit = !activity.hapticDualMotorSplit
                Log.i(TAG, "Dual motor split: ${activity.hapticDualMotorSplit}")
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.lightingPresetMode && hoveredLightingPresetIndex >= 0) {
                val presets = com.ashairfoil.prism.settings.LightingPresets.getAllPresets()
                val preset = presets.getOrNull(hoveredLightingPresetIndex)
                if (preset != null) {
                    val renderer = activity.glesRenderer
                    if (renderer != null) {
                        com.ashairfoil.prism.settings.LightingPresets.applyPreset(preset, renderer) { activity.autoAmbient = it }
                        activity.activeLightingPresetName = preset.name
                        Log.i(TAG, "Applied lighting preset: ${preset.name}")
                    }
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.lightingPresetMode && hoveredActionButton == 131) {
                activity.lightingPresetMode = false
                hoveredLightingPresetIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.lightingPresetMode && hoveredActionButton == 132) {
                // SAVE CURRENT: open save name editor to name the preset
                activity.lightingPresetMode = false
                val renderer = activity.glesRenderer
                if (renderer != null) {
                    val preset = com.ashairfoil.prism.settings.LightingPresets.captureCurrentLighting("Custom", renderer, activity.autoAmbient)
                    com.ashairfoil.prism.settings.LightingPresets.saveCustomPreset(preset)
                    activity.activeLightingPresetName = preset.name
                    Log.i(TAG, "Saved custom lighting preset")
                    activity.uiRenderer?.showMessage("Lighting preset saved: Custom")
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.lightingPresetMode && hoveredActionButton == 133) {
                // SET DEFAULT
                val presets = com.ashairfoil.prism.settings.LightingPresets.getAllPresets()
                val preset = presets.getOrNull(hoveredLightingPresetIndex)
                val name = preset?.name ?: activity.activeLightingPresetName
                if (name != null) {
                    com.ashairfoil.prism.settings.LightingPresets.setDefaultPresetName(name)
                    Log.i(TAG, "Set default lighting preset: $name")
                    activity.uiRenderer?.showMessage("Default lighting: $name")
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 130) {
                Log.i(TAG, "Action: Lighting presets")
                activity.lightingPresetMode = true
                hoveredLightingPresetIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 134) {
                activity.sensorHudVisible = !activity.sensorHudVisible
                Log.i(TAG, "Sensor HUD: ${activity.sensorHudVisible}")
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 135) {
                val gr = activity.glesRenderer
                if (gr != null) {
                    gr.bloomEnabled = !gr.bloomEnabled
                    Log.i(TAG, "Bloom: ${gr.bloomEnabled}")
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.saveNameMode && hoveredKeyboardKey >= 0) {
                val kb = activity.uiRenderer.keyboard
                val (action, ch) = kb.getKeyChar(hoveredKeyboardKey)
                when (action) {
                    VirtualKeyboard.KeyAction.INSERT -> {
                        if (activity.saveNameLen < 20) {
                            activity.saveNameChars[activity.saveNameLen] = ch
                            activity.saveNameLen++
                            activity.saveNameCursor = activity.saveNameLen.coerceAtMost(19)
                        }
                    }
                    VirtualKeyboard.KeyAction.BACKSPACE -> {
                        if (activity.saveNameLen > 0) {
                            activity.saveNameLen--
                            activity.saveNameChars[activity.saveNameLen] = ' '
                            activity.saveNameCursor = activity.saveNameLen.coerceAtMost(19)
                        }
                    }
                    VirtualKeyboard.KeyAction.SHIFT -> kb.isShifted = !kb.isShifted
                    VirtualKeyboard.KeyAction.SPACE -> {
                        if (activity.saveNameLen < 20) {
                            activity.saveNameChars[activity.saveNameLen] = ' '
                            activity.saveNameLen++
                            activity.saveNameCursor = activity.saveNameLen.coerceAtMost(19)
                        }
                    }
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.saveNameMode && hoveredSaveButton == 0) {
                val name = String(activity.saveNameChars, 0, activity.saveNameLen).trim()
                if (name.isNotEmpty()) {
                    Log.i(TAG, "Saving scene as: '$name'")
                    activity.saveScene(name)
                    activity.saveNameMode = false
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.saveNameMode && hoveredSaveButton == 1) {
                activity.saveNameMode = false
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.saveNameMode && hoveredSceneIndex >= 0 && hoveredSceneIndex < activity.savedSceneFiles.size) {
                val existingFile = activity.savedSceneFiles[hoveredSceneIndex]
                val name = existingFile.nameWithoutExtension
                Log.i(TAG, "Overwriting scene: '$name'")
                activity.saveScene(name)
                activity.saveNameMode = false
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.glbPickerMode && hoveredActionButton == 103) {
                activity.glbPickerMode = false
                hoveredGlbIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.glbPickerMode && hoveredGlbIndex >= 0 && hoveredGlbIndex < activity.availableGlbFiles.size) {
                val file = activity.availableGlbFiles[hoveredGlbIndex]
                Log.i(TAG, "GLB picker: queuing ${file.name} for load")
                activity.pendingModelLoad = file
                activity.glbPickerMode = false
                hoveredGlbIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 109) {
                Log.i(TAG, "Action: Audio player")
                activity.audioPlayerMode = true
                hoveredAudioButton = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 100) {
                Log.i(TAG, "Action: Return to file menu")
                activity.startActivity(android.content.Intent(activity, com.ashairfoil.prism.MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                })
                activity.finish()
                return
            } else if (activity.menuVisible && hoveredActionButton == 102) {
                Log.i(TAG, "Action: Exit app")
                activity.running = false
                activity.runOnUiThread { activity.finishAffinity() }
                return
            } else if (activity.menuVisible && hoveredActionButton == 104) {
                Log.i(TAG, "Action: Save scene -- opening name editor")
                activity.saveNameMode = true
                hoveredSaveButton = -1
                val defaultName = models.firstOrNull()?.file?.nameWithoutExtension?.take(15) ?: "Scene"
                activity.saveNameChars = CharArray(20) { ' ' }
                for (i in defaultName.indices) activity.saveNameChars[i] = defaultName[i]
                activity.saveNameLen = defaultName.length
                activity.saveNameCursor = activity.saveNameLen.coerceAtMost(19)
                activity.saveNameStickCooldown = 0
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 107) {
                // DELETE selected model -- yeet it off screen
                val model = models.getOrNull(selectedModelIndex)
                if (model != null) {
                    Log.i(TAG, "Action: Yeet model ${model.file.name}")
                    val dx = model.posX - activity.camPosX
                    val dz = model.posZ - activity.camPosZ
                    val dist = kotlin.math.sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
                    val speed = 8f
                    synchronized(activity.sceneManager.yeetingModelsLock) {
                    yeetingModels.add(SceneManager.YeetingModel(
                        gpuModelId = model.gpuModelId,
                        posX = model.posX, posY = model.posY, posZ = model.posZ,
                        velX = (dx / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        velY = 3f + Math.random().toFloat() * 2f,
                        velZ = (dz / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        scale = model.scale
                    ))
                    }
                    models.removeAt(selectedModelIndex)
                    selectedModelIndex = if (models.isNotEmpty()) 0 else -1
                    activity.uiNeedsRefresh = true
                }
            } else if (activity.menuVisible && hoveredActionButton == 108) {
                val model = models.getOrNull(selectedModelIndex)
                val gr = activity.glesRenderer
                if (model != null && gr != null) {
                    val gpuModel = gr.getModel(model.gpuModelId)
                    model.metallic = 0f; model.roughness = 0.9f; model.exposure = 0f
                    model.contrast = 1f; model.saturation = 1f
                    gpuModel?.metallic = 0f; gpuModel?.roughness = 0.9f; gpuModel?.exposure = 0f
                    gpuModel?.contrast = 1f; gpuModel?.saturation = 1f
                    Log.i(TAG, "Action: Reset material for ${model.file.name}")
                    activity.uiNeedsRefresh = true
                }
            } else if (activity.menuVisible && hoveredActionButton == 105) {
                Log.i(TAG, "Action: Load scene -- opening scene picker")
                activity.refreshSceneList()
                activity.scenePickerMode = true
                hoveredSceneIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.scenePickerMode && hoveredActionButton == 106) {
                activity.scenePickerMode = false
                hoveredSceneIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.scenePickerMode && hoveredSceneIndex >= 0 && hoveredSceneIndex < activity.savedSceneFiles.size) {
                activity.pendingSceneLoad = activity.savedSceneFiles[hoveredSceneIndex]
                activity.scenePickerMode = false
                hoveredSceneIndex = -1
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredActionButton == 101) {
                Log.i(TAG, "Action: Add object -- opening GLB picker")
                activity.glbPickerMode = true
                hoveredGlbIndex = -1
                activity.glbPickerScrollOffset = 0
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredMenuParam in 0..12) {
                activity.selectedParam = hoveredMenuParam
                val sliderLeft = 240f; val sliderRight = 904f
                if (lastLaserBx in sliderLeft..sliderRight) {
                    val t = ((lastLaserBx - sliderLeft) / (sliderRight - sliderLeft)).coerceIn(0f, 1f)
                    val range = activity.PARAM_RANGES[hoveredMenuParam]
                    setParamValue(hoveredMenuParam, range.first + t * (range.second - range.first))
                    sliderDragging = hoveredMenuParam
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && hoveredMenuParam >= 13) {
                activity.selectedParam = hoveredMenuParam
                if (hoveredMenuParam == 13) {
                    if (!activity.beatReactorEnabled) {
                        activity.beatReactorEnabled = true
                        val reactor = activity.audioReactor
                        if (reactor != null) {
                            reactor.enabled = true
                            // Use audio player session ID if available, else system audio
                            val sid = activity.audioPlayer?.audioSessionId ?: 0
                            if (!reactor.isActive) reactor.start(sid)
                            else if (sid != 0) reactor.restart(sid)
                        }
                        Log.i(TAG, "BeatReactor ON")
                    } else {
                        activity.beatSettingsMode = true
                        Log.i(TAG, "BeatReactor settings opened")
                    }
                } else if (hoveredMenuParam == 14) {
                    val nextLevel = (activity.foveationLevel + 1) % 4
                    if (activity.setFoveationLevelSafe(nextLevel)) {
                        Log.i(TAG, "Foveation: ${activity.foveationLevel}")
                    } else {
                        Log.i(TAG, "Foveation unavailable on this runtime")
                    }
                } else if (hoveredMenuParam == 15) {
                    activity.textureQuality = (activity.textureQuality + 1) % 4
                    Log.i(TAG, "Tex quality: ${activity.textureQuality} (${arrayOf("Auto","4096","2048","1024")[activity.textureQuality]})")
                    activity.reloadAllModels()
                } else if (hoveredMenuParam == 16) {
                    val gr = activity.glesRenderer
                    if (gr != null) {
                        gr.showPlaneVisualization = !gr.showPlaneVisualization
                        Log.i(TAG, "Plane visualization: ${gr.showPlaneVisualization}")
                    }
                } else if (hoveredMenuParam == 17) {
                    activity.roomTrackingEnabled = !activity.roomTrackingEnabled
                    activity.sensorPoller.planeDetectionEnabled = activity.roomTrackingEnabled
                    if (!activity.roomTrackingEnabled) {
                        activity.sensorPoller.detectedPlaneCount = 0
                        activity.glesRenderer?.shadowPlanes = emptyList()
                    }
                    Log.i(TAG, "Room tracking: ${activity.roomTrackingEnabled}")
                } else if (hoveredMenuParam == 18) {
                    activity.roomEditMode = !activity.roomEditMode
                    if (activity.roomEditMode) {
                        // Auto-enable room tracking + plane vis
                        activity.roomTrackingEnabled = true
                        activity.sensorPoller.planeDetectionEnabled = true
                        activity.glesRenderer?.showPlaneVisualization = true
                        activity.selectedPlaneIndex = -1
                        Log.i(TAG, "Room edit ON — select planes with laser, stick Y to adjust height")
                    } else {
                        activity.selectedPlaneIndex = -1
                        Log.i(TAG, "Room edit OFF")
                    }
                }
                activity.uiNeedsRefresh = true
            } else if (hoveredModelIndex >= 0 && hoveredModelIndex != selectedModelIndex) {
                selectedModelIndex = hoveredModelIndex
                activity.uiNeedsRefresh = true
            } else if (hoveredModelIndex == selectedModelIndex && selectedModelIndex >= 0 && !activity.menuVisible) {
                selectedModelIndex = -1
                if (renderer != null) {
                    for (placed in models) {
                        renderer.getModel(placed.gpuModelId)?.selected = false
                    }
                }
                activity.uiNeedsRefresh = true
            } else {
                selectedModelIndex = -1
                if (renderer != null) {
                    for (placed in models) {
                        renderer.getModel(placed.gpuModelId)?.selected = false
                    }
                }
                activity.uiNeedsRefresh = true
            }
        }
        // Slider drag: while trigger held and dragging a slider, update continuously
        if (rightTriggerPressed && sliderDragging in 0..12 && activity.menuVisible) {
            val sliderLeft = 240f; val sliderRight = 904f
            if (lastLaserBx in sliderLeft..sliderRight) {
                val t = ((lastLaserBx - sliderLeft) / (sliderRight - sliderLeft)).coerceIn(0f, 1f)
                val range = activity.PARAM_RANGES[sliderDragging]
                setParamValue(sliderDragging, range.first + t * (range.second - range.first))
                activity.uiNeedsRefresh = true
            }
        }
        if (activity.menuVisible && activity.audioPlayerMode && !activity.audioPickerMode && rightTriggerPressed && audioSeekDragging) {
            val t = ((lastLaserBx - 60f) / (1024f - 120f)).coerceIn(0f, 1f)
            activity.runOnUiThread {
                val ap = activity.audioPlayer
                val dur = ap?.durationMs ?: 0
                if (dur > 0) {
                    val targetMs = (t * dur).toLong()
                    if (lastAudioSeekTargetMs == Long.MIN_VALUE ||
                        kotlin.math.abs(targetMs - lastAudioSeekTargetMs) >= 120L
                    ) {
                        ap?.seekTo(targetMs)
                        lastAudioSeekTargetMs = targetMs
                    }
                }
            }
        }
        if (!rightTriggerPressed || !activity.menuVisible || !activity.audioPlayerMode || activity.audioPickerMode) {
            audioSeekDragging = false
            lastAudioSeekTargetMs = Long.MIN_VALUE
        }
        if (!rightTriggerPressed) sliderDragging = -1
        lastRightTriggerState = rightTriggerPressed

        // Right stick click = toggle grid
        val rightStickClick = inputBuffer[14] > 0.5f
        if (rightStickClick && !lastRightStickClick && !activity.menuVisible) {
            activity.gridVisible = !activity.gridVisible
            activity.uiNeedsRefresh = true
        }

        // Grid height: grip + stick Y with nothing selected -> adjust grid Y
        if (selectedModelIndex < 0 && rightHandValid && rightSqueeze > 0.5f && !emitterGrabbed && !blockGripInteractionsUntilRelease && renderer != null) {
            if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                renderer.gridHeight += rightThumbY * 0.02f
                renderer.gridHeight = renderer.gridHeight.coerceIn(-3f, 3f)
                activity.uiNeedsRefresh = true
            }
        }

        // X = toggle gizmo
        if (xButton && !lastXState) {
            activity.gizmoVisible = !activity.gizmoVisible
            activity.uiNeedsRefresh = true
        }
        lastXState = xButton

        // B = toggle menu panel (or close sub-pickers first)
        if (bButton && !lastBState) {
            if (activity.audioPickerMode) {
                activity.audioPickerMode = false
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.audioPlayerMode) {
                activity.audioPlayerMode = false
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.glbPickerMode) {
                activity.glbPickerMode = false
                hoveredGlbIndex = -1
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.beatSettingsMode) {
                activity.beatSettingsMode = false
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.lightingPresetMode) {
                activity.lightingPresetMode = false
                hoveredLightingPresetIndex = -1
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.saveNameMode) {
                activity.saveNameMode = false
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            if (activity.scenePickerMode) {
                activity.scenePickerMode = false
                hoveredSceneIndex = -1
                activity.uiNeedsRefresh = true
                lastBState = bButton
                return
            }
            activity.menuVisible = !activity.menuVisible
            if (activity.menuVisible && renderer != null) {
                renderer.panelW = activity.PANEL_WIDTH * activity.panelScale
                renderer.panelH = activity.PANEL_HEIGHT * activity.panelScale
                renderer.panelX = activity.camPosX + activity.camFwdX * 1.2f
                renderer.panelY = activity.camPosY + activity.camFwdY * 1.2f
                renderer.panelZ = activity.camPosZ + activity.camFwdZ * 1.2f

                val yaw = kotlin.math.atan2(-activity.camFwdX, -activity.camFwdZ)
                activity.panelRotX = 0f
                activity.panelRotY = kotlin.math.sin(yaw * 0.5f)
                activity.panelRotZ = 0f
                activity.panelRotW = kotlin.math.cos(yaw * 0.5f)
            } else if (!activity.menuVisible) {
                blockGripInteractionsUntilRelease = true
                grabbing = false
                gizmoDragging = false
                emitterGrabbed = false
                activity.beatCursorX = -1f
                activity.beatCursorY = -1f
            }
            activity.uiNeedsRefresh = true
        }
        lastBState = bButton

        // When menu is visible: param controls
        if (activity.menuVisible) {
            // Save name editor: stick edits the name
            if (activity.saveNameMode) {
                if (activity.saveNameStickCooldown > 0) activity.saveNameStickCooldown--
                if (activity.saveNameStickCooldown == 0) {
                    if (rightThumbY > 0.4f) {
                        val curChar = activity.saveNameChars[activity.saveNameCursor]
                        val idx = activity.SAVE_NAME_CHARS.indexOf(curChar)
                        activity.saveNameChars[activity.saveNameCursor] = if (idx < 0) 'A' else activity.SAVE_NAME_CHARS[(idx + 1) % activity.SAVE_NAME_CHARS.length]
                        if (activity.saveNameCursor >= activity.saveNameLen) activity.saveNameLen = activity.saveNameCursor + 1
                        activity.saveNameStickCooldown = 6
                        activity.uiNeedsRefresh = true
                    } else if (rightThumbY < -0.4f) {
                        val curChar = activity.saveNameChars[activity.saveNameCursor]
                        val idx = activity.SAVE_NAME_CHARS.indexOf(curChar)
                        activity.saveNameChars[activity.saveNameCursor] = if (idx < 0) 'Z' else activity.SAVE_NAME_CHARS[(idx - 1 + activity.SAVE_NAME_CHARS.length) % activity.SAVE_NAME_CHARS.length]
                        if (activity.saveNameCursor >= activity.saveNameLen) activity.saveNameLen = activity.saveNameCursor + 1
                        activity.saveNameStickCooldown = 6
                        activity.uiNeedsRefresh = true
                    }
                    if (rightThumbX > 0.5f) {
                        activity.saveNameCursor = (activity.saveNameCursor + 1).coerceAtMost(19)
                        activity.saveNameStickCooldown = 8
                        activity.uiNeedsRefresh = true
                    } else if (rightThumbX < -0.5f) {
                        activity.saveNameCursor = (activity.saveNameCursor - 1).coerceAtLeast(0)
                        activity.saveNameStickCooldown = 8
                        activity.uiNeedsRefresh = true
                    }
                    if (aButton && !lastAState && activity.saveNameLen > 0) {
                        if (activity.saveNameCursor < activity.saveNameLen) {
                            for (i in activity.saveNameCursor until 19) activity.saveNameChars[i] = activity.saveNameChars[i + 1]
                            activity.saveNameChars[19] = ' '
                            activity.saveNameLen = (activity.saveNameLen - 1).coerceAtLeast(0)
                        }
                        activity.uiNeedsRefresh = true
                    }
                    lastAState = aButton
                }
                lastRightStickClick = rightStickClick
                if (activity.uiNeedsRefresh) {
                    activity.uiNeedsRefresh = false
                    activity.requestUiRender()
                }
                return
            }

            // Audio picker mode: stick scrolls the file list
            if (activity.audioPickerMode) {
                if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                    val maxVisible = 10
                    val maxScroll = (activity.availableAudioFiles.size - maxVisible).coerceAtLeast(0)
                    if (rightThumbY < -STICK_DEADZONE && activity.audioPickerScrollOffset < maxScroll) {
                        activity.audioPickerScrollOffset++; activity.uiNeedsRefresh = true
                    } else if (rightThumbY > STICK_DEADZONE && activity.audioPickerScrollOffset > 0) {
                        activity.audioPickerScrollOffset--; activity.uiNeedsRefresh = true
                    }
                }
                if (activity.uiNeedsRefresh) { activity.uiNeedsRefresh = false; activity.requestUiRender() }
                lastRightStickClick = rightStickClick
                return
            }
            if (activity.glbPickerMode) {
                if (kotlin.math.abs(rightThumbY) > STICK_DEADZONE) {
                    val maxVisible = 13
                    val maxScroll = (activity.availableGlbFiles.size - maxVisible).coerceAtLeast(0)
                    if (rightThumbY < -STICK_DEADZONE && activity.glbPickerScrollOffset < maxScroll) {
                        activity.glbPickerScrollOffset++
                        activity.uiNeedsRefresh = true
                    } else if (rightThumbY > STICK_DEADZONE && activity.glbPickerScrollOffset > 0) {
                        activity.glbPickerScrollOffset--
                        activity.uiNeedsRefresh = true
                    }
                }
                lastRightStickClick = rightStickClick
                if (activity.uiNeedsRefresh) {
                    activity.uiNeedsRefresh = false
                    activity.requestUiRender()
                }
                return
            }

            val model = models.getOrNull(selectedModelIndex)

            // Right stick click = cycle parameter
            if (rightStickClick && !lastRightStickClick) {
                activity.selectedParam = (activity.selectedParam + 1) % activity.PARAM_NAMES.size
                activity.uiNeedsRefresh = true
            }

            // Right thumbstick left/right = adjust selected parameter (horizontal sliders)
            if (kotlin.math.abs(rightThumbX) > STICK_DEADZONE && renderer != null) {
                val delta = rightThumbX * 0.015f
                val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
                when (activity.selectedParam) {
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
                        model.contrast = (model.contrast + delta * 0.2f).coerceIn(0.5f, 2.0f)
                        gpuModel?.contrast = model.contrast
                    }
                    4 -> if (model != null) {
                        model.saturation = (model.saturation + delta * 2f).coerceIn(0f, 3f)
                        gpuModel?.saturation = model.saturation
                    }
                    5 -> renderer.lightIntensity = (renderer.lightIntensity + delta * 2f).coerceIn(0f, 10f)
                    6 -> renderer.fillLightIntensity = (renderer.fillLightIntensity + delta).coerceIn(0f, 5f)
                    7 -> {
                        activity.autoAmbient = false
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
                        if (!activity.beatToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                            activity.beatToggleLatch = true
                            activity.beatReactorEnabled = !activity.beatReactorEnabled
                            Log.i(TAG, "BeatReactor toggled: ${activity.beatReactorEnabled}")
                            val reactor = activity.audioReactor
                            if (activity.beatReactorEnabled && reactor != null) {
                                reactor.enabled = true
                                val started = if (!reactor.isActive) reactor.start() else true
                                Log.i(TAG, "BeatReactor started=$started, isActive=${reactor.isActive}")
                            } else {
                                reactor?.enabled = false
                            }
                        }
                        if (kotlin.math.abs(rightThumbX) < 0.3f) activity.beatToggleLatch = false
                    }
                    14 -> {
                        if (!activity.foveationToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                            activity.foveationToggleLatch = true
                            val nextLevel = if (rightThumbX > 0) {
                                (activity.foveationLevel + 1).coerceAtMost(3)
                            } else {
                                (activity.foveationLevel - 1).coerceAtLeast(0)
                            }
                            activity.setFoveationLevelSafe(nextLevel)
                        }
                        if (kotlin.math.abs(rightThumbX) < 0.3f) activity.foveationToggleLatch = false
                    }
                    15 -> {
                        if (!activity.foveationToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                            activity.foveationToggleLatch = true
                            activity.textureQuality = if (rightThumbX > 0) {
                                (activity.textureQuality + 1) % 4
                            } else {
                                (activity.textureQuality - 1 + 4) % 4
                            }
                            activity.reloadAllModels()
                        }
                        if (kotlin.math.abs(rightThumbX) < 0.3f) activity.foveationToggleLatch = false
                    }
                    16 -> {
                        if (!activity.planeVisToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                            activity.planeVisToggleLatch = true
                            val gr = activity.glesRenderer
                            if (gr != null) {
                                gr.showPlaneVisualization = !gr.showPlaneVisualization
                                Log.i(TAG, "Plane visualization: ${gr.showPlaneVisualization}")
                            }
                        }
                        if (kotlin.math.abs(rightThumbX) < 0.3f) activity.planeVisToggleLatch = false
                    }
                    17 -> {
                        if (!activity.roomTrackToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                            activity.roomTrackToggleLatch = true
                            activity.roomTrackingEnabled = !activity.roomTrackingEnabled
                            activity.sensorPoller.planeDetectionEnabled = activity.roomTrackingEnabled
                            if (!activity.roomTrackingEnabled) {
                                activity.sensorPoller.detectedPlaneCount = 0
                                activity.glesRenderer?.shadowPlanes = emptyList()
                            }
                            Log.i(TAG, "Room tracking: ${activity.roomTrackingEnabled}")
                        }
                        if (kotlin.math.abs(rightThumbX) < 0.3f) activity.roomTrackToggleLatch = false
                    }
                    18 -> {
                        // Room Edit: stick Y adjusts selected plane height, stick X cycles planes
                        if (activity.roomEditMode) {
                            val planes = activity.glesRenderer?.shadowPlanes ?: emptyList()
                            if (planes.isNotEmpty()) {
                                // Stick X: cycle through planes
                                if (!activity.roomEditToggleLatch && kotlin.math.abs(rightThumbX) > 0.5f) {
                                    activity.roomEditToggleLatch = true
                                    val cur = activity.selectedPlaneIndex
                                    activity.selectedPlaneIndex = if (rightThumbX > 0) {
                                        (cur + 1) % planes.size
                                    } else {
                                        (cur - 1 + planes.size) % planes.size
                                    }
                                }
                                if (kotlin.math.abs(rightThumbX) < 0.3f) activity.roomEditToggleLatch = false

                                // Stick Y: adjust selected plane Y offset (5mm per tick)
                                val sel = activity.selectedPlaneIndex
                                if (sel in planes.indices && kotlin.math.abs(rightThumbY) > 0.15f) {
                                    val p = planes[sel]
                                    val key = activity.planeKey(p.posX, p.posZ)
                                    val cur = activity.planeAdjustments[key] ?: 0f
                                    val step = rightThumbY * 0.005f // 5mm per unit
                                    activity.planeAdjustments[key] = (cur + step).coerceIn(-0.5f, 0.5f)
                                }
                            }
                        }
                    }
                }
                activity.uiNeedsRefresh = true
            }

            // A button = reset selected parameter
            if (aButton && !lastAState && renderer != null) {
                val gpuModel = if (model != null) renderer.getModel(model.gpuModelId) else null
                when (activity.selectedParam) {
                    0 -> if (model != null) { model.metallic = 0f; gpuModel?.metallic = 0f }
                    1 -> if (model != null) { model.roughness = 0.9f; gpuModel?.roughness = 0.9f }
                    2 -> if (model != null) { model.exposure = 0f; gpuModel?.exposure = 0f }
                    3 -> if (model != null) { model.contrast = 1f; gpuModel?.contrast = 1f }
                    4 -> if (model != null) { model.saturation = 1f; gpuModel?.saturation = 1f }
                    5 -> renderer.lightIntensity = 2.0f
                    6 -> renderer.fillLightIntensity = 0.5f
                    7 -> { activity.autoAmbient = true; renderer.ambientIntensity = 1.0f }
                    8 -> { renderer.lightAngleDeg = 0f; renderer.updateLightDirFromAngles() }
                    9 -> { renderer.lightElevDeg = 60f; renderer.updateLightDirFromAngles() }
                    10 -> renderer.shadowDarkness = 0.7f
                    11 -> renderer.shadowSoftness = 2.0f
                    12 -> renderer.shadowSpread = 8f
                    13 -> {
                        activity.beatReactorEnabled = false
                        activity.audioReactor?.enabled = false
                    }
                    14 -> { activity.setFoveationLevelSafe(0) }
                    15 -> { activity.textureQuality = 0; activity.reloadAllModels() }
                    16 -> { activity.glesRenderer?.showPlaneVisualization = false }
                    17 -> {
                        activity.roomTrackingEnabled = false
                        activity.sensorPoller.planeDetectionEnabled = false
                        activity.sensorPoller.detectedPlaneCount = 0
                        activity.glesRenderer?.shadowPlanes = emptyList()
                    }
                    18 -> {
                        // Reset: clear selected plane's adjustment, or all if none selected
                        val sel = activity.selectedPlaneIndex
                        val planes = activity.glesRenderer?.shadowPlanes ?: emptyList()
                        if (sel in planes.indices) {
                            val key = activity.planeKey(planes[sel].posX, planes[sel].posZ)
                            activity.planeAdjustments.remove(key)
                            Log.i(TAG, "Reset plane $sel adjustment")
                        } else {
                            activity.planeAdjustments.clear()
                            Log.i(TAG, "Reset all plane adjustments")
                        }
                    }
                }
                activity.uiNeedsRefresh = true
            }
            lastAState = aButton

            if (activity.uiNeedsRefresh) {
                activity.uiNeedsRefresh = false
                activity.requestUiRender()
            }
            lastRightStickClick = rightStickClick
            return
        }
        lastRightStickClick = rightStickClick

        if (activity.handsLocked) return
        if (blockGripInteractionsUntilRelease) return

        // ── Model manipulation (skip if gizmo is being dragged) ──
        if (gizmoDragging) return

        val leftGripHeld = leftSqueeze > 0.5f && leftHandValid
        val rightGripHeld = rightSqueeze > 0.5f && rightHandValid
        val isGrabbing = leftGripHeld || rightGripHeld

        // Auto-select hovered model on grip
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

        // Use pre-allocated scratch buffers instead of per-frame floatArrayOf
        val grabHandPos: FloatArray? = when {
            rightGripHeld -> { scratchGrabHandPos[0] = rightHandPosX; scratchGrabHandPos[1] = rightHandPosY; scratchGrabHandPos[2] = rightHandPosZ; scratchGrabHandPos }
            leftGripHeld -> { scratchGrabHandPos[0] = leftHandPosX; scratchGrabHandPos[1] = leftHandPosY; scratchGrabHandPos[2] = leftHandPosZ; scratchGrabHandPos }
            else -> null
        }
        val grabAimRot: FloatArray? = when {
            rightGripHeld && rightAimValid -> { scratchGrabAimRot[0] = rightAimRotX; scratchGrabAimRot[1] = rightAimRotY; scratchGrabAimRot[2] = rightAimRotZ; scratchGrabAimRot[3] = rightAimRotW; scratchGrabAimRot }
            leftGripHeld && leftAimValid -> { scratchGrabAimRot[0] = leftAimRotX; scratchGrabAimRot[1] = leftAimRotY; scratchGrabAimRot[2] = leftAimRotZ; scratchGrabAimRot[3] = leftAimRotW; scratchGrabAimRot }
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
                grabbing = true
                val fwdInit = activity.glesRenderer?.quatForward(grabAimRot)
                if (fwdInit == null) { scratchGrabFwd[0] = 0f; scratchGrabFwd[1] = 0f; scratchGrabFwd[2] = -1f }
                val fi = fwdInit ?: scratchGrabFwd
                if (hitDistance > 0f) {
                    grabDistance = hitDistance
                } else {
                    val dx = selected.posX - grabHandPos[0]
                    val dy = selected.posY - grabHandPos[1]
                    val dz = selected.posZ - grabHandPos[2]
                    grabDistance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                }
                if (grabDistance < 0.1f) grabDistance = 0.5f
                val hitX = grabHandPos[0] + fi[0] * grabDistance
                val hitY = grabHandPos[1] + fi[1] * grabDistance
                val hitZ = grabHandPos[2] + fi[2] * grabDistance
                scratchWorldOff[0] = selected.posX - hitX; scratchWorldOff[1] = selected.posY - hitY; scratchWorldOff[2] = selected.posZ - hitZ
                scratchInvAim[0] = -grabAimRot[0]; scratchInvAim[1] = -grabAimRot[1]; scratchInvAim[2] = -grabAimRot[2]; scratchInvAim[3] = grabAimRot[3]
                grabOffset = activity.glesRenderer?.rotateVecByQuat(scratchWorldOff, scratchInvAim) ?: scratchWorldOff.copyOf()
                System.arraycopy(grabAimRot, 0, grabStartAimRot, 0, 4)
                grabStartModelRot[0] = selected.rotX; grabStartModelRot[1] = selected.rotY; grabStartModelRot[2] = selected.rotZ; grabStartModelRot[3] = selected.rotW
            }

            val fwdResult = activity.glesRenderer?.quatForward(grabAimRot)
            if (fwdResult == null) { scratchGrabFwd[0] = 0f; scratchGrabFwd[1] = 0f; scratchGrabFwd[2] = -1f }
            val fwd = fwdResult ?: scratchGrabFwd

            if (kotlin.math.abs(grabThumbY) > STICK_DEADZONE) {
                grabDistance += grabThumbY * 0.05f
                grabDistance = grabDistance.coerceIn(0.05f, 30f)
            }

            if (kotlin.math.abs(grabThumbX) > STICK_DEADZONE) {
                selected.scale = (selected.scale + grabThumbX * 0.03f).coerceIn(0.05f, 10f)
            }

            val rotOff = activity.glesRenderer?.rotateVecByQuat(grabOffset, grabAimRot) ?: grabOffset
            selected.posX = grabHandPos[0] + fwd[0] * grabDistance + rotOff[0]
            selected.posY = grabHandPos[1] + fwd[1] * grabDistance + rotOff[1]
            selected.posZ = grabHandPos[2] + fwd[2] * grabDistance + rotOff[2]

            // Grip = move + rotate (ShapesXR style)
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
                // Renormalize to prevent floating-point drift over extended manipulation
                val qLen = kotlin.math.sqrt(selected.rotX * selected.rotX + selected.rotY * selected.rotY +
                    selected.rotZ * selected.rotZ + selected.rotW * selected.rotW)
                if (qLen > 0.001f) {
                    val inv = 1f / qLen
                    selected.rotX *= inv; selected.rotY *= inv; selected.rotZ *= inv; selected.rotW *= inv
                }
            }

            activity.updateModelTransform(selected)
        } else {
            grabbing = false
        }

        // Free thumbstick = rotate/height
        if (!isGrabbing && !activity.menuVisible) {
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
                activity.updateModelTransform(selected)
            }

            val vAxis = leftThumbY
            if (kotlin.math.abs(vAxis) > STICK_DEADZONE) {
                selected.posY += vAxis * 0.06f
                activity.updateModelTransform(selected)
            }
        }

        // Left stick click = toggle light emitter visibility
        val leftStickClick = inputBuffer[13] > 0.5f
        if (leftStickClick && !lastLeftStickClick) {
            val gr = activity.glesRenderer
            if (gr != null) {
                gr.emitterVisible = !gr.emitterVisible
                Log.i(TAG, "Light emitter ${if (gr.emitterVisible) "ON" else "OFF"}")
            }
        }
        lastLeftStickClick = leftStickClick

        // Y = cycle selected model
        if (yButton && !lastYState && models.size > 1) {
            selectedModelIndex = (selectedModelIndex + 1) % models.size
            if (activity.menuVisible) activity.uiNeedsRefresh = true
        }
        lastYState = yButton

        if (aButton && !lastAState && !activity.menuVisible) {
            // Snap selected model to nearest detected surface (or grid floor)
            val model = models.getOrNull(selectedModelIndex)
            val gr = activity.glesRenderer
            if (model != null && gr != null) {
                val gpuModel = gr.getModel(model.gpuModelId)
                if (gpuModel != null) {
                    var snapY = gr.gridHeight
                    val planes = gr.shadowPlanes
                    if (planes.isNotEmpty()) {
                        var bestDist = Float.MAX_VALUE
                        for (p in planes) {
                            if (p.label != 2 && p.label != 4) continue
                            val dx = model.posX - p.posX
                            val dz = model.posZ - p.posZ
                            val hDist = kotlin.math.sqrt(dx*dx + dz*dz)
                            if (hDist < maxOf(p.extentX, p.extentY) * 1.5f && hDist < bestDist) {
                                bestDist = hDist
                                snapY = p.posY
                            }
                        }
                    }
                    val worldMinY = model.posY + gpuModel.boundsMinY * model.scale
                    model.posY += (snapY - worldMinY)
                    activity.updateModelTransform(model)
                    Log.i(TAG, "Snap to surface: posY=${model.posY} (minY=${gpuModel.boundsMinY}, snapY=$snapY)")
                }
            }
        }
        lastAState = aButton

        if (activity.uiNeedsRefresh) {
            activity.uiNeedsRefresh = false
            activity.requestUiRender()
        }
    }
}
