package com.ashairfoil.prism

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.InputEvent as XrInputEvent
import androidx.xr.scenecore.InteractableComponent
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment
import androidx.xr.scenecore.AnchorEntity
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.GltfModel
import androidx.xr.scenecore.GltfModelEntity
import androidx.xr.scenecore.KhronosPbrMaterial
import androidx.xr.scenecore.PlaneOrientation
import androidx.xr.scenecore.PlaneSemanticType
import androidx.xr.scenecore.SurfaceEntity
import androidx.xr.scenecore.scene
import com.ashairfoil.prism.data.MediaLibrary
import com.ashairfoil.prism.effects.ColorGradingState
import com.ashairfoil.prism.effects.DepthSimulation
import com.ashairfoil.prism.effects.LensDistortionState
import com.ashairfoil.prism.effects.SpatialAudioEffect
import com.ashairfoil.prism.effects.StereoAdjustmentState
import com.ashairfoil.prism.playback.SubtitleRenderer
import com.ashairfoil.prism.settings.SettingsManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : ComponentActivity(), OpenXRInput.ControllerListener {

    private var xrSession: Session? = null
    private var videoPlayer: VideoPlayer? = null
    private var surfaceEntity: SurfaceEntity? = null
    private var isPlaying = false
    private var menuVisible = true
    private var currentFile: File? = null

    // Native OpenXR controller input
    private var openXRInput: OpenXRInput? = null

    // ── Feature modules ──
    private var mediaLibrary: MediaLibrary? = null
    private val colorGradingState = ColorGradingState()
    private val lensDistortionState = LensDistortionState()
    private val stereoAdjustmentState = StereoAdjustmentState()
    private val depthSimulation = DepthSimulation()
    private val spatialAudio = SpatialAudioEffect()
    private var subtitleRenderer: SubtitleRenderer? = null

    // MediaLibrary state for file picker
    private var mediaEntries: List<MediaLibrary.MediaEntry> = emptyList()
    private var filePickerFilterBy = MediaLibrary.FilterBy.ALL
    private var filePickerSortBy = MediaLibrary.SortBy.NAME
    private var filePickerSortAscending = true

    // Resume position save job
    private var resumeSaveJob: Job? = null

    // Video info overlay
    private var videoInfoOverlay: TextView? = null
    private var videoInfoVisible = false

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
    private val minPlaybackSpeed = 0.25f
    private val maxPlaybackSpeed = 4.0f
    private val playbackSpeedStep = 0.05f
    private var alphaPassthroughEnabled = false
    private var currentDeoAlphaActive = false

    // Thumbstick seek increment (seconds)
    private var seekIncrementSec = 10

    // Grab-to-reposition: grip+trigger, aim direction drives movement, wrist drives roll
    private var isGrabbing = false
    private var grabStartHandPos = floatArrayOf(0f, 0f, 0f)
    private var grabStartAimDir = floatArrayOf(0f, 0f, -1f)
    private var grabStartAimRot = floatArrayOf(0f, 0f, 0f, 1f)
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
    private var triggerOnlyStartTime = 0L
    private val TRIGGER_ZOOM_DELAY_MS = 200L  // delay before zoom engages (avoids grip+trigger race)

    // Screen world position (for flat screens placed in room)
    private var screenX = 0f
    private var screenY = 0f
    private var screenZ = -8f  // default: 8m in front

    // Scrub bar (quick B button access)
    private var scrubBarVisible = false

    // Chroma key
    private val chromaKeyState = ChromaKeyState()
    private var colorPickMode = false
    private var crosshairEntity: SurfaceEntity? = null
    private var currentQuadWidth = 8f
    private var currentQuadHeight = 4.5f

    // Image vs video vs 3D model + file navigation
    private var currentFileIsImage = false
    private var currentFileIsModel = false

    // 3D Model viewer state
    data class PlacedModel(
        val file: File,
        val entity: GltfModelEntity,
        var scale: Float = 1f,
        var anchor: AnchorEntity? = null,  // floor anchor (null = free-floating)
        var isAnchored: Boolean = false
    )
    private var placedModels = mutableListOf<PlacedModel>()
    private var selectedModelIndex = -1
    private var modelMode = false
    private var floorAnchor: AnchorEntity? = null  // shared floor anchor for all models
    private var modelGrabbing = false
    private var modelGrabStartAimDir = floatArrayOf(0f, 0f, -1f)
    private var modelGrabStartAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    private var modelGrabStartPose = Pose()
    private var modelGrabStartScale = 1f
    private var currentPlaylist: List<File> = emptyList()
    private var currentPlaylistIndex = -1
    private var lastFileNavTime = 0L
    private val FILE_NAV_THROTTLE_MS = 500L

    // A/B repeat loop
    private var abRepeatA: Long? = null
    private var abRepeatB: Long? = null
    private var abRepeatBoomerang = false
    private var abReversePhase = false
    private var abReversePos = 0L
    private val AB_REVERSE_STEP_MS = 200L
    private val AB_REVERSE_INTERVAL_MS = 200L
    private var abLabel: TextView? = null

    // File picker state
    private var filePickerActive = false
    private var filePickerLoading = false
    private var filePickerStatusText = ""
    private var filePickerSearchQuery = ""
    private var filePickerProjectionFilter: ScreenType? = null
    private var filePickerStereoFilter: StereoMode? = null
    private var filePickerAlphaOnly = false
    private var filePickerSort = FilePickerSort.NAME
    private var filePickerFiles: List<File> = emptyList()
    private var filePickerScanSession = 0
    private var filePickerScanJob: Job? = null
    private var filePickerResultsContainer: LinearLayout? = null
    private var filePickerCountLabel: TextView? = null
    private var filePickerStatusLabel: TextView? = null
    private val filePickerMetadataCache = mutableMapOf<String, VideoMetadata>()
    private var mediaEntryMap: Map<String, MediaLibrary.MediaEntry> = emptyMap()
    private var filePickerLastRefreshTime = 0L
    private val DISPLAY_LIMIT = 200
    private val SCAN_REFRESH_DEBOUNCE_MS = 500L

    private enum class FilePickerSort {
        NAME,
        NEWEST,
        SIZE,
        LAST_PLAYED,
        RATING
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermission()
    }

    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            onPermissionGranted()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            onPermissionGranted()
        } else {
            showMessage("Storage permission required to browse media files.")
        }
    }

    private fun onPermissionGranted() {
        // Initialize settings persistence
        SettingsManager.init(this)
        loadGlobalSettings()

        // Try native OpenXR first (before Jetpack claims the session)
        initOpenXRInput()
        initXrSession()
        showFilePicker()
    }

    private fun loadGlobalSettings() {
        playbackSpeed = SettingsManager.playbackSpeed
        seekIncrementSec = (SettingsManager.seekIncrementMs / 1000).toInt().coerceIn(5, 60)
        screenZoom = SettingsManager.zoomLevel

        // Load color grading state
        colorGradingState.brightness = SettingsManager.cgBrightness
        colorGradingState.contrast = SettingsManager.cgContrast
        colorGradingState.saturation = SettingsManager.cgSaturation
        colorGradingState.sharpening = SettingsManager.cgSharpening
        colorGradingState.gamma = SettingsManager.cgGamma
        colorGradingState.hueShift = SettingsManager.cgHueShift
        colorGradingState.toneMapMode = SettingsManager.cgToneMapMode
        colorGradingState.enabled = SettingsManager.cgEnabled

        // Load lens distortion state
        lensDistortionState.k1 = SettingsManager.lensK1
        lensDistortionState.k2 = SettingsManager.lensK2
        lensDistortionState.fov = SettingsManager.lensFov
        lensDistortionState.centerX = SettingsManager.lensCx
        lensDistortionState.centerY = SettingsManager.lensCy
        lensDistortionState.enabled = SettingsManager.lensEnabled

        // Load stereo adjustment state
        stereoAdjustmentState.horizontalOffset = SettingsManager.ipdOffset
        stereoAdjustmentState.verticalOffset = SettingsManager.stereoVerticalOffset
        stereoAdjustmentState.enabled = SettingsManager.stereoEnabled

        // Load chroma key state
        chromaKeyState.keyR = SettingsManager.chromaKeyR
        chromaKeyState.keyG = SettingsManager.chromaKeyG
        chromaKeyState.keyB = SettingsManager.chromaKeyB
        chromaKeyState.tolerance = SettingsManager.chromaTolerance
        chromaKeyState.softness = SettingsManager.chromaSoftness
        chromaKeyState.enabled = SettingsManager.chromaEnabled
    }

    private fun saveColorGradingSettings() {
        SettingsManager.cgBrightness = colorGradingState.brightness
        SettingsManager.cgContrast = colorGradingState.contrast
        SettingsManager.cgSaturation = colorGradingState.saturation
        SettingsManager.cgSharpening = colorGradingState.sharpening
        SettingsManager.cgGamma = colorGradingState.gamma
        SettingsManager.cgHueShift = colorGradingState.hueShift
        SettingsManager.cgToneMapMode = colorGradingState.toneMapMode
        SettingsManager.cgEnabled = colorGradingState.enabled
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

        // Create a root FrameLayout for subtitle overlay
        val rootFrame = FrameLayout(this)
        subtitleRenderer = SubtitleRenderer(rootFrame)
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

    private fun showFilePicker(forceRescan: Boolean = false) {
        isPlaying = false
        menuVisible = true
        currentFile = null
        filePickerActive = true

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF111111.toInt())
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 40)
        }

        layout.addView(TextView(this).apply {
            text = "ChloeVR"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 4)
            gravity = Gravity.CENTER
        })

        filePickerCountLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        layout.addView(filePickerCountLabel)

        filePickerStatusLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        layout.addView(filePickerStatusLabel)

        layout.addView(TextView(this).apply {
            text = "Search"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 6)
        })
        layout.addView(EditText(this).apply {
            setText(filePickerSearchQuery)
            hint = "Type to filter files"
            setSingleLine(true)
            setBackgroundColor(0xFF222222.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF777777.toInt())
            setPadding(20, 12, 20, 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString() ?: ""
                    if (query != filePickerSearchQuery) {
                        filePickerSearchQuery = query
                        refreshFilePickerResults()
                    }
                }
            })
        })

        layout.addView(makeSpacer(10))
        layout.addView(makeSectionLabel("Projection Filter"))
        layout.addView(makeToggleRow(
            listOf(
                null to "All",
                ScreenType.DOME_180 to "180°",
                ScreenType.SPHERE_360 to "360°",
                ScreenType.FISHEYE to "Fisheye",
                ScreenType.FLAT to "Flat"
            ),
            selected = { it.first == filePickerProjectionFilter }
        ) { chosen ->
            filePickerProjectionFilter = chosen.first
            refreshFilePickerResults()
        })

        layout.addView(makeSpacer(6))
        layout.addView(makeSectionLabel("Stereo Filter"))
        layout.addView(makeToggleRow(
            listOf(
                null to "All",
                StereoMode.SIDE_BY_SIDE to "SBS",
                StereoMode.TOP_BOTTOM to "T/B",
                StereoMode.MONO to "2D"
            ),
            selected = { it.first == filePickerStereoFilter }
        ) { chosen ->
            filePickerStereoFilter = chosen.first
            refreshFilePickerResults()
        })

        layout.addView(makeSpacer(6))
        layout.addView(makeSectionLabel("Alpha Filter"))
        layout.addView(makeToggleRow(
            listOf(false to "Any", true to "Alpha Only"),
            selected = { it.first == filePickerAlphaOnly }
        ) { chosen ->
            filePickerAlphaOnly = chosen.first
            refreshFilePickerResults()
        })

        layout.addView(makeSpacer(6))
        layout.addView(makeSectionLabel("Library Filter"))
        layout.addView(makeToggleRow(
            listOf(
                MediaLibrary.FilterBy.ALL to "All",
                MediaLibrary.FilterBy.FAVORITES to "Fav",
                MediaLibrary.FilterBy.VR180 to "VR180",
                MediaLibrary.FilterBy.VR360 to "VR360",
                MediaLibrary.FilterBy.UNPLAYED to "New"
            ),
            selected = { it.first == filePickerFilterBy }
        ) { chosen ->
            filePickerFilterBy = chosen.first
            refreshFilePickerResults()
        })

        layout.addView(makeSpacer(6))
        layout.addView(makeSectionLabel("Sort"))
        layout.addView(makeToggleRow(
            listOf(
                FilePickerSort.NAME to "Name",
                FilePickerSort.NEWEST to "Newest",
                FilePickerSort.SIZE to "Size",
                FilePickerSort.LAST_PLAYED to "Recent",
                FilePickerSort.RATING to "Rating"
            ),
            selected = { it.first == filePickerSort }
        ) { chosen ->
            filePickerSort = chosen.first
            refreshFilePickerResults()
        })

        layout.addView(makeSpacer(8))
        layout.addView(makeButtonRow(
            "Clear Filters" to {
                filePickerSearchQuery = ""
                filePickerProjectionFilter = null
                filePickerStereoFilter = null
                filePickerAlphaOnly = false
                filePickerFilterBy = MediaLibrary.FilterBy.ALL
                showFilePicker()
            },
            "Rescan" to { startFilePickerScan(force = true) }
        ))

        filePickerResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        layout.addView(filePickerResultsContainer)

        scrollView.addView(layout)
        setContentView(scrollView)
        setPanelVisible(true)

        if (forceRescan || (filePickerFiles.isEmpty() && !filePickerLoading)) {
            startFilePickerScan(force = forceRescan)
        } else {
            refreshFilePickerResults()
        }
    }

    private fun startFilePickerScan(force: Boolean = false) {
        if (!filePickerActive) return

        filePickerScanJob?.cancel()
        val session = ++filePickerScanSession

        if (force) {
            filePickerFiles = emptyList()
            filePickerMetadataCache.clear()
        }

        filePickerLoading = true
        filePickerStatusText = "Scanning storage..."
        refreshFilePickerResults()

        filePickerScanJob = lifecycleScope.launch(Dispatchers.IO) {
            val finalResults = FilePicker.listVideoFilesProgressive(this@MainActivity) { partial, scannedRoots, totalRoots ->
                runOnUiThread {
                    if (!filePickerActive || session != filePickerScanSession) return@runOnUiThread
                    filePickerFiles = partial
                    filePickerLoading = scannedRoots < totalRoots
                    filePickerStatusText = if (totalRoots > 0) {
                        "Scanning storage... $scannedRoots/$totalRoots roots (${partial.size} files)"
                    } else {
                        "Scanning storage..."
                    }
                    // Debounce UI rebuilds during scan — only refresh every 500ms
                    val now = SystemClock.uptimeMillis()
                    if (now - filePickerLastRefreshTime >= SCAN_REFRESH_DEBOUNCE_MS || scannedRoots >= totalRoots) {
                        filePickerLastRefreshTime = now
                        refreshFilePickerResults()
                    } else {
                        // Still update status text without full rebuild
                        filePickerCountLabel?.text = "${partial.size} found"
                        filePickerStatusLabel?.text = filePickerStatusText
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (!filePickerActive || session != filePickerScanSession) return@withContext
                filePickerFiles = finalResults
                filePickerLoading = false
                filePickerStatusText = ""

                // Build MediaLibrary index from scanned files on IO to avoid blocking UI
                val lib = MediaLibrary(this@MainActivity)
                withContext(Dispatchers.IO) {
                    mediaEntries = lib.buildFromFiles(finalResults)
                    mediaEntryMap = mediaEntries.associateBy { it.file.absolutePath }
                }
                mediaLibrary = lib

                refreshFilePickerResults()
            }
        }
    }

    private fun refreshFilePickerResults() {
        if (!filePickerActive) return

        val container = filePickerResultsContainer ?: return
        val filteredFiles = filePickerFiles.filter { matchesFilePickerFilters(it) }
        val sortedFiles = sortFilePickerFiles(filteredFiles)

        // Apply MediaLibrary filter if active (using map for O(1) lookups)
        val lib = mediaLibrary
        val entryMap = mediaEntryMap
        val allMatchingFiles = if (lib != null && filePickerFilterBy != MediaLibrary.FilterBy.ALL) {
            val matchingPaths = sortedFiles.mapNotNull { entryMap[it.absolutePath] }
            val filteredEntries = lib.filter(matchingPaths, filePickerFilterBy)
            val filteredPaths = filteredEntries.map { it.file.absolutePath }.toSet()
            sortedFiles.filter { it.absolutePath in filteredPaths }
        } else {
            sortedFiles
        }

        // Cap the number of rendered views to prevent ANR on large libraries
        val totalMatchCount = allMatchingFiles.size
        val displayFiles = if (totalMatchCount > DISPLAY_LIMIT) allMatchingFiles.take(DISPLAY_LIMIT) else allMatchingFiles
        val isCapped = totalMatchCount > DISPLAY_LIMIT

        filePickerCountLabel?.text = when {
            filePickerLoading && isCapped -> "Showing $DISPLAY_LIMIT of ${filePickerFiles.size} found"
            filePickerLoading -> "${totalMatchCount} shown / ${filePickerFiles.size} found"
            isCapped -> "Showing $DISPLAY_LIMIT of $totalMatchCount — use search/filters to narrow"
            else -> "$totalMatchCount files"
        }

        val statusText = when {
            filePickerLoading && filePickerFiles.isEmpty() -> {
                if (filePickerStatusText.isNotBlank()) filePickerStatusText else "Scanning storage..."
            }
            filePickerLoading && filePickerStatusText.isNotBlank() -> filePickerStatusText
            filePickerFiles.isEmpty() -> "No media files found.\nSupported: MP4, MKV, WebM, JPG, PNG, WebP\nCheck internal storage or connect a USB drive."
            allMatchingFiles.isEmpty() -> "No files match your current search or filters."
            else -> ""
        }

        filePickerStatusLabel?.text = statusText
        filePickerStatusLabel?.visibility =
            if (statusText.isBlank()) View.GONE else View.VISIBLE

        container.removeAllViews()
        if (filePickerFiles.isEmpty() || displayFiles.isEmpty()) return

        val grouped = displayFiles.groupBy { it.parentFile?.absolutePath ?: "" }.toSortedMap()
        for ((dirPath, dirFiles) in grouped) {
            val dirName = dirPath.let { path ->
                val storageRoot = android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: ""
                if (path.startsWith(storageRoot)) {
                    path.removePrefix(storageRoot).removePrefix("/").ifEmpty { "Internal Storage" }
                } else {
                    path.substringAfterLast("/").ifEmpty { "Storage" }
                }
            }

            container.addView(TextView(this).apply {
                text = dirName
                textSize = 13f
                setTextColor(0xFF4FC3F7.toInt())
                setPadding(8, 16, 0, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            for (file in dirFiles) {
                val metadata = filePickerMetadataCache.getOrPut(file.absolutePath) { FileNameParser.parse(file) }
                val stereoLabel = when (metadata.stereoMode) {
                    StereoMode.SIDE_BY_SIDE -> "SBS"
                    StereoMode.TOP_BOTTOM -> "TB"
                    StereoMode.MONO -> "2D"
                }
                val sizeLabel = formatFileSize(file.length())
                val projLabel = metadata.screenType.displayName

                // MediaLibrary entry for resume/favorite badges (O(1) map lookup)
                val entry = entryMap[file.absolutePath]
                val hasResume = (entry?.resumePositionMs ?: 0) > 0
                val isFavorite = entry?.isFavorite == true

                container.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(if (isFavorite) 0xFF1A2A1A.toInt() else 0xFF1E1E1E.toInt())
                    setPadding(32, 28, 32, 28)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, 8)
                    layoutParams = params
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openFileFromPicker(file) }
                    setOnLongClickListener {
                        // Long press = toggle favorite
                        val newFav = lib?.toggleFavorite(file) ?: false
                        // Update entry in local list
                        entry?.isFavorite = newFav
                        refreshFilePickerResults()
                        true
                    }

                    // Title row with favorite star
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        if (isFavorite) {
                            addView(TextView(this@MainActivity).apply {
                                text = "*"
                                textSize = 18f
                                setTextColor(0xFFFFD600.toInt())
                                setPadding(0, 0, 8, 0)
                            })
                        }

                        addView(TextView(this@MainActivity).apply {
                            text = file.nameWithoutExtension
                            textSize = 16f
                            setTextColor(0xFFE0E0E0.toInt())
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    })

                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 0)

                        addView(makeTag(projLabel, 0xFF1565C0.toInt()))
                        addView(
                            makeTag(
                                stereoLabel,
                                if (stereoLabel == "2D") 0xFF555555.toInt() else 0xFF2E7D32.toInt()
                            )
                        )
                        if (hasResume) {
                            addView(makeTag("RESUME", 0xFF6A1B9A.toInt()))
                        }
                        if (FilePicker.isImageFile(file)) {
                            addView(makeTag("IMG", 0xFF7B1FA2.toInt()))
                        }
                        if (FilePicker.isModelFile(file)) {
                            addView(makeTag("3D", 0xFF00BFA5.toInt()))
                        }
                        if (metadata.hasAlpha) {
                            addView(makeTag("ALPHA", 0xFFD84315.toInt()))
                        }
                        if (entry?.hasSubtitles == true) {
                            addView(makeTag("SUB", 0xFF00838F.toInt()))
                        }
                        addView(TextView(this@MainActivity).apply {
                            text = sizeLabel
                            textSize = 12f
                            setTextColor(0xFF888888.toInt())
                            setPadding(12, 0, 0, 0)
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = file.extension.uppercase()
                            textSize = 12f
                            setTextColor(0xFF666666.toInt())
                            setPadding(12, 0, 0, 0)
                            gravity = Gravity.CENTER_VERTICAL
                        })
                    })
                })
            }
        }
    }

    private fun matchesFilePickerFilters(file: File): Boolean {
        val query = filePickerSearchQuery.trim().lowercase()
        if (query.isNotEmpty()) {
            val haystack = "${file.nameWithoutExtension} ${file.parent ?: ""}".lowercase()
            if (!haystack.contains(query)) return false
        }

        val metadata = filePickerMetadataCache.getOrPut(file.absolutePath) { FileNameParser.parse(file) }
        if (filePickerProjectionFilter != null && metadata.screenType != filePickerProjectionFilter) return false
        if (filePickerStereoFilter != null && metadata.stereoMode != filePickerStereoFilter) return false
        if (filePickerAlphaOnly && !metadata.hasAlpha) return false
        return true
    }

    private fun sortFilePickerFiles(files: List<File>): List<File> {
        val entryMap = mediaEntryMap
        return when (filePickerSort) {
            FilePickerSort.NAME -> files.sortedBy { it.name.lowercase() }
            FilePickerSort.NEWEST -> files.sortedByDescending { it.lastModified() }
            FilePickerSort.SIZE -> files.sortedByDescending { it.length() }
            FilePickerSort.LAST_PLAYED -> {
                files.sortedByDescending { f -> entryMap[f.absolutePath]?.lastPlayedMs ?: 0 }
            }
            FilePickerSort.RATING -> {
                files.sortedByDescending { f -> entryMap[f.absolutePath]?.rating ?: 0 }
            }
        }
    }

    private fun makeTag(text: String, bgColor: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(bgColor)
            setPadding(14, 4, 14, 4)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 6, 0)
            layoutParams = lp
        }
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
            "Play/Pause" to { videoPlayer?.togglePlayPause() },
            "+10s" to { videoPlayer?.seekBy(10000) },
            "+30s" to { videoPlayer?.seekBy(30000) }
        ))

        // A/B repeat
        abLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF66BB6A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 4)
        }
        layout.addView(abLabel)
        updateAbLabel()
        layout.addView(makeButtonRow(
            "Set A" to { setAbPointA() },
            "Set B" to { setAbPointB() },
            "Clear A/B" to { clearAbRepeat() }
        ))
        layout.addView(makeToggleRow(
            listOf(false to "Loop", true to "Boomerang"),
            selected = { it.first == abRepeatBoomerang }
        ) { chosen ->
            setAbModeBoomerang(chosen.first)
        })

        // Speed control + seek increment
        layout.addView(makeSpacer(12))
        layout.addView(makeSpeedRow())
        layout.addView(makeSpacer(8))
        layout.addView(makeSectionLabel("Stick Seek"))
        layout.addView(makeToggleRow(
            listOf(10 to "10s", 20 to "20s", 30 to "30s"),
            selected = { it.first == seekIncrementSec }
        ) { chosen ->
            seekIncrementSec = chosen.first
            SettingsManager.seekIncrementMs = chosen.first * 1000L
        })

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

        // ── Color Grading ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Color"))
        layout.addView(makeColorGradingSection())

        // ── 6DOF Depth Simulation ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("6DOF Depth"))
        layout.addView(makeDepthSimulationSection())

        // ── Spatial Audio ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Spatial Audio"))
        layout.addView(makeSpatialAudioSection())

        // ── Subtitles ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Subtitles"))
        layout.addView(makeSubtitleSection())

        // ── Chroma Key ──
        layout.addView(makeSpacer(16))
        layout.addView(makeSectionLabel("Chroma Key"))
        layout.addView(makeChromaKeySection())

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
            rowParams.setMargins(0, 4, 0, 4)
            layoutParams = rowParams

            for ((i, option) in options.withIndex()) {
                val isSelected = selected(option)
                val btn = Button(this@MainActivity).apply {
                    text = option.second
                    textSize = 16f
                    minHeight = 72
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(if (isSelected) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                    setPadding(20, 16, 20, 16)
                    val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    btnParams.setMargins(5, 0, 5, 0)
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
        val speedMax = ((maxPlaybackSpeed - minPlaybackSpeed) / playbackSpeedStep).toInt()
        val speedProgress = ((playbackSpeed - minPlaybackSpeed) / playbackSpeedStep)
            .coerceIn(0f, speedMax.toFloat())
            .toInt()
        val speedLabel = TextView(this).apply {
            text = "${formatSpeed(playbackSpeed)}x"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.END
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2, 0, 2)
            layoutParams = params

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Speed"
                    textSize = 14f
                    setTextColor(0xFFCCCCCC.toInt())
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                })
                addView(speedLabel)
            })

            addView(SeekBar(this@MainActivity).apply {
                max = speedMax
                progress = speedProgress
                setPadding(0, 2, 0, 2)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val snapped = (minPlaybackSpeed + progress * playbackSpeedStep)
                            .coerceIn(minPlaybackSpeed, maxPlaybackSpeed)
                        val rounded = (kotlin.math.round(snapped * 100f) / 100f)
                        playbackSpeed = rounded
                        videoPlayer?.speed = playbackSpeed
                        speedLabel.text = "${formatSpeed(playbackSpeed)}x"
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        SettingsManager.playbackSpeed = playbackSpeed
                    }
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = "${formatSpeed(minPlaybackSpeed)}x - ${formatSpeed(maxPlaybackSpeed)}x"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.END
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
                    textSize = 16f
                    minHeight = 80
                    setPadding(24, 18, 24, 18)
                    val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    btnParams.setMargins(6, 0, 6, 0)
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
                textSize = 16f
                minWidth = 100
                minHeight = 56
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 0, 4, 0)
                layoutParams = lp
                setOnClickListener { onAdjust(-1f) }
            })

            addView(Button(this@MainActivity).apply {
                text = plusLabel
                textSize = 16f
                minWidth = 100
                minHeight = 56
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

    // ── Chroma Key UI ──

    private fun makeChromaKeySection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Enable/Disable toggle
            val toggleBtn = Button(this@MainActivity).apply {
                text = if (chromaKeyState.enabled) "Chroma Key: ON" else "Chroma Key: OFF"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (chromaKeyState.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 4, 4, 4)
                layoutParams = lp
            }
            toggleBtn.setOnClickListener {
                chromaKeyState.enabled = !chromaKeyState.enabled
                toggleBtn.text = if (chromaKeyState.enabled) "Chroma Key: ON" else "Chroma Key: OFF"
                toggleBtn.setBackgroundColor(if (chromaKeyState.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                restartCurrentVideo()
            }
            addView(toggleBtn)

            // Color preview bar
            val colorPreview = android.view.View(this@MainActivity).apply {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40)
                lp.setMargins(8, 8, 8, 8)
                layoutParams = lp
                setBackgroundColor(
                    android.graphics.Color.rgb(
                        (chromaKeyState.keyR * 255).toInt(),
                        (chromaKeyState.keyG * 255).toInt(),
                        (chromaKeyState.keyB * 255).toInt()
                    )
                )
            }
            addView(colorPreview)
            fun updatePreview() {
                colorPreview.setBackgroundColor(
                    android.graphics.Color.rgb(
                        (chromaKeyState.keyR * 255).toInt(),
                        (chromaKeyState.keyG * 255).toInt(),
                        (chromaKeyState.keyB * 255).toInt()
                    )
                )
            }

            // Pick color from screen
            addView(makeButtonRow(
                "Pick from Screen" to {
                    val session = xrSession ?: return@to
                    // Create crosshair dot
                    crosshairEntity?.dispose()
                    crosshairEntity = SurfaceEntity.create(
                        session, Pose(), SurfaceEntity.Shape.Quad(FloatSize2d(0.15f, 0.15f)),
                        SurfaceEntity.StereoMode.MONO
                    ).also { dot ->
                        val s = dot.getSurface()
                        OpenXRInput.nativeSetSurfaceSize(s, 32, 32)
                        // Render a white crosshair
                        try {
                            val canvas = s.lockCanvas(null)
                            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                strokeWidth = 3f
                                style = android.graphics.Paint.Style.STROKE
                                isAntiAlias = true
                            }
                            val cx = canvas.width / 2f
                            val cy = canvas.height / 2f
                            canvas.drawCircle(cx, cy, 10f, paint)
                            paint.strokeWidth = 2f
                            canvas.drawLine(cx - 14f, cy, cx + 14f, cy, paint)
                            canvas.drawLine(cx, cy - 14f, cx, cy + 14f, paint)
                            s.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            android.util.Log.e("ChloeVR", "Crosshair render failed", e)
                        }
                    }
                    colorPickMode = true
                    menuVisible = false
                    setPanelVisible(false)
                }
            ))

            // Color preset buttons (two rows)
            fun onChromaChange() { updatePreview(); restartCurrentVideo() }
            addView(makeButtonRow(
                "Green" to { chromaKeyState.keyR = 0f; chromaKeyState.keyG = 1f; chromaKeyState.keyB = 0f; onChromaChange() },
                "Blue" to { chromaKeyState.keyR = 0f; chromaKeyState.keyG = 0f; chromaKeyState.keyB = 1f; onChromaChange() },
                "Magenta" to { chromaKeyState.keyR = 1f; chromaKeyState.keyG = 0f; chromaKeyState.keyB = 1f; onChromaChange() }
            ))
            addView(makeButtonRow(
                "Red" to { chromaKeyState.keyR = 1f; chromaKeyState.keyG = 0f; chromaKeyState.keyB = 0f; onChromaChange() },
                "Black" to { chromaKeyState.keyR = 0f; chromaKeyState.keyG = 0f; chromaKeyState.keyB = 0f; onChromaChange() },
                "White" to { chromaKeyState.keyR = 1f; chromaKeyState.keyG = 1f; chromaKeyState.keyB = 1f; onChromaChange() }
            ))

            // RGB +/- controls
            addView(makeAdjustRow("Red", "+", "\u2212") { delta ->
                chromaKeyState.keyR = (chromaKeyState.keyR + delta * 0.05f).coerceIn(0f, 1f)
                onChromaChange()
            })
            addView(makeAdjustRow("Green", "+", "\u2212") { delta ->
                chromaKeyState.keyG = (chromaKeyState.keyG + delta * 0.05f).coerceIn(0f, 1f)
                onChromaChange()
            })
            addView(makeAdjustRow("Blue", "+", "\u2212") { delta ->
                chromaKeyState.keyB = (chromaKeyState.keyB + delta * 0.05f).coerceIn(0f, 1f)
                onChromaChange()
            })

            // Tolerance slider
            addView(makeSpacer(8))
            val tolLabel = TextView(this@MainActivity).apply {
                text = "Tolerance: ${(chromaKeyState.tolerance * 100).toInt()}%"
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                gravity = Gravity.CENTER
            }
            addView(tolLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 100
                progress = (chromaKeyState.tolerance * 100).toInt()
                setPadding(0, 8, 0, 8)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        chromaKeyState.tolerance = progress / 100f
                        tolLabel.text = "Tolerance: ${progress}%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { restartCurrentVideo() }
                })
            })

            // Softness slider
            val softLabel = TextView(this@MainActivity).apply {
                text = "Softness: ${(chromaKeyState.softness * 100).toInt()}%"
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                gravity = Gravity.CENTER
            }
            addView(softLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 100
                progress = (chromaKeyState.softness * 100).toInt()
                setPadding(0, 8, 0, 8)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        chromaKeyState.softness = progress / 100f
                        softLabel.text = "Softness: ${progress}%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { restartCurrentVideo() }
                })
            })
        }
    }

    // ── Color Grading UI ──

    private fun makeColorGradingSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Enable toggle
            val toggleBtn = Button(this@MainActivity).apply {
                text = if (colorGradingState.enabled) "Color Grading: ON" else "Color Grading: OFF"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (colorGradingState.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 4, 4, 4)
                layoutParams = lp
            }
            toggleBtn.setOnClickListener {
                colorGradingState.enabled = !colorGradingState.enabled
                toggleBtn.text = if (colorGradingState.enabled) "Color Grading: ON" else "Color Grading: OFF"
                toggleBtn.setBackgroundColor(if (colorGradingState.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                saveColorGradingSettings()
            }
            addView(toggleBtn)

            // Preset buttons
            addView(makeSpacer(8))
            val presets = SettingsManager.getColorGradingPresets()
            val presetRow1 = presets.take(3)
            val presetRow2 = presets.drop(3).take(3)
            if (presetRow1.isNotEmpty()) {
                addView(makeButtonRow(*presetRow1.map { preset ->
                    preset.name to {
                        colorGradingState.brightness = preset.brightness
                        colorGradingState.contrast = preset.contrast
                        colorGradingState.saturation = preset.saturation
                        colorGradingState.sharpening = preset.sharpening
                        colorGradingState.gamma = preset.gamma
                        colorGradingState.hueShift = preset.hueShift
                        colorGradingState.toneMapMode = preset.toneMapMode
                        colorGradingState.enabled = true
                        saveColorGradingSettings()
                        // Rebuild panel to update sliders
                        showControlPanel()
                    }
                }.toTypedArray()))
            }
            if (presetRow2.isNotEmpty()) {
                addView(makeButtonRow(*presetRow2.map { preset ->
                    preset.name to {
                        colorGradingState.brightness = preset.brightness
                        colorGradingState.contrast = preset.contrast
                        colorGradingState.saturation = preset.saturation
                        colorGradingState.sharpening = preset.sharpening
                        colorGradingState.gamma = preset.gamma
                        colorGradingState.hueShift = preset.hueShift
                        colorGradingState.toneMapMode = preset.toneMapMode
                        colorGradingState.enabled = true
                        saveColorGradingSettings()
                        showControlPanel()
                    }
                }.toTypedArray()))
            }

            // Brightness slider: -1 to 1, default 0
            addView(makeSpacer(8))
            addView(makeEffectSlider("Brightness", -100, 100, (colorGradingState.brightness * 100).toInt()) { value ->
                colorGradingState.brightness = value / 100f
            })

            // Contrast slider: 0 to 2, default 1
            addView(makeEffectSlider("Contrast", 0, 200, (colorGradingState.contrast * 100).toInt()) { value ->
                colorGradingState.contrast = value / 100f
            })

            // Saturation slider: 0 to 2, default 1
            addView(makeEffectSlider("Saturation", 0, 200, (colorGradingState.saturation * 100).toInt()) { value ->
                colorGradingState.saturation = value / 100f
            })

            // Sharpening slider: 0 to 1, default 0
            addView(makeEffectSlider("Sharpening", 0, 100, (colorGradingState.sharpening * 100).toInt()) { value ->
                colorGradingState.sharpening = value / 100f
            })

            // Gamma slider: 0.2 to 3.0, default 1.0 — map 20..300
            addView(makeEffectSlider("Gamma", 20, 300, (colorGradingState.gamma * 100).toInt()) { value ->
                colorGradingState.gamma = value / 100f
            })
        }
    }

    /**
     * Helper: creates a labeled SeekBar for effect parameters.
     * Calls onChanged during drag; saves color grading settings on release.
     */
    private fun makeEffectSlider(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val range = max - min
        val valueLabel = TextView(this).apply {
            text = "$label: $initial"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2, 0, 2)
            layoutParams = params

            addView(valueLabel)
            addView(SeekBar(this@MainActivity).apply {
                this.max = range
                progress = (initial - min).coerceIn(0, range)
                setPadding(0, 4, 0, 4)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueLabel.text = "$label: $value"
                        onChanged(value)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) { saveColorGradingSettings() }
                })
            })
        }
    }

    // ── 6DOF Depth Simulation UI ──

    private fun makeDepthSimulationSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Enable toggle
            val toggleBtn = Button(this@MainActivity).apply {
                text = if (depthSimulation.enabled) "6DOF Depth: ON" else "6DOF Depth: OFF"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (depthSimulation.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 4, 4, 4)
                layoutParams = lp
            }
            toggleBtn.setOnClickListener {
                depthSimulation.enabled = !depthSimulation.enabled
                toggleBtn.text = if (depthSimulation.enabled) "6DOF Depth: ON" else "6DOF Depth: OFF"
                toggleBtn.setBackgroundColor(if (depthSimulation.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
            }
            addView(toggleBtn)

            // X sensitivity: 0 to 0.4
            addView(makeEffectSliderNoSave("X Sensitivity", 0, 40, (depthSimulation.sensitivityX * 100).toInt()) { value ->
                depthSimulation.sensitivityX = value / 100f
            })

            // Y sensitivity: 0 to 0.4
            addView(makeEffectSliderNoSave("Y Sensitivity", 0, 40, (depthSimulation.sensitivityY * 100).toInt()) { value ->
                depthSimulation.sensitivityY = value / 100f
            })

            // Z sensitivity: 0 to 0.4
            addView(makeEffectSliderNoSave("Z Sensitivity", 0, 40, (depthSimulation.sensitivityZ * 100).toInt()) { value ->
                depthSimulation.sensitivityZ = value / 100f
            })

            // Recenter button
            addView(makeSpacer(8))
            addView(makeButtonRow(
                "Recenter" to { depthSimulation.reset() }
            ))
        }
    }

    /**
     * Slider variant that doesn't save color grading on release.
     */
    private fun makeEffectSliderNoSave(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val range = max - min
        val valueLabel = TextView(this).apply {
            text = "$label: $initial"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 2, 0, 2)
            layoutParams = params

            addView(valueLabel)
            addView(SeekBar(this@MainActivity).apply {
                this.max = range
                progress = (initial - min).coerceIn(0, range)
                setPadding(0, 4, 0, 4)
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val value = progress + min
                        valueLabel.text = "$label: $value"
                        onChanged(value)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
    }

    // ── Spatial Audio UI ──

    private fun makeSpatialAudioSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Enable toggle
            val toggleBtn = Button(this@MainActivity).apply {
                text = if (spatialAudio.enabled) "Spatial Audio: ON" else "Spatial Audio: OFF"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (spatialAudio.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 4, 4, 4)
                layoutParams = lp
            }
            toggleBtn.setOnClickListener {
                spatialAudio.updateEnabled(!spatialAudio.enabled)
                toggleBtn.text = if (spatialAudio.enabled) "Spatial Audio: ON" else "Spatial Audio: OFF"
                toggleBtn.setBackgroundColor(if (spatialAudio.enabled) 0xFF1565C0.toInt() else 0xFF333333.toInt())
            }
            addView(toggleBtn)

            // Strength slider: 0 to 1
            addView(makeEffectSliderNoSave("Strength", 0, 100, (spatialAudio.strength * 100).toInt()) { value ->
                spatialAudio.updateStrength(value / 100f)
            })
        }
    }

    // ── Subtitle UI ──

    private fun makeSubtitleSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val renderer = subtitleRenderer
            val hasSubs = renderer?.hasSubtitles() == true

            val toggleBtn = Button(this@MainActivity).apply {
                text = when {
                    !hasSubs -> "Subtitles: No file found"
                    renderer?.isVisible == true -> "Subtitles: ON"
                    else -> "Subtitles: OFF"
                }
                textSize = 16f
                minHeight = 72
                isEnabled = hasSubs
                setBackgroundColor(
                    when {
                        !hasSubs -> 0xFF555555.toInt()
                        renderer?.isVisible == true -> 0xFF1565C0.toInt()
                        else -> 0xFF333333.toInt()
                    }
                )
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(4, 4, 4, 4)
                layoutParams = lp
            }
            if (hasSubs) {
                toggleBtn.setOnClickListener {
                    renderer?.isVisible = !(renderer?.isVisible ?: false)
                    toggleBtn.text = if (renderer?.isVisible == true) "Subtitles: ON" else "Subtitles: OFF"
                    toggleBtn.setBackgroundColor(if (renderer?.isVisible == true) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                }
            }
            addView(toggleBtn)
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

    private fun supportsDeoAlpha(screenType: ScreenType): Boolean {
        return when (screenType) {
            ScreenType.FISHEYE, ScreenType.MKX200, ScreenType.RF52, ScreenType.VRCA220 -> true
            else -> false
        }
    }

    private fun setAlphaPassthroughEnabled(enabled: Boolean) {
        if (alphaPassthroughEnabled == enabled) return

        val scene = xrSession?.scene
        if (scene == null) {
            if (!enabled) alphaPassthroughEnabled = false
            return
        }
        try {
            if (scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
                scene.spatialEnvironment.preferredPassthroughOpacity = if (enabled) {
                    1f
                } else {
                    SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE
                }
                alphaPassthroughEnabled = enabled
            } else if (enabled) {
                android.util.Log.w(
                    "ChloeVR",
                    "Passthrough control unavailable; alpha video transparency will fall back to black"
                )
            } else {
                alphaPassthroughEnabled = false
            }
        } catch (e: Exception) {
            android.util.Log.w("ChloeVR", "Failed to set passthrough opacity", e)
            if (!enabled) alphaPassthroughEnabled = false
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

    // Get forward direction (-Z) from a quaternion
    private fun quatForward(rot: FloatArray): FloatArray {
        val x = rot[0].toDouble(); val y = rot[1].toDouble()
        val z = rot[2].toDouble(); val w = rot[3].toDouble()
        return floatArrayOf(
            (-2.0 * (x * z + w * y)).toFloat(),
            (2.0 * (w * x - y * z)).toFloat(),
            (2.0 * (x * x + y * y) - 1.0).toFloat()
        )
    }

    // Rotate a vector by a quaternion
    private fun quatRotate(rot: FloatArray, v: FloatArray): FloatArray {
        val qx = rot[0].toDouble(); val qy = rot[1].toDouble()
        val qz = rot[2].toDouble(); val qw = rot[3].toDouble()
        val vx = v[0].toDouble(); val vy = v[1].toDouble(); val vz = v[2].toDouble()
        val tx = 2.0 * (qy * vz - qz * vy)
        val ty = 2.0 * (qz * vx - qx * vz)
        val tz = 2.0 * (qx * vy - qy * vx)
        return floatArrayOf(
            (vx + qw * tx + qy * tz - qz * ty).toFloat(),
            (vy + qw * ty + qz * tx - qx * tz).toFloat(),
            (vz + qw * tz + qx * ty - qy * tx).toFloat()
        )
    }

    // Ray-cast against the flat screen quad. Returns UV [0..1, 0..1] or null if miss.
    private fun raycastScreenQuad(rayOrigin: FloatArray, rayDir: FloatArray): FloatArray? {
        if (currentScreenType != ScreenType.FLAT) return null
        val rot = floatArrayOf(0f, 0f, 0f, 1f).also {
            val q = Quaternion.fromEulerAngles(screenPitch, screenYaw, screenRoll)
            it[0] = q.x; it[1] = q.y; it[2] = q.z; it[3] = q.w
        }
        val halfW = currentQuadWidth * screenZoom / 2f
        val halfH = currentQuadHeight * screenZoom / 2f
        val center = floatArrayOf(screenX, screenY, screenZ)
        val normal = quatRotate(rot, floatArrayOf(0f, 0f, 1f))
        val right = quatRotate(rot, floatArrayOf(1f, 0f, 0f))
        val up = quatRotate(rot, floatArrayOf(0f, 1f, 0f))

        val denom = rayDir[0] * normal[0] + rayDir[1] * normal[1] + rayDir[2] * normal[2]
        if (kotlin.math.abs(denom) < 0.0001f) return null

        val dx = center[0] - rayOrigin[0]
        val dy = center[1] - rayOrigin[1]
        val dz = center[2] - rayOrigin[2]
        val t = (dx * normal[0] + dy * normal[1] + dz * normal[2]) / denom
        if (t < 0) return null

        val hitX = rayOrigin[0] + t * rayDir[0] - center[0]
        val hitY = rayOrigin[1] + t * rayDir[1] - center[1]
        val hitZ = rayOrigin[2] + t * rayDir[2] - center[2]

        val localU = (hitX * right[0] + hitY * right[1] + hitZ * right[2]) / halfW
        val localV = (hitX * up[0] + hitY * up[1] + hitZ * up[2]) / halfH

        if (kotlin.math.abs(localU) > 1f || kotlin.math.abs(localV) > 1f) return null

        return floatArrayOf((localU + 1f) / 2f, (1f - localV) / 2f)
    }

    private fun sampleColorAtUV(u: Float, v: Float) {
        val file = currentFile ?: return
        val bitmap: android.graphics.Bitmap?
        if (currentFileIsImage) {
            bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } else {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val timeUs = (videoPlayer?.currentPositionMs ?: 0) * 1000
                bitmap = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            }
        }
        if (bitmap == null) return

        val px = (u * (bitmap.width - 1)).toInt().coerceIn(0, bitmap.width - 1)
        val py = (v * (bitmap.height - 1)).toInt().coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(px, py)
        bitmap.recycle()

        chromaKeyState.keyR = android.graphics.Color.red(pixel) / 255f
        chromaKeyState.keyG = android.graphics.Color.green(pixel) / 255f
        chromaKeyState.keyB = android.graphics.Color.blue(pixel) / 255f
        android.util.Log.d("ChloeVR", "Picked color: R=${chromaKeyState.keyR} G=${chromaKeyState.keyG} B=${chromaKeyState.keyB}")
    }

    // Get up direction (+Y) from a quaternion
    private fun quatUp(rot: FloatArray): FloatArray {
        val x = rot[0].toDouble(); val y = rot[1].toDouble()
        val z = rot[2].toDouble(); val w = rot[3].toDouble()
        return floatArrayOf(
            (2.0 * (x * y + w * z)).toFloat(),
            (1.0 - 2.0 * (x * x + z * z)).toFloat(),
            (2.0 * (y * z - w * x)).toFloat()
        )
    }

    // Extract wrist twist (roll) between two aim orientations using twist-swing decomposition.
    // Returns the rotation around the aim forward axis only, ignoring pitch/yaw changes.
    private fun relativeRollDeg(startRot: FloatArray, currentRot: FloatArray): Float {
        // Q_rel = Q_current * inverse(Q_start)
        val isx = -startRot[0].toDouble(); val isy = -startRot[1].toDouble()
        val isz = -startRot[2].toDouble(); val isw = startRot[3].toDouble()
        val cx = currentRot[0].toDouble(); val cy = currentRot[1].toDouble()
        val cz = currentRot[2].toDouble(); val cw = currentRot[3].toDouble()

        val rw = cw*isw - cx*isx - cy*isy - cz*isz
        val rx = cw*isx + cx*isw + cy*isz - cz*isy
        val ry = cw*isy - cx*isz + cy*isw + cz*isx
        val rz = cw*isz + cx*isy - cy*isx + cz*isw

        // Twist-swing decomposition: project Q_rel vector onto current aim forward
        val fwd = quatForward(currentRot)
        val dot = rx * fwd[0] + ry * fwd[1] + rz * fwd[2]

        // Twist angle = 2 * atan2(projected_length, w_component)
        return Math.toDegrees(2.0 * Math.atan2(dot, rw)).toFloat()
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
        abRepeatA = null
        abRepeatB = null
        abRepeatBoomerang = false
        abReversePhase = false
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
                val pos = player.currentPositionMs
                val dur = player.durationMs

                val a = abRepeatA
                val b = abRepeatB
                if (a != null && b != null) {
                    if (abRepeatBoomerang) {
                        if (!abReversePhase) {
                            if (pos >= b) {
                                abReversePos = b
                                abReversePhase = true
                                videoPlayer?.speed = 0.01f
                            }
                        } else {
                            abReversePos = (abReversePos - AB_REVERSE_STEP_MS).coerceAtLeast(a)
                            player.seekTo(abReversePos)
                            if (abReversePos <= a) {
                                player.seekTo(a)
                                abReversePhase = false
                                videoPlayer?.speed = playbackSpeed
                            }
                        }
                    } else {
                        if (abReversePhase) {
                            abReversePhase = false
                            videoPlayer?.speed = playbackSpeed
                        }
                        if (pos >= b) {
                            player.seekTo(a)
                        }
                    }
                }

                if (!isSeekBarTracking && dur > 0) {
                    seekBar?.progress = ((pos * 1000) / dur).toInt()
                    seekLabel?.text = "${formatTime(pos)} / ${formatTime(dur)}"
                }
                updateAbLabel()
                updateSubtitles()

                val interval = when {
                    a != null && b != null && abRepeatBoomerang && abReversePhase -> AB_REVERSE_INTERVAL_MS
                    a != null && b != null -> 100L
                    else -> 500L
                }
                handler.postDelayed(this, interval)
            }
        })
    }

    private fun setAbPointA() {
        val pos = videoPlayer?.currentPositionMs ?: return
        abRepeatA = pos
        abRepeatB = null
        abReversePhase = false
        updateAbLabel()
    }

    private fun setAbPointB() {
        val pos = videoPlayer?.currentPositionMs ?: return
        val a = abRepeatA ?: return
        if (pos >= a) {
            abRepeatB = pos
        } else {
            abRepeatA = pos
            abRepeatB = a
        }
        abReversePhase = false
        updateAbLabel()
    }

    private fun clearAbRepeat() {
        abRepeatA = null
        abRepeatB = null
        abRepeatBoomerang = false
        if (abReversePhase) {
            abReversePhase = false
            videoPlayer?.speed = playbackSpeed
        }
        updateAbLabel()
    }

    private fun setAbModeBoomerang(enabled: Boolean) {
        abRepeatBoomerang = enabled
        if (abReversePhase) {
            abReversePhase = false
            videoPlayer?.speed = playbackSpeed
        }
        updateAbLabel()
    }

    private fun toggleAbRepeat() {
        when {
            abRepeatA == null -> setAbPointA()
            abRepeatB == null -> setAbPointB()
            !abRepeatBoomerang -> setAbModeBoomerang(true)
            else -> clearAbRepeat()
        }
    }

    private fun updateAbLabel() {
        val a = abRepeatA
        val b = abRepeatB
        abLabel?.text = when {
            a != null && b != null -> {
                val mode = if (abRepeatBoomerang) "Boomerang" else "Loop"
                "A: ${formatTime(a)}  B: ${formatTime(b)}  ($mode)"
            }
            a != null -> "A: ${formatTime(a)}  B: ?"
            else -> ""
        }
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

        // A/B repeat label
        abLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF66BB6A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 2)
        }
        bar.addView(abLabel)
        updateAbLabel()

        // Transport + A/B + Menu row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }
        val abButtonLabel = if (abRepeatBoomerang) "A/B Ping" else "A/B"
        for ((label, action) in listOf(
            "-10s" to { videoPlayer?.seekBy(-10000) },
            "Play/Pause" to { videoPlayer?.togglePlayPause() },
            "+10s" to { videoPlayer?.seekBy(10000) },
            abButtonLabel to { toggleAbRepeat() }
        )) {
            btnRow.addView(Button(this).apply {
                text = label
                textSize = 16f
                minWidth = 120
                minHeight = 72
                setPadding(16, 16, 16, 16)
                if (label == abButtonLabel) {
                    setBackgroundColor(
                        when {
                            abRepeatA == null -> 0xFF333333.toInt()
                            abRepeatBoomerang -> 0xFF6A1B9A.toInt()
                            else -> 0xFF2E7D32.toInt()
                        }
                    )
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(3, 0, 3, 0)
                layoutParams = lp
                setOnClickListener { action() }
            })
        }
        btnRow.addView(Button(this).apply {
            text = "Menu"
            textSize = 16f
            minWidth = 120
            minHeight = 72
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(3, 0, 3, 0)
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

    private fun openFileFromPicker(file: File) {
        if (FilePicker.isModelFile(file)) {
            loadModelFile(file)
            return
        }
        val filtered = filePickerFiles.filter { matchesFilePickerFilters(it) }
        currentPlaylist = sortFilePickerFiles(filtered)
        currentPlaylistIndex = currentPlaylist.indexOf(file).coerceAtLeast(0)
        playFile(file)
    }

    // ── 3D Model Loading ──

    private fun loadModelFile(file: File) {
        val session = xrSession ?: run {
            showMessage("XR session not available")
            return
        }

        // Enter model mode (stop any video playback, keep existing models)
        if (!modelMode) {
            stopPlayback()
            modelMode = true
            currentFileIsModel = true
            isPlaying = false
        }

        // Enable passthrough for AR placement
        setAlphaPassthroughEnabled(true)

        // Close file picker UI
        if (filePickerActive) {
            filePickerActive = false
            filePickerScanJob?.cancel()
            filePickerScanJob = null
            filePickerResultsContainer = null
            filePickerCountLabel = null
            filePickerStatusLabel = null
        }

        // Hide panel during loading
        setPanelVisible(false)
        menuVisible = false

        // Load the GLB model asynchronously
        lifecycleScope.launch {
            try {
                val gltfModel = GltfModel.create(session, android.net.Uri.fromFile(file))

                // Try to find the floor for grounded placement.
                // This works with safety boundary ON or OFF — plane detection uses
                // depth sensors and SLAM, independent of the guardian boundary.
                // Timeout after 3s and fall back to fixed placement if no floor found.
                var anchor: AnchorEntity? = floorAnchor
                if (anchor == null) {
                    try {
                        anchor = AnchorEntity.create(
                            session,
                            androidx.xr.runtime.math.FloatSize2d(0.5f, 0.5f), // min 0.5m plane
                            PlaneOrientation.HORIZONTAL,
                            PlaneSemanticType.FLOOR,
                            java.time.Duration.ofSeconds(3)
                        )
                        floorAnchor = anchor
                        android.util.Log.i("ChloeVR", "Floor plane detected — models will be grounded")
                    } catch (e: Exception) {
                        android.util.Log.w("ChloeVR", "Floor detection failed (boundary off or no planes): ${e.message}")
                        anchor = null
                    }
                }

                // Place model: on floor if found, otherwise 1m up and 2m in front
                val placePose = if (anchor != null) {
                    // On the floor, 2m in front of user, at floor level
                    Pose(Vector3(0f, 0f, -2f), Quaternion.Identity)
                } else {
                    // Fallback: floating at eye height
                    Pose(Vector3(0f, 1f, -2f), Quaternion.Identity)
                }

                val entity = if (anchor != null) {
                    // Parent to floor anchor — model sits ON the real floor
                    GltfModelEntity.create(session, gltfModel, placePose, anchor)
                } else {
                    GltfModelEntity.create(session, gltfModel, placePose)
                }

                // Auto-scale based on bounding box so models appear at a reasonable size
                val bbox = entity.gltfModelBoundingBox
                val extents = bbox.halfExtents
                val maxExtent = maxOf(
                    extents.width * 2f,
                    extents.height * 2f,
                    extents.depth * 2f
                ).coerceAtLeast(0.01f)
                // Target size: ~1.5 meters tall (human-scale for figure models)
                val targetSize = 1.5f
                val autoScale = targetSize / maxExtent
                entity.setScale(autoScale)

                val placed = PlacedModel(file, entity, autoScale, anchor, anchor != null)
                placedModels.add(placed)
                selectedModelIndex = placedModels.size - 1

                val anchorStatus = if (anchor != null) "anchored to floor" else "free-floating"
                android.util.Log.i("ChloeVR", "Model loaded: ${file.name}, scale=$autoScale, $anchorStatus")

                // Show model control panel
                runOnUiThread {
                    showModelPanel()
                    menuVisible = true
                    setPanelVisible(true)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChloeVR", "Failed to load model: ${file.name}", e)
                runOnUiThread {
                    showMessage("Failed to load 3D model: ${e.message}")
                }
            }
        }
    }

    private fun showModelPanel() {
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
            text = "3D Model Viewer"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        })

        // Model count
        layout.addView(TextView(this).apply {
            text = "${placedModels.size} model${if (placedModels.size != 1) "s" else ""} in scene"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 16)
        })

        // List placed models with select buttons
        for ((i, model) in placedModels.withIndex()) {
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

                addView(TextView(this@MainActivity).apply {
                    text = model.file.nameWithoutExtension
                    textSize = 14f
                    setTextColor(0xFFE0E0E0.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(Button(this@MainActivity).apply {
                    text = if (isSelected) "Selected" else "Select"
                    textSize = 12f
                    setOnClickListener {
                        selectedModelIndex = i
                        showModelPanel()
                    }
                })

                addView(Button(this@MainActivity).apply {
                    text = "X"
                    textSize = 12f
                    minWidth = 60
                    setBackgroundColor(0xFF8B0000.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    setOnClickListener {
                        removeModel(i)
                        showModelPanel()
                    }
                })
            })
        }

        // Selected model controls
        if (selectedModelIndex in placedModels.indices) {
            val model = placedModels[selectedModelIndex]

            // Anchor status
            layout.addView(makeSpacer(8))
            layout.addView(TextView(this).apply {
                text = if (model.isAnchored) "Anchored to floor" else "Free-floating"
                textSize = 12f
                setTextColor(if (model.isAnchored) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
                gravity = Gravity.CENTER
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Transform"))
            layout.addView(makeEffectSliderNoSave("Size", 10, 500, (model.scale * 100).toInt()) { value ->
                val newScale = value / 100f
                placedModels.getOrNull(selectedModelIndex)?.let {
                    it.scale = newScale
                    it.entity.setScale(newScale)
                }
            })

            // Height offset (useful for fine-tuning floor contact)
            layout.addView(makeEffectSliderNoSave("Height", -100, 100, 0) { value ->
                placedModels.getOrNull(selectedModelIndex)?.let {
                    val current = it.entity.getPose()
                    it.entity.setPose(Pose(
                        Vector3(current.translation.x, value / 100f, current.translation.z),
                        current.rotation
                    ))
                }
            })

            layout.addView(makeSpacer(8))
            layout.addView(makeSectionLabel("Appearance"))
            layout.addView(makeEffectSliderNoSave("Alpha", 10, 100, 100) { value ->
                placedModels.getOrNull(selectedModelIndex)?.entity?.setAlpha(value / 100f)
            })

            // Environment lighting control (affects all models via IBL)
            layout.addView(makeSpacer(12))
            layout.addView(makeSectionLabel("Lighting"))
            layout.addView(makeEffectSliderNoSave("Passthrough", 0, 100, 100) { value ->
                // Blend between full passthrough (bright, real lighting) and dark void
                try {
                    val scene = xrSession?.scene ?: return@makeEffectSliderNoSave
                    if (scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
                        scene.spatialEnvironment.preferredPassthroughOpacity = value / 100f
                    }
                } catch (e: Exception) {}
            })

            // ── Material (PBR) ──
            // KhronosPbrMaterial lets us override baseColor, metallic, roughness,
            // emissive, clearcoat, sheen — more than ShapesXR exposes
            layout.addView(makeSpacer(12))
            layout.addView(makeSectionLabel("Material Override"))
            layout.addView(makeButtonRow(
                "Matte" to {
                    applyMaterialPreset(selectedModelIndex, metallic = 0f, roughness = 1f)
                },
                "Glossy" to {
                    applyMaterialPreset(selectedModelIndex, metallic = 0.3f, roughness = 0.2f)
                },
                "Chrome" to {
                    applyMaterialPreset(selectedModelIndex, metallic = 1f, roughness = 0.05f)
                },
                "Reset" to {
                    placedModels.getOrNull(selectedModelIndex)?.entity
                        ?.clearMaterialOverride("")
                }
            ))

            layout.addView(makeSpacer(12))
            layout.addView(makeButtonRow(
                "Reset Position" to {
                    placedModels.getOrNull(selectedModelIndex)?.let {
                        val y = if (it.isAnchored) 0f else 1f
                        it.entity.setPose(Pose(Vector3(0f, y, -2f), Quaternion.Identity))
                    }
                },
                "Recenter All" to {
                    for ((idx, m) in placedModels.withIndex()) {
                        val offset = idx * 1.5f - (placedModels.size - 1) * 0.75f
                        m.entity.setPose(Pose(Vector3(offset, 1f, -2f), Quaternion.Identity))
                    }
                }
            ))
        }

        layout.addView(makeSpacer(16))

        // Action buttons
        layout.addView(makeButtonRow(
            "Add Model" to {
                menuVisible = false
                setPanelVisible(false)
                showFilePicker()
            },
            "Clear All" to {
                clearAllModels()
                showFilePicker()
            }
        ))

        layout.addView(makeSpacer(8))
        layout.addView(makeButtonRow(
            "Back to Files" to {
                clearAllModels()
                showFilePicker()
            }
        ))

        // Instructions
        layout.addView(makeSpacer(16))
        layout.addView(TextView(this).apply {
            text = "Controls (ShapesXR-style):\n" +
                    "  Grip = Grab & move model\n" +
                    "  Grip + Stick L/R = Scale (right=bigger)\n" +
                    "  Grip + Stick fwd/back = Push/pull\n" +
                    "  Grip + Wrist twist = Rotate\n" +
                    "  Free stick L/R = Rotate on Y axis\n" +
                    "  A = Play/stop animation\n" +
                    "  Menu = This panel"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 0)
        })

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun removeModel(index: Int) {
        if (index in placedModels.indices) {
            placedModels[index].entity.dispose()
            placedModels.removeAt(index)
            if (selectedModelIndex >= placedModels.size) {
                selectedModelIndex = placedModels.size - 1
            }
        }
        if (placedModels.isEmpty()) {
            modelMode = false
            currentFileIsModel = false
            setAlphaPassthroughEnabled(false)
        }
    }

    private fun applyMaterialPreset(modelIndex: Int, metallic: Float, roughness: Float) {
        val model = placedModels.getOrNull(modelIndex) ?: return
        val session = xrSession ?: return
        lifecycleScope.launch {
            try {
                val material = KhronosPbrMaterial.create(session, AlphaMode.OPAQUE)
                material.setMetallicFactor(metallic)
                material.setRoughnessFactor(roughness)
                model.entity.setMaterialOverride(material, "")
            } catch (e: Exception) {
                android.util.Log.w("ChloeVR", "Material override failed: ${e.message}")
            }
        }
    }

    private fun clearAllModels() {
        for (model in placedModels) {
            model.entity.dispose()
        }
        placedModels.clear()
        selectedModelIndex = -1
        modelMode = false
        currentFileIsModel = false
        floorAnchor?.dispose()
        floorAnchor = null
        setAlphaPassthroughEnabled(false)
    }

    private fun navigateFile(delta: Int) {
        if (currentPlaylist.isEmpty()) return
        val newIndex = (currentPlaylistIndex + delta).coerceIn(0, currentPlaylist.size - 1)
        if (newIndex == currentPlaylistIndex) return
        currentPlaylistIndex = newIndex
        playFile(currentPlaylist[newIndex])
    }

    private fun showImageOnSurface(file: File) {
        android.util.Log.d("ChloeVR", "showImageOnSurface: ${file.name}")
        lifecycleScope.launch(Dispatchers.IO) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            android.util.Log.d("ChloeVR", "Image bounds: ${opts.outWidth}x${opts.outHeight}")
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                android.util.Log.e("ChloeVR", "Invalid image bounds")
                return@launch
            }

            var sampleSize = 1
            val maxDim = 4096
            while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val bitmap = android.graphics.BitmapFactory.decodeFile(
                file.absolutePath,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
            )
            if (bitmap == null) {
                android.util.Log.e("ChloeVR", "Bitmap decode null: ${file.absolutePath}")
                return@launch
            }
            android.util.Log.d("ChloeVR", "Decoded: ${bitmap.width}x${bitmap.height} config=${bitmap.config}")

            withContext(Dispatchers.Main) {
                val entity = surfaceEntity
                if (entity == null) {
                    android.util.Log.e("ChloeVR", "surfaceEntity null")
                    bitmap.recycle()
                    return@withContext
                }
                val surface = entity.getSurface()
                android.util.Log.d("ChloeVR", "Surface valid=${surface.isValid}")
                if (!surface.isValid) {
                    bitmap.recycle()
                    return@withContext
                }
                // Set surface buffer size to match image (default is 1x1)
                OpenXRInput.nativeSetSurfaceSize(surface, bitmap.width, bitmap.height)
                android.util.Log.d("ChloeVR", "Surface buffer resized to ${bitmap.width}x${bitmap.height}")
                try {
                    renderBitmapGL(bitmap, surface)
                } catch (e: Exception) {
                    android.util.Log.e("ChloeVR", "Image render failed", e)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun renderBitmapGL(bitmap: android.graphics.Bitmap, surface: android.view.Surface) {
        val TAG = "ChloeVR"

        // EGL setup
        val eglDisplay = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == android.opengl.EGL14.EGL_NO_DISPLAY) {
            android.util.Log.e(TAG, "eglGetDisplay failed")
            return
        }
        val major = IntArray(1)
        val minor = IntArray(1)
        if (!android.opengl.EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
            android.util.Log.e(TAG, "eglInitialize failed: 0x${Integer.toHexString(android.opengl.EGL14.eglGetError())}")
            return
        }
        android.util.Log.d(TAG, "EGL initialized: ${major[0]}.${minor[0]}")

        val configAttribs = intArrayOf(
            android.opengl.EGL14.EGL_RED_SIZE, 8,
            android.opengl.EGL14.EGL_GREEN_SIZE, 8,
            android.opengl.EGL14.EGL_BLUE_SIZE, 8,
            android.opengl.EGL14.EGL_ALPHA_SIZE, 8,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_WINDOW_BIT,
            android.opengl.EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        android.opengl.EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        if (numConfigs[0] == 0 || configs[0] == null) {
            android.util.Log.e(TAG, "eglChooseConfig: no configs found")
            android.opengl.EGL14.eglTerminate(eglDisplay)
            return
        }
        val eglConfig = configs[0]!!
        android.util.Log.d(TAG, "EGL config chosen")

        val contextAttribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE)
        val eglContext = android.opengl.EGL14.eglCreateContext(eglDisplay, eglConfig, android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == android.opengl.EGL14.EGL_NO_CONTEXT) {
            android.util.Log.e(TAG, "eglCreateContext failed: 0x${Integer.toHexString(android.opengl.EGL14.eglGetError())}")
            android.opengl.EGL14.eglTerminate(eglDisplay)
            return
        }
        android.util.Log.d(TAG, "EGL context created")

        val surfaceAttribs = intArrayOf(android.opengl.EGL14.EGL_NONE)
        val eglSurface = android.opengl.EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == android.opengl.EGL14.EGL_NO_SURFACE) {
            android.util.Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(android.opengl.EGL14.eglGetError())}")
            android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext)
            android.opengl.EGL14.eglTerminate(eglDisplay)
            return
        }

        // Query actual surface dimensions
        val sw = IntArray(1)
        val sh = IntArray(1)
        android.opengl.EGL14.eglQuerySurface(eglDisplay, eglSurface, android.opengl.EGL14.EGL_WIDTH, sw, 0)
        android.opengl.EGL14.eglQuerySurface(eglDisplay, eglSurface, android.opengl.EGL14.EGL_HEIGHT, sh, 0)
        android.util.Log.d(TAG, "EGL surface: ${sw[0]}x${sh[0]}")

        if (!android.opengl.EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            android.util.Log.e(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(android.opengl.EGL14.eglGetError())}")
            android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface)
            android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext)
            android.opengl.EGL14.eglTerminate(eglDisplay)
            return
        }

        // Set viewport to actual surface size
        android.opengl.GLES20.glViewport(0, 0, sw[0], sh[0])
        android.util.Log.d(TAG, "Viewport set to ${sw[0]}x${sh[0]}")

        // Upload bitmap as texture
        val texIds = IntArray(1)
        android.opengl.GLES20.glGenTextures(1, texIds, 0)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texIds[0])
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val glErr = android.opengl.GLES20.glGetError()
        android.util.Log.d(TAG, "Texture uploaded, texId=${texIds[0]}, glError=0x${Integer.toHexString(glErr)}")

        // Compile shaders
        val vs = "attribute vec4 aPos; attribute vec2 aUV; varying vec2 vUV; void main() { gl_Position = aPos; vUV = aUV; }"
        val fs = "precision mediump float; varying vec2 vUV; uniform sampler2D uTex;" +
            "uniform vec3 uKeyColor; uniform float uTolerance; uniform float uSoftness; uniform float uChromaEnabled;" +
            "void main() {" +
            "  vec4 color = texture2D(uTex, vUV);" +
            "  if (uChromaEnabled > 0.5) {" +
            "    float yP = 0.299*color.r + 0.587*color.g + 0.114*color.b;" +
            "    float cbP = 0.5*(color.b - yP) / 0.886;" +
            "    float crP = 0.5*(color.r - yP) / 0.701;" +
            "    float yK = 0.299*uKeyColor.r + 0.587*uKeyColor.g + 0.114*uKeyColor.b;" +
            "    float cbK = 0.5*(uKeyColor.b - yK) / 0.886;" +
            "    float crK = 0.5*(uKeyColor.r - yK) / 0.701;" +
            "    float dist = distance(vec2(cbP,crP), vec2(cbK,crK));" +
            "    float alpha = smoothstep(uTolerance, uTolerance + uSoftness, dist);" +
            "    color.a *= alpha;" +
            "  }" +
            "  gl_FragColor = color;" +
            "}"

        val vShader = android.opengl.GLES20.glCreateShader(android.opengl.GLES20.GL_VERTEX_SHADER)
        android.opengl.GLES20.glShaderSource(vShader, vs)
        android.opengl.GLES20.glCompileShader(vShader)
        val vsStatus = IntArray(1)
        android.opengl.GLES20.glGetShaderiv(vShader, android.opengl.GLES20.GL_COMPILE_STATUS, vsStatus, 0)
        if (vsStatus[0] == 0) {
            android.util.Log.e(TAG, "Vertex shader error: ${android.opengl.GLES20.glGetShaderInfoLog(vShader)}")
        }

        val fShader = android.opengl.GLES20.glCreateShader(android.opengl.GLES20.GL_FRAGMENT_SHADER)
        android.opengl.GLES20.glShaderSource(fShader, fs)
        android.opengl.GLES20.glCompileShader(fShader)
        val fsStatus = IntArray(1)
        android.opengl.GLES20.glGetShaderiv(fShader, android.opengl.GLES20.GL_COMPILE_STATUS, fsStatus, 0)
        if (fsStatus[0] == 0) {
            android.util.Log.e(TAG, "Fragment shader error: ${android.opengl.GLES20.glGetShaderInfoLog(fShader)}")
        }

        val program = android.opengl.GLES20.glCreateProgram()
        android.opengl.GLES20.glAttachShader(program, vShader)
        android.opengl.GLES20.glAttachShader(program, fShader)
        android.opengl.GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        android.opengl.GLES20.glGetProgramiv(program, android.opengl.GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            android.util.Log.e(TAG, "Program link error: ${android.opengl.GLES20.glGetProgramInfoLog(program)}")
        }
        android.opengl.GLES20.glUseProgram(program)
        android.util.Log.d(TAG, "Shader program ready: vs=${vsStatus[0]} fs=${fsStatus[0]} link=${linkStatus[0]}")

        // Set chroma key uniforms
        android.opengl.GLES20.glUniform3f(
            android.opengl.GLES20.glGetUniformLocation(program, "uKeyColor"),
            chromaKeyState.keyR, chromaKeyState.keyG, chromaKeyState.keyB
        )
        android.opengl.GLES20.glUniform1f(
            android.opengl.GLES20.glGetUniformLocation(program, "uTolerance"),
            chromaKeyState.tolerance
        )
        android.opengl.GLES20.glUniform1f(
            android.opengl.GLES20.glGetUniformLocation(program, "uSoftness"),
            chromaKeyState.softness
        )
        android.opengl.GLES20.glUniform1f(
            android.opengl.GLES20.glGetUniformLocation(program, "uChromaEnabled"),
            if (chromaKeyState.enabled) 1f else 0f
        )

        // Fullscreen quad
        val quadData = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        val buf = java.nio.ByteBuffer.allocateDirect(quadData.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quadData).position(0)

        val aPosLoc = android.opengl.GLES20.glGetAttribLocation(program, "aPos")
        val aUVLoc = android.opengl.GLES20.glGetAttribLocation(program, "aUV")
        android.util.Log.d(TAG, "Attrib locations: aPos=$aPosLoc aUV=$aUVLoc")
        android.opengl.GLES20.glEnableVertexAttribArray(aPosLoc)
        android.opengl.GLES20.glVertexAttribPointer(aPosLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2)
        android.opengl.GLES20.glEnableVertexAttribArray(aUVLoc)
        android.opengl.GLES20.glVertexAttribPointer(aUVLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, buf)

        // Draw
        android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
        android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_SRC_ALPHA, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA)
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 0f)
        android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
        android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val drawErr = android.opengl.GLES20.glGetError()
        android.util.Log.d(TAG, "Draw complete, glError=0x${Integer.toHexString(drawErr)}")

        if (!android.opengl.EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            android.util.Log.e(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(android.opengl.EGL14.eglGetError())}")
        } else {
            android.util.Log.d(TAG, "eglSwapBuffers OK - image should be visible")
        }

        // Cleanup
        android.opengl.GLES20.glDeleteTextures(1, texIds, 0)
        android.opengl.GLES20.glDeleteProgram(program)
        android.opengl.GLES20.glDeleteShader(vShader)
        android.opengl.GLES20.glDeleteShader(fShader)
        android.opengl.EGL14.eglMakeCurrent(eglDisplay, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
        android.opengl.EGL14.eglDestroySurface(eglDisplay, eglSurface)
        android.opengl.EGL14.eglDestroyContext(eglDisplay, eglContext)
        android.opengl.EGL14.eglTerminate(eglDisplay)
        android.util.Log.d(TAG, "EGL cleanup done")
    }

    private fun restartCurrentVideo() {
        val file = currentFile ?: return
        if (currentFileIsImage) {
            // Re-render image with updated chroma key settings
            setAlphaPassthroughEnabled(chromaKeyState.enabled)
            showImageOnSurface(file)
            return
        }
        val pos = videoPlayer?.currentPositionMs ?: 0
        val wasPlaying = videoPlayer?.isPlaying ?: true
        val metadata = FileNameParser.parse(file)
        val useDeoAlphaPacking = metadata.hasAlpha && supportsDeoAlpha(metadata.screenType)
        currentDeoAlphaActive = useDeoAlphaPacking
        setAlphaPassthroughEnabled(useDeoAlphaPacking || chromaKeyState.enabled)
        videoPlayer?.release()
        videoPlayer = VideoPlayer(this).also {
            it.start(
                file, surfaceEntity!!.getSurface(),
                useDeoAlphaPacking = useDeoAlphaPacking,
                chromaKeyState = chromaKeyState,
                colorGradingState = colorGradingState,
                lensDistortionState = lensDistortionState,
                stereoAdjustmentState = stereoAdjustmentState
            )
            it.seekTo(pos)
            it.speed = playbackSpeed
            if (!wasPlaying) it.pause()
        }
    }

    private fun playFile(file: File) {
        val session = xrSession ?: run {
            showMessage("XR session not available")
            return
        }

        if (filePickerActive) {
            filePickerActive = false
            filePickerScanJob?.cancel()
            filePickerScanJob = null
            filePickerResultsContainer = null
            filePickerCountLabel = null
            filePickerStatusLabel = null
        }

        val metadata = FileNameParser.parse(file)
        currentFile = file
        currentFileIsImage = FilePicker.isImageFile(file)
        currentScreenType = metadata.screenType
        currentStereoMode = metadata.stereoMode
        playbackSpeed = 1.0f
        val useDeoAlphaPacking = !currentFileIsImage && metadata.hasAlpha && supportsDeoAlpha(metadata.screenType)
        if (metadata.hasAlpha && !useDeoAlphaPacking && !currentFileIsImage) {
            android.util.Log.w(
                "ChloeVR",
                "Ignoring _ALPHA on non-fisheye projection: ${metadata.screenType}"
            )
        }

        stopPlayback()
        resetAdjustments()
        isGrabbing = false
        isTriggerZooming = false

        // For flat images, match the quad shape to the image aspect ratio
        val shape = if (currentFileIsImage && currentScreenType == ScreenType.FLAT) {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val aspect = opts.outWidth.toFloat() / opts.outHeight.toFloat()
                val maxDim = 6f
                if (aspect >= 1f) {
                    currentQuadWidth = maxDim; currentQuadHeight = maxDim / aspect
                } else {
                    currentQuadWidth = maxDim * aspect; currentQuadHeight = maxDim
                }
                SurfaceEntity.Shape.Quad(FloatSize2d(currentQuadWidth, currentQuadHeight))
            } else {
                currentQuadWidth = 8f; currentQuadHeight = 4.5f
                shapeForScreenType(currentScreenType)
            }
        } else {
            currentQuadWidth = 8f; currentQuadHeight = 4.5f
            shapeForScreenType(currentScreenType)
        }

        surfaceEntity = SurfaceEntity.create(
            session = session,
            pose = Pose(),
            shape = shape,
            stereoMode = sdkStereoMode(currentStereoMode)
        )

        // Note: play/pause is handled by native OpenXR A button only.
        // InteractableComponent was removed because it double-toggles
        // (A button fires both native handler AND interactable select event).

        if (currentFileIsImage) {
            setAlphaPassthroughEnabled(chromaKeyState.enabled)
            showImageOnSurface(file)
        } else {
            currentDeoAlphaActive = useDeoAlphaPacking
            setAlphaPassthroughEnabled(useDeoAlphaPacking || chromaKeyState.enabled)

            // Set stereo layout for IPD adjustment
            stereoAdjustmentState.stereoLayout = when (currentStereoMode) {
                StereoMode.TOP_BOTTOM -> 1
                else -> 0
            }

            videoPlayer = VideoPlayer(this).also {
                it.start(
                    file, surfaceEntity!!.getSurface(),
                    useDeoAlphaPacking = useDeoAlphaPacking,
                    chromaKeyState = chromaKeyState,
                    colorGradingState = colorGradingState,
                    lensDistortionState = lensDistortionState,
                    stereoAdjustmentState = stereoAdjustmentState
                )

                // Resume from saved position
                val resumePos = mediaLibrary?.getResumePosition(file) ?: 0L
                if (resumePos > 0) {
                    it.seekTo(resumePos)
                }

                it.speed = playbackSpeed
            }

            // Record playback in media library
            mediaLibrary?.recordPlayback(file)

            // Save as last played file
            SettingsManager.lastPlayedFile = file.absolutePath

            // Attach spatial audio
            try {
                spatialAudio.is360 = (currentScreenType == ScreenType.SPHERE_360)
                videoPlayer?.let { vp ->
                    // Delay briefly to ensure audio session is ready
                    lifecycleScope.launch {
                        delay(200)
                        // ExoPlayer exposes audioSessionId through the underlying player
                        // For now, attach with session 0 which uses output mix
                        spatialAudio.attach(0)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ChloeVR", "Spatial audio attach failed", e)
            }

            // Load subtitles
            subtitleRenderer?.clear()
            subtitleRenderer?.loadForVideo(file)

            // Calibrate depth simulation
            depthSimulation.reset()

            // Start periodic resume position saving
            startResumeSaveLoop(file)
        }

        isPlaying = true
        menuVisible = false
        scrubBarVisible = false
        setPanelVisible(false)

        // Show video info overlay
        if (!currentFileIsImage) {
            showVideoInfoOverlay(file)
        }
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
        if (event.action == android.view.MotionEvent.ACTION_SCROLL) {
            // Amplify vertical scroll for faster menu/file picker scrolling
            val vScroll = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            if (kotlin.math.abs(vScroll) > 0.01f) {
                val scrollView = findScrollView(window.decorView)
                if (scrollView != null) {
                    scrollView.scrollBy(0, -(vScroll * 3000).toInt())
                    return true
                }
            }

            // Horizontal seek during playback with control panel open
            if (isPlaying && menuVisible) {
                val hScroll = event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL)
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
        }
        return super.onGenericMotionEvent(event)
    }

    private fun findScrollView(view: android.view.View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findScrollView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun stopPlayback() {
        // Save resume position before releasing
        val file = currentFile
        val pos = videoPlayer?.currentPositionMs ?: 0
        val dur = videoPlayer?.durationMs ?: 0
        if (file != null && pos > 0 && dur > 0) {
            // Only save if not near the end (within 5% of duration = consider finished)
            if (pos < dur * 0.95) {
                mediaLibrary?.saveResumePosition(file, pos)
                SettingsManager.setResumePosition(file.absolutePath, pos)
            } else {
                // Finished — clear resume
                mediaLibrary?.saveResumePosition(file, 0)
                SettingsManager.clearResumePosition(file.absolutePath)
            }
        }

        // Stop resume save loop
        resumeSaveJob?.cancel()
        resumeSaveJob = null

        // Release spatial audio
        spatialAudio.release()

        // Clear subtitles
        subtitleRenderer?.clear()

        // Dismiss video info overlay
        videoInfoOverlay = null
        videoInfoVisible = false

        setAlphaPassthroughEnabled(false)
        currentDeoAlphaActive = false
        videoPlayer?.release()
        videoPlayer = null
        surfaceEntity?.dispose()
        surfaceEntity = null
        isPlaying = false
        seekBar = null
        seekLabel = null
    }

    // ── Resume Position Save Loop ──

    private fun startResumeSaveLoop(file: File) {
        resumeSaveJob?.cancel()
        resumeSaveJob = lifecycleScope.launch {
            while (true) {
                delay(10_000) // Save every 10 seconds
                val pos = videoPlayer?.currentPositionMs ?: continue
                val dur = videoPlayer?.durationMs ?: continue
                if (pos > 0 && dur > 0 && pos < dur * 0.95) {
                    mediaLibrary?.saveResumePosition(file, pos)
                    SettingsManager.setResumePosition(file.absolutePath, pos)
                }
            }
        }
    }

    // ── Video Info Overlay ──

    private fun showVideoInfoOverlay(file: File) {
        videoInfoVisible = true

        // Gather video metadata
        val sizeStr = formatFileSize(file.length())
        val metadata = FileNameParser.parse(file)
        val projStr = metadata.screenType.displayName
        val stereoStr = when (metadata.stereoMode) {
            StereoMode.SIDE_BY_SIDE -> "SBS"
            StereoMode.TOP_BOTTOM -> "TB"
            StereoMode.MONO -> "Mono"
        }

        // Try to get resolution/codec from MediaMetadataRetriever
        var resolution = ""
        var codec = ""
        var durationStr = ""
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
            val mimeType = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            resolution = if (width.isNotEmpty() && height.isNotEmpty()) "${width}x${height}" else ""
            codec = mimeType.replace("video/", "").uppercase()
            durationStr = if (durationMs > 0) formatTime(durationMs) else ""
            retriever.release()
        } catch (e: Exception) {
            android.util.Log.w("ChloeVR", "MediaMetadataRetriever failed", e)
        }

        val infoText = buildString {
            if (resolution.isNotEmpty()) append(resolution)
            if (codec.isNotEmpty()) { if (isNotEmpty()) append("  |  "); append(codec) }
            if (durationStr.isNotEmpty()) { if (isNotEmpty()) append("  |  "); append(durationStr) }
            if (isNotEmpty()) append("  |  ")
            append(sizeStr)
            append("  |  $projStr $stereoStr")
        }

        // Show as a toast-like overlay that auto-dismisses after 3s
        runOnUiThread {
            val frame = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val infoView = TextView(this).apply {
                text = infoText
                textSize = 14f
                setTextColor(0xFFCCCCCC.toInt())
                setBackgroundColor(0xAA000000.toInt())
                setPadding(24, 12, 24, 12)
                gravity = Gravity.CENTER
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
                lp.topMargin = 32
                layoutParams = lp
            }
            frame.addView(infoView)
            videoInfoOverlay = infoView

            // If no panel is visible, we can temporarily show this
            // The overlay gets cleared when control panel or scrub bar appears
            if (!menuVisible && !scrubBarVisible) {
                setContentView(frame)
                setPanelVisible(true)

                // Auto-hide after 3 seconds
                lifecycleScope.launch {
                    delay(3000)
                    if (videoInfoVisible && !menuVisible && !scrubBarVisible) {
                        videoInfoVisible = false
                        videoInfoOverlay = null
                        setPanelVisible(false)
                    }
                }
            }
        }
    }

    // ── Subtitle Update (called from seek bar loop) ──

    private fun updateSubtitles() {
        val pos = videoPlayer?.currentPositionMs ?: return
        subtitleRenderer?.update(pos)
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

    private var loggedAimState = false
    override fun onControllerState(input: OpenXRInput) {
        // Thumbstick scroll when menu/file picker is showing
        if (menuVisible || filePickerActive) {
            val vAxis = if (kotlin.math.abs(input.rightThumbY) > kotlin.math.abs(input.leftThumbY))
                input.rightThumbY else input.leftThumbY
            if (kotlin.math.abs(vAxis) > NATIVE_STICK_DEADZONE) {
                runOnUiThread {
                    val sv = findScrollView(window.decorView)
                    sv?.scrollBy(0, -(vAxis * 80).toInt())
                }
            }
        }

        if (!isPlaying && !modelMode) return

        // ── 3D Model interaction ──
        if (modelMode && selectedModelIndex in placedModels.indices) {
            val selected = placedModels[selectedModelIndex]
            val entity = selected.entity

            // ── ShapesXR-style controls ──
            // Grip = grab/move, Thumbstick L/R = scale (while grabbing),
            // Thumbstick fwd/back = push/pull, Wrist twist = rotate
            // Trigger = select (future: multi-select)
            // Free thumbstick = rotate model on Y axis

            val leftGripHeld = input.leftSqueeze > 0.5f && input.leftHandValid
            val rightGripHeld = input.rightSqueeze > 0.5f && input.rightHandValid
            val grabbing = leftGripHeld || rightGripHeld

            val grabAimRot = if (rightGripHeld) input.rightAimRot
                             else if (leftGripHeld) input.leftAimRot
                             else null
            val grabAimValid = if (rightGripHeld) input.rightAimValid
                               else if (leftGripHeld) input.leftAimValid
                               else false
            val grabThumbX = if (rightGripHeld) input.rightThumbX
                             else if (leftGripHeld) input.leftThumbX
                             else 0f
            val grabThumbY = if (rightGripHeld) input.rightThumbY
                             else if (leftGripHeld) input.leftThumbY
                             else 0f

            if (grabbing && grabAimValid && grabAimRot != null) {
                val rollRot = grabAimRot
                if (!modelGrabbing) {
                    modelGrabbing = true
                    modelGrabStartAimDir = quatForward(grabAimRot)
                    modelGrabStartAimRot = rollRot.copyOf()
                    modelGrabStartPose = entity.getPose()
                    modelGrabStartScale = selected.scale
                } else {
                    val aimDir = quatForward(grabAimRot)
                    val dAimX = aimDir[0] - modelGrabStartAimDir[0]
                    val dAimY = aimDir[1] - modelGrabStartAimDir[1]
                    val startPos = modelGrabStartPose.translation
                    val dist = 2f

                    // Move model by aim direction delta
                    val newPos = Vector3(
                        startPos.x + dAimX * dist,
                        startPos.y + dAimY * dist,
                        startPos.z
                    )

                    // Thumbstick forward/back while grabbing = push/pull (depth)
                    if (kotlin.math.abs(grabThumbY) > NATIVE_STICK_DEADZONE) {
                        val fwd = quatForward(grabAimRot)
                        val pushSpeed = grabThumbY * 0.05f
                        entity.setPose(Pose(
                            Vector3(
                                entity.getPose().translation.x + fwd[0] * pushSpeed,
                                entity.getPose().translation.y + fwd[1] * pushSpeed,
                                entity.getPose().translation.z + fwd[2] * pushSpeed
                            ),
                            entity.getPose().rotation
                        ))
                    }

                    // Thumbstick left/right while grabbing = scale
                    if (kotlin.math.abs(grabThumbX) > NATIVE_STICK_DEADZONE) {
                        selected.scale = (selected.scale + grabThumbX * 0.03f).coerceIn(0.05f, 10f)
                        entity.setScale(selected.scale)
                    }

                    // Wrist twist = rotate on Y axis (like ShapesXR grab-rotate)
                    var rollDelta = relativeRollDeg(modelGrabStartAimRot, rollRot)
                    while (rollDelta > 180f) rollDelta -= 360f
                    while (rollDelta < -180f) rollDelta += 360f
                    val currentRot = modelGrabStartPose.rotation
                    val newRot = if (!rollDelta.isNaN()) {
                        val rollQuat = Quaternion.fromEulerAngles(0f, rollDelta, 0f)
                        Quaternion(
                            rollQuat.w * currentRot.x + rollQuat.x * currentRot.w + rollQuat.y * currentRot.z - rollQuat.z * currentRot.y,
                            rollQuat.w * currentRot.y - rollQuat.x * currentRot.z + rollQuat.y * currentRot.w + rollQuat.z * currentRot.x,
                            rollQuat.w * currentRot.z + rollQuat.x * currentRot.y - rollQuat.y * currentRot.x + rollQuat.z * currentRot.w,
                            rollQuat.w * currentRot.w - rollQuat.x * currentRot.x - rollQuat.y * currentRot.y - rollQuat.z * currentRot.z
                        )
                    } else currentRot

                    entity.setPose(Pose(newPos, newRot))
                }
            } else {
                modelGrabbing = false
            }

            // Free thumbstick (no grip) = rotate model on Y axis
            if (!grabbing && !menuVisible) {
                val hAxis = if (kotlin.math.abs(input.rightThumbX) > kotlin.math.abs(input.leftThumbX))
                    input.rightThumbX else input.leftThumbX
                if (kotlin.math.abs(hAxis) > NATIVE_STICK_DEADZONE) {
                    val currentPose = entity.getPose()
                    val rotDelta = Quaternion.fromEulerAngles(0f, -hAxis * 2f, 0f)
                    val newRot = Quaternion(
                        rotDelta.w * currentPose.rotation.x + rotDelta.x * currentPose.rotation.w + rotDelta.y * currentPose.rotation.z - rotDelta.z * currentPose.rotation.y,
                        rotDelta.w * currentPose.rotation.y - rotDelta.x * currentPose.rotation.z + rotDelta.y * currentPose.rotation.w + rotDelta.z * currentPose.rotation.x,
                        rotDelta.w * currentPose.rotation.z + rotDelta.x * currentPose.rotation.y - rotDelta.y * currentPose.rotation.x + rotDelta.z * currentPose.rotation.w,
                        rotDelta.w * currentPose.rotation.w - rotDelta.x * currentPose.rotation.x - rotDelta.y * currentPose.rotation.y - rotDelta.z * currentPose.rotation.z
                    )
                    entity.setPose(Pose(currentPose.translation, newRot))
                }
            }

            // A button = toggle animation
            if (input.aButton && !lastAState) {
                val animState = selected.entity.animationState
                if (animState == GltfModelEntity.AnimationState.PLAYING) {
                    selected.entity.stopAnimation()
                } else {
                    selected.entity.startAnimation(true) // loop = true
                }
            }
            lastAState = input.aButton

            // Menu button = toggle model panel
            if (input.menuButton && !lastMenuState) {
                runOnUiThread {
                    if (menuVisible) {
                        menuVisible = false
                        setPanelVisible(false)
                    } else {
                        showModelPanel()
                        menuVisible = true
                        setPanelVisible(true)
                    }
                }
            }
            lastMenuState = input.menuButton

            // B button = toggle scrub-style quick panel
            if (input.bButton && !lastBState) {
                runOnUiThread {
                    if (menuVisible) {
                        menuVisible = false
                        setPanelVisible(false)
                    } else {
                        showModelPanel()
                        menuVisible = true
                        setPanelVisible(true)
                    }
                }
            }
            lastBState = input.bButton

            return // Don't fall through to video controls
        }

        if (!isPlaying) return

        // ── 6DOF depth simulation: feed head position ──
        if (depthSimulation.enabled) {
            val headPos = if (input.rightHandValid) input.rightHandPos
                          else if (input.leftHandValid) input.leftHandPos
                          else null
            if (headPos != null) {
                val adj = depthSimulation.compute(headPos[0], headPos[1], headPos[2])
                // Apply offset to surface entity pose
                val entity = surfaceEntity
                if (entity != null && (adj.offsetX != 0f || adj.offsetY != 0f || adj.scaleZ != 1f)) {
                    val baseRot = Quaternion.fromEulerAngles(screenPitch, screenYaw, screenRoll)
                    val basePos = if (currentScreenType == ScreenType.FLAT) {
                        Vector3(screenX + adj.offsetX, screenY + adj.offsetY, screenZ)
                    } else {
                        Vector3(adj.offsetX, screenHeight + adj.offsetY, screenDepth)
                    }
                    entity.setPose(Pose(basePos, baseRot))
                    if (adj.scaleZ != 1f) {
                        entity.setScale(screenZoom * adj.scaleZ)
                    }
                }
            }
        }

        // ── Spatial audio: feed head yaw ──
        if (spatialAudio.enabled) {
            val aimRot = if (input.rightAimValid) input.rightAimRot
                         else if (input.leftAimValid) input.leftAimRot
                         else null
            if (aimRot != null) {
                // Extract yaw from aim rotation quaternion
                val fwd = quatForward(aimRot)
                val yaw = kotlin.math.atan2(fwd[0].toDouble(), (-fwd[2]).toDouble().coerceAtLeast(0.001)).toFloat()
                spatialAudio.updateHeadYaw(yaw)
            }
        }

        // Color pick mode: aim to preview, trigger to sample color
        if (colorPickMode) {
            val aimRot = if (input.rightAimValid) input.rightAimRot else if (input.leftAimValid) input.leftAimRot else null
            val handPos = if (input.rightAimValid) input.rightHandPos else if (input.leftAimValid) input.leftHandPos else null
            if (aimRot != null && handPos != null) {
                val rayDir = quatForward(aimRot)
                val uv = raycastScreenQuad(handPos, rayDir)
                if (uv != null) {
                    // Move crosshair to hit point
                    val rot = floatArrayOf(0f, 0f, 0f, 1f).also {
                        val q = Quaternion.fromEulerAngles(screenPitch, screenYaw, screenRoll)
                        it[0] = q.x; it[1] = q.y; it[2] = q.z; it[3] = q.w
                    }
                    val localU = uv[0] * 2f - 1f  // [-1, 1]
                    val localV = -(uv[1] * 2f - 1f)
                    val halfW = currentQuadWidth * screenZoom / 2f
                    val halfH = currentQuadHeight * screenZoom / 2f
                    val right = quatRotate(rot, floatArrayOf(1f, 0f, 0f))
                    val up = quatRotate(rot, floatArrayOf(0f, 1f, 0f))
                    val normal = quatRotate(rot, floatArrayOf(0f, 0f, 1f))
                    val hx = screenX + right[0] * localU * halfW + up[0] * localV * halfH + normal[0] * 0.05f
                    val hy = screenY + right[1] * localU * halfW + up[1] * localV * halfH + normal[1] * 0.05f
                    val hz = screenZ + right[2] * localU * halfW + up[2] * localV * halfH + normal[2] * 0.05f
                    crosshairEntity?.setPose(Pose(Vector3(hx, hy, hz), Quaternion.fromEulerAngles(screenPitch, screenYaw, screenRoll)))
                }

                val triggerDown = input.rightTrigger > 0.5f || input.leftTrigger > 0.5f
                if (triggerDown && uv != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        sampleColorAtUV(uv[0], uv[1])
                        withContext(Dispatchers.Main) {
                            colorPickMode = false
                            crosshairEntity?.dispose()
                            crosshairEntity = null
                            restartCurrentVideo()
                            showControlPanel()
                        }
                    }
                    return
                }
            }
            return
        }

        if (!loggedAimState && (input.rightHandValid || input.leftHandValid)) {
            loggedAimState = true
            android.util.Log.i("ChloeVR", "Aim pose: leftValid=${input.leftAimValid} rightValid=${input.rightAimValid}")
        }

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
        // Aim rotation (for laser pointer direction)
        val grabAimRot = if (rightGrab) input.rightAimRot
                         else if (leftGrab) input.leftAimRot
                         else null
        val grabAimValid = if (rightGrab) input.rightAimValid
                           else if (leftGrab) input.leftAimValid
                           else false

        val triggerHandPos = if (rightTriggerOnly) input.rightHandPos
                             else if (leftTriggerOnly) input.leftHandPos
                             else null

        if (grabbing && grabHandPos != null) {
            // Aim direction drives screen movement, wrist twist drives roll
            isTriggerZooming = false

            val useAim = grabAimValid && grabAimRot != null
            val rollRot = if (useAim) grabAimRot!! else (if (rightGrab) input.rightHandRot else input.leftHandRot)

            if (!isGrabbing) {
                isGrabbing = true
                grabStartAimDir = if (useAim) quatForward(grabAimRot!!) else floatArrayOf(0f, 0f, -1f)
                grabStartAimRot = rollRot.copyOf()
                grabStartScreenRoll = screenRoll
                grabStartScreenYaw = screenYaw
                grabStartScreenPitch = screenPitch
                grabStartScreenX = screenX
                grabStartScreenY = screenY
                grabStartScreenZ = screenZ
                grabStartHandPos = grabHandPos.copyOf()
            } else {
                val smooth = 0.5f

                // Roll via twist-swing decomposition (immune to pitch/yaw changes)
                var rollDelta = relativeRollDeg(grabStartAimRot, rollRot)
                while (rollDelta > 180f) rollDelta -= 360f
                while (rollDelta < -180f) rollDelta += 360f
                if (!rollDelta.isNaN()) {
                    val targetRoll = grabStartScreenRoll - rollDelta
                    screenRoll += (targetRoll - screenRoll) * smooth
                }

                if (useAim) {
                    // Aim direction delta for screen movement (laser pointer tracking)
                    val aimDir = quatForward(grabAimRot!!)
                    val dAimX = aimDir[0] - grabStartAimDir[0]
                    val dAimY = aimDir[1] - grabStartAimDir[1]

                    if (currentScreenType == ScreenType.FLAT) {
                        val dist = kotlin.math.abs(grabStartScreenZ)
                        val targetX = grabStartScreenX + dAimX * dist
                        val targetY = grabStartScreenY + dAimY * dist
                        screenX += (targetX - screenX) * smooth
                        screenY += (targetY - screenY) * smooth
                    } else {
                        val targetYaw = grabStartScreenYaw - Math.toDegrees(dAimX.toDouble()).toFloat()
                        val targetPitch = grabStartScreenPitch + Math.toDegrees(dAimY.toDouble()).toFloat()
                        screenYaw += (targetYaw - screenYaw) * smooth
                        screenPitch += (targetPitch - screenPitch) * smooth
                    }
                } else {
                    // Fallback: hand position delta (no aim pose available)
                    val dx = grabHandPos[0] - grabStartHandPos[0]
                    val dy = grabHandPos[1] - grabStartHandPos[1]
                    if (currentScreenType == ScreenType.FLAT) {
                        val scale = kotlin.math.abs(grabStartScreenZ) * 3f
                        val targetX = grabStartScreenX + dx * scale
                        val targetY = grabStartScreenY + dy * scale
                        screenX += (targetX - screenX) * smooth
                        screenY += (targetY - screenY) * smooth
                    } else {
                        val targetYaw = grabStartScreenYaw - dx * 120f
                        val targetPitch = grabStartScreenPitch + dy * 120f
                        screenYaw += (targetYaw - screenYaw) * smooth
                        screenPitch += (targetPitch - screenPitch) * smooth
                    }
                }
                updateScreenPose()
            }
        } else if (triggerOnly && triggerHandPos != null) {
            // Trigger-only: left/right hand movement = zoom (with delay to avoid grip+trigger race)
            isGrabbing = false
            if (!isTriggerZooming) {
                if (triggerOnlyStartTime == 0L) {
                    triggerOnlyStartTime = now
                } else if (now - triggerOnlyStartTime > TRIGGER_ZOOM_DELAY_MS) {
                    isTriggerZooming = true
                    triggerZoomStartHandX = triggerHandPos[0]
                    triggerZoomStartZoom = screenZoom
                }
            } else {
                val dx = triggerHandPos[0] - triggerZoomStartHandX
                val targetZoom = (triggerZoomStartZoom + dx * 3f).coerceIn(0.3f, 3f)
                screenZoom += (targetZoom - screenZoom) * 0.25f
                updateScreenScale()
            }
        } else {
            if (isGrabbing) isGrabbing = false
            if (isTriggerZooming) isTriggerZooming = false
            triggerOnlyStartTime = 0L

            // Thumbstick controls (only when no panel is showing)
            if (!menuVisible && !scrubBarVisible) {
                val hAxis = if (kotlin.math.abs(input.rightThumbX) > kotlin.math.abs(input.leftThumbX))
                    input.rightThumbX else input.leftThumbX
                val vAxis = if (kotlin.math.abs(input.rightThumbY) > kotlin.math.abs(input.leftThumbY))
                    input.rightThumbY else input.leftThumbY

                val hMag = kotlin.math.abs(hAxis)
                val vMag = kotlin.math.abs(vAxis)

                // Grip held (no trigger) = file nav modifier for video
                val gripHeld = input.rightSqueeze > 0.5f || input.leftSqueeze > 0.5f

                // Dominant axis wins — prevents cross-axis bleed
                if (hMag > NATIVE_STICK_DEADZONE && hMag >= vMag) {
                    if (currentFileIsImage || gripHeld) {
                        // Image or grip+thumbstick: navigate files
                        if (now - lastFileNavTime > FILE_NAV_THROTTLE_MS) {
                            lastFileNavTime = now
                            val delta = if (hAxis > 0) 1 else -1
                            runOnUiThread { navigateFile(delta) }
                        }
                    } else {
                        // Video (no grip): thumbstick left/right = seek
                        if (now - lastNativeSeekTime > NATIVE_SEEK_THROTTLE_MS) {
                            lastNativeSeekTime = now
                            val deltaMs = if (hAxis > 0) seekIncrementSec * 1000L else -seekIncrementSec * 1000L
                            videoPlayer?.seekBy(deltaMs)
                        }
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

        // X button → A/B repeat toggle (edge detection)
        if (input.xButton && !lastXState) {
            runOnUiThread { toggleAbRepeat() }
        }
        lastXState = input.xButton

        // Y button → toggle video info overlay (edge detection)
        if (input.yButton && !lastYState) {
            runOnUiThread {
                val file = currentFile
                if (file != null && !currentFileIsImage) {
                    if (videoInfoVisible) {
                        videoInfoVisible = false
                        videoInfoOverlay = null
                        if (!menuVisible && !scrubBarVisible) {
                            setPanelVisible(false)
                        }
                    } else {
                        showVideoInfoOverlay(file)
                    }
                }
            }
        }
        lastYState = input.yButton

        lastBState = input.bButton
    }

    override fun onDestroy() {
        filePickerActive = false
        filePickerScanJob?.cancel()
        filePickerScanJob = null
        resumeSaveJob?.cancel()
        resumeSaveJob = null
        spatialAudio.release()
        clearAllModels()
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

    private fun formatSpeed(speed: Float): String {
        val rounded = kotlin.math.round(speed * 100f) / 100f
        val asInt = rounded.toInt()
        return if (rounded == asInt.toFloat()) {
            asInt.toString()
        } else {
            "%.2f".format(rounded).trimEnd('0').trimEnd('.')
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
