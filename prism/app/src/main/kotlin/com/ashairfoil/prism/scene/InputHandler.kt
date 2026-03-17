package com.ashairfoil.prism.scene

import android.util.Log
import com.ashairfoil.prism.AudioReactor
import com.ashairfoil.prism.FilamentModelActivity
import com.ashairfoil.prism.FilePicker
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

    // Laser / selection
    var laserHandPos = floatArrayOf(0f, 0f, 0f)
    var laserAimRot = floatArrayOf(0f, 0f, 0f, 1f)
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
    private var gizmoDragStartHandPos = floatArrayOf(0f, 0f, 0f)
    private var gizmoDragStartModelPos = floatArrayOf(0f, 0f, 0f)

    // Panel drag
    var draggingPanel = false
    private var panelGrabDist = 1.0f

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
    private var audioSeekDragging = false

    // Beat settings drag
    var beatDraggingSlider = -1
    var beatSliderLaserX = 0f
    private var beatDragCorner = -1
    private var beatLockedSlider = -1

    // Save name hover
    var hoveredSaveButton = -1

    // Slider dragging
    private var sliderDragging = -1
    private var lastLaserBx = 0f

    // ── BeatSlider definitions ──

    data class BeatSlider(
        val name: String, val unit: String, val min: Float, val max: Float,
        val get: () -> Float, val set: (Float) -> Unit
    )

    val beatSliders by lazy {
        arrayOf(
            BeatSlider("GAIN", "x", 0.5f, 10f,
                { activity.audioReactor?.sensitivity ?: 3f },
                { activity.audioReactor?.sensitivity = it }),
            BeatSlider("BOX LEFT", "Hz", 20f, 2000f,
                { val r = activity.audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxLeft.toDouble()).toFloat() else 20f },
                { activity.audioReactor?.boxLeft = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) }),
            BeatSlider("BOX RIGHT", "Hz", 100f, 20000f,
                { val r = activity.audioReactor; if (r != null) 20f * Math.pow(1000.0, r.boxRight.toDouble()).toFloat() else 300f },
                { activity.audioReactor?.boxRight = (kotlin.math.ln(it / 20f) / kotlin.math.ln(1000f)).coerceIn(0f, 1f) }),
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
                { activity.audioReactor?.releaseMs = it }),
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
        val value = slider.min + normalizedX * (slider.max - slider.min)
        slider.set(value)
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
            laserHandPos = floatArrayOf(rightHandPosX, rightHandPosY, rightHandPosZ)
            laserAimRot = floatArrayOf(rightAimRotX, rightAimRotY, rightAimRotZ, rightAimRotW)

            val rayDir = renderer.quatForward(laserAimRot)

            // ── Menu panel hit test ──
            hoveredMenuParam = -1
            hoveredActionButton = -1
            var laserOnPanel = false
            if (activity.menuVisible && renderer != null) {
                val pcx = renderer.panelX
                val pcy = renderer.panelY
                val pcz = renderer.panelZ
                val panelHW = (renderer.panelW * 0.5f).coerceAtLeast(0.001f)
                val panelHH = (renderer.panelH * 0.5f).coerceAtLeast(0.001f)

                // Billboard axes -- must match renderer exactly
                var fwdX = pcx - activity.camPosX; var fwdY = pcy - activity.camPosY; var fwdZ = pcz - activity.camPosZ
                val fLen = kotlin.math.sqrt(fwdX*fwdX + fwdY*fwdY + fwdZ*fwdZ).coerceAtLeast(0.001f)
                fwdX /= fLen; fwdY /= fLen; fwdZ /= fLen

                // Right = cross(fwd, worldUp)
                var rx = fwdY*0f - fwdZ*1f
                var ry = fwdZ*0f - fwdX*0f
                var rz = fwdX*1f - fwdY*0f
                val rLen = kotlin.math.sqrt(rx*rx + ry*ry + rz*rz).coerceAtLeast(0.001f)
                rx /= rLen; ry /= rLen; rz /= rLen

                // Up = cross(fwd, right) then negate
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
                                activity.uiNeedsRefresh = true
                            } else if (activity.beatSettingsMode) {
                                val prevDragging = beatDraggingSlider
                                beatDraggingSlider = -1
                                beatSliderLaserX = 0f
                                hoveredActionButton = -1
                                activity.beatCursorX = bx; activity.beatCursorY = by

                                // Layout: spectrum 140..390, sliders 418..786, buttons 920+
                                val specTopHit = 155f; val specBotHit = 435f
                                val specLeftHit = 40f; val specRightHit = 984f
                                val sliderAreaTopHit = 465f
                                val sliderRowH = 32f

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

                                        if (rightTrigger > 0.5f) {
                                            if (beatDragCorner < 0) {
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
                            } else if (activity.saveNameMode) {
                                hoveredSaveButton = -1
                                hoveredSceneIndex = -1
                                if (by in 190f..270f) hoveredSaveButton = 0
                                if (by in 280f..880f) {
                                    val maxVisible = 10
                                    val frac = (by - 280f) / (880f - 280f)
                                    val idx = (frac * maxVisible).toInt()
                                    if (idx < activity.savedSceneFiles.size) hoveredSceneIndex = idx
                                }
                                if (by > 920f) hoveredSaveButton = 1
                            } else if (activity.glbPickerMode) {
                                if (by > 920f) {
                                    hoveredActionButton = 103
                                }
                                if (by in 120f..900f) {
                                    val maxVisible = 13
                                    val frac = (by - 120f) / (900f - 120f)
                                    val idx = activity.glbPickerScrollOffset + (frac * maxVisible).toInt()
                                    if (idx < activity.availableGlbFiles.size) {
                                        hoveredGlbIndex = idx
                                    }
                                }
                            } else if (activity.scenePickerMode) {
                                if (by > 920f) {
                                    hoveredActionButton = 106
                                }
                                if (by in 120f..900f) {
                                    val maxVisible = 13
                                    val frac = (by - 120f) / (900f - 120f)
                                    val idx = (frac * maxVisible).toInt()
                                    if (idx < activity.savedSceneFiles.size) {
                                        hoveredSceneIndex = idx
                                    }
                                }
                            } else {
                                // Action buttons: 3 rows at bottom
                                if (by in 800f..860f) {
                                    if (bx < 520f) hoveredActionButton = 104
                                    else hoveredActionButton = 105
                                }
                                if (by in 860f..925f) {
                                    if (bx < 360f) hoveredActionButton = 101
                                    else if (bx < 690f) hoveredActionButton = 107
                                    else hoveredActionButton = 108
                                }
                                if (by > 925f) {
                                    if (bx < 360f) hoveredActionButton = 109
                                    else if (bx < 690f) hoveredActionButton = 100
                                    else hoveredActionButton = 102
                                }

                                // Param rows with section headers (rowH=46px render, 8px section pad)
                                // Render coords map to hit coords via ×(1024/1280)=×0.8
                                val paramRowHit = 46f * 0.8f  // 36.8
                                val sectionPadHit = 10f * 0.8f  // 8.0
                                if (by in 130f..850f) {
                                    val adjustedBy = by - 130f
                                    var acc = 0f; var idx = -1
                                    for (p in 0 until activity.PARAM_NAMES.size) {
                                        if (p == 0 || p == 5 || p == 13) acc += sectionPadHit
                                        if (adjustedBy >= acc && adjustedBy < acc + paramRowHit) { idx = p; break }
                                        acc += paramRowHit
                                    }
                                    if (idx >= 0) hoveredMenuParam = idx
                                    lastLaserBx = bx
                                }
                            }
                        }
                    }
                }

                // Drag update -- full 3D: place panel along laser ray at grab distance
                if (draggingPanel && renderer != null) {
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

                // Refresh only when hover state actually changes
                if (hoveredMenuParam != lastHoveredMenuParam || hoveredActionButton != lastHoveredActionButton
                    || (activity.glbPickerMode && hoveredGlbIndex != lastHoveredGlbIndex)
                    || (activity.scenePickerMode && hoveredSceneIndex != lastHoveredSceneIndex)) {
                    lastHoveredMenuParam = hoveredMenuParam
                    lastHoveredActionButton = hoveredActionButton
                    lastHoveredGlbIndex = hoveredGlbIndex
                    lastHoveredSceneIndex = hoveredSceneIndex
                    activity.uiNeedsRefresh = true
                }

                // When menu is up, block ALL model/gizmo interaction
                hoveredModelIndex = -1
                hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            }

            // Test gizmo + model only when menu is NOT up
            hoveredGizmoAxis = GlesModelRenderer.GIZMO_AXIS_NONE
            val selModel = models.getOrNull(selectedModelIndex)
            if (!activity.menuVisible && activity.gizmoVisible && selModel != null && !gizmoDragging) {
                val gPos = floatArrayOf(selModel.posX, selModel.posY, selModel.posZ)
                val gRot = floatArrayOf(selModel.rotX, selModel.rotY, selModel.rotZ, selModel.rotW)
                val (axis, _) = renderer.testGizmoHit(laserHandPos, rayDir, gPos, gRot)
                hoveredGizmoAxis = axis
            }

            // Test emitter hit
            emitterHovered = false
            if (!activity.menuVisible && !emitterGrabbed) {
                val eDist = renderer.testEmitterHit(laserHandPos, rayDir)
                if (eDist > 0f) emitterHovered = true
            }

            // Emitter grab: grip on hovered emitter -> drag along ray, delta-based
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
                    val rightThumbY = inputBuffer[3]
                    if (kotlin.math.abs(rightThumbY) > 0.15f) {
                        emitterGrabDist += rightThumbY * 0.02f
                        emitterGrabDist = emitterGrabDist.coerceIn(0.3f, 5f)
                    }
                    renderer.emitterPos = floatArrayOf(
                        laserHandPos[0] + rayDir[0] * emitterGrabDist,
                        laserHandPos[1] + rayDir[1] * emitterGrabDist,
                        laserHandPos[2] + rayDir[2] * emitterGrabDist
                    )
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
                    val center = floatArrayOf(worldCx, worldCy, worldCz)
                    val sx = kotlin.math.sqrt(m[0]*m[0] + m[1]*m[1] + m[2]*m[2])
                    val worldR = gpuModel.boundsRadius * sx
                    var dist = raySphereIntersect(laserHandPos, rayDir, center, worldR)
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
                    val scaled = proj * 3f
                    selModel.posX = gizmoDragStartModelPos[0] + worldAxis[0] * scaled
                    selModel.posY = gizmoDragStartModelPos[1] + worldAxis[1] * scaled
                    selModel.posZ = gizmoDragStartModelPos[2] + worldAxis[2] * scaled
                    activity.updateModelTransform(selModel)
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
            if (activity.menuVisible && activity.audioPlayerMode && activity.audioPickerMode) {
                when {
                    hoveredAudioFileIndex >= 0 && hoveredAudioFileIndex < activity.availableAudioFiles.size -> {
                        val file = activity.availableAudioFiles[hoveredAudioFileIndex]
                        // ExoPlayer must be created and accessed on the main thread
                        activity.runOnUiThread {
                            if (activity.audioPlayer == null) activity.audioPlayer = com.ashairfoil.prism.AudioPlayer(activity)
                            activity.audioPlayer?.play(file)
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
                        Log.i(TAG, "Audio: playing ${file.name}")
                    }
                    hoveredAudioButton == 50 -> activity.audioPickerMode = false
                }
                activity.uiNeedsRefresh = true
            } else if (activity.menuVisible && activity.audioPlayerMode && !activity.audioPickerMode) {
                val btn = hoveredAudioButton
                val laserBx = lastLaserBx
                // ExoPlayer must be accessed on the main thread
                activity.runOnUiThread {
                    val ap = activity.audioPlayer
                    when (btn) {
                        0 -> ap?.seekBy(-10000)
                        1 -> ap?.togglePlayPause()
                        2 -> ap?.seekBy(10000)
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
                when (btn) {
                    51 -> {
                        activity.availableAudioFiles = FilePicker.listVideoFiles(activity)
                            .filter { FilePicker.isAudioFile(it) }
                            .sortedBy { it.nameWithoutExtension.lowercase() }
                        activity.audioPickerMode = true
                        activity.audioPickerScrollOffset = 0
                        hoveredAudioFileIndex = -1
                    }
                    52 -> activity.audioPlayerMode = false
                    60 -> audioSeekDragging = true
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
                    activity.hapticManager?.setIntensity(0)
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
                    }
                    Log.i(TAG, "Starting haptic BLE scan...")
                    val scanStarted = activity.hapticManager?.startScan() ?: false
                    if (scanStarted) {
                        activity.hapticEnabled = true
                    } else {
                        Log.w(TAG, "BLE scan failed to start — permissions missing or BT off")
                        activity.hapticEnabled = false
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
                    yeetingModels.add(SceneManager.YeetingModel(
                        gpuModelId = model.gpuModelId,
                        posX = model.posX, posY = model.posY, posZ = model.posZ,
                        velX = (dx / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        velY = 3f + Math.random().toFloat() * 2f,
                        velZ = (dz / dist) * speed + (Math.random().toFloat() - 0.5f) * 2f,
                        scale = model.scale
                    ))
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
                            if (!reactor.isActive) reactor.start()
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
                }
                activity.uiNeedsRefresh = true
            } else if (hoveredModelIndex >= 0 && hoveredModelIndex != selectedModelIndex) {
                selectedModelIndex = hoveredModelIndex
                activity.uiNeedsRefresh = true
            } else if (hoveredModelIndex == selectedModelIndex && selectedModelIndex >= 0) {
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
        if (!rightTriggerPressed) sliderDragging = -1
        lastRightTriggerState = rightTriggerPressed

        // Right stick click = toggle grid
        val rightStickClick = inputBuffer[14] > 0.5f
        if (rightStickClick && !lastRightStickClick && !activity.menuVisible) {
            activity.gridVisible = !activity.gridVisible
            activity.uiNeedsRefresh = true
        }

        // Grid height: grip + stick Y with nothing selected -> adjust grid Y
        if (selectedModelIndex < 0 && rightHandValid && rightSqueeze > 0.5f && !emitterGrabbed && renderer != null) {
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
                    activity.runOnUiThread { activity.renderUiToBitmap() }
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
                if (activity.uiNeedsRefresh) { activity.uiNeedsRefresh = false; activity.runOnUiThread { activity.renderUiToBitmap() } }
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
                    activity.runOnUiThread { activity.renderUiToBitmap() }
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
                        model.contrast = (model.contrast + delta * 0.2f).coerceIn(0.85f, 1.15f)
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
                }
                activity.uiNeedsRefresh = true
            }
            lastAState = aButton

            if (activity.uiNeedsRefresh) {
                activity.uiNeedsRefresh = false
                activity.runOnUiThread { activity.renderUiToBitmap() }
            }
            lastRightStickClick = rightStickClick
            return
        }
        lastRightStickClick = rightStickClick

        if (activity.handsLocked) return

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
                grabbing = true
                val fwdInit = activity.glesRenderer?.quatForward(grabAimRot) ?: floatArrayOf(0f, 0f, -1f)
                if (hitDistance > 0f) {
                    grabDistance = hitDistance
                } else {
                    val dx = selected.posX - grabHandPos[0]
                    val dy = selected.posY - grabHandPos[1]
                    val dz = selected.posZ - grabHandPos[2]
                    grabDistance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                }
                if (grabDistance < 0.1f) grabDistance = 0.5f
                val hitX = grabHandPos[0] + fwdInit[0] * grabDistance
                val hitY = grabHandPos[1] + fwdInit[1] * grabDistance
                val hitZ = grabHandPos[2] + fwdInit[2] * grabDistance
                val worldOff = floatArrayOf(selected.posX - hitX, selected.posY - hitY, selected.posZ - hitZ)
                val invAim = floatArrayOf(-grabAimRot[0], -grabAimRot[1], -grabAimRot[2], grabAimRot[3])
                grabOffset = activity.glesRenderer?.rotateVecByQuat(worldOff, invAim) ?: worldOff
                grabStartAimRot = grabAimRot.copyOf()
                grabStartModelRot = floatArrayOf(selected.rotX, selected.rotY, selected.rotZ, selected.rotW)
            }

            val fwd = activity.glesRenderer?.quatForward(grabAimRot) ?: floatArrayOf(0f, 0f, -1f)

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
            activity.runOnUiThread { activity.renderUiToBitmap() }
        }
    }
}
