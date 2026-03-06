package com.ashairfoil.prism

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.InputEvent as XrInputEvent
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import java.io.File

class MainActivity : ComponentActivity(), OpenXRInput.ControllerListener {

    private var xrSession: Session? = null
    private var videoPlayer: VideoPlayer? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var isPlaying = false
    private var menuVisible = true
    private var currentFile: File? = null

    // Native OpenXR controller input
    private var openXRInput: OpenXRInput? = null

    // Current playback modes (auto-detected, user-overridable)
    private var currentScreenType = ScreenType.DOME_180
    private var currentStereoMode = StereoMode.SIDE_BY_SIDE

    // Screen adjustments
    private var screenHeight = 0f
    private var screenDepth = 0f
    private var screenZoom = 1f
    private var screenRoll = 0f
    private var screenYaw = 0f
    private var screenPitch = 0f

    // Trigger tap detection
    private var triggerDownTime = 0L
    private val TAP_THRESHOLD_MS = 300

    // Seek bar
    private var seekBar: SeekBar? = null
    private var seekLabel: TextView? = null
    private var isSeekBarTracking = false

    // Playback speed
    private var playbackSpeed = 1.0f

    // Thumbstick seek increment (seconds)
    private var seekIncrementSec = 10

    // Grab-to-reposition: grip+trigger, hand position drives movement, wrist drives roll
    private var isGrabbing = false
    private var grabStartHandPos = floatArrayOf(0f, 0f, 0f)
    private var grabStartControllerRoll = 0f
    private var grabStartScreenRoll = 0f
    private var grabStartScreenYaw = 0f
    private var grabStartScreenPitch = 0f
    private var grabStartScreenX = 0f
    private var grabStartScreenY = 0f
    private var grabStartScreenZ = -8f

    // Trigger-only zoom: hold trigger (no grip), move hand left/right
    private var isTriggerZooming = false
    private var triggerZoomStartHandX = 0f
    private var triggerZoomStartZoom = 1f

    // Screen world position (for flat screens placed in room)
    private var screenX = 0f
    private var screenY = 0f
    private var screenZ = -8f  // default: 8m in front

    // Scrub bar (quick B button access)
    private var scrubBarVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onPermissionGranted()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            onPermissionGranted()
        } else {
            showMessage("Storage permission required to browse video files.")
        }
    }

    private fun onPermissionGranted() {
        // Try native OpenXR first (before Jetpack claims the session)
        initOpenXRInput()
        initXrSession()
        showFilePicker()
    }

    private fun initXrSession() {
        try {
            when (val result = Session.create(this)) {
                is SessionCreateSuccess -> {
                    xrSession = result.session
                }
                else -> {}
            }
        } catch (e: Exception) {}
    }

    private fun initOpenXRInput() {
        try {
            openXRInput = OpenXRInput(this).also {
                it.setListener(this)
                if (!it.start()) {
                    android.util.Log.w("ChloeVR", "OpenXR input failed to start — using fallback input")
                    openXRInput = null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ChloeVR", "OpenXR input unavailable", e)
            openXRInput = null
        }
    }

    // ── File Picker ──

    private fun showFilePicker() {
        isPlaying = false
        menuVisible = true
        currentFile = null
        val files = FilePicker.listVideoFiles(this)

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "ChloeVR"
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 32)
            gravity = Gravity.CENTER
        })

        if (files.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "No video files found.\nSupported: MP4, MKV, WebM\nCheck internal storage or connect a USB drive."
                textSize = 18f
                setTextColor(0xFFAAAAAA.toInt())
                gravity = Gravity.CENTER
            })
        } else {
            layout.addView(TextView(this).apply {
                text = "${files.size} videos found"
                textSize = 16f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 24)
            })

            for (file in files) {
                val metadata = FileNameParser.parse(file)
                val stereoLabel = when (metadata.stereoMode) {
                    StereoMode.SIDE_BY_SIDE -> "SBS"
                    StereoMode.TOP_BOTTOM -> "TB"
                    StereoMode.MONO -> "Mono"
                }
                val sizeLabel = formatFileSize(file.length())

                layout.addView(TextView(this).apply {
                    text = "${file.name}\n${metadata.screenType.displayName}  $stereoLabel  |  $sizeLabel"
                    textSize = 18f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 20, 24, 20)
                    setBackgroundColor(0xFF222222.toInt())
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, 8)
                    layoutParams = params
                    setOnClickListener { playVideo(file) }
                })
            }
        }

        scrollView.addView(layout)
        setContentView(scrollView)
        setPanelVisible(true)
    }

    // ── Control Panel (shown during playback) ──

    private fun showControlPanel() {
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

        // Title row
        layout.addView(TextView(this).apply {
            text = currentFile?.name ?: "ChloeVR"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 16)
            maxLines = 1
        })

        // Seek bar
        seekLabel = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
        }
        layout.addView(seekLabel)

        seekBar = SeekBar(this).apply {
            max = 1000
            setPadding(0, 8, 0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = videoPlayer?.durationMs ?: return
                        val pos = (progress.toLong() * duration) / 1000
                        seekLabel?.text = "${formatTime(pos)} / ${formatTime(duration)}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeekBarTracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeekBarTracking = false
                    val duration = videoPlayer?.durationMs ?: return
                    val pos = ((sb?.progress ?: 0).toLong() * duration) / 1000
                    videoPlayer?.seekTo(pos)
                }
            })
        }
        layout.addView(seekBar)

        // Seek buttons row
        layout.addView(makeButtonRow(
            "-30s" to { videoPlayer?.seekBy(-30000) },
            "-10s" to { videoPlayer?.seekBy(-10000) },
            "⏯" to { videoPlayer?.togglePlayPause() },
            "+10s" to { videoPlayer?.seekBy(10000) },
            "+30s" to { videoPlayer?.seekBy(30000) }
        ))

        // Speed control + seek increment
        layout.addView(makeSpacer(12))
        layout.addView(makeSpeedRow())
        layout.addView(makeSpacer(8))
        layout.addView(makeSectionLabel("Stick Seek"))
        layout.addView(makeToggleRow(
            listOf(10 to "10s", 20 to "20s", 30 to "30s"),
            selected = { it.first == seekIncrementSec }
        ) { chosen -> seekIncrementSec = chosen.first })

        // ── Projection & Stereo Mode ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Projection"))
        layout.addView(makeToggleRow(
            listOf(
                ScreenType.FLAT to "Flat",
                ScreenType.DOME_180 to "180\u00B0",
                ScreenType.SPHERE_360 to "360\u00B0",
                ScreenType.FISHEYE to "Fisheye"
            ),
            selected = { it.first == currentScreenType }
        ) { chosen ->
            applyScreenType(chosen.first)
        })

        layout.addView(makeSpacer(8))
        layout.addView(makeSectionLabel("Stereo Mode"))
        layout.addView(makeToggleRow(
            listOf(
                StereoMode.MONO to "Mono",
                StereoMode.SIDE_BY_SIDE to "SBS",
                StereoMode.TOP_BOTTOM to "T/B"
            ),
            selected = { it.first == currentStereoMode }
        ) { chosen ->
            applyStereoMode(chosen.first)
        })

        // ── Screen Adjustments ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Screen Adjustments"))

        layout.addView(makeAdjustRow("Height", "\u25B2", "\u25BC") { delta ->
            screenHeight += delta * 0.5f
            updateScreenPose()
        })

        layout.addView(makeAdjustRow("Distance", "Closer", "Farther") { delta ->
            screenDepth += delta * 0.5f
            updateScreenPose()
        })

        layout.addView(makeAdjustRow("Zoom", "+", "\u2212") { delta ->
            screenZoom = (screenZoom + delta * 0.1f).coerceIn(0.3f, 3f)
            updateScreenScale()
        })

        layout.addView(makeAdjustRow("Roll", "\u21BB", "\u21BA") { delta ->
            screenRoll += delta * 5f
            updateScreenPose()
        })

        layout.addView(makeSpacer(16))

        // Reset + Back buttons
        layout.addView(makeButtonRow(
            "Reset View" to { resetAdjustments() },
            "Back to Files" to {
                stopPlayback()
                showFilePicker()
            }
        ))

        scrollView.addView(layout)
        setContentView(scrollView)

        updateSeekBarLoop()
    }

    // ── Toggle Row (for projection/stereo selection) ──

    private fun <T> makeToggleRow(
        options: List<Pair<T, String>>,
        selected: (Pair<T, String>) -> Boolean,
        onSelect: (Pair<T, String>) -> Unit
    ): LinearLayout {
        val buttons = mutableListOf<Button>()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val rowParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowParams.setMargins(0, 2, 0, 2)
            layoutParams = rowParams

            for ((i, option) in options.withIndex()) {
                val isSelected = selected(option)
                val btn = Button(this@MainActivity).apply {
                    text = option.second
                    textSize = 13f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(if (isSelected) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                    setPadding(12, 8, 12, 8)
                    val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    btnParams.setMargins(3, 0, 3, 0)
                    layoutParams = btnParams
                    setOnClickListener {
                        onSelect(option)
                        // Update button colors
                        buttons.forEachIndexed { j, b ->
                            b.setBackgroundColor(if (j == i) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                        }
                    }
                }
                buttons.add(btn)
                addView(btn)
            }
        }
    }

    // ── Speed Row ──

    private fun makeSpeedRow(): LinearLayout {
        val speedLabel = TextView(this).apply {
            text = "${playbackSpeed}x"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2, 0, 2)
            layoutParams = params

            addView(TextView(this@MainActivity).apply {
                text = "Speed"
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 12, 0)
                layoutParams = lp
            })

            addView(Button(this@MainActivity).apply {
                text = "-"
                textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener {
                    playbackSpeed = (playbackSpeed - 0.25f).coerceIn(0.25f, 4f)
                    videoPlayer?.speed = playbackSpeed
                    speedLabel.text = "${playbackSpeed}x"
                }
            })

            addView(speedLabel)

            addView(Button(this@MainActivity).apply {
                text = "+"
                textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener {
                    playbackSpeed = (playbackSpeed + 0.25f).coerceIn(0.25f, 4f)
                    videoPlayer?.speed = playbackSpeed
                    speedLabel.text = "${playbackSpeed}x"
                }
            })
        }
    }

    // ── Shared UI Helpers ──

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 8)
        }
    }

    private fun makeButtonRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 4, 0, 4)
            layoutParams = params

            for ((label, action) in buttons) {
                addView(Button(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    setPadding(16, 8, 16, 8)
                    val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    btnParams.setMargins(4, 0, 4, 0)
                    layoutParams = btnParams
                    setOnClickListener { action() }
                })
            }
        }
    }

    private fun makeAdjustRow(label: String, plusLabel: String, minusLabel: String, onAdjust: (Float) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2, 0, 2)
            layoutParams = params

            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            })

            addView(Button(this@MainActivity).apply {
                text = minusLabel
                textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener { onAdjust(-1f) }
            })

            addView(Button(this@MainActivity).apply {
                text = plusLabel
                textSize = 14f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener { onAdjust(1f) }
            })
        }
    }

    private fun makeSpacer(height: Int): android.view.View {
        return android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
        }
    }

    // ── Shape / Stereo Mapping ──

    private fun shapeForScreenType(type: ScreenType): SurfaceEntity.Shape {
        return when (type) {
            ScreenType.FLAT -> SurfaceEntity.Shape.Quad(FloatSize2d(8f, 4.5f))
            ScreenType.DOME_180 -> SurfaceEntity.Shape.Hemisphere(50f)
            ScreenType.SPHERE_360 -> SurfaceEntity.Shape.Sphere(50f)
            // Fisheye variants: use Hemisphere as approximation (correct mesh needs CustomMesh)
            ScreenType.FISHEYE, ScreenType.MKX200, ScreenType.RF52, ScreenType.VRCA220 ->
                SurfaceEntity.Shape.Hemisphere(50f)
        }
    }

    private fun sdkStereoMode(mode: StereoMode): SurfaceEntity.StereoMode {
        return when (mode) {
            StereoMode.MONO -> SurfaceEntity.StereoMode.MONO
            StereoMode.SIDE_BY_SIDE -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
            StereoMode.TOP_BOTTOM -> SurfaceEntity.StereoMode.TOP_BOTTOM
        }
    }

    private fun applyScreenType(type: ScreenType) {
        currentScreenType = type
        try {
            surfaceEntity?.shape = shapeForScreenType(type)
        } catch (e: Exception) {
            android.util.Log.e("ChloeVR", "Failed to set shape", e)
        }
        updateScreenPose()
    }

    private fun applyStereoMode(mode: StereoMode) {
        currentStereoMode = mode
        try {
            surfaceEntity?.stereoMode = sdkStereoMode(mode)
        } catch (e: Exception) {
            android.util.Log.e("ChloeVR", "Failed to set stereo mode", e)
        }
    }

    // ── Screen Adjustments ──

    // Pitch correction: grip pose forward is tilted ~45° up from laser pointer
    // Apply -45° local X rotation to approximate aim pose
    private val AIM_COS = 0.9239 // cos(-22.5°)
    private val AIM_SIN = -0.3827 // sin(-22.5°)

    private fun correctedRotation(rot: FloatArray): FloatArray {
        val gw = rot[3].toDouble(); val gx = rot[0].toDouble()
        val gy = rot[1].toDouble(); val gz = rot[2].toDouble()
        // grip * Quaternion(AIM_COS, AIM_SIN, 0, 0)
        return floatArrayOf(
            (-gw * AIM_SIN + gx * AIM_COS).toFloat(),
            (gy * AIM_COS - gz * AIM_SIN).toFloat(),
            (gz * AIM_COS + gy * AIM_SIN).toFloat(),
            (gw * AIM_COS + gx * AIM_SIN).toFloat()
        )
    }

    private fun aimDirection(rot: FloatArray): FloatArray {
        val corrected = correctedRotation(rot)
        val x = corrected[0].toDouble(); val y = corrected[1].toDouble()
        val z = corrected[2].toDouble(); val w = corrected[3].toDouble()
        // Forward = rotate (0,0,-1) by quaternion
        return floatArrayOf(
            (-2.0 * (x * z + w * y)).toFloat(),
            (2.0 * (w * x - y * z)).toFloat(),
            (2.0 * (x * x + y * y) - 1.0).toFloat()
        )
    }

    private fun controllerUp(rot: FloatArray): FloatArray {
        val corrected = correctedRotation(rot)
        val x = corrected[0].toDouble(); val y = corrected[1].toDouble()
        val z = corrected[2].toDouble(); val w = corrected[3].toDouble()
        // Rotate (0,1,0) by quaternion
        return floatArrayOf(
            (2.0 * (x * y + w * z)).toFloat(),
            (1.0 - 2.0 * (x * x + z * z)).toFloat(),
            (2.0 * (y * z - w * x)).toFloat()
        )
    }

    private fun controllerRollDeg(rot: FloatArray): Float {
        val aim = aimDirection(rot)
        val up = controllerUp(rot)
        // Project world-up (0,1,0) onto plane perpendicular to aim
        val dAimUp = aim[1].toDouble() // dot(aim, worldUp)
        var refX = -aim[0] * dAimUp
        var refY = 1.0 - aim[1] * dAimUp
        var refZ = -aim[2] * dAimUp
        val refLen = Math.sqrt(refX * refX + refY * refY + refZ * refZ)
        if (refLen < 0.05) return Float.NaN // controller near vertical — can't measure roll
        refX /= refLen; refY /= refLen; refZ /= refLen

        // Project controller up onto same plane
        val dAimCtrlUp = (aim[0] * up[0] + aim[1] * up[1] + aim[2] * up[2]).toDouble()
        var pX = up[0] - aim[0] * dAimCtrlUp
        var pY = up[1] - aim[1] * dAimCtrlUp
        var pZ = up[2] - aim[2] * dAimCtrlUp
        val pLen = Math.sqrt(pX * pX + pY * pY + pZ * pZ)
        if (pLen < 0.05) return Float.NaN
        pX /= pLen; pY /= pLen; pZ /= pLen

        // Signed angle between normalized vectors (via aim axis)
        val cosA = refX * pX + refY * pY + refZ * pZ
        val crossX = refY * pZ - refZ * pY
        val crossY = refZ * pX - refX * pZ
        val crossZ = refX * pY - refY * pX
        val sinA = crossX * aim[0] + crossY * aim[1] + crossZ * aim[2]
        return Math.toDegrees(Math.atan2(sinA, cosA)).toFloat()
    }

    private fun updateScreenPose() {
        val entity = surfaceEntity ?: return
        val rotation = Quaternion.fromEulerAngles(screenPitch, screenYaw, screenRoll)
        if (currentScreenType == ScreenType.FLAT) {
            // Flat screen: placed at world position
            entity.setPose(Pose(Vector3(screenX, screenY, screenZ), rotation))
        } else {
            // Immersive: centered on user, only rotation matters
            entity.setPose(Pose(Vector3(0f, screenHeight, screenDepth), rotation))
        }
    }

    private fun updateScreenScale() {
        val entity = surfaceEntity ?: return
        entity.setScale(screenZoom)
    }

    private fun resetAdjustments() {
        screenHeight = 0f
        screenDepth = 0f
        screenZoom = 1f
        screenRoll = 0f
        screenYaw = 0f
        screenPitch = 0f
        screenX = 0f
        screenY = 0f
        screenZ = if (currentScreenType == ScreenType.FLAT) -8f else 0f
        updateScreenPose()
        updateScreenScale()
    }

    // ── Seek Bar Update ──

    private fun updateSeekBarLoop() {
        val handler = window.decorView.handler ?: return
        handler.post(object : Runnable {
            override fun run() {
                if (!isPlaying) return
                val player = videoPlayer ?: return
                if (!isSeekBarTracking) {
                    val pos = player.currentPositionMs
                    val dur = player.durationMs
                    if (dur > 0) {
                        seekBar?.progress = ((pos * 1000) / dur).toInt()
                        seekLabel?.text = "${formatTime(pos)} / ${formatTime(dur)}"
                    }
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // ── Playback ──

    private fun setPanelVisible(visible: Boolean) {
        if (!visible) {
            // Clear stale content so it doesn't flash when panel is next shown
            setContentView(android.view.View(this))
            window.decorView.setBackgroundColor(0xFF000000.toInt())
        }
        try {
            xrSession?.scene?.mainPanelEntity?.setEnabled(visible)
        } catch (_: Exception) {}
    }

    private fun toggleMenu() {
        if (menuVisible || scrubBarVisible) {
            menuVisible = false
            scrubBarVisible = false
            setPanelVisible(false)
        } else {
            showControlPanel()
            menuVisible = true
            scrubBarVisible = false
            setPanelVisible(true)
        }
    }

    private fun toggleScrubBar() {
        if (scrubBarVisible) {
            scrubBarVisible = false
            menuVisible = false
            setPanelVisible(false)
        } else {
            showScrubBar()
            scrubBarVisible = true
            menuVisible = false
            setPanelVisible(true)
        }
    }

    private fun showScrubBar() {
        menuVisible = false

        window.decorView.setBackgroundColor(0xFF000000.toInt())

        // Outer frame fills panel, content at bottom
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(32, 12, 32, 12)
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            layoutParams = lp
        }

        seekLabel = TextView(this).apply {
            text = "0:00 / 0:00"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        bar.addView(seekLabel)

        seekBar = SeekBar(this).apply {
            max = 1000
            minHeight = 64
            setPadding(0, 16, 0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = videoPlayer?.durationMs ?: return
                        val pos = (progress.toLong() * duration) / 1000
                        seekLabel?.text = "${formatTime(pos)} / ${formatTime(duration)}"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeekBarTracking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeekBarTracking = false
                    val duration = videoPlayer?.durationMs ?: return
                    val pos = ((sb?.progress ?: 0).toLong() * duration) / 1000
                    videoPlayer?.seekTo(pos)
                }
            })
        }
        bar.addView(seekBar)

        // Transport + Menu row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }
        for ((label, action) in listOf(
            "-10s" to { videoPlayer?.seekBy(-10000) },
            "Play/Pause" to { videoPlayer?.togglePlayPause() },
            "+10s" to { videoPlayer?.seekBy(10000) }
        )) {
            btnRow.addView(Button(this).apply {
                text = label
                textSize = 14f
                minWidth = 120
                minHeight = 64
                setPadding(16, 12, 16, 12)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener { action() }
            })
        }
        btnRow.addView(Button(this).apply {
            text = "Menu"
            textSize = 16f
            minWidth = 160
            minHeight = 64
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(8, 0, 4, 0)
            layoutParams = lp
            setOnClickListener {
                scrubBarVisible = false
                window.decorView.setBackgroundColor(0xFF000000.toInt())
                showControlPanel()
                menuVisible = true
            }
        })
        bar.addView(btnRow)

        frame.addView(bar)
        setContentView(frame)
        updateSeekBarLoop()
    }

    private fun playVideo(file: File) {
        val session = xrSession ?: run {
            showMessage("XR session not available")
            return
        }

        val metadata = FileNameParser.parse(file)
        currentFile = file
        currentScreenType = metadata.screenType
        currentStereoMode = metadata.stereoMode
        playbackSpeed = 1.0f

        stopPlayback()
        resetAdjustments()

        surfaceEntity = SurfaceEntity.create(
            session = session,
            pose = Pose(),
            shape = shapeForScreenType(currentScreenType),
            stereoMode = sdkStereoMode(currentStereoMode)
        )

        // Trigger tap on surface = play/pause
        try {
            val interactable = InteractableComponent.create(session, mainExecutor) { inputEvent ->
                when (inputEvent.action) {
                    XrInputEvent.Action.DOWN -> {
                        triggerDownTime = SystemClock.uptimeMillis()
                    }
                    XrInputEvent.Action.UP -> {
                        val held = SystemClock.uptimeMillis() - triggerDownTime
                        if (held < TAP_THRESHOLD_MS) {
                            runOnUiThread { videoPlayer?.togglePlayPause() }
                        }
                    }
                    else -> {}
                }
            }
            surfaceEntity?.addComponent(interactable)
        } catch (e: Exception) {
            android.util.Log.e("ChloeVR", "Failed to add interactable", e)
        }

        videoPlayer = VideoPlayer(this).also {
            it.start(file, surfaceEntity!!.getSurface())
        }

        isPlaying = true
        menuVisible = false
        scrubBarVisible = false
        setPanelVisible(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (isPlaying) {
                        if (menuVisible) {
                            menuVisible = false
                            setPanelVisible(false)
                        } else {
                            // Toggle scrub bar
                            toggleScrubBar()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Throttle thumbstick seek to avoid spamming
    private var lastSeekTime = 0L
    private val SEEK_THROTTLE_MS = 400
    private val STICK_DEADZONE = 0.25f

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        // Only intercept thumbstick during playback with control panel open
        if (isPlaying && menuVisible && event.action == android.view.MotionEvent.ACTION_SCROLL) {
            val hScroll = event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)

            // Only handle horizontal seek — let vertical pass through to scroll the list
            if (kotlin.math.abs(hScroll) > STICK_DEADZONE) {
                val now = SystemClock.uptimeMillis()
                if (now - lastSeekTime > SEEK_THROTTLE_MS) {
                    lastSeekTime = now
                    val deltaMs = if (hScroll > 0) seekIncrementSec * 1000L else -seekIncrementSec * 1000L
                    videoPlayer?.seekBy(deltaMs)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun stopPlayback() {
        videoPlayer?.release()
        videoPlayer = null
        surfaceEntity?.dispose()
        surfaceEntity = null
        isPlaying = false
        seekBar = null
        seekLabel = null
    }

    // ── OpenXR Controller Input ──

    // Debounce state for native controller
    private var lastNativeSeekTime = 0L
    private var lastThumbstickActive = false
    private var lastMenuState = false
    private var lastAState = false
    private var lastBState = false
    private var lastXState = false
    private var lastYState = false
    private var lastStickClickL = false
    private var lastStickClickR = false
    private val NATIVE_SEEK_THROTTLE_MS = 400
    private val NATIVE_STICK_DEADZONE = 0.25f

    override fun onControllerState(input: OpenXRInput) {
        if (!isPlaying) return

        val now = SystemClock.uptimeMillis()

        // Determine which hand is grabbing (grip+trigger both held)
        val leftGrab = input.leftSqueeze > 0.5f && input.leftTrigger > 0.5f && input.leftHandValid
        val rightGrab = input.rightSqueeze > 0.5f && input.rightTrigger > 0.5f && input.rightHandValid
        val grabbing = leftGrab || rightGrab

        // Trigger-only (no grip) = zoom mode
        val leftTriggerOnly = input.leftTrigger > 0.5f && input.leftSqueeze <= 0.5f && input.leftHandValid
        val rightTriggerOnly = input.rightTrigger > 0.5f && input.rightSqueeze <= 0.5f && input.rightHandValid
        val triggerOnly = leftTriggerOnly || rightTriggerOnly

        // Use the grabbing hand's pose (prefer right if both)
        val grabHandPos = if (rightGrab) input.rightHandPos
                          else if (leftGrab) input.leftHandPos
                          else null
        val grabHandRot = if (rightGrab) input.rightHandRot
                          else if (leftGrab) input.leftHandRot
                          else null

        val triggerHandPos = if (rightTriggerOnly) input.rightHandPos
                             else if (leftTriggerOnly) input.leftHandPos
                             else null

        if (grabbing && grabHandPos != null && grabHandRot != null) {
            // Hand position drives movement, wrist rotation drives roll only
            isTriggerZooming = false
            if (!isGrabbing) {
                isGrabbing = true
                grabStartHandPos = grabHandPos.copyOf()
                grabStartControllerRoll = controllerRollDeg(grabHandRot)
                grabStartScreenRoll = screenRoll
                grabStartScreenYaw = screenYaw
                grabStartScreenPitch = screenPitch
                grabStartScreenX = screenX
                grabStartScreenY = screenY
                grabStartScreenZ = screenZ
            } else {
                val smooth = 0.7f

                // Roll from wrist twist (geometric)
                var rollDelta = controllerRollDeg(grabHandRot) - grabStartControllerRoll
                while (rollDelta > 180f) rollDelta -= 360f
                while (rollDelta < -180f) rollDelta += 360f
                if (!rollDelta.isNaN()) {
                    val targetRoll = grabStartScreenRoll + rollDelta
                    screenRoll += (targetRoll - screenRoll) * smooth
                }

                if (currentScreenType == ScreenType.FLAT) {
                    val dx = grabHandPos[0] - grabStartHandPos[0]
                    val dy = grabHandPos[1] - grabStartHandPos[1]
                    val scale = kotlin.math.abs(grabStartScreenZ) * 3f // adapts to distance
                    val targetX = grabStartScreenX + dx * scale
                    val targetY = grabStartScreenY + dy * scale
                    screenX += (targetX - screenX) * smooth
                    screenY += (targetY - screenY) * smooth
                } else {
                    val dx = grabHandPos[0] - grabStartHandPos[0]
                    val dy = grabHandPos[1] - grabStartHandPos[1]
                    val targetYaw = grabStartScreenYaw - dx * 120f
                    val targetPitch = grabStartScreenPitch + dy * 120f
                    screenYaw += (targetYaw - screenYaw) * smooth
                    screenPitch += (targetPitch - screenPitch) * smooth
                }
                updateScreenPose()
            }
        } else if (triggerOnly && triggerHandPos != null) {
            // Trigger-only: left/right hand movement = zoom
            isGrabbing = false
            if (!isTriggerZooming) {
                isTriggerZooming = true
                triggerZoomStartHandX = triggerHandPos[0]
                triggerZoomStartZoom = screenZoom
            } else {
                val dx = triggerHandPos[0] - triggerZoomStartHandX
                val targetZoom = (triggerZoomStartZoom + dx * 3f).coerceIn(0.3f, 3f)
                screenZoom += (targetZoom - screenZoom) * 0.25f
                updateScreenScale()
            }
        } else {
            if (isGrabbing) isGrabbing = false
            if (isTriggerZooming) isTriggerZooming = false

            // Thumbstick controls (only when no panel is showing)
            if (!menuVisible && !scrubBarVisible) {
                val hAxis = if (kotlin.math.abs(input.rightThumbX) > kotlin.math.abs(input.leftThumbX))
                    input.rightThumbX else input.leftThumbX
                val vAxis = if (kotlin.math.abs(input.rightThumbY) > kotlin.math.abs(input.leftThumbY))
                    input.rightThumbY else input.leftThumbY

                val hMag = kotlin.math.abs(hAxis)
                val vMag = kotlin.math.abs(vAxis)

                // Dominant axis wins — prevents cross-axis bleed
                if (hMag > NATIVE_STICK_DEADZONE && hMag >= vMag) {
                    if (now - lastNativeSeekTime > NATIVE_SEEK_THROTTLE_MS) {
                        lastNativeSeekTime = now
                        val deltaMs = if (hAxis > 0) seekIncrementSec * 1000L else -seekIncrementSec * 1000L
                        videoPlayer?.seekBy(deltaMs)
                    }
                } else if (vMag > NATIVE_STICK_DEADZONE && vMag > hMag) {
                    screenZoom = (screenZoom + vAxis * 0.02f).coerceIn(0.3f, 3f)
                    updateScreenScale()
                }
            }
        }

        // Menu button → toggle full menu (edge detection)
        if (input.menuButton && !lastMenuState) {
            runOnUiThread { toggleMenu() }
        }
        lastMenuState = input.menuButton

        // A button → play/pause (edge detection)
        if (input.aButton && !lastAState) {
            videoPlayer?.togglePlayPause()
        }
        lastAState = input.aButton

        lastBState = input.bButton
    }

    override fun onDestroy() {
        openXRInput?.stop()
        openXRInput = null
        stopPlayback()
        super.onDestroy()
    }

    // ── Helpers ──

    private fun showMessage(text: String) {
        setContentView(TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}
