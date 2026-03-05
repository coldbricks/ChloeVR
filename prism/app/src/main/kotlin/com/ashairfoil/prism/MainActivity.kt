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
import androidx.xr.scenecore.InputEvent as XrInputEvent
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import java.io.File

class MainActivity : ComponentActivity() {

    private var xrSession: Session? = null
    private var videoPlayer: VideoPlayer? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var isPlaying = false
    private var menuVisible = true
    private var currentFile: File? = null

    // Screen adjustments
    private var screenHeight = 0f
    private var screenDepth = 0f
    private var screenZoom = 1f
    private var screenRoll = 0f

    // Trigger tap detection
    private var triggerDownTime = 0L
    private val TAP_THRESHOLD_MS = 300

    // Seek bar
    private var seekBar: SeekBar? = null
    private var seekLabel: TextView? = null
    private var isSeekBarTracking = false

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
                    text = "${file.name}\n$stereoLabel  |  $sizeLabel"
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
        setMainPanelEnabled(true)
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
                    videoPlayer?.seekBy(pos - (videoPlayer?.currentPositionMs ?: 0))
                }
            })
        }
        layout.addView(seekBar)

        // Seek buttons row
        layout.addView(makeButtonRow(
            "-30s" to { videoPlayer?.seekBy(-30000) },
            "-10s" to { videoPlayer?.seekBy(-10000) },
            "Play/Pause" to { videoPlayer?.togglePlayPause() },
            "+10s" to { videoPlayer?.seekBy(10000) },
            "+30s" to { videoPlayer?.seekBy(30000) }
        ))

        // Spacer
        layout.addView(makeSpacer(24))

        // Screen adjustments header
        layout.addView(TextView(this).apply {
            text = "Screen Adjustments"
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 8)
        })

        // Height
        layout.addView(makeAdjustRow("Height", "▲", "▼") { delta ->
            screenHeight += delta * 0.5f
            updateScreenPose()
        })

        // Depth
        layout.addView(makeAdjustRow("Distance", "Closer", "Farther") { delta ->
            screenDepth += delta * 0.5f
            updateScreenPose()
        })

        // Zoom
        layout.addView(makeAdjustRow("Zoom", "+", "−") { delta ->
            screenZoom = (screenZoom + delta * 0.1f).coerceIn(0.3f, 3f)
            updateScreenScale()
        })

        // Roll
        layout.addView(makeAdjustRow("Roll", "↻", "↺") { delta ->
            screenRoll += delta * 5f
            updateScreenPose()
        })

        // Spacer
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

        // Start updating seek bar
        updateSeekBarLoop()
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

    // ── Screen Adjustments ──

    private fun updateScreenPose() {
        val entity = surfaceEntity ?: return
        val rollRad = Math.toRadians(screenRoll.toDouble()).toFloat()
        entity.setPose(
            Pose(
                Vector3(0f, screenHeight, screenDepth),
                Quaternion.fromAxisAngle(Vector3(0f, 0f, 1f), rollRad)
            )
        )
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

    private fun setMainPanelEnabled(enabled: Boolean) {
        menuVisible = enabled
        try {
            xrSession?.scene?.mainPanelEntity?.setEnabled(enabled)
        } catch (_: Exception) {}
    }

    private fun toggleMenu() {
        if (menuVisible) {
            setMainPanelEnabled(false)
        } else {
            showControlPanel()
            setMainPanelEnabled(true)
        }
    }

    private fun playVideo(file: File) {
        val session = xrSession ?: run {
            showMessage("XR session not available")
            return
        }

        val metadata = FileNameParser.parse(file)
        currentFile = file

        val sdkStereoMode = when (metadata.stereoMode) {
            StereoMode.SIDE_BY_SIDE -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
            StereoMode.TOP_BOTTOM -> SurfaceEntity.StereoMode.TOP_BOTTOM
            StereoMode.MONO -> SurfaceEntity.StereoMode.SIDE_BY_SIDE
        }

        stopPlayback()
        resetAdjustments()

        surfaceEntity = SurfaceEntity.create(
            session = session,
            pose = Pose(),
            shape = SurfaceEntity.Shape.Hemisphere(50.0f),
            stereoMode = sdkStereoMode
        )

        // Controller interaction on hemisphere
        try {
            val interactable = InteractableComponent.create(session, mainExecutor) { inputEvent ->
                when (inputEvent.action) {
                    XrInputEvent.Action.DOWN -> {
                        triggerDownTime = SystemClock.uptimeMillis()
                    }
                    XrInputEvent.Action.UP -> {
                        val held = SystemClock.uptimeMillis() - triggerDownTime
                        if (held < TAP_THRESHOLD_MS) {
                            // Quick tap = play/pause
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
        setMainPanelEnabled(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (isPlaying) {
                        toggleMenu()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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

    override fun onDestroy() {
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
