package com.ashairfoil.prism.scene

import android.graphics.*
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ashairfoil.prism.AudioPlayer
import com.ashairfoil.prism.AudioReactor
import com.ashairfoil.prism.FilamentModelActivity
import com.ashairfoil.prism.ui.ThemeManager

/**
 * Renders the VR menu panel UI to a bitmap for compositor overlay.
 * Owns rendering-specific state (bitmap, flip canvas for GL upload).
 * Reads hover state from InputHandler and shared state from the activity.
 *
 * Extracted from FilamentModelActivity to reduce file size.
 * Also contains buildModelPanelView() and UI helper methods (showMessage, etc.).
 */
class UiRenderer(private val activity: FilamentModelActivity) {

    companion object {
        private const val TAG = "ChloeVR-UiRenderer"
    }

    // ── UI texture / bitmap state (rendering-specific, owned here) ──
    var uiTextureId = 0
    @Volatile var pendingUiBitmap: Bitmap? = null
    var uiFlipBitmap: Bitmap? = null
    var uiFlipCanvas: Canvas? = null
    val uiFlipMatrix = Matrix()
    val bitmapLock = java.util.concurrent.locks.ReentrantLock()  // non-blocking guard for flip buffer

    // Preallocated render bitmap (reused every frame to avoid GC pressure)
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    // On-screen keyboard for save name editor
    internal val keyboard = VirtualKeyboard()

    // Reusable Paint objects for hot-path rendering (avoid per-frame allocations)
    private val tmpPaint = Paint().apply { isAntiAlias = true }
    private val tmpPaint2 = Paint().apply { isAntiAlias = true }

    // Pre-allocated paint bank to eliminate per-frame Paint() allocations in main menu
    private val pBg = Paint().apply { isAntiAlias = true }
    private val pBorder = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val pText = Paint().apply { isAntiAlias = true }
    private val pFill = Paint().apply { isAntiAlias = true }
    private val pGlow = Paint().apply { isAntiAlias = true }
    private val pDim = Paint().apply { isAntiAlias = true }

    // Pre-allocated shaders / paints / filters for menu frame rendering.
    private val bgGradientColors = intArrayOf(ThemeManager.BG_VOID, ThemeManager.BG_PANEL, ThemeManager.BG_VOID)
    private val bgGradientStops = floatArrayOf(0f, 0.5f, 1f)
    private val backgroundPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 1280f, bgGradientColors, bgGradientStops, Shader.TileMode.CLAMP)
    }
    // Pre-rendered overlay bitmap (vignette + scanlines) — drawn once, blitted per-frame
    private val overlayBitmap: Bitmap = Bitmap.createBitmap(1024, 1280, Bitmap.Config.ARGB_8888).also { bmp ->
        val c = Canvas(bmp)
        // Radial vignette: darken corners ~15%
        c.drawRect(0f, 0f, 1024f, 1280f, Paint().apply {
            shader = RadialGradient(512f, 640f, 720f, 0x00000000, 0x26000000, Shader.TileMode.CLAMP)
        })
        // Scanlines: horizontal lines every 4px at 3% white
        val slp = Paint().apply { color = 0x08FFFFFF; strokeWidth = 1f }
        var sy = 0f
        while (sy < 1280f) { c.drawLine(0f, sy, 1024f, sy, slp); sy += 4f }
    }

    private val blurOuter3 = BlurMaskFilter(3f, BlurMaskFilter.Blur.OUTER)
    private val blurOuter6 = BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER)
    private val blurOuter8 = BlurMaskFilter(8f, BlurMaskFilter.Blur.OUTER)
    private val blurOuter12 = BlurMaskFilter(12f, BlurMaskFilter.Blur.OUTER)
    private val blurNormal3 = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
    private val blurNormal4 = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
    private val blurNormal6 = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    private val blurNormal8 = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    private val blurSolid2 = BlurMaskFilter(2f, BlurMaskFilter.Blur.SOLID)

    private val neonBorderGlowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ThemeManager.PINK_SOFT
        maskFilter = blurOuter6
        isAntiAlias = true
    }
    private val neonBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = ThemeManager.BORDER_PINK
        isAntiAlias = true
    }
    private val titleBgPaint = Paint().apply { isAntiAlias = true }
    private val titleGlowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ThemeManager.PINK_SOFT
        maskFilter = blurOuter8
        isAntiAlias = true
    }
    private val titleTextPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_HERO
        isFakeBoldText = true
        maskFilter = blurSolid2
    }
    private val titleHintPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_CAPTION
    }

    private val cursorCirclePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val cursorCrossPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val cursorDotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ThemeManager.PINK_HOT
    }
    private val cursorGlowPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = ThemeManager.PINK_GLOW
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.OUTER)
    }

    private val paramNormalPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY
        color = ThemeManager.TEXT_MID
    }
    private val paramHighlightPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY + 2f
        color = ThemeManager.CYAN_ICE
        isFakeBoldText = true
    }
    private val paramDisabledPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY
        color = ThemeManager.BORDER
    }
    private val paramValuePaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY
        color = ThemeManager.TEXT_BRIGHT
    }
    private val paramValueHighlightPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val sectionLabelPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_LABEL
        letterSpacing = 0.15f
        isFakeBoldText = true
    }
    private val sectionDotPaint = Paint().apply { isAntiAlias = true }
    private val sectionLinePaint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 1.5f
    }

    private val actionButtonGlowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        maskFilter = blurOuter12
        isAntiAlias = true
    }
    private val actionButtonBgPaint = Paint().apply { isAntiAlias = true }
    private val actionButtonBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val actionButtonTextPaint = Paint().apply {
        isAntiAlias = true
        textSize = ThemeManager.PX_BODY
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val sensorHudTextPaint = Paint().apply {
        isAntiAlias = true
        textSize = 20f
        color = ThemeManager.GREEN
        typeface = Typeface.MONOSPACE
    }
    private val sensorHudBgPaint = Paint().apply { color = 0xC0080810.toInt() }
    private val hsvScratch = floatArrayOf(0f, 1f, 1f)

    // Pre-allocated date formatter to avoid per-frame SimpleDateFormat creation
    private val sceneDateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US)

    // All other UI state lives on the activity or inputHandler.
    // Convenience accessors to avoid verbose chains in rendering code:
    private inline val ih get() = activity.inputHandler

    /** Flip-copy renderBitmap into uiFlipBitmap under lock, then publish. */
    private fun publishBitmap(bitmap: Bitmap) {
        val w = bitmap.width; val h = bitmap.height
        bitmapLock.lock()
        try {
            var fb = uiFlipBitmap
            if (fb == null || fb.width != w || fb.height != h) {
                fb?.recycle()
                fb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                uiFlipBitmap = fb
                uiFlipCanvas = Canvas(fb)
                uiFlipMatrix.setScale(1f, -1f, w / 2f, h / 2f)
            }
            (uiFlipCanvas ?: Canvas(fb).also { uiFlipCanvas = it }).drawBitmap(bitmap, uiFlipMatrix, null)
        } finally {
            bitmapLock.unlock()
        }
        pendingUiBitmap = uiFlipBitmap
    }

    // ═══════════════════════════════════════════════════════════════════
    //  renderUiToBitmap — Render HUD text panel to bitmap
    // ═══════════════════════════════════════════════════════════════════

    fun renderUiToBitmap() {
        val uiW = 1024
        val uiH = 1280
        var rb = renderBitmap
        if (rb == null || rb.width != uiW || rb.height != uiH) {
            rb?.recycle()
            rb = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
            renderBitmap = rb
            renderCanvas = Canvas(rb)
        }
        val bitmap = rb
        val canvas = renderCanvas ?: Canvas(rb).also { renderCanvas = it }
        canvas.drawPaint(clearPaint)  // erase previous frame

        // ── Convenience accessors: activity state ──
        val models = activity.sceneManager.models
        val selectedModelIndex = activity.sceneManager.selectedModelIndex
        val renderer = activity.glesRenderer
        val audioReactor = activity.audioReactor
        val audioPlayer = activity.audioPlayer
        val beatReactorEnabled = activity.beatReactorEnabled
        val beatSliders = activity.inputHandler.beatSliders
        val foveationLevel = activity.foveationLevel
        val foveationAvailable = activity.foveationAvailable
        val textureQuality = activity.textureQuality
        val autoAmbient = activity.autoAmbient
        val gridVisible = activity.gridVisible
        val roomLux = activity.roomLux
        val xrLightEstimateAvailable = activity.xrLightEstimateAvailable
        val xrSHAvailable = activity.xrSHAvailable
        val sensorHudVisible = activity.sensorHudVisible
        val sensorDebugStr = activity.sensorDebugStr
        val sensorPollFrame = activity.sensorPollFrame
        val availableGlbFiles = activity.availableGlbFiles
        val availableAudioFiles = activity.availableAudioFiles
        val savedSceneFiles = activity.sceneManager.savedSceneFiles
        val hapticConnected = activity.hapticConnected
        val hapticEnabled = activity.hapticEnabled

        // ── Convenience accessors: input handler hover state ──
        val hoveredMenuParam = ih.hoveredMenuParam
        val hoveredActionButton = ih.hoveredActionButton
        val hoveredGlbIndex = ih.hoveredGlbIndex
        val hoveredSceneIndex = ih.hoveredSceneIndex
        val hoveredSaveButton = ih.hoveredSaveButton
        val hoveredAudioButton = ih.hoveredAudioButton
        val hoveredAudioFileIndex = ih.hoveredAudioFileIndex
        val beatDraggingSlider = ih.beatDraggingSlider
        val draggingPanel = ih.draggingPanel
        val hoveredLightingPresetIndex = ih.hoveredLightingPresetIndex

        // ── Convenience accessors: activity mode state ──
        val audioPlayerMode = activity.audioPlayerMode
        val audioPickerMode = activity.audioPickerMode
        val audioScanInProgress = activity.audioScanInProgress
        val beatSettingsMode = activity.beatSettingsMode
        val glbPickerMode = activity.glbPickerMode
        val scenePickerMode = activity.scenePickerMode
        val lightingPresetMode = activity.lightingPresetMode
        val activeLightingPresetName = activity.activeLightingPresetName
        val saveNameMode = activity.saveNameMode
        val selectedParam = activity.selectedParam
        val audioPickerScrollOffset = activity.audioPickerScrollOffset
        val beatCursorX = activity.beatCursorX
        val beatCursorY = activity.beatCursorY
        val saveNameChars = activity.saveNameChars
        val saveNameLen = activity.saveNameLen
        val saveNameCursor = activity.saveNameCursor
        val glbPickerScrollOffset = activity.glbPickerScrollOffset
        val drawLaserCursorOverlay = {
            if (beatCursorX > 0f && beatCursorY > 0f) {
                // Soft glow ring
                cursorGlowPaint.strokeWidth = 3f
                canvas.drawCircle(beatCursorX, beatCursorY, 14f, cursorGlowPaint)
                // Outer circle
                cursorCirclePaint.strokeWidth = 1.5f
                cursorCirclePaint.color = ThemeManager.TEXT_BRIGHT
                canvas.drawCircle(beatCursorX, beatCursorY, 12f, cursorCirclePaint)
                // Crosshairs
                cursorCrossPaint.strokeWidth = 1f
                cursorCrossPaint.color = (ThemeManager.TEXT_MID and 0x00FFFFFF) or 0x80000000.toInt()
                canvas.drawLine(beatCursorX - 20f, beatCursorY, beatCursorX + 20f, beatCursorY, cursorCrossPaint)
                canvas.drawLine(beatCursorX, beatCursorY - 20f, beatCursorX, beatCursorY + 20f, cursorCrossPaint)
                // Center dot
                canvas.drawCircle(beatCursorX, beatCursorY, 3f, cursorDotPaint)
            }
        }

        // ═══ Velvet Dark Theme ═══
        // Background: BG_VOID → BG_PANEL → BG_VOID gradient
        canvas.drawRect(0f, 0f, uiW.toFloat(), uiH.toFloat(), backgroundPaint)
        // Pre-rendered vignette + scanline overlay (single bitmap blit, no per-frame draw calls)
        canvas.drawBitmap(overlayBitmap, 0f, 0f, null)

        // Neon border glow
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, neonBorderGlowPaint)
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, neonBorderPaint)

        // ── Title bar (drag zone) — illuminates on hover ──
        val titleHovered = hoveredActionButton == 200
        titleBgPaint.color = if (titleHovered) 0x4DFF2D7B.toInt() else 0x26FF2D7B.toInt()
        canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, titleBgPaint)
        if (titleHovered) {
            canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, titleGlowPaint)
        }

        titleTextPaint.color = if (titleHovered) Color.WHITE else ThemeManager.PINK_SOFT
        canvas.drawText("ChloeVR", 50f, 62f, titleTextPaint)
        titleHintPaint.color = if (titleHovered) ThemeManager.PINK_SOFT else ThemeManager.TEXT_DIM
        canvas.drawText(if (draggingPanel) "dragging..." else "grip to drag", uiW - 280f, 56f, titleHintPaint)

        // ═══ BeatReactor Settings ═══
        if (beatSettingsMode) {
            val reactor = audioReactor
            val p = tmpPaint

            // Auto-refresh spectrum every 3 frames while settings panel is open
            if (reactor != null && reactor.isActive && sensorPollFrame % 3 == 0) {
                activity.uiNeedsRefresh = true
            }

            // Header
            p.textSize = ThemeManager.PX_TITLE; p.color = ThemeManager.PINK_HOT; p.isFakeBoldText = true
            canvas.drawText("BEATREACTOR", 50f, 105f, p)

            // FILL display top right
            val fillPct = reactor?.boxFillPct ?: 0f
            p.textSize = ThemeManager.PX_TITLE; p.color = ThemeManager.TEXT_BRIGHT
            canvas.drawText("FILL: ${(fillPct * 100).toInt()}%", 550f, 105f, p)
            p.isFakeBoldText = false

            // ── BPM LOCK + quantization rate (right of mode buttons) ──
            val bpmLocked = reactor?.autoBpm ?: false
            val bpmVal = reactor?.detectedBpm?.toInt() ?: 0
            val bpmRate = reactor?.bpmRate ?: 4
            val rateLabel = "1/$bpmRate"
            val lockHover = hoveredActionButton == 131
            val rateHover = hoveredActionButton == 132
            // LOCK button (top row)
            p.color = when {
                lockHover -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x80000000.toInt()
                bpmLocked -> ThemeManager.GREEN
                else -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x25000000
            }
            canvas.drawRoundRect(820f, 108f, 984f, 133f, 6f, 6f, p)
            p.textAlign = Paint.Align.CENTER
            p.color = if (bpmLocked) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            p.textSize = 18f; p.isFakeBoldText = bpmLocked
            val lockText = if (bpmLocked && bpmVal > 0) "LOCK $bpmVal" else "LOCK BPM"
            canvas.drawText(lockText, 902f, 126f, p)
            p.isFakeBoldText = false
            // RATE cycle button (second row)
            p.color = when {
                rateHover -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x80000000.toInt()
                bpmLocked -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x60000000
                else -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x20000000
            }
            canvas.drawRoundRect(820f, 137f, 984f, 157f, 6f, 6f, p)
            p.color = if (rateHover) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            p.textSize = 14f; p.isFakeBoldText = false
            canvas.drawText("RATE $rateLabel", 902f, 152f, p)
            p.textAlign = Paint.Align.LEFT

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
                    isActive -> ThemeManager.PINK_HOT
                    isHover -> ThemeManager.BORDER_GLOW
                    else -> (ThemeManager.PINK_HOT and 0x00FFFFFF) or 0x20000000
                }
                canvas.drawRoundRect(mx, 108f, mx + mw, 133f, 6f, 6f, p)
                p.textSize = 20f; p.isFakeBoldText = isActive
                p.color = if (isActive || isHover) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(label, mx + mw / 2f, 126f, p)
                p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
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
                    isActive -> ThemeManager.GREEN
                    isHover -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x60000000
                    else -> (ThemeManager.GREEN and 0x00FFFFFF) or 0x20000000
                }
                canvas.drawRoundRect(cx, 130f, cx + cw, 148f, 4f, 4f, p)
                p.color = if (isActive || isHover) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_DIM
                p.isFakeBoldText = isActive
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(clabel, cx + cw / 2f, 145f, p)
                p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
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
                p.color = if (isAct) ThemeManager.PURPLE_DEEP else if (isHov) (ThemeManager.PURPLE_DEEP and 0x00FFFFFF) or 0x60000000 else (ThemeManager.PURPLE_DEEP and 0x00FFFFFF) or 0x20000000
                canvas.drawRoundRect(cx, 130f, cx + sw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_DIM
                p.isFakeBoldText = isAct; p.textSize = 16f
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(slabel, cx + sw / 2f, 145f, p)
                p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
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
                p.color = if (isAct) ThemeManager.ORANGE else if (isHov) (ThemeManager.ORANGE and 0x00FFFFFF) or 0x60000000 else (ThemeManager.ORANGE and 0x00FFFFFF) or 0x20000000
                canvas.drawRoundRect(cx, 130f, cx + bw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_DIM
                p.isFakeBoldText = isAct; p.textSize = 14f
                p.textAlign = Paint.Align.CENTER
                canvas.drawText(blabel, cx + bw / 2f, 145f, p)
                p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
                cx += bw + 4f
            }

            // ── SPECTRUM ANALYZER ──
            val specLeft = 40f; val specRight = uiW - 80f; val specTop = 155f; val specH = 280f
            val specBot = specTop + specH

            // Background
            p.color = ThemeManager.BG_VOID
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
            val binScreenW = specW / (visRange * 64f)
            val gap = 2f.coerceAtMost(binScreenW * 0.3f)
            val barW = (binScreenW - gap).coerceAtLeast(2f)

            for (i in 0 until 64) {
                val binLeft = i.toFloat() / 64f
                val binRight = (i + 1).toFloat() / 64f
                if (binRight < visLeft || binLeft > visRight) continue

                val screenX = specLeft + ((binLeft - visLeft) / visRange) * specW

                val rawLevel = bins[i] * vZoom * expand
                if (rawLevel < 0.003f) continue
                val barH = (rawLevel * specH).coerceAtMost(specH)

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

                val level = rawLevel.coerceAtMost(1f)
                p.color = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
                canvas.drawRect(screenX, specBot - barH, screenX + barW, specBot, p)

                if (barH > 4f) {
                    p.color = (0xDD shl 24) or 0xFFFFFF
                    canvas.drawRect(screenX, specBot - barH, screenX + barW, specBot - barH + 2f, p)
                }
            }

            // Bounding box — mapped to visible range
            if (reactor != null) {
                @Suppress("NAME_SHADOWING")
                val visRange = visRight - visLeft
                val bxL = specLeft + ((reactor.boxLeft - visLeft) / visRange).coerceIn(0f, 1f) * specW
                val bxR = specLeft + ((reactor.boxRight - visLeft) / visRange).coerceIn(0f, 1f) * specW
                val vz = reactor.specVZoom
                val ex = reactor.dynRange
                val bxT = specBot - (reactor.boxTop * vz * ex).coerceAtMost(1f) * specH
                val bxB = specBot - (reactor.boxBottom * vz * ex).coerceAtMost(1f) * specH

                // Fill
                p.color = 0x18FFFF00.toInt()
                canvas.drawRect(bxL, bxT, bxR, bxB, p)

                // All 4 edges
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                p.color = 0xFFFFFF00.toInt()
                canvas.drawRect(bxL, bxT, bxR, bxB, p)
                p.style = Paint.Style.FILL

                // Corner markers at all 4 corners
                val cm = 8f
                p.color = 0xFFFFFF00.toInt()
                canvas.drawRect(bxL - 1f, bxT - 1f, bxL + cm, bxT + cm, p)
                canvas.drawRect(bxR - cm, bxT - 1f, bxR + 1f, bxT + cm, p)
                canvas.drawRect(bxL - 1f, bxB - cm, bxL + cm, bxB + 1f, p)
                canvas.drawRect(bxR - cm, bxB - cm, bxR + 1f, bxB + 1f, p)

                // Threshold line (STROBE mode)
                if (curMode == AudioReactor.Rolloff.INSTANT) {
                    val threshY = specBot - reactor.threshold * specH
                    p.color = 0xFFFF0000.toInt(); p.strokeWidth = 2f
                    canvas.drawLine(specLeft, threshY, specRight, threshY, p)
                    p.textSize = 16f; canvas.drawText("TRIGGER", specRight - 80f, threshY - 4f, p)
                }

                // ── Output meter (right side) ──
                val meterX = specRight + 12f; val meterW = 28f
                p.color = ThemeManager.BG_PANEL
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                p.color = 0x20FFFFFF.toInt(); p.strokeWidth = 1f
                for (pct2 in arrayOf(0.25f, 0.5f, 0.75f)) {
                    val my = specBot - pct2 * specH
                    canvas.drawLine(meterX, my, meterX + meterW, my, p)
                }
                val meterFillH = reactor.boxFillPct * specH
                if (meterFillH > 0f) {
                    val meterFillTop = specBot - meterFillH
                    p.color = ThemeManager.PINK_HOT
                    canvas.drawRoundRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, specBot, 3f, 3f, p)
                    if (meterFillH > 4f) {
                        p.color = ThemeManager.TEXT_BRIGHT
                        canvas.drawRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, meterFillTop + 3f, p)
                    }
                }
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                p.color = 0x80FFFFFF.toInt()
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                p.style = Paint.Style.FILL

                p.color = ThemeManager.TEXT_BRIGHT; p.textSize = ThemeManager.PX_BODY; p.isFakeBoldText = true
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("${(reactor.boxFillPct * 100).toInt()}%", meterX + meterW / 2f, specTop - 8f, p)
                p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false

                // ── Box B (cyan, only when dual motor split active) ──
                if (activity.hapticDualMotorSplit) {
                    val b2L = specLeft + ((reactor.box2Left - visLeft) / visRange).coerceIn(0f, 1f) * specW
                    val b2R = specLeft + ((reactor.box2Right - visLeft) / visRange).coerceIn(0f, 1f) * specW
                    val b2T = specBot - (reactor.box2Top * vz * ex).coerceAtMost(1f) * specH
                    val b2B = specBot - (reactor.box2Bottom * vz * ex).coerceAtMost(1f) * specH

                    // Fill
                    p.color = 0x1800FFFF.toInt()
                    canvas.drawRect(b2L, b2T, b2R, b2B, p)

                    // Edges
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                    p.color = 0xFF00FFFF.toInt()
                    canvas.drawRect(b2L, b2T, b2R, b2B, p)
                    p.style = Paint.Style.FILL

                    // Corner markers
                    p.color = 0xFF00FFFF.toInt()
                    canvas.drawRect(b2L - 1f, b2T - 1f, b2L + cm, b2T + cm, p)
                    canvas.drawRect(b2R - cm, b2T - 1f, b2R + 1f, b2T + cm, p)
                    canvas.drawRect(b2L - 1f, b2B - cm, b2L + cm, b2B + 1f, p)
                    canvas.drawRect(b2R - cm, b2B - cm, b2R + 1f, b2B + 1f, p)

                    // Box B label
                    p.color = 0xFF00FFFF.toInt(); p.textSize = 16f
                    canvas.drawText("B", b2L + 4f, b2T + 14f, p)

                    // Box A label
                    p.color = 0xFFFFFF00.toInt(); p.textSize = 16f
                    canvas.drawText("A", bxL + 4f, bxT + 14f, p)

                    // Box B output meter (far right)
                    val m2X = meterX + meterW + 6f
                    p.color = ThemeManager.BG_PANEL
                    canvas.drawRoundRect(m2X, specTop, m2X + meterW, specBot, 4f, 4f, p)
                    p.color = 0x20FFFFFF.toInt(); p.strokeWidth = 1f
                    for (pct2 in arrayOf(0.25f, 0.5f, 0.75f)) {
                        val my = specBot - pct2 * specH
                        canvas.drawLine(m2X, my, m2X + meterW, my, p)
                    }
                    val m2FillH = reactor.box2FillPct * specH
                    if (m2FillH > 0f) {
                        val m2FillTop = specBot - m2FillH
                        p.color = ThemeManager.CYAN_ICE
                        canvas.drawRoundRect(m2X + 2f, m2FillTop, m2X + meterW - 2f, specBot, 3f, 3f, p)
                        if (m2FillH > 4f) {
                            p.color = ThemeManager.TEXT_BRIGHT
                            canvas.drawRect(m2X + 2f, m2FillTop, m2X + meterW - 2f, m2FillTop + 3f, p)
                        }
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                    p.color = 0x80FFFFFF.toInt()
                    canvas.drawRoundRect(m2X, specTop, m2X + meterW, specBot, 4f, 4f, p)
                    p.style = Paint.Style.FILL

                    p.color = 0xFF00FFFF.toInt(); p.textSize = 18f; p.isFakeBoldText = true
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText("${(reactor.box2FillPct * 100).toInt()}%", m2X + meterW / 2f, specTop - 8f, p)
                    p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
                }
            }

            // Frequency labels (mapped to visible range)
            p.color = ThemeManager.TEXT_DIM; p.textSize = ThemeManager.PX_CAPTION
            p.textAlign = Paint.Align.LEFT
            fun binToHz(norm: Float): String {
                val hz = 20f * Math.pow(1000.0, norm.toDouble()).toFloat()
                return if (hz >= 1000f) "${(hz / 100f).toInt() / 10f}k" else "${hz.toInt()}"
            }
            canvas.drawText(binToHz(visLeft), specLeft, specBot + 16f, p)
            canvas.drawText(binToHz(visLeft + (visRight - visLeft) * 0.33f), specLeft + specW * 0.33f, specBot + 16f, p)
            canvas.drawText(binToHz(visLeft + (visRight - visLeft) * 0.66f), specLeft + specW * 0.66f, specBot + 16f, p)
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(binToHz(visRight), specRight, specBot + 16f, p)
            p.textAlign = Paint.Align.LEFT
            p.textAlign = Paint.Align.LEFT

            // ── SLIDERS (below spectrum) ──
            val sliderAreaTop = specBot + 20f
            val trackLeft = 260f; val trackRight = uiW - 40f; val trackH = 24f
            val labelP = tmpPaint2.apply { textSize = ThemeManager.PX_BODY; color = ThemeManager.TEXT_BRIGHT; isFakeBoldText = false }
            val valP = pText.apply { textSize = 20f; color = ThemeManager.TEXT_MID; isFakeBoldText = false; textAlign = Paint.Align.LEFT }
            var sy = sliderAreaTop

            for ((i, slider) in beatSliders.withIndex()) {
                val isHovered = i == beatDraggingSlider
                val value = slider.get()
                val norm = activity.inputHandler.sliderNorm(slider)

                canvas.drawText(slider.name, 40f, sy + 13f, labelP)
                val valStr = when (slider.unit) {
                    "x" -> "${(value * 10).toInt() / 10f}x"
                    "Hz" -> "${value.toInt()} Hz"
                    "ms" -> "${value.toInt()} ms"
                    else -> "${value.toInt()}%"
                }
                val vw = valP.measureText(valStr)
                canvas.drawText(valStr, trackLeft - vw - 6f, sy + 13f, valP)

                // Track background
                p.color = ThemeManager.BG_PANEL
                canvas.drawRoundRect(trackLeft, sy, trackRight, sy + trackH, 4f, 4f, p)

                if (slider.name == "COLOR") {
                    val segments = 12
                    val segW = (trackRight - trackLeft) / segments
                    for (s in 0 until segments) {
                        val hue = (s.toFloat() / segments) * 360f
                        hsvScratch[0] = hue
                        val rgb = Color.HSVToColor(hsvScratch)
                        p.color = rgb
                        canvas.drawRect(trackLeft + s * segW, sy, trackLeft + (s + 1) * segW, sy + trackH, p)
                    }
                } else {
                    val fillEnd2 = trackLeft + norm * (trackRight - trackLeft)
                    p.color = if (isHovered) ThemeManager.PINK_HOT else ThemeManager.PINK_SOFT
                    canvas.drawRoundRect(trackLeft, sy, fillEnd2, sy + trackH, 4f, 4f, p)
                }

                // Knob
                val fillEnd = trackLeft + norm * (trackRight - trackLeft)
                if (slider.name == "COLOR") {
                    hsvScratch[0] = value
                    val rgb = Color.HSVToColor(hsvScratch)
                    p.color = rgb
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.color = ThemeManager.TEXT_BRIGHT
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.style = Paint.Style.FILL
                } else {
                    p.color = Color.WHITE
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                }

                sy += 28f
            }

            // ── Chloe Vibes presets + script mode (row above the main buttons) ──
            val presetY = uiH - 145f; val presetH = 42f
            val presetNamesRow = arrayOf("LOOSE" to "Loose", "MEDIUM" to "Medium", "ULTIMATE" to "Ultimate")
            val presetIds = intArrayOf(140, 141, 142)
            val currentPreset = reactor?.vibesEngine?.presetName ?: ""
            val useVibes = reactor?.useVibesEngine == true
            val cellW = (uiW - 70f) / 4f
            p.textSize = 18f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
            for (idx in presetNamesRow.indices) {
                val (label, nameKey) = presetNamesRow[idx]
                val isActive = useVibes && currentPreset == nameKey
                val isHover = hoveredActionButton == presetIds[idx]
                val xL = 30f + idx * (cellW + 10f)
                val xR = xL + cellW - 10f
                p.color = when {
                    isActive -> ThemeManager.PINK_HOT
                    isHover -> (ThemeManager.PINK_HOT and 0x00FFFFFF) or 0x80000000.toInt()
                    else -> (ThemeManager.PINK_HOT and 0x00FFFFFF) or 0x20000000
                }
                canvas.drawRoundRect(xL, presetY, xR, presetY + presetH, 8f, 8f, p)
                p.color = if (isActive || isHover) ThemeManager.TEXT_BRIGHT else ThemeManager.PINK_SOFT
                canvas.drawText(label, (xL + xR) / 2f, presetY + 28f, p)
            }
            // Script mode pill (cycle Off → Reactive → Scripted → Mixed)
            val scriptMode = com.ashairfoil.prism.settings.SettingsManager.hapticScriptMode
            val smHover = hoveredActionButton == 143
            val smX = 30f + 3 * (cellW + 10f)
            val smR = uiW - 30f
            p.color = if (smHover) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x80000000.toInt()
                else (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x40000000
            canvas.drawRoundRect(smX, presetY, smR, presetY + presetH, 8f, 8f, p)
            p.color = if (smHover) ThemeManager.TEXT_BRIGHT else ThemeManager.CYAN_ICE
            p.textSize = 16f
            canvas.drawText("SCRIPT: $scriptMode", (smX + smR) / 2f, presetY + 28f, p)
            p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false

            // ── Beat-driven motion controls (two rows) ──
            //  Row A (y 1030-1065): YAW | PITCH | BOB rate cycles | SHUFFLE
            //  Row B (y 1070-1120): SHAKE (legacy A/B) | DANCE (multi-axis)
            //  Moved lower to clear the last slider's thumb glow.
            val selIdx = activity.sceneManager.selectedModelIndex
            val selModel = activity.sceneManager.models.getOrNull(selIdx)

            // ── Row A: per-axis rates + SHUFFLE ──
            fun drawRateCell(x0: Float, x1: Float, label: String, rate: Int, hoverId: Int) {
                val hov = hoveredActionButton == hoverId
                p.color = if (hov) (ThemeManager.PURPLE_DEEP and 0x00FFFFFF) or 0x80000000.toInt()
                    else (ThemeManager.PURPLE_DEEP and 0x00FFFFFF) or 0x30000000
                canvas.drawRoundRect(x0, 1030f, x1, 1065f, 8f, 8f, p)
                p.textAlign = Paint.Align.CENTER; p.textSize = 16f
                p.color = if (hov) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
                canvas.drawText("$label 1/$rate", (x0 + x1) / 2f, 1053f, p)
            }
            drawRateCell(30f, 270f, "YAW", selModel?.danceYawRate ?: 8, 135)
            drawRateCell(280f, 520f, "PITCH", selModel?.dancePitchRate ?: 4, 136)
            drawRateCell(530f, 770f, "BOB", selModel?.danceYRate ?: 2, 137)
            val shufHov = hoveredActionButton == 134
            p.color = if (shufHov) (ThemeManager.ORANGE and 0x00FFFFFF) or 0x80000000.toInt()
                else (ThemeManager.ORANGE and 0x00FFFFFF) or 0x35000000
            canvas.drawRoundRect(780f, 1030f, 994f, 1065f, 8f, 8f, p)
            p.color = if (shufHov) ThemeManager.TEXT_BRIGHT else ThemeManager.ORANGE
            p.isFakeBoldText = shufHov
            canvas.drawText("SHUFFLE", 887f, 1053f, p)
            p.isFakeBoldText = false

            // ── Row B: SHAKE | DANCE | EASE | IMPROV ──
            val rowBY = 1070f; val rowBH = 45f
            p.textAlign = Paint.Align.CENTER
            // SHAKE
            val shakeLabel: String
            val shakeColor: Int
            when {
                selModel == null -> { shakeLabel = "SHAKE"; shakeColor = (ThemeManager.TEXT_DIM and 0x00FFFFFF) or 0x20000000 }
                !selModel.animHasA -> { shakeLabel = "SHAKE \u25B8A"; shakeColor = (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x30000000 }
                !selModel.animHasB -> { shakeLabel = "SHAKE \u25B8B"; shakeColor = (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x50000000 }
                else -> { shakeLabel = "SHAKE \u25CF"; shakeColor = ThemeManager.CYAN_ICE }
            }
            val shakeHover = hoveredActionButton == 130
            p.color = if (shakeHover) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x80000000.toInt() else shakeColor
            canvas.drawRoundRect(30f, rowBY, 240f, rowBY + rowBH, 10f, 10f, p)
            p.textSize = 17f
            p.color = if (shakeHover || selModel?.animHasB == true) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            p.isFakeBoldText = selModel?.animHasB == true
            canvas.drawText(shakeLabel, 135f, rowBY + 29f, p)
            p.isFakeBoldText = false
            // DANCE (wider — primary control)
            val danceLabel: String
            val danceColor: Int
            when {
                selModel == null -> { danceLabel = "DANCE — select a model"; danceColor = (ThemeManager.TEXT_DIM and 0x00FFFFFF) or 0x20000000 }
                !selModel.animHasBase -> { danceLabel = "DANCE \u25B8 Arm"; danceColor = (ThemeManager.PINK_HOT and 0x00FFFFFF) or 0x30000000 }
                else -> { danceLabel = "DANCE \u25CF armed"; danceColor = ThemeManager.PINK_HOT }
            }
            val danceHover = hoveredActionButton == 133
            p.color = if (danceHover) (ThemeManager.PINK_HOT and 0x00FFFFFF) or 0x80000000.toInt() else danceColor
            canvas.drawRoundRect(246f, rowBY, 555f, rowBY + rowBH, 10f, 10f, p)
            p.color = if (danceHover || selModel?.animHasBase == true) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            p.isFakeBoldText = selModel?.animHasBase == true
            canvas.drawText(danceLabel, 400f, rowBY + 29f, p)
            p.isFakeBoldText = false
            // EASE cycle
            val easeLabel = selModel?.danceEase?.name ?: "SINE"
            val easeHover = hoveredActionButton == 138
            p.color = if (easeHover) (ThemeManager.GREEN and 0x00FFFFFF) or 0x80000000.toInt()
                else (ThemeManager.GREEN and 0x00FFFFFF) or 0x30000000
            canvas.drawRoundRect(561f, rowBY, 770f, rowBY + rowBH, 10f, 10f, p)
            p.color = if (easeHover) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            canvas.drawText("EASE $easeLabel", 665f, rowBY + 29f, p)
            // IMPROV toggle
            val improvOn = selModel?.danceImprov == true
            val improvHover = hoveredActionButton == 139
            val improvColor = when {
                improvHover -> (ThemeManager.ORANGE and 0x00FFFFFF) or 0x80000000.toInt()
                improvOn -> ThemeManager.ORANGE
                else -> (ThemeManager.ORANGE and 0x00FFFFFF) or 0x25000000
            }
            p.color = improvColor
            canvas.drawRoundRect(776f, rowBY, uiW - 30f, rowBY + rowBH, 10f, 10f, p)
            p.color = if (improvOn || improvHover) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            p.isFakeBoldText = improvOn
            val improvLabel = if (improvOn) "IMPROV \u25CF ${selModel?.improvBars ?: 4}bar" else "IMPROV"
            canvas.drawText(improvLabel, 885f, rowBY + 29f, p)
            p.isFakeBoldText = false
            p.textAlign = Paint.Align.LEFT

            // ── Buttons: BOOM / VIBES / SPLIT / OFF / BACK ──
            val btnY = uiH - 75f; val btnH = 50f
            val fifthW = (uiW - 130f) / 5f
            p.textSize = 20f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true

            // BOOM button
            val boomReady = reactor?.boomReady ?: false
            p.color = if (hoveredActionButton == 127) (ThemeManager.ORANGE and 0x00FFFFFF) or 0x80000000.toInt()
                else if (boomReady) (ThemeManager.ORANGE and 0x00FFFFFF) or 0x40000000 else (ThemeManager.ORANGE and 0x00FFFFFF) or 0x15000000
            canvas.drawRoundRect(30f, btnY, 30f + fifthW, btnY + btnH, 10f, 10f, p)
            p.color = if (boomReady) ThemeManager.ORANGE else ThemeManager.TEXT_DIM
            canvas.drawText("BOOM", 30f + fifthW / 2f, btnY + 33f, p)

            // VIBES button
            val vibesColor = if (hapticConnected) ThemeManager.PURPLE_DEEP else ThemeManager.PINK_SOFT
            val vibesLabel = if (hapticConnected) "VIBES:ON"
                else if (hapticEnabled) "Scan..."
                else "VIBES"
            p.color = if (hoveredActionButton == 128) (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x80000000.toInt()
                else if (hapticConnected) (ThemeManager.PURPLE_DEEP and 0x00FFFFFF) or 0x40000000 else (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x20000000
            canvas.drawRoundRect(40f + fifthW, btnY, 40f + fifthW * 2f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 128) ThemeManager.TEXT_BRIGHT else vibesColor
            canvas.drawText(vibesLabel, 40f + fifthW * 1.5f, btnY + 33f, p)

            // SPLIT button (dual motor toggle)
            val splitActive = activity.hapticDualMotorSplit
            val splitColor = if (splitActive) ThemeManager.CYAN_ICE else ThemeManager.TEXT_DIM
            p.color = if (hoveredActionButton == 129) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x80000000.toInt()
                else if (splitActive) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x40000000 else (ThemeManager.TEXT_DIM and 0x00FFFFFF) or 0x15000000
            canvas.drawRoundRect(50f + fifthW * 2f, btnY, 50f + fifthW * 3f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 129) ThemeManager.TEXT_BRIGHT else splitColor
            canvas.drawText(if (splitActive) "SPLIT" else "UNIFIED", 50f + fifthW * 2.5f, btnY + 33f, p)

            // OFF button
            p.color = if (hoveredActionButton == 112) (ThemeManager.RED and 0x00FFFFFF) or 0x80000000.toInt() else (ThemeManager.RED and 0x00FFFFFF) or 0x20000000
            canvas.drawRoundRect(60f + fifthW * 3f, btnY, 60f + fifthW * 4f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 112) ThemeManager.TEXT_BRIGHT else ThemeManager.RED
            canvas.drawText("OFF", 60f + fifthW * 3.5f, btnY + 33f, p)

            // BACK button
            p.color = if (hoveredActionButton == 111) (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x80000000.toInt() else (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x20000000
            canvas.drawRoundRect(70f + fifthW * 4f, btnY, uiW - 30f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 111) ThemeManager.TEXT_BRIGHT else ThemeManager.PINK_SOFT
            canvas.drawText("BACK", 70f + fifthW * 4f + (uiW - 100f - fifthW * 4f) / 2f, btnY + 33f, p)
            p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ═══ Audio Player ═══
        if (audioPlayerMode) {
            val ap = audioPlayer
            val p = tmpPaint

            // Audio file picker sub-mode
            if (audioPickerMode) {
                p.textSize = ThemeManager.PX_TITLE; p.color = ThemeManager.PURPLE_DEEP; p.isFakeBoldText = true
                canvas.drawText("Select Audio File", 60f, 112f, p)
                tmpPaint2.apply { color = ThemeManager.PURPLE_GLOW; strokeWidth = 2f; style = Paint.Style.FILL_AND_STROKE }
                canvas.drawLine(60f, 120f, uiW - 60f, 120f, tmpPaint2)

                val files = availableAudioFiles
                if (files.isEmpty()) {
                    p.textSize = ThemeManager.PX_TITLE
                    p.color = if (audioScanInProgress) ThemeManager.PURPLE_DEEP else ThemeManager.TEXT_DIM
                    p.isFakeBoldText = false
                    canvas.drawText(if (audioScanInProgress) "Scanning audio files..." else "No audio files found", 60f, 200f, p)
                    if (audioScanInProgress) {
                        p.textSize = ThemeManager.PX_BODY
                        p.color = ThemeManager.TEXT_MID
                        canvas.drawText("Browsing will populate automatically.", 60f, 238f, p)
                    }
                } else {
                    val maxVis = 10; val rowH = 72f; val startY = 140f
                    for (vi in 0 until maxVis) {
                        val idx = audioPickerScrollOffset + vi
                        if (idx >= files.size) break
                        val file = files[idx]
                        val ry = startY + vi * rowH
                        val isHov = idx == hoveredAudioFileIndex

                        if (isHov) {
                            tmpPaint2.apply { color = ThemeManager.BG_ELEVATED; style = Paint.Style.FILL }
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, tmpPaint2)
                        }
                        if (vi % 2 == 0 && !isHov) {
                            tmpPaint2.apply { color = ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, tmpPaint2)
                        }
                        val name = file.nameWithoutExtension
                        val display = if (name.length > 28) name.take(26) + ".." else name
                        p.textSize = if (isHov) 36f else 34f
                        p.color = if (isHov) ThemeManager.PURPLE_DEEP else ThemeManager.TEXT_BRIGHT
                        p.isFakeBoldText = isHov
                        canvas.drawText(display, 50f, ry + 32f, p)

                        p.textSize = ThemeManager.PX_LABEL; p.color = ThemeManager.TEXT_DIM; p.isFakeBoldText = false
                        val ext = file.extension.uppercase()
                        val sizeMB = file.length() / 1048576f
                        val info = "$ext  ${(sizeMB * 10).toInt() / 10f} MB"
                        canvas.drawText(info, 50f, ry + 52f, p)
                    }
                    if (files.size > maxVis) {
                        val pg = audioPickerScrollOffset / maxVis + 1
                        val total = (files.size + maxVis - 1) / maxVis
                        p.textSize = 20f; p.color = ThemeManager.TEXT_DIM
                        p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = false
                        canvas.drawText("Page $pg of $total  (${files.size} files)",
                            uiW / 2f, startY + maxVis * rowH + 16f, p)
                        p.textAlign = Paint.Align.LEFT
                    }
                }
                if (audioScanInProgress) {
                    p.textSize = 20f
                    p.color = ThemeManager.PURPLE_DEEP
                    p.textAlign = Paint.Align.RIGHT
                    canvas.drawText("SCANNING...", uiW - 40f, 112f, p)
                    p.textAlign = Paint.Align.LEFT
                }
                // BACK button
                val apBtnY = uiH - 80f
                val isBack = hoveredAudioButton == 50
                tmpPaint2.apply { color = if (isBack) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
                canvas.drawRoundRect(30f, apBtnY, uiW - 30f, apBtnY + 60f, 12f, 12f, tmpPaint2)
                p.textSize = ThemeManager.PX_TITLE; p.textAlign = Paint.Align.CENTER
                p.color = if (isBack) ThemeManager.TEXT_BRIGHT else ThemeManager.PURPLE_DEEP; p.isFakeBoldText = true
                canvas.drawText("\u25C0 BACK", uiW / 2f, apBtnY + 40f, p)
                p.textAlign = Paint.Align.LEFT

                drawLaserCursorOverlay()
                publishBitmap(bitmap)
                return
            }

            // ── Audio Player Main Panel ──
            p.textSize = ThemeManager.PX_TITLE; p.color = ThemeManager.PURPLE_DEEP; p.isFakeBoldText = true
            canvas.drawText("AUDIO PLAYER", 60f, 108f, p)
            tmpPaint2.apply { color = ThemeManager.PURPLE_GLOW; strokeWidth = 2f; style = Paint.Style.FILL_AND_STROKE }
            canvas.drawLine(60f, 116f, uiW - 60f, 116f, tmpPaint2)

            // Now playing
            val fileName = ap?.currentFile?.nameWithoutExtension ?: "No track loaded"
            val displayName = if (fileName.length > 36) fileName.take(34) + ".." else fileName
            p.textSize = 30f; p.color = ThemeManager.TEXT_BRIGHT; p.isFakeBoldText = false
            canvas.drawText(displayName, 60f, 160f, p)

            // Time + progress bar
            val posMs = ap?.currentPositionMs ?: 0
            val durMs = ap?.durationMs ?: 0
            fun fmtTime(ms: Long): String {
                val s = ms / 1000; val m = s / 60; val r = s % 60
                return "$m:${if (r < 10) "0$r" else "$r"}"
            }
            p.textSize = ThemeManager.PX_BODY; p.color = ThemeManager.TEXT_MID
            canvas.drawText("${fmtTime(posMs)} / ${fmtTime(durMs)}", 60f, 195f, p)

            // Progress bar
            val barLeft = 60f; val barRight = uiW - 60f; val barY = 210f; val barH = 10f
            tmpPaint2.apply { color = ThemeManager.BG_PANEL; style = Paint.Style.FILL; shader = null }
            canvas.drawRoundRect(barLeft, barY, barRight, barY + barH, 5f, 5f, tmpPaint2)
            if (durMs > 0) {
                val prog = (posMs.toFloat() / durMs).coerceIn(0f, 1f)
                val fillR = barLeft + (barRight - barLeft) * prog
                tmpPaint2.apply {
                    shader = LinearGradient(barLeft, 0f, fillR, 0f,
                        ThemeManager.PINK_HOT, ThemeManager.PINK_SOFT, Shader.TileMode.CLAMP)
                }
                canvas.drawRoundRect(barLeft, barY, fillR, barY + barH, 5f, 5f, tmpPaint2)
                tmpPaint2.shader = null
                // A/B markers
                if (ap != null && ap.hasLoop()) {
                    val aX = barLeft + (barRight - barLeft) * (ap.loopA.toFloat() / durMs)
                    val bX = barLeft + (barRight - barLeft) * (ap.loopB.toFloat() / durMs)
                    tmpPaint2.color = ThemeManager.GREEN
                    canvas.drawRect(aX - 1f, barY - 4f, aX + 1f, barY + barH + 4f, tmpPaint2)
                    tmpPaint2.color = ThemeManager.RED
                    canvas.drawRect(bX - 1f, barY - 4f, bX + 1f, barY + barH + 4f, tmpPaint2)
                    tmpPaint2.color = (ThemeManager.GREEN and 0x00FFFFFF) or 0x30000000
                    canvas.drawRect(aX, barY, bX, barY + 2f, tmpPaint2)
                }
                // Thumb
                tmpPaint2.color = Color.WHITE
                canvas.drawCircle(fillR, barY + barH / 2f, 7f, tmpPaint2)
            }

            // Transport buttons
            val txY = 245f; val btnW = 130f; val txBtnH = 55f; val txGap = 12f
            val transportBtns = arrayOf(
                "|<<" to 0, (if (ap?.isPlaying == true) "PAUSE" else "PLAY") to 1, ">>|" to 2, "STOP" to 3
            )
            for ((i, btn) in transportBtns.withIndex()) {
                val bx = 60f + i * (btnW + txGap)
                val isHov = hoveredAudioButton == btn.second
                val col = when (i) {
                    1 -> ThemeManager.PURPLE_DEEP
                    3 -> ThemeManager.RED
                    else -> ThemeManager.BLUE
                }
                tmpPaint2.apply {
                    style = Paint.Style.FILL; shader = null; maskFilter = null
                    color = if (isHov) (col and 0x00FFFFFF) or 0x60000000
                    else (col and 0x00FFFFFF) or 0x18000000
                }
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + txBtnH, 10f, 10f, tmpPaint2)
                tmpPaint2.apply {
                    style = Paint.Style.STROKE; strokeWidth = if (isHov) 2f else 1f
                    color = if (isHov) col else (col and 0x00FFFFFF) or 0x50000000
                }
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + txBtnH, 10f, 10f, tmpPaint2)
                p.textSize = ThemeManager.PX_BODY; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) ThemeManager.TEXT_BRIGHT else col; p.isFakeBoldText = true
                canvas.drawText(btn.first, bx + btnW / 2f, txY + 37f, p)
            }

            // Speed
            val spY = 325f
            p.textAlign = Paint.Align.LEFT
            p.textSize = ThemeManager.PX_BODY; p.color = ThemeManager.TEXT_DIM; p.isFakeBoldText = false
            canvas.drawText("SPEED", 60f, spY, p)
            val spLabel = AudioPlayer.SPEED_LABELS.getOrElse(ap?.speedIndex ?: 2) { "1.0x" }
            p.color = ThemeManager.CYAN_ICE; p.isFakeBoldText = true
            canvas.drawText(spLabel, 160f, spY, p)
            val isSpDown = hoveredAudioButton == 10; val isSpUp = hoveredAudioButton == 11
            p.textSize = 28f; p.textAlign = Paint.Align.CENTER
            tmpPaint2.apply { color = if (isSpDown) (ThemeManager.BLUE and 0x00FFFFFF) or 0x40000000 else (ThemeManager.BLUE and 0x00FFFFFF) or 0x10000000; style = Paint.Style.FILL; shader = null; maskFilter = null }
            canvas.drawRoundRect(260f, spY - 22f, 320f, spY + 6f, 8f, 8f, tmpPaint2)
            p.color = if (isSpDown) ThemeManager.TEXT_BRIGHT else ThemeManager.BLUE
            canvas.drawText("-", 290f, spY, p)
            tmpPaint2.color = if (isSpUp) (ThemeManager.BLUE and 0x00FFFFFF) or 0x40000000 else (ThemeManager.BLUE and 0x00FFFFFF) or 0x10000000
            canvas.drawRoundRect(330f, spY - 22f, 390f, spY + 6f, 8f, 8f, tmpPaint2)
            p.color = if (isSpUp) ThemeManager.TEXT_BRIGHT else ThemeManager.BLUE
            canvas.drawText("+", 360f, spY, p)

            // A/B Loop
            val abY = 375f
            p.textAlign = Paint.Align.LEFT
            p.textSize = ThemeManager.PX_BODY; p.color = ThemeManager.TEXT_DIM; p.isFakeBoldText = false
            canvas.drawText("A/B LOOP", 60f, abY, p)
            val abBtns = arrayOf("Set A" to 20, "Set B" to 21, "Clear" to 22)
            for ((i, ab) in abBtns.withIndex()) {
                val bx = 220f + i * 100f
                val isHov = hoveredAudioButton == ab.second
                val col = when (i) { 0 -> ThemeManager.GREEN; 1 -> ThemeManager.RED; else -> ThemeManager.TEXT_DIM }
                tmpPaint2.apply { color = if (isHov) (col and 0x00FFFFFF) or 0x40000000 else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
                canvas.drawRoundRect(bx, abY - 20f, bx + 90f, abY + 8f, 8f, 8f, tmpPaint2)
                p.textSize = 20f; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) ThemeManager.TEXT_BRIGHT else col; p.isFakeBoldText = isHov
                canvas.drawText(ab.first, bx + 45f, abY, p)
            }
            if (ap != null && ap.hasLoop()) {
                p.textSize = ThemeManager.PX_LABEL; p.color = ThemeManager.TEXT_DIM; p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
                canvas.drawText("A=${fmtTime(ap.loopA)}  B=${fmtTime(ap.loopB)}", 540f, abY, p)
            }

            // Repeat
            val rpY = 425f
            p.textSize = ThemeManager.PX_BODY; p.color = ThemeManager.TEXT_DIM; p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
            canvas.drawText("REPEAT", 60f, rpY, p)
            val rpLabel = ap?.repeatMode?.label ?: "OFF"
            val isRpHov = hoveredAudioButton == 30
            tmpPaint2.apply { color = if (isRpHov) (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x40000000 else (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x10000000; style = Paint.Style.FILL }
            canvas.drawRoundRect(180f, rpY - 20f, 280f, rpY + 8f, 8f, 8f, tmpPaint2)
            p.textSize = ThemeManager.PX_BODY; p.textAlign = Paint.Align.CENTER
            p.color = if (rpLabel != "OFF") ThemeManager.PINK_SOFT else ThemeManager.TEXT_DIM; p.isFakeBoldText = rpLabel != "OFF"
            canvas.drawText(rpLabel, 230f, rpY, p)

            // EQ
            val eqY = 475f
            p.textSize = ThemeManager.PX_BODY; p.color = ThemeManager.TEXT_DIM; p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
            canvas.drawText("EQ", 60f, eqY, p)
            val eqPresets = AudioPlayer.EqPreset.entries
            for ((i, eq) in eqPresets.withIndex()) {
                val bx = 130f + i * 120f
                val isHov = hoveredAudioButton == 40 + i
                val isCurrent = ap?.eqPreset == eq
                val col = if (isCurrent) ThemeManager.ORANGE else ThemeManager.TEXT_DIM
                tmpPaint2.apply {
                    style = Paint.Style.FILL
                    color = if (isHov) (col and 0x00FFFFFF) or 0x40000000
                    else if (isCurrent) (ThemeManager.ORANGE and 0x00FFFFFF) or 0x20000000 else ThemeManager.BG_SURFACE
                }
                canvas.drawRoundRect(bx, eqY - 20f, bx + 110f, eqY + 8f, 8f, 8f, tmpPaint2)
                p.textSize = ThemeManager.PX_LABEL; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) ThemeManager.TEXT_BRIGHT else col; p.isFakeBoldText = isCurrent
                canvas.drawText(eq.label, bx + 55f, eqY, p)
            }

            // Bottom buttons: BROWSE / BACK
            val bbY = uiH - 80f
            val halfW = (uiW - 70f) / 2f
            val isBrowse = hoveredAudioButton == 51; val isBack = hoveredAudioButton == 52
            tmpPaint2.apply { color = if (isBrowse) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
            canvas.drawRoundRect(30f, bbY, 30f + halfW, bbY + 60f, 12f, 12f, tmpPaint2)
            p.textSize = 28f; p.textAlign = Paint.Align.CENTER
            p.color = if (isBrowse) ThemeManager.TEXT_BRIGHT else ThemeManager.PURPLE_DEEP; p.isFakeBoldText = true
            canvas.drawText("BROWSE", 30f + halfW / 2f, bbY + 40f, p)

            tmpPaint2.color = if (isBack) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE
            canvas.drawRoundRect(40f + halfW, bbY, uiW - 30f, bbY + 60f, 12f, 12f, tmpPaint2)
            p.color = if (isBack) ThemeManager.TEXT_BRIGHT else ThemeManager.PINK_SOFT
            canvas.drawText("BACK", 40f + halfW + (uiW - 70f - halfW) / 2f, bbY + 40f, p)
            p.textAlign = Paint.Align.LEFT

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ═══ Lighting Presets Sub-Menu ═══
        if (lightingPresetMode) {
            tmpPaint.apply { textSize = ThemeManager.PX_TITLE; color = ThemeManager.GOLD_WARM; isFakeBoldText = true; textAlign = Paint.Align.LEFT; style = Paint.Style.FILL; shader = null; maskFilter = null; typeface = Typeface.DEFAULT }
            canvas.drawText("Lighting Presets", 50f, 115f, tmpPaint)

            val presets = com.ashairfoil.prism.settings.LightingPresets.getAllPresets()
            val maxVisible = 12
            val rowH = 65f
            val startY = 140f

            for (vi in 0 until minOf(maxVisible, presets.size)) {
                val preset = presets[vi]
                val ry = startY + vi * rowH
                val isHovered = vi == hoveredLightingPresetIndex
                val isActive = preset.name == activeLightingPresetName

                if (isHovered) {
                    tmpPaint2.apply { color = ThemeManager.BG_ELEVATED; style = Paint.Style.FILL; maskFilter = null; shader = null }
                    canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, tmpPaint2)
                }

                // Active preset indicator dot
                if (isActive) {
                    tmpPaint2.apply { color = ThemeManager.GREEN; style = Paint.Style.FILL; maskFilter = null }
                    canvas.drawCircle(38f, ry + 24f, 5f, tmpPaint2)
                    tmpPaint2.apply { color = (ThemeManager.GREEN and 0x00FFFFFF) or 0x40000000; maskFilter = blurNormal3 }
                    canvas.drawCircle(38f, ry + 24f, 8f, tmpPaint2)
                    tmpPaint2.maskFilter = null
                }

                val nameX = if (isActive) 56f else 50f
                if (isHovered) {
                    tmpPaint.apply { textSize = 32f; color = ThemeManager.GOLD_WARM; isFakeBoldText = true; typeface = Typeface.DEFAULT }
                } else {
                    tmpPaint.apply { textSize = 30f; color = ThemeManager.TEXT_BRIGHT; isFakeBoldText = false; typeface = Typeface.DEFAULT }
                }
                canvas.drawText(preset.name, nameX, ry + 28f, tmpPaint)

                if (preset.isBuiltIn) {
                    tmpPaint.apply { textSize = 30f; color = ThemeManager.TEXT_BRIGHT; isFakeBoldText = false }
                    val nameW = tmpPaint.measureText(preset.name)
                    tmpPaint.apply { textSize = ThemeManager.PX_MICRO; color = ThemeManager.TEXT_DIM }
                    canvas.drawText("built-in", nameX + nameW + 12f, ry + 28f, tmpPaint)
                }

                // Summary line
                val li = (preset.lightIntensity * 10).toInt() / 10f
                val fi = (preset.fillLightIntensity * 10).toInt() / 10f
                val ai = (preset.ambientIntensity * 10).toInt() / 10f
                val sd = (preset.shadowDarkness * 100).toInt()
                val am = if (preset.autoAmbient) "Auto" else "Manual"
                val summary = "L:$li  F:$fi  A:$ai  Shd:$sd%  $am"
                tmpPaint.apply { textSize = 20f; color = ThemeManager.TEXT_DIM; isFakeBoldText = false; typeface = Typeface.MONOSPACE }
                canvas.drawText(summary, nameX, ry + 50f, tmpPaint)
                tmpPaint.typeface = Typeface.DEFAULT
            }

            // SAVE CURRENT button
            val saveBtnY = uiH - 150f
            val isSaveHovered = hoveredActionButton == 132
            tmpPaint2.apply { color = if (isSaveHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL; maskFilter = null; shader = null }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, tmpPaint2)
            tmpPaint2.apply { style = Paint.Style.STROKE; strokeWidth = if (isSaveHovered) 2f else 1f; color = if (isSaveHovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, tmpPaint2)
            tmpPaint.apply { textSize = ThemeManager.PX_HEADING; textAlign = Paint.Align.CENTER; color = if (isSaveHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.PURPLE_DEEP; isFakeBoldText = true }
            canvas.drawText("SAVE CURRENT LIGHTING", uiW / 2f, saveBtnY + 34f, tmpPaint)

            // SET DEFAULT / BACK row
            val row2Y = uiH - 80f
            val halfW = (uiW - 68f) / 2f
            val isDefaultHovered = hoveredActionButton == 133
            val isBackHovered = hoveredActionButton == 131

            // SET DEFAULT button
            tmpPaint2.apply { color = if (isDefaultHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
            canvas.drawRoundRect(30f, row2Y, 30f + halfW, row2Y + 60f, 12f, 12f, tmpPaint2)
            tmpPaint2.apply { style = Paint.Style.STROKE; strokeWidth = if (isDefaultHovered) 2f else 1f; color = if (isDefaultHovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT }
            canvas.drawRoundRect(30f, row2Y, 30f + halfW, row2Y + 60f, 12f, 12f, tmpPaint2)
            tmpPaint.apply { textSize = ThemeManager.PX_HEADING; textAlign = Paint.Align.CENTER; color = if (isDefaultHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.GOLD_WARM; isFakeBoldText = true }
            canvas.drawText("SET DEFAULT", 30f + halfW / 2f, row2Y + 40f, tmpPaint)

            // BACK button
            tmpPaint2.apply { color = if (isBackHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
            canvas.drawRoundRect(38f + halfW, row2Y, uiW - 30f, row2Y + 60f, 12f, 12f, tmpPaint2)
            tmpPaint.apply { textSize = ThemeManager.PX_HEADING; textAlign = Paint.Align.CENTER; color = if (isBackHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.PINK_SOFT; isFakeBoldText = true }
            canvas.drawText("\u25C0 BACK", 38f + halfW + (uiW - 68f - halfW) / 2f, row2Y + 40f, tmpPaint)

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ═══ Save Name Editor ═══
        if (saveNameMode) {
            tmpPaint.apply { textSize = ThemeManager.PX_TITLE; color = ThemeManager.PURPLE_DEEP; isFakeBoldText = true; textAlign = Paint.Align.LEFT; style = Paint.Style.FILL; shader = null; maskFilter = null; typeface = Typeface.DEFAULT }
            canvas.drawText("Save Scene", 50f, 110f, tmpPaint)

            // Name display with cursor
            val nameY = 155f
            val charW = 38f
            val nameStartX = 50f
            for (i in 0 until 20) {
                val ch = if (i < saveNameLen) saveNameChars[i] else ' '
                val isCursor = i == saveNameCursor
                if (isCursor) {
                    tmpPaint2.apply { color = ThemeManager.PURPLE_DEEP; style = Paint.Style.FILL; maskFilter = null; shader = null }
                    canvas.drawRoundRect(nameStartX + i * charW - 2f, nameY - 32f,
                        nameStartX + i * charW + charW - 4f, nameY + 6f, 4f, 4f, tmpPaint2)
                }
                tmpPaint.apply {
                    textSize = 36f; textAlign = Paint.Align.LEFT
                    color = if (isCursor) Color.WHITE else if (i < saveNameLen) ThemeManager.TEXT_BRIGHT else ThemeManager.BORDER
                    isFakeBoldText = isCursor
                }
                canvas.drawText(ch.toString(), nameStartX + i * charW, nameY, tmpPaint)
            }

            tmpPaint2.apply { color = ThemeManager.PURPLE_GLOW; strokeWidth = 2f; style = Paint.Style.FILL_AND_STROKE }
            canvas.drawLine(nameStartX, nameY + 8f, nameStartX + 20 * charW, nameY + 8f, tmpPaint2)

            // On-screen QWERTY keyboard
            val hoveredKey = ih.hoveredKeyboardKey
            keyboard.render(canvas, hoveredKey)

            // SAVE button (below keyboard)
            val saveBtnY = 555f
            val isSaveHovered = hoveredSaveButton == 0
            tmpPaint2.apply { color = if (isSaveHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL; maskFilter = null; shader = null }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, tmpPaint2)
            tmpPaint2.apply { style = Paint.Style.STROKE; strokeWidth = if (isSaveHovered) 2f else 1f; color = if (isSaveHovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, tmpPaint2)
            tmpPaint.apply { textSize = ThemeManager.PX_HEADING; textAlign = Paint.Align.CENTER; color = if (isSaveHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.PURPLE_DEEP; isFakeBoldText = true }
            @Suppress("NAME_SHADOWING")
            val displayName = String(saveNameChars, 0, saveNameLen).trim().ifEmpty { "untitled" }
            canvas.drawText("SAVE \"$displayName\"", uiW / 2f, saveBtnY + 34f, tmpPaint)

            // Existing scenes (overwrite targets)
            activity.refreshSceneList()
            val scenes = savedSceneFiles
            if (scenes.isNotEmpty()) {
                tmpPaint.apply { textSize = ThemeManager.PX_BODY; color = ThemeManager.TEXT_DIM; isFakeBoldText = false; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT }
                canvas.drawText("Or overwrite existing:", 50f, 630f, tmpPaint)

                val rowH = 50f
                val startY = 650f

                for (vi in 0 until minOf(10, scenes.size)) {
                    val scene = scenes[vi]
                    val ry = startY + vi * rowH
                    val isHovered = vi == hoveredSceneIndex

                    if (isHovered) {
                        tmpPaint2.apply { color = ThemeManager.BG_ELEVATED; style = Paint.Style.FILL; maskFilter = null; shader = null }
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, tmpPaint2)
                    }
                    if (isHovered) {
                        tmpPaint.apply { textSize = 32f; color = ThemeManager.PURPLE_DEEP; isFakeBoldText = true }
                    } else {
                        tmpPaint.apply { textSize = 30f; color = ThemeManager.TEXT_BRIGHT; isFakeBoldText = false }
                    }
                    canvas.drawText(scene.nameWithoutExtension, 50f, ry + 30f, tmpPaint)

                    val dateStr = sceneDateFormat.format(java.util.Date(scene.lastModified()))
                    tmpPaint.apply { textSize = ThemeManager.PX_BODY; color = ThemeManager.TEXT_DIM; isFakeBoldText = false }
                    val dw = tmpPaint.measureText(dateStr)
                    canvas.drawText(dateStr, uiW - 60f - dw, ry + 30f, tmpPaint)
                }
            }

            // BACK button
            val snBtnY = uiH - 80f
            val snBtnH = 60f
            val isBackHovered = hoveredSaveButton == 1
            tmpPaint2.apply { color = if (isBackHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL; maskFilter = null; shader = null }
            canvas.drawRoundRect(30f, snBtnY, uiW - 30f, snBtnY + snBtnH, 12f, 12f, tmpPaint2)
            tmpPaint.apply { textSize = 30f; textAlign = Paint.Align.CENTER; color = if (isBackHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.PURPLE_DEEP; isFakeBoldText = true }
            canvas.drawText("\u25C0 BACK", uiW / 2f, snBtnY + 40f, tmpPaint)

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ═══ GLB Picker Sub-Menu ═══
        if (glbPickerMode) {
            tmpPaint.apply { textSize = ThemeManager.PX_TITLE; color = ThemeManager.GREEN; isFakeBoldText = true; textAlign = Paint.Align.LEFT; style = Paint.Style.FILL; shader = null; maskFilter = null; typeface = Typeface.DEFAULT }
            canvas.drawText("Select a 3D Model", 70f, 112f, tmpPaint)
            tmpPaint2.apply { color = (ThemeManager.GREEN and 0x00FFFFFF) or 0x40000000; strokeWidth = 2f; style = Paint.Style.FILL_AND_STROKE; maskFilter = blurNormal4; shader = null }
            canvas.drawLine(70f, 120f, uiW - 70f, 120f, tmpPaint2)
            tmpPaint2.maskFilter = null

            val files = availableGlbFiles
            if (files.isEmpty()) {
                tmpPaint.apply { textSize = 34f; color = ThemeManager.TEXT_DIM; isFakeBoldText = false }
                canvas.drawText("No .glb files found on device", 70f, 200f, tmpPaint)
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

                    tmpPaint2.apply {
                        style = Paint.Style.FILL; maskFilter = null; shader = null
                        color = when {
                            isHovered -> ThemeManager.BG_ELEVATED
                            isLoaded -> (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x14000000
                            vi % 2 == 0 -> ThemeManager.BG_SURFACE
                            else -> 0x00000000.toInt()
                        }
                    }
                    canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, tmpPaint2)

                    if (isHovered) {
                        tmpPaint2.apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = ThemeManager.BORDER_PINK; maskFilter = blurOuter3 }
                        canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, tmpPaint2)
                        tmpPaint2.maskFilter = null
                    }

                    if (isLoaded) {
                        tmpPaint2.apply { color = ThemeManager.PINK_SOFT; style = Paint.Style.FILL; maskFilter = null }
                        canvas.drawCircle(46f, ry + 30f, 4f, tmpPaint2)
                        tmpPaint2.apply { color = ThemeManager.PINK_GLOW; maskFilter = blurNormal3 }
                        canvas.drawCircle(46f, ry + 30f, 6f, tmpPaint2)
                        tmpPaint2.maskFilter = null
                    }

                    val label = file.nameWithoutExtension
                    @Suppress("NAME_SHADOWING")
                    val displayName = if (label.length > 26) label.take(24) + ".." else label
                    tmpPaint.apply {
                        textSize = if (isHovered) 38f else 36f; textAlign = Paint.Align.LEFT; letterSpacing = 0f; typeface = Typeface.DEFAULT
                        color = when {
                            isHovered -> ThemeManager.GREEN
                            isLoaded -> ThemeManager.TEXT_MID
                            else -> ThemeManager.TEXT_BRIGHT
                        }
                        isFakeBoldText = isHovered
                    }
                    canvas.drawText(displayName, if (isLoaded) 62f else 50f, ry + 34f, tmpPaint)

                    val sizeMB = file.length() / 1048576f
                    val sizeStr = if (sizeMB >= 10f) "${sizeMB.toInt()} MB" else "${(sizeMB * 10).toInt() / 10f} MB"
                    val badgeColor = when {
                        sizeMB > 50f -> ThemeManager.RED
                        sizeMB > 10f -> ThemeManager.ORANGE
                        else -> ThemeManager.TEXT_DIM
                    }
                    tmpPaint.apply { textSize = 22f; color = badgeColor; isFakeBoldText = false }
                    val sw = tmpPaint.measureText(sizeStr)
                    val bx = uiW - 60f - sw - 16f
                    tmpPaint2.apply { color = (badgeColor and 0x00FFFFFF) or 0x18000000; style = Paint.Style.FILL }
                    canvas.drawRoundRect(bx, ry + 18f, uiW - 50f, ry + 42f, 12f, 12f, tmpPaint2)
                    canvas.drawText(sizeStr, bx + 8f, ry + 37f, tmpPaint)

                    val ext = file.extension.uppercase()
                    tmpPaint.apply { textSize = ThemeManager.PX_CAPTION; color = ThemeManager.PURPLE_DEEP; letterSpacing = 0.05f }
                    canvas.drawText(ext, if (isLoaded) 62f else 50f, ry + 54f, tmpPaint)
                    tmpPaint.letterSpacing = 0f
                }

                if (files.size > maxVisible) {
                    val page = glbPickerScrollOffset / maxVisible + 1
                    val totalPages = (files.size + maxVisible - 1) / maxVisible
                    tmpPaint.apply { textSize = ThemeManager.PX_BODY; color = ThemeManager.TEXT_DIM; textAlign = Paint.Align.CENTER; isFakeBoldText = false }
                    canvas.drawText("Page $page of $totalPages  (${files.size} files)", uiW / 2f,
                        startY + maxVisible * rowH + 16f, tmpPaint)
                }
            }

            // BACK button
            val glbBtnY = uiH - 80f
            val glbBtnH = 60f
            val isBackHovered = hoveredActionButton == 103
            if (isBackHovered) {
                tmpPaint2.apply { color = ThemeManager.PINK_GLOW; maskFilter = blurOuter8; style = Paint.Style.STROKE; strokeWidth = 2f; shader = null }
                canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f, tmpPaint2)
                tmpPaint2.maskFilter = null
            }
            tmpPaint2.apply { color = if (isBackHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE; style = Paint.Style.FILL }
            canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f, tmpPaint2)
            tmpPaint2.apply { style = Paint.Style.STROKE; strokeWidth = if (isBackHovered) 2f else 1f; color = if (isBackHovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT }
            canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f, tmpPaint2)
            tmpPaint.apply { textSize = ThemeManager.PX_TITLE; textAlign = Paint.Align.CENTER; color = if (isBackHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.PINK_SOFT; isFakeBoldText = true }
            canvas.drawText("\u25C0 BACK", uiW / 2f, glbBtnY + 40f, tmpPaint)

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ═══ Scene Picker Sub-Menu ═══
        if (scenePickerMode) {
            tmpPaint.apply {
                textSize = ThemeManager.PX_TITLE; color = ThemeManager.BLUE; isFakeBoldText = true
                textAlign = Paint.Align.LEFT; style = Paint.Style.FILL; shader = null; maskFilter = null; typeface = Typeface.DEFAULT
            }
            canvas.drawText("Load Scene", 50f, 115f, tmpPaint)

            val scenes = savedSceneFiles
            if (scenes.isEmpty()) {
                tmpPaint.apply { textSize = ThemeManager.PX_TITLE; color = ThemeManager.TEXT_DIM; isFakeBoldText = false }
                canvas.drawText("No saved scenes", 50f, 200f, tmpPaint)
            } else {
                val maxVisible = 13
                val rowH = 60f
                val startY = 130f

                for (vi in 0 until minOf(maxVisible, scenes.size)) {
                    val scene = scenes[vi]
                    val ry = startY + vi * rowH
                    val isHovered = vi == hoveredSceneIndex

                    if (isHovered) {
                        tmpPaint2.apply { color = ThemeManager.BG_ELEVATED; style = Paint.Style.FILL }
                        canvas.drawRoundRect(24f, ry - 4f, uiW - 24f, ry + rowH - 10f, 8f, 8f, tmpPaint2)
                    }

                    if (isHovered) {
                        tmpPaint.apply { textSize = 36f; color = ThemeManager.BLUE; isFakeBoldText = true }
                    } else {
                        tmpPaint.apply { textSize = 34f; color = ThemeManager.TEXT_BRIGHT; isFakeBoldText = false }
                    }
                    canvas.drawText(scene.nameWithoutExtension, 50f, ry + 34f, tmpPaint)

                    val dateStr = sceneDateFormat.format(java.util.Date(scene.lastModified()))
                    tmpPaint2.apply { textSize = ThemeManager.PX_BODY; color = ThemeManager.TEXT_DIM; style = Paint.Style.FILL }
                    val dw = tmpPaint2.measureText(dateStr)
                    canvas.drawText(dateStr, uiW - 60f - dw, ry + 34f, tmpPaint2)
                }
            }

            // BACK button
            val scBtnY = uiH - 80f
            val scBtnH = 60f
            val isBackHovered = hoveredActionButton == 106
            tmpPaint2.apply {
                color = if (isBackHovered) ThemeManager.BG_ELEVATED else ThemeManager.BG_SURFACE
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(30f, scBtnY, uiW - 30f, scBtnY + scBtnH, 12f, 12f, tmpPaint2)
            tmpPaint2.apply {
                style = Paint.Style.STROKE; strokeWidth = if (isBackHovered) 2f else 1f
                color = if (isBackHovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT
            }
            canvas.drawRoundRect(30f, scBtnY, uiW - 30f, scBtnY + scBtnH, 12f, 12f, tmpPaint2)
            tmpPaint.apply {
                textSize = 30f; textAlign = Paint.Align.CENTER
                color = if (isBackHovered) ThemeManager.TEXT_BRIGHT else ThemeManager.BLUE; isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, scBtnY + 40f, tmpPaint)

            drawLaserCursorOverlay()
            publishBitmap(bitmap)
            return
        }

        // ── Status chips ──
        var y = 110f
        val luxStr = when {
            !autoAmbient -> "Manual"
            xrLightEstimateAvailable && xrSHAvailable -> "XR+SH"
            xrLightEstimateAvailable -> "XR Light"
            else -> "${roomLux.toInt()} lux"
        }
        val gridStr = if (gridVisible) "ON" else "OFF"
        val gridColor = if (gridVisible) ThemeManager.GREEN else ThemeManager.TEXT_DIM

        var chipX = 40f
        chipX = drawStatusChip(canvas, chipX, y, "${models.size} mdl", ThemeManager.GREEN)
        chipX = drawStatusChip(canvas, chipX + 8f, y, "Grid: $gridStr", gridColor)
        chipX = drawStatusChip(canvas, chipX + 8f, y, luxStr, ThemeManager.GOLD_WARM)
        if (hapticConnected) {
            drawStatusChip(canvas, chipX + 8f, y, "Haptic", ThemeManager.PURPLE_DEEP)
        }
        y += 28f

        val model = models.getOrNull(selectedModelIndex)
        val modelName = model?.file?.nameWithoutExtension ?: "No selection"
        tmpPaint.textSize = 30f; tmpPaint.color = ThemeManager.CYAN_ICE; tmpPaint.isFakeBoldText = true
        canvas.drawText(modelName, 50f, y, tmpPaint)
        tmpPaint.isFakeBoldText = false
        if (selectedModelIndex >= 0 && models.size > 1) {
            tmpPaint.textSize = 20f; tmpPaint.color = ThemeManager.TEXT_DIM
            tmpPaint.typeface = Typeface.MONOSPACE
            canvas.drawText("[${selectedModelIndex + 1}/${models.size}]",
                50f + tmpPaint.measureText(modelName) + 10f, y, tmpPaint)
            tmpPaint.typeface = Typeface.DEFAULT
        }
        y += 12f

        // ── Separator ──
        tmpPaint.color = ThemeManager.BORDER_PINK; tmpPaint.strokeWidth = 1f
        canvas.drawLine(40f, y, uiW - 40f, y, tmpPaint)
        y += 14f

        // ── Parameters ──
        val noModel = "---"
        fun fmtDec1(v: Float): String { val i = (v * 10).toInt(); return "${i / 10}.${kotlin.math.abs(i % 10)}" }
        fun fmtSign1(v: Float): String { val s = if (v >= 0f) "+" else ""; return "$s${fmtDec1(v)}" }
        val params = arrayOf(
            "Metallic" to (if (model != null) "${(model.metallic * 100).toInt()}%" else noModel),
            "Roughness" to (if (model != null) "${(model.roughness * 100).toInt()}%" else noModel),
            "Exposure" to (if (model != null) "${fmtSign1(model.exposure)} EV" else noModel),
            "Contrast" to (if (model != null) "${(model.contrast * 100).toInt()}%" else noModel),
            "Saturation" to (if (model != null) "${(model.saturation * 100).toInt()}%" else noModel),
            "Light" to fmtDec1(renderer?.lightIntensity ?: 2f),
            "Fill" to fmtDec1(renderer?.fillLightIntensity ?: 0.5f),
            "Ambient" to "${fmtDec1(renderer?.ambientIntensity ?: 1f)}${if (autoAmbient) " auto" else ""}",
            "Azimuth" to "${(renderer?.lightAngleDeg ?: 0f).toInt()}\u00B0",
            "Elevation" to "${(renderer?.lightElevDeg ?: 60f).toInt()}\u00B0",
            "Shadow" to "${((renderer?.shadowDarkness ?: 0.7f) * 100).toInt()}%",
            "Softness" to fmtDec1(renderer?.shadowSoftness ?: 2f),
            "Spread" to "${fmtDec1(renderer?.shadowSpread ?: 8f)}m",
            "BeatReactor" to if (beatReactorEnabled) {
                val r2 = audioReactor
                if (r2 != null) "ON ${(r2.boxFillPct * 100).toInt()}%" else "ON"
            } else "OFF",
            "Foveation" to if (foveationAvailable) {
                arrayOf("OFF", "LOW", "MED", "HIGH")[foveationLevel]
            } else {
                "N/A"
            },
            "Tex Quality" to arrayOf("Auto", "4096", "2048", "1024")[textureQuality],
            "Show Planes" to if (activity.glesRenderer?.showPlaneVisualization == true) "ON" else "OFF",
            "Room Track" to if (activity.roomTrackingEnabled) "ON" else "OFF",
            "Room Edit" to if (activity.roomEditMode) {
                val idx = activity.selectedPlaneIndex
                if (idx >= 0) {
                    val p2 = activity.glesRenderer?.shadowPlanes?.getOrNull(idx)
                    if (p2 != null) {
                        val key = activity.planeKey(p2.posX, p2.posZ)
                        val off = activity.planeAdjustments[key] ?: 0f
                        val offCm = (off * 100).toInt()
                        "Plane $idx ${if (offCm >= 0) "+$offCm" else "$offCm"}cm"
                    } else "ON"
                } else "SELECT"
            } else "OFF",
        )

        val rowH = 46f

        val paramRanges = activity.PARAM_RANGES
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

        // Section dividers with colored accents
        val sections = mapOf(0 to "MODEL", 5 to "LIGHTING", 13 to "SYSTEM")
        val sectionColors = mapOf(0 to ThemeManager.GREEN, 5 to ThemeManager.GOLD_WARM, 13 to ThemeManager.PURPLE_DEEP)

        for ((i, param) in params.withIndex()) {
            // Section header with colored dot and gradient line
            val sectionLabel = sections[i]
            if (sectionLabel != null) {
                val sColor = sectionColors[i] ?: ThemeManager.TEXT_DIM
                y += 6f

                // Colored dot
                sectionDotPaint.color = sColor
                canvas.drawCircle(44f, y - 2f, 4f, sectionDotPaint)

                // Section label
                sectionLabelPaint.color = sColor
                canvas.drawText(sectionLabel, 56f, y + 2f, sectionLabelPaint)

                // Gradient separator line
                val lineStart = 56f + sectionLabelPaint.measureText(sectionLabel) + 10f
                sectionLinePaint.shader = LinearGradient(
                    lineStart,
                    0f,
                    uiW - 40f,
                    0f,
                    (sColor and 0x00FFFFFF) or 0x40000000,
                    0x00000000,
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(lineStart, y - 2f, uiW - 40f, y - 2f, sectionLinePaint)
                y += 8f
            }

            val rowTop = y - 4f
            val rowBot = y + rowH - 14f

            val isSelected = i == selectedParam
            val isHovered = i == hoveredMenuParam

            // Row background
            if (isSelected) {
                pGlow.color = (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x20000000
                pGlow.maskFilter = blurNormal8
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f, pGlow)
                pBg.color = ThemeManager.BG_ELEVATED
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f, pBg)
                pGlow.color = ThemeManager.PINK_SOFT
                pGlow.maskFilter = blurNormal4
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f, pGlow)
                pBg.color = ThemeManager.PINK_SOFT
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f, pBg)
                pGlow.maskFilter = null
            } else if (isHovered) {
                pBg.color = (ThemeManager.PINK_SOFT and 0x00FFFFFF) or 0x14000000
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f, pBg)
            }

            val isPerModel = i <= 4
            val isDead = isPerModel && model == null
            val labelP = when {
                isDead -> paramDisabledPaint
                isSelected -> paramHighlightPaint
                isHovered -> { pDim.textSize = ThemeManager.PX_BODY + 2f; pDim.color = ThemeManager.TEXT_BRIGHT; pDim }
                else -> paramNormalPaint
            }
            val arrow = if (isSelected) "\u25B6 " else "  "
            canvas.drawText("$arrow${param.first}", 44f, y + 14f, labelP)

            // Slider bar for continuous params (0-12)
            if (i <= 12) {
                val sliderLeft = 240f
                val sliderRight = uiW - 120f
                val sliderY = y + 6f
                val sliderH = 10f
                val range = paramRanges[i]
                val value = getParamValue(i)
                val t = ((value - range.first) / (range.second - range.first)).coerceIn(0f, 1f)
                val fillRight = sliderLeft + (sliderRight - sliderLeft) * t

                // Track
                pBg.color = ThemeManager.BG_PANEL; pBg.style = Paint.Style.FILL
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f, pBg)
                pBorder.strokeWidth = 0.5f; pBorder.color = ThemeManager.BORDER
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f, pBorder)

                if (!isDead) {
                    // Section-appropriate slider gradient colors
                    val secColor = when { i <= 4 -> ThemeManager.GREEN; i <= 12 -> ThemeManager.GOLD_WARM; else -> ThemeManager.PURPLE_DEEP }
                    pFill.shader = null; pFill.style = Paint.Style.FILL
                    val fillPaint = pFill
                    if (isSelected) {
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            ThemeManager.CYAN_ICE, ThemeManager.PURPLE_DEEP,
                            Shader.TileMode.CLAMP)
                    } else if (isHovered) {
                        val darkSec = (secColor and 0x00FFFFFF) or 0xFF0A0000.toInt()
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            darkSec, secColor, Shader.TileMode.CLAMP)
                    } else {
                        val dimSec = (secColor and 0x00FFFFFF) or 0xFF080000.toInt()
                        val midSec = (secColor and 0x00FFFFFF) or 0xFF1A0000.toInt()
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            dimSec, midSec, Shader.TileMode.CLAMP)
                    }
                    canvas.drawRoundRect(sliderLeft, sliderY, fillRight, sliderY + sliderH, 4f, 4f, fillPaint)

                    if (isSelected && t > 0.02f) {
                        pGlow.color = (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x60000000
                        pGlow.maskFilter = blurNormal6
                        canvas.drawCircle(fillRight, sliderY + sliderH / 2f, 6f, pGlow)
                        pGlow.maskFilter = null
                    }

                    // Thumb
                    val thumbX = fillRight.coerceIn(sliderLeft + 4f, sliderRight - 4f)
                    val thumbR = if (isSelected) 6f else 4f
                    if (isSelected) {
                        pGlow.color = (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x40000000
                        pGlow.maskFilter = blurNormal3
                        canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR + 3f, pGlow)
                        pGlow.maskFilter = null
                    }
                    pBg.color = if (isSelected) Color.WHITE
                        else if (isHovered) ThemeManager.TEXT_MID
                        else ThemeManager.TEXT_DIM
                    pBg.style = Paint.Style.FILL
                    canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR, pBg)

                    val vp = if (isSelected) paramValueHighlightPaint else paramValuePaint
                    val valStr = param.second
                    canvas.drawText(valStr, sliderRight + 10f, y + 16f, vp)
                } else {
                    canvas.drawText("---", sliderRight + 10f, y + 16f, paramDisabledPaint)
                }
            } else {
                // Toggle params (13-16)
                val valStr = param.second
                val vp = if (isSelected) paramValueHighlightPaint else paramValuePaint
                val badgeLeft = uiW - 150f
                val badgeW = vp.measureText(valStr) + 24f
                val badgeY = y + 1f
                val badgeH = 20f
                val isOn = valStr.startsWith("ON") || valStr == "HIGH" || valStr == "MED" || valStr == "LOW"
                        || valStr == "4096" || valStr == "2048" || valStr == "1024"
                val badgeBg = if (isOn) {
                    if (isSelected) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x40000000 else (ThemeManager.GREEN and 0x00FFFFFF) or 0x20000000
                } else {
                    ThemeManager.BG_SURFACE
                }
                pBg.color = badgeBg; pBg.style = Paint.Style.FILL
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f, pBg)
                pBorder.strokeWidth = 0.8f
                pBorder.color = if (isOn && isSelected) (ThemeManager.CYAN_ICE and 0x00FFFFFF) or 0x60000000
                    else if (isOn) (ThemeManager.GREEN and 0x00FFFFFF) or 0x30000000
                    else ThemeManager.BORDER_SOFT
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f, pBorder)
                pText.textSize = ThemeManager.PX_LABEL; pText.textAlign = Paint.Align.CENTER
                pText.color = if (isOn) {
                    if (isSelected) ThemeManager.CYAN_ICE else ThemeManager.GREEN
                } else ThemeManager.TEXT_DIM
                pText.isFakeBoldText = isSelected
                canvas.drawText(valStr, badgeLeft + badgeW / 2f, badgeY + 15f, pText)
                pText.textAlign = Paint.Align.LEFT; pText.isFakeBoldText = false
            }

            y += rowH
        }

        // Controls hint
        pDim.textSize = ThemeManager.PX_MICRO; pDim.color = ThemeManager.TEXT_DIM
        canvas.drawText("Stick:Adjust  A:Reset  B:Close  X:Gizmo  Y:Next  Grip:Grab", 40f, y + 4f, pDim)

        // ── Action buttons (2 rows) ──
        val btnGap = 8f
        val btnH = 48f

        fun drawButton(bx1: Float, bx2: Float, by: Float, label: String, hovered: Boolean, normalColor: Int) {
            if (hovered) {
                actionButtonGlowPaint.color = ThemeManager.PINK_GLOW
                actionButtonGlowPaint.maskFilter = blurOuter8
                canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, actionButtonGlowPaint)
                actionButtonGlowPaint.maskFilter = blurOuter12
            }
            // Fill: colored fill for primary actions, BG_SURFACE for normal, BG_ELEVATED for hover
            actionButtonBgPaint.color = if (hovered) normalColor
            else (normalColor and 0x00FFFFFF) or 0xD9000000.toInt()
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, actionButtonBgPaint)
            actionButtonBorderPaint.strokeWidth = if (hovered) 2f else 1f
            actionButtonBorderPaint.color = if (hovered) ThemeManager.BORDER_GLOW else ThemeManager.BORDER_SOFT
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, actionButtonBorderPaint)
            actionButtonTextPaint.color = if (hovered) ThemeManager.TEXT_BRIGHT else ThemeManager.TEXT_MID
            canvas.drawText(label, (bx1 + bx2) / 2f, by + 35f, actionButtonTextPaint)
        }

        // Row 1: SAVE / LOAD
        val row1Y = uiH - 226f
        val btn2W = (uiW - 60f - btnGap) / 2f
        drawButton(30f, 30f + btn2W, row1Y, "SAVE SCENE", hoveredActionButton == 104, ThemeManager.PURPLE_DEEP)
        drawButton(30f + btn2W + btnGap, uiW - 30f, row1Y, "LOAD SCENE", hoveredActionButton == 105, ThemeManager.BLUE)

        // Row 2: ADD / DELETE / RESET
        val row2Y = row1Y + btnH + btnGap
        val btn3W = (uiW - 60f - btnGap * 2f) / 3f
        val r2b1 = 30f; val r2b2 = r2b1 + btn3W + btnGap; val r2b3 = r2b2 + btn3W + btnGap
        drawButton(r2b1, r2b1 + btn3W, row2Y, "+ ADD", hoveredActionButton == 101, ThemeManager.GREEN)
        drawButton(r2b2, r2b2 + btn3W, row2Y, "DELETE", hoveredActionButton == 107, ThemeManager.RED)
        drawButton(r2b3, r2b3 + btn3W, row2Y, "RESET", hoveredActionButton == 108, ThemeManager.ORANGE)

        // Row 3: AUDIO / FILE MENU / EXIT
        val row3Y = row2Y + btnH + btnGap
        drawButton(r2b1, r2b1 + btn3W, row3Y, "AUDIO", hoveredActionButton == 109, ThemeManager.PURPLE_DEEP)
        drawButton(r2b2, r2b2 + btn3W, row3Y, "FILE MENU", hoveredActionButton == 100, ThemeManager.PINK_HOT)
        drawButton(r2b3, r2b3 + btn3W, row3Y, "EXIT", hoveredActionButton == 102, ThemeManager.RED)

        // Row 4: LIGHTS / SENSOR HUD / BLOOM (or anchor-clear when 3rd slot is re-purposed).
        // When spatial anchors are supported, replace BLOOM's slot with a combined
        // "CLEAR ANCH" label so the user has a visible control without overflowing the panel.
        // Bloom remains accessible via param adjustments (BeatReactor setting).
        val row4Y = row3Y + btnH + btnGap
        val anchorMgr = try { activity.spatialAnchors } catch (_: UninitializedPropertyAccessException) { null }
        val anchorsUiVisible = anchorMgr != null && anchorMgr.isSupported()
        drawButton(r2b1, r2b1 + btn3W, row4Y, "LIGHTS", hoveredActionButton == 130, ThemeManager.GOLD_WARM)
        drawButton(r2b2, r2b2 + btn3W, row4Y, "SENSOR HUD", hoveredActionButton == 134, ThemeManager.GREEN)
        if (anchorsUiVisible) {
            val n = anchorMgr.allRecords().size
            val lbl = if (n > 0) "CLR ANCH ($n)" else "CLR ANCH"
            drawButton(r2b3, r2b3 + btn3W, row4Y, lbl, hoveredActionButton == 140, ThemeManager.PINK_HOT)
        } else {
            drawButton(r2b3, r2b3 + btn3W, row4Y, "BLOOM", hoveredActionButton == 135, ThemeManager.PURPLE_DEEP)
        }

        // ── Sensor debug HUD overlay ──
        if (sensorHudVisible && sensorDebugStr.isNotEmpty()) {
            y = row1Y - 20f
            canvas.drawRoundRect(20f, y - 260f, uiW - 20f, y, 8f, 8f, sensorHudBgPaint)
            var sy = y - 240f
            for (line in sensorDebugStr.lines()) {
                if (sy > y - 10f) break
                canvas.drawText(line, 30f, sy, sensorHudTextPaint)
                sy += 22f
            }
        }

        drawLaserCursorOverlay()
        publishBitmap(bitmap)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  buildModelPanelView — Android View-based panel (legacy)
    // ═══════════════════════════════════════════════════════════════════

    fun buildModelPanelView(): android.view.View {
        val models = activity.sceneManager.models
        val selectedModelIndex = activity.sceneManager.selectedModelIndex

        val scrollView = ScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        layout.addView(TextView(activity).apply {
            text = "3D Model Viewer (Filament)"
            textSize = 20f
            setTextColor(ThemeManager.TEXT_BRIGHT)
            setPadding(0, 0, 0, 12)
        })

        layout.addView(TextView(activity).apply {
            text = "${models.size} model${if (models.size != 1) "s" else ""} in scene"
            textSize = 14f
            setTextColor(ThemeManager.TEXT_MID)
            setPadding(0, 0, 0, 16)
        })

        for ((i, model) in models.withIndex()) {
            val isSelected = i == selectedModelIndex
            layout.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (isSelected) ThemeManager.BLUE else ThemeManager.BG_SURFACE)
                setPadding(24, 16, 24, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 2, 0, 2)
                layoutParams = lp

                addView(TextView(activity).apply {
                    text = "${model.file.name} (${String.format("%.2f", model.scale)}x)"
                    textSize = 14f
                    setTextColor(ThemeManager.TEXT_BRIGHT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(Button(activity).apply {
                    text = if (isSelected) "Selected" else "Select"
                    textSize = 12f
                    setOnClickListener {
                        activity.sceneManager.selectedModelIndex = i
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
                models.getOrNull(activity.sceneManager.selectedModelIndex)?.let {
                    it.scale = value / 100f
                    activity.sceneManager.updateModelTransform(it)
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("PBR Material"))

            layout.addView(makeSlider("Metallic", 0, 100, (model.metallic * 100).toInt()) { value ->
                models.getOrNull(activity.sceneManager.selectedModelIndex)?.let {
                    it.metallic = value / 100f
                    activity.glesRenderer?.getModel(it.gpuModelId)?.metallic = it.metallic
                }
            })

            layout.addView(makeSlider("Roughness", 0, 100, (model.roughness * 100).toInt()) { value ->
                models.getOrNull(activity.sceneManager.selectedModelIndex)?.let {
                    it.roughness = value / 100f
                    activity.glesRenderer?.getModel(it.gpuModelId)?.roughness = it.roughness
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Lighting"))

            layout.addView(makeSlider("Exposure", -500, 500, (model.exposure * 100).toInt()) { value ->
                models.getOrNull(activity.sceneManager.selectedModelIndex)?.let {
                    it.exposure = value / 100f
                    activity.glesRenderer?.getModel(it.gpuModelId)?.exposure = it.exposure
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(makeSectionLabel("Hands"))
            layout.addView(Button(activity).apply {
                text = if (activity.handsLocked) "Unlock Hands" else "Lock Hands (walk around)"
                textSize = 16f
                minHeight = 72
                setBackgroundColor(if (activity.handsLocked) ThemeManager.BLUE else ThemeManager.BG_ELEVATED)
                setTextColor(ThemeManager.TEXT_BRIGHT)
                setPadding(20, 16, 20, 16)
                setOnClickListener {
                    activity.handsLocked = !activity.handsLocked
                    text = if (activity.handsLocked) "Unlock Hands" else "Lock Hands (walk around)"
                    setBackgroundColor(if (activity.handsLocked) ThemeManager.BLUE else ThemeManager.BG_ELEVATED)
                    if (activity.handsLocked) {
                        activity.menuVisible = false
                        activity.setContentView(android.view.View(activity))
                    }
                }
            })

            layout.addView(makeSpacer(16))
            layout.addView(Button(activity).apply {
                text = "Reset Position"
                textSize = 14f
                setBackgroundColor(ThemeManager.BG_ELEVATED)
                setTextColor(ThemeManager.TEXT_BRIGHT)
                setOnClickListener {
                    models.getOrNull(activity.sceneManager.selectedModelIndex)?.let {
                        it.posX = 0f; it.posY = 0f; it.posZ = -1f
                        it.rotX = 0f; it.rotY = 0f; it.rotZ = 0f; it.rotW = 1f
                        activity.sceneManager.updateModelTransform(it)
                    }
                }
            })
        }

        layout.addView(makeSpacer(16))
        layout.addView(TextView(activity).apply {
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
            setTextColor(ThemeManager.TEXT_DIM)
        })

        layout.addView(makeSpacer(16))
        layout.addView(Button(activity).apply {
            text = "Back to Files"
            textSize = 16f
            setBackgroundColor(ThemeManager.RED)
            setTextColor(ThemeManager.TEXT_BRIGHT)
            setOnClickListener {
                activity.running = false
                activity.finish()
            }
        })

        scrollView.addView(layout)
        return scrollView
    }

    // ── Canvas UI Helpers ──

    private val chipBgPaint = Paint().apply { isAntiAlias = true }
    private val chipBorderPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 0.8f }
    private val chipTextPaint = Paint().apply { isAntiAlias = true; textSize = ThemeManager.PX_LABEL; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    /** Draw a colored status chip. Returns the right edge X for chaining. */
    private fun drawStatusChip(canvas: Canvas, x: Float, y: Float, text: String, color: Int): Float {
        chipTextPaint.textSize = ThemeManager.PX_LABEL
        val textW = chipTextPaint.measureText(text)
        val chipW = textW + 20f
        val chipH = 22f
        val cy = y - 16f

        chipBgPaint.color = (color and 0x00FFFFFF) or 0x28000000
        canvas.drawRoundRect(x, cy, x + chipW, cy + chipH, 8f, 8f, chipBgPaint)

        chipBorderPaint.color = (color and 0x00FFFFFF) or 0x50000000
        canvas.drawRoundRect(x, cy, x + chipW, cy + chipH, 8f, 8f, chipBorderPaint)

        chipTextPaint.color = color
        canvas.drawText(text, x + chipW / 2f, cy + 16f, chipTextPaint)

        return x + chipW
    }

    // ── UI Helpers ──

    fun showMessage(text: String) {
        // Callers on the render loop thread must not touch Views directly.
        activity.runOnUiThread {
            activity.setContentView(TextView(activity).apply {
                this.text = text
                textSize = 20f
                setTextColor(ThemeManager.TEXT_BRIGHT)
                gravity = Gravity.CENTER
            })
        }
    }

    fun makeSpacer(height: Int): android.view.View {
        return android.view.View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height
            )
        }
    }

    fun makeSectionLabel(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 16f
            setTextColor(ThemeManager.TEXT_MID)
            setPadding(0, 8, 0, 4)
        }
    }

    fun makeSlider(label: String, min: Int, max: Int, initial: Int,
                   onChange: (Int) -> Unit): LinearLayout {
        val range = max - min
        val valueLabel = TextView(activity).apply {
            text = "$label: $initial"
            textSize = 14f
            setTextColor(ThemeManager.TEXT_BRIGHT)
            gravity = Gravity.CENTER
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 2, 0, 2)
            layoutParams = lp
            addView(valueLabel)
            addView(SeekBar(activity).apply {
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
}
