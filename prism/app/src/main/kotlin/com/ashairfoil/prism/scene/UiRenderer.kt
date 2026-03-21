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

    // Preallocated render bitmap (reused every frame to avoid GC pressure)
    private var renderBitmap: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    // Reusable Paint objects for hot-path rendering (avoid per-frame allocations)
    private val tmpPaint = Paint().apply { isAntiAlias = true }
    private val tmpPaint2 = Paint().apply { isAntiAlias = true }

    // All other UI state lives on the activity or inputHandler.
    // Convenience accessors to avoid verbose chains in rendering code:
    private inline val ih get() = activity.inputHandler

    // ═══════════════════════════════════════════════════════════════════
    //  renderUiToBitmap — Render HUD text panel to bitmap
    // ═══════════════════════════════════════════════════════════════════

    fun renderUiToBitmap() {
        val uiW = 1024
        val uiH = 1280
        if (renderBitmap == null || renderBitmap!!.width != uiW || renderBitmap!!.height != uiH) {
            renderBitmap?.recycle()
            renderBitmap = Bitmap.createBitmap(uiW, uiH, Bitmap.Config.ARGB_8888)
            renderCanvas = Canvas(renderBitmap!!)
        }
        val bitmap = renderBitmap!!
        val canvas = renderCanvas!!
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

        // ── Convenience accessors: activity mode state ──
        val audioPlayerMode = activity.audioPlayerMode
        val audioPickerMode = activity.audioPickerMode
        val audioScanInProgress = activity.audioScanInProgress
        val beatSettingsMode = activity.beatSettingsMode
        val glbPickerMode = activity.glbPickerMode
        val scenePickerMode = activity.scenePickerMode
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
                val cp = Paint().apply {
                    isAntiAlias = true
                    color = 0xFFFFFFFF.toInt()
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                canvas.drawCircle(beatCursorX, beatCursorY, 12f, cp)
                cp.strokeWidth = 1f
                cp.color = 0x90FFFFFF.toInt()
                canvas.drawLine(beatCursorX - 20f, beatCursorY, beatCursorX + 20f, beatCursorY, cp)
                canvas.drawLine(beatCursorX, beatCursorY - 20f, beatCursorX, beatCursorY + 20f, cp)
                cp.style = Paint.Style.FILL
                cp.color = 0xFFEC4899.toInt()
                canvas.drawCircle(beatCursorX, beatCursorY, 3f, cp)
            }
        }

        // ═══ ChloeVibes Neon Theme ═══
        // Background: dark with subtle purple gradient
        val bgPaint = Paint()
        val bgGrad = LinearGradient(
            0f, 0f, 0f, uiH.toFloat(),
            intArrayOf(0xE8100818.toInt(), 0xE80A0A14.toInt(), 0xE8120818.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP)
        bgPaint.shader = bgGrad
        canvas.drawRect(0f, 0f, uiW.toFloat(), uiH.toFloat(), bgPaint)

        // Neon border glow
        val borderPink = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFFEC4899.toInt()
            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.OUTER)
            isAntiAlias = true
        }
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, borderPink)
        val borderSolid = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xAAEC4899.toInt()
            isAntiAlias = true
        }
        canvas.drawRoundRect(8f, 8f, uiW - 8f, uiH - 8f, 20f, 20f, borderSolid)

        // ── Title bar (drag zone) — illuminates on hover ──
        val titleHovered = hoveredActionButton == 200
        val titleBg = Paint().apply {
            color = if (titleHovered) 0x80EC4899.toInt() else 0x40EC4899.toInt()
        }
        canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, titleBg)
        if (titleHovered) {
            val glow = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = 3f
                color = 0xFFEC4899.toInt()
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.OUTER)
                isAntiAlias = true
            }
            canvas.drawRoundRect(10f, 10f, uiW - 10f, 80f, 18f, 18f, glow)
        }

        val titlePaint = Paint().apply {
            isAntiAlias = true; textSize = 48f
            color = if (titleHovered) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            isFakeBoldText = true
            maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.SOLID)
        }
        canvas.drawText("ChloeVR", 50f, 62f, titlePaint)
        val dragHint = Paint().apply {
            isAntiAlias = true; textSize = 24f
            color = if (titleHovered) 0xFFEC4899.toInt() else 0x60FFFFFF.toInt()
        }
        canvas.drawText(if (draggingPanel) "dragging..." else "grip to drag", uiW - 280f, 56f, dragHint)

        // ═══ BeatReactor Settings ═══
        if (beatSettingsMode) {
            val reactor = audioReactor
            val p = Paint().apply { isAntiAlias = true }

            // Auto-refresh spectrum every 3 frames while settings panel is open
            if (reactor != null && reactor.isActive && sensorPollFrame % 3 == 0) {
                activity.uiNeedsRefresh = true
            }

            // Header
            p.textSize = 34f; p.color = 0xFFEC4899.toInt(); p.isFakeBoldText = true
            canvas.drawText("BEATREACTOR", 50f, 105f, p)

            // FILL display top right
            val fillPct = reactor?.boxFillPct ?: 0f
            p.textSize = 38f; p.color = 0xFFFFFFFF.toInt()
            canvas.drawText("FILL: %.0f%%".format(fillPct * 100), 550f, 105f, p)
            p.isFakeBoldText = false

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
                    isActive -> 0xFFEC4899.toInt()
                    isHover -> 0x60EC4899.toInt()
                    else -> 0x20EC4899.toInt()
                }
                canvas.drawRoundRect(mx, 108f, mx + mw, 133f, 6f, 6f, p)
                p.textSize = 20f; p.isFakeBoldText = isActive
                p.color = if (isActive || isHover) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt()
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
                    isActive -> 0xFF10B981.toInt()
                    isHover -> 0x6010B981.toInt()
                    else -> 0x2010B981.toInt()
                }
                canvas.drawRoundRect(cx, 130f, cx + cw, 148f, 4f, 4f, p)
                p.color = if (isActive || isHover) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
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
                p.color = if (isAct) 0xFF8B5CF6.toInt() else if (isHov) 0x608B5CF6.toInt() else 0x208B5CF6.toInt()
                canvas.drawRoundRect(cx, 130f, cx + sw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
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
                p.color = if (isAct) 0xFFFF9500.toInt() else if (isHov) 0x60FF9500.toInt() else 0x20FF9500.toInt()
                canvas.drawRoundRect(cx, 130f, cx + bw, 148f, 4f, 4f, p)
                p.color = if (isAct || isHov) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt()
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
            p.color = 0xFF080812.toInt()
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
                p.color = 0xFF0A0A14.toInt()
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                p.color = 0x20FFFFFF.toInt(); p.strokeWidth = 1f
                for (pct2 in arrayOf(0.25f, 0.5f, 0.75f)) {
                    val my = specBot - pct2 * specH
                    canvas.drawLine(meterX, my, meterX + meterW, my, p)
                }
                val meterFillH = reactor.boxFillPct * specH
                if (meterFillH > 0f) {
                    val meterFillTop = specBot - meterFillH
                    p.color = 0xFFFF4090.toInt()
                    canvas.drawRoundRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, specBot, 3f, 3f, p)
                    if (meterFillH > 4f) {
                        p.color = 0xFFFFFFFF.toInt()
                        canvas.drawRect(meterX + 2f, meterFillTop, meterX + meterW - 2f, meterFillTop + 3f, p)
                    }
                }
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                p.color = 0x80FFFFFF.toInt()
                canvas.drawRoundRect(meterX, specTop, meterX + meterW, specBot, 4f, 4f, p)
                p.style = Paint.Style.FILL

                p.color = 0xFFFFFFFF.toInt(); p.textSize = 22f; p.isFakeBoldText = true
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("%.0f%%".format(reactor.boxFillPct * 100), meterX + meterW / 2f, specTop - 8f, p)
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
                    p.color = 0xFF0A0A14.toInt()
                    canvas.drawRoundRect(m2X, specTop, m2X + meterW, specBot, 4f, 4f, p)
                    p.color = 0x20FFFFFF.toInt(); p.strokeWidth = 1f
                    for (pct2 in arrayOf(0.25f, 0.5f, 0.75f)) {
                        val my = specBot - pct2 * specH
                        canvas.drawLine(m2X, my, m2X + meterW, my, p)
                    }
                    val m2FillH = reactor.box2FillPct * specH
                    if (m2FillH > 0f) {
                        val m2FillTop = specBot - m2FillH
                        p.color = 0xFF00CCDD.toInt()
                        canvas.drawRoundRect(m2X + 2f, m2FillTop, m2X + meterW - 2f, specBot, 3f, 3f, p)
                        if (m2FillH > 4f) {
                            p.color = 0xFFFFFFFF.toInt()
                            canvas.drawRect(m2X + 2f, m2FillTop, m2X + meterW - 2f, m2FillTop + 3f, p)
                        }
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
                    p.color = 0x80FFFFFF.toInt()
                    canvas.drawRoundRect(m2X, specTop, m2X + meterW, specBot, 4f, 4f, p)
                    p.style = Paint.Style.FILL

                    p.color = 0xFF00FFFF.toInt(); p.textSize = 18f; p.isFakeBoldText = true
                    p.textAlign = Paint.Align.CENTER
                    canvas.drawText("%.0f%%".format(reactor.box2FillPct * 100), m2X + meterW / 2f, specTop - 8f, p)
                    p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
                }
            }

            // Frequency labels (mapped to visible range)
            p.color = 0xFF6B7280.toInt(); p.textSize = 16f
            p.textAlign = Paint.Align.LEFT
            fun binToHz(norm: Float): String {
                val hz = 20f * Math.pow(1000.0, norm.toDouble()).toFloat()
                return if (hz >= 1000f) "%.1fk".format(hz / 1000f) else "%.0f".format(hz)
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
            val labelP = Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFD1D5DB.toInt() }
            val valP = Paint().apply { isAntiAlias = true; textSize = 20f; color = 0xFF9CA3AF.toInt() }
            var sy = sliderAreaTop

            for ((i, slider) in beatSliders.withIndex()) {
                val isHovered = i == beatDraggingSlider
                val value = slider.get()
                val norm = activity.inputHandler.sliderNorm(slider)

                canvas.drawText(slider.name, 40f, sy + 13f, labelP)
                val valStr = when (slider.unit) {
                    "x" -> "%.1fx".format(value)
                    "Hz" -> "%.0f Hz".format(value)
                    "ms" -> "%.0f ms".format(value)
                    else -> "%.0f%%".format(value)
                }
                val vw = valP.measureText(valStr)
                canvas.drawText(valStr, trackLeft - vw - 6f, sy + 13f, valP)

                // Track background
                p.color = 0xFF1E1E28.toInt()
                canvas.drawRoundRect(trackLeft, sy, trackRight, sy + trackH, 4f, 4f, p)

                if (slider.name == "COLOR") {
                    val segments = 12
                    val segW = (trackRight - trackLeft) / segments
                    for (s in 0 until segments) {
                        val hue = (s.toFloat() / segments) * 360f
                        val rgb = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        p.color = rgb
                        canvas.drawRect(trackLeft + s * segW, sy, trackLeft + (s + 1) * segW, sy + trackH, p)
                    }
                } else {
                    val fillEnd2 = trackLeft + norm * (trackRight - trackLeft)
                    p.color = if (isHovered) 0xFFFF6BB5.toInt() else 0xFFEC4899.toInt()
                    canvas.drawRoundRect(trackLeft, sy, fillEnd2, sy + trackH, 4f, 4f, p)
                }

                // Knob
                val fillEnd = trackLeft + norm * (trackRight - trackLeft)
                if (slider.name == "COLOR") {
                    val rgb = Color.HSVToColor(floatArrayOf(value, 1f, 1f))
                    p.color = rgb
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.color = 0xFFFFFFFF.toInt()
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                    p.style = Paint.Style.FILL
                } else {
                    p.color = 0xFFFFFFFF.toInt()
                    canvas.drawCircle(fillEnd, sy + trackH / 2f, if (isHovered) 16f else 12f, p)
                }

                sy += 32f
            }

            // ── Buttons: BOOM / VIBES / SPLIT / OFF / BACK ──
            val btnY = uiH - 75f; val btnH = 50f
            val fifthW = (uiW - 130f) / 5f
            p.textSize = 20f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true

            // BOOM button
            val boomReady = reactor?.boomReady ?: false
            p.color = if (hoveredActionButton == 127) 0x80FF9500.toInt()
                else if (boomReady) 0x40FF9500.toInt() else 0x15FF9500.toInt()
            canvas.drawRoundRect(30f, btnY, 30f + fifthW, btnY + btnH, 10f, 10f, p)
            p.color = if (boomReady) 0xFFFF9500.toInt() else 0xFF555555.toInt()
            canvas.drawText("BOOM", 30f + fifthW / 2f, btnY + 33f, p)

            // VIBES button
            val vibesColor = if (hapticConnected) 0xFF8B5CF6.toInt() else 0xFFEC4899.toInt()
            val vibesLabel = if (hapticConnected) "VIBES:ON"
                else if (hapticEnabled) "Scan..."
                else "VIBES"
            p.color = if (hoveredActionButton == 128) 0x80EC4899.toInt()
                else if (hapticConnected) 0x408B5CF6.toInt() else 0x20EC4899.toInt()
            canvas.drawRoundRect(40f + fifthW, btnY, 40f + fifthW * 2f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 128) 0xFFFFFFFF.toInt() else vibesColor
            canvas.drawText(vibesLabel, 40f + fifthW * 1.5f, btnY + 33f, p)

            // SPLIT button (dual motor toggle)
            val splitActive = activity.hapticDualMotorSplit
            val splitColor = if (splitActive) 0xFF22D3EE.toInt() else 0xFF666666.toInt()
            p.color = if (hoveredActionButton == 129) 0x8022D3EE.toInt()
                else if (splitActive) 0x4022D3EE.toInt() else 0x15666666.toInt()
            canvas.drawRoundRect(50f + fifthW * 2f, btnY, 50f + fifthW * 3f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 129) 0xFFFFFFFF.toInt() else splitColor
            canvas.drawText(if (splitActive) "SPLIT" else "UNIFIED", 50f + fifthW * 2.5f, btnY + 33f, p)

            // OFF button
            p.color = if (hoveredActionButton == 112) 0x80F04858.toInt() else 0x20F04858.toInt()
            canvas.drawRoundRect(60f + fifthW * 3f, btnY, 60f + fifthW * 4f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 112) 0xFFFFFFFF.toInt() else 0xFFF04858.toInt()
            canvas.drawText("OFF", 60f + fifthW * 3.5f, btnY + 33f, p)

            // BACK button
            p.color = if (hoveredActionButton == 111) 0x80EC4899.toInt() else 0x20EC4899.toInt()
            canvas.drawRoundRect(70f + fifthW * 4f, btnY, uiW - 30f, btnY + btnH, 10f, 10f, p)
            p.color = if (hoveredActionButton == 111) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            canvas.drawText("BACK", 70f + fifthW * 4f + (uiW - 100f - fifthW * 4f) / 2f, btnY + 33f, p)
            p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false

            drawLaserCursorOverlay()
            pendingUiBitmap = bitmap
            return
        }

        // ═══ Audio Player ═══
        if (audioPlayerMode) {
            val ap = audioPlayer
            val p = Paint().apply { isAntiAlias = true }

            // Audio file picker sub-mode
            if (audioPickerMode) {
                p.textSize = 42f; p.color = 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
                canvas.drawText("Select Audio File", 60f, 112f, p)
                canvas.drawLine(60f, 120f, uiW - 60f, 120f,
                    Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f })

                val files = availableAudioFiles
                if (files.isEmpty()) {
                    p.textSize = 32f
                    p.color = if (audioScanInProgress) 0xFF8B5CF6.toInt() else 0xFF6B7280.toInt()
                    p.isFakeBoldText = false
                    canvas.drawText(if (audioScanInProgress) "Scanning audio files..." else "No audio files found", 60f, 200f, p)
                    if (audioScanInProgress) {
                        p.textSize = 22f
                        p.color = 0xFF9CA3AF.toInt()
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
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                                Paint().apply { color = 0x208B5CF6.toInt(); isAntiAlias = true })
                        }
                        if (vi % 2 == 0) {
                            canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                                Paint().apply { color = 0x08FFFFFF.toInt(); isAntiAlias = true })
                        }
                        val name = file.nameWithoutExtension
                        val display = if (name.length > 28) name.take(26) + ".." else name
                        p.textSize = if (isHov) 36f else 34f
                        p.color = if (isHov) 0xFF8B5CF6.toInt() else 0xFFE8EAF0.toInt()
                        p.isFakeBoldText = isHov
                        canvas.drawText(display, 50f, ry + 32f, p)

                        p.textSize = 18f; p.color = 0xFF505868.toInt(); p.isFakeBoldText = false
                        val ext = file.extension.uppercase()
                        val sizeMB = file.length() / 1048576f
                        val info = "$ext  %.1f MB".format(sizeMB)
                        canvas.drawText(info, 50f, ry + 52f, p)
                    }
                    if (files.size > maxVis) {
                        val pg = audioPickerScrollOffset / maxVis + 1
                        val total = (files.size + maxVis - 1) / maxVis
                        p.textSize = 20f; p.color = 0xFF505868.toInt()
                        p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = false
                        canvas.drawText("Page $pg of $total  (${files.size} files)",
                            uiW / 2f, startY + maxVis * rowH + 16f, p)
                        p.textAlign = Paint.Align.LEFT
                    }
                }
                if (audioScanInProgress) {
                    p.textSize = 20f
                    p.color = 0xFF8B5CF6.toInt()
                    p.textAlign = Paint.Align.RIGHT
                    canvas.drawText("SCANNING...", uiW - 40f, 112f, p)
                    p.textAlign = Paint.Align.LEFT
                }
                // BACK button
                val apBtnY = uiH - 80f
                val isBack = hoveredAudioButton == 50
                canvas.drawRoundRect(30f, apBtnY, uiW - 30f, apBtnY + 60f, 12f, 12f,
                    Paint().apply { color = if (isBack) 0x608B5CF6.toInt() else 0x188B5CF6.toInt() })
                p.textSize = 32f; p.textAlign = Paint.Align.CENTER
                p.color = if (isBack) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
                canvas.drawText("\u25C0 BACK", uiW / 2f, apBtnY + 40f, p)
                p.textAlign = Paint.Align.LEFT

                drawLaserCursorOverlay()
                pendingUiBitmap = bitmap
                return
            }

            // ── Audio Player Main Panel ──
            p.textSize = 38f; p.color = 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
            canvas.drawText("AUDIO PLAYER", 60f, 108f, p)
            canvas.drawLine(60f, 116f, uiW - 60f, 116f,
                Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f })

            // Now playing
            val fileName = ap?.currentFile?.nameWithoutExtension ?: "No track loaded"
            val displayName = if (fileName.length > 36) fileName.take(34) + ".." else fileName
            p.textSize = 30f; p.color = 0xFFD0D0E0.toInt(); p.isFakeBoldText = false
            canvas.drawText(displayName, 60f, 160f, p)

            // Time + progress bar
            val posMs = ap?.currentPositionMs ?: 0
            val durMs = ap?.durationMs ?: 0
            fun fmtTime(ms: Long): String {
                val s = ms / 1000; val m = s / 60
                return "%d:%02d".format(m, s % 60)
            }
            p.textSize = 24f; p.color = 0xFF9CA3AF.toInt()
            canvas.drawText("${fmtTime(posMs)} / ${fmtTime(durMs)}", 60f, 195f, p)

            // Progress bar
            val barLeft = 60f; val barRight = uiW - 60f; val barY = 210f; val barH = 10f
            canvas.drawRoundRect(barLeft, barY, barRight, barY + barH, 5f, 5f,
                Paint().apply { color = 0xFF0E0E1C.toInt() })
            if (durMs > 0) {
                val prog = (posMs.toFloat() / durMs).coerceIn(0f, 1f)
                val fillR = barLeft + (barRight - barLeft) * prog
                canvas.drawRoundRect(barLeft, barY, fillR, barY + barH, 5f, 5f,
                    Paint().apply {
                        shader = LinearGradient(barLeft, 0f, fillR, 0f,
                            0xFF2A1048.toInt(), 0xFF8B5CF6.toInt(), Shader.TileMode.CLAMP)
                    })
                // A/B markers
                if (ap != null && ap.hasLoop()) {
                    val aX = barLeft + (barRight - barLeft) * (ap.loopA.toFloat() / durMs)
                    val bX = barLeft + (barRight - barLeft) * (ap.loopB.toFloat() / durMs)
                    canvas.drawRect(aX - 1f, barY - 4f, aX + 1f, barY + barH + 4f,
                        Paint().apply { color = 0xFF10B981.toInt() })
                    canvas.drawRect(bX - 1f, barY - 4f, bX + 1f, barY + barH + 4f,
                        Paint().apply { color = 0xFFF04858.toInt() })
                    canvas.drawRect(aX, barY, bX, barY + 2f,
                        Paint().apply { color = 0x3010B981.toInt() })
                }
                // Thumb
                canvas.drawCircle(fillR, barY + barH / 2f, 7f,
                    Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true })
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
                    1 -> 0xFF8B5CF6.toInt()
                    3 -> 0xFFF04858.toInt()
                    else -> 0xFF3B82F6.toInt()
                }
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + txBtnH, 10f, 10f,
                    Paint().apply {
                        color = if (isHov) (col and 0x00FFFFFF) or 0x60000000
                        else (col and 0x00FFFFFF) or 0x18000000
                    })
                canvas.drawRoundRect(bx, txY, bx + btnW, txY + txBtnH, 10f, 10f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = if (isHov) 2f else 1f
                        color = if (isHov) col else (col and 0x00FFFFFF) or 0x50000000; isAntiAlias = true
                    })
                p.textSize = 22f; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = true
                canvas.drawText(btn.first, bx + btnW / 2f, txY + 37f, p)
            }

            // Speed
            val spY = 325f
            p.textAlign = Paint.Align.LEFT
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false
            canvas.drawText("SPEED", 60f, spY, p)
            val spLabel = AudioPlayer.SPEED_LABELS.getOrElse(ap?.speedIndex ?: 2) { "1.0x" }
            p.color = 0xFF30D8D0.toInt(); p.isFakeBoldText = true
            canvas.drawText(spLabel, 160f, spY, p)
            val isSpDown = hoveredAudioButton == 10; val isSpUp = hoveredAudioButton == 11
            p.textSize = 28f; p.textAlign = Paint.Align.CENTER
            canvas.drawRoundRect(260f, spY - 22f, 320f, spY + 6f, 8f, 8f,
                Paint().apply { color = if (isSpDown) 0x403B82F6.toInt() else 0x103B82F6.toInt() })
            p.color = if (isSpDown) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt()
            canvas.drawText("-", 290f, spY, p)
            canvas.drawRoundRect(330f, spY - 22f, 390f, spY + 6f, 8f, 8f,
                Paint().apply { color = if (isSpUp) 0x403B82F6.toInt() else 0x103B82F6.toInt() })
            p.color = if (isSpUp) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt()
            canvas.drawText("+", 360f, spY, p)

            // A/B Loop
            val abY = 375f
            p.textAlign = Paint.Align.LEFT
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false
            canvas.drawText("A/B LOOP", 60f, abY, p)
            val abBtns = arrayOf("Set A" to 20, "Set B" to 21, "Clear" to 22)
            for ((i, ab) in abBtns.withIndex()) {
                val bx = 220f + i * 100f
                val isHov = hoveredAudioButton == ab.second
                val col = when (i) { 0 -> 0xFF10B981.toInt(); 1 -> 0xFFF04858.toInt(); else -> 0xFF6B7280.toInt() }
                canvas.drawRoundRect(bx, abY - 20f, bx + 90f, abY + 8f, 8f, 8f,
                    Paint().apply { color = if (isHov) (col and 0x00FFFFFF) or 0x40000000 else 0x10404050.toInt() })
                p.textSize = 20f; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = isHov
                canvas.drawText(ab.first, bx + 45f, abY, p)
            }
            if (ap != null && ap.hasLoop()) {
                p.textSize = 18f; p.color = 0xFF505868.toInt(); p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
                canvas.drawText("A=${fmtTime(ap.loopA)}  B=${fmtTime(ap.loopB)}", 540f, abY, p)
            }

            // Repeat
            val rpY = 425f
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
            canvas.drawText("REPEAT", 60f, rpY, p)
            val rpLabel = ap?.repeatMode?.label ?: "OFF"
            val isRpHov = hoveredAudioButton == 30
            canvas.drawRoundRect(180f, rpY - 20f, 280f, rpY + 8f, 8f, 8f,
                Paint().apply { color = if (isRpHov) 0x40EC4899.toInt() else 0x10EC4899.toInt() })
            p.textSize = 22f; p.textAlign = Paint.Align.CENTER
            p.color = if (rpLabel != "OFF") 0xFFEC4899.toInt() else 0xFF505868.toInt(); p.isFakeBoldText = rpLabel != "OFF"
            canvas.drawText(rpLabel, 230f, rpY, p)

            // EQ
            val eqY = 475f
            p.textSize = 24f; p.color = 0xFF6B7280.toInt(); p.isFakeBoldText = false; p.textAlign = Paint.Align.LEFT
            canvas.drawText("EQ", 60f, eqY, p)
            val eqPresets = AudioPlayer.EqPreset.entries
            for ((i, eq) in eqPresets.withIndex()) {
                val bx = 130f + i * 120f
                val isHov = hoveredAudioButton == 40 + i
                val isCurrent = ap?.eqPreset == eq
                val col = if (isCurrent) 0xFFFF9500.toInt() else 0xFF505868.toInt()
                canvas.drawRoundRect(bx, eqY - 20f, bx + 110f, eqY + 8f, 8f, 8f,
                    Paint().apply {
                        color = if (isHov) (col and 0x00FFFFFF) or 0x40000000
                        else if (isCurrent) 0x20FF9500.toInt() else 0x10404050.toInt()
                    })
                p.textSize = 18f; p.textAlign = Paint.Align.CENTER
                p.color = if (isHov) 0xFFFFFFFF.toInt() else col; p.isFakeBoldText = isCurrent
                canvas.drawText(eq.label, bx + 55f, eqY, p)
            }

            // Bottom buttons: BROWSE / BACK
            val bbY = uiH - 80f
            val halfW = (uiW - 70f) / 2f
            val isBrowse = hoveredAudioButton == 51; val isBack = hoveredAudioButton == 52
            canvas.drawRoundRect(30f, bbY, 30f + halfW, bbY + 60f, 12f, 12f,
                Paint().apply { color = if (isBrowse) 0x608B5CF6.toInt() else 0x188B5CF6.toInt() })
            p.textSize = 28f; p.textAlign = Paint.Align.CENTER
            p.color = if (isBrowse) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); p.isFakeBoldText = true
            canvas.drawText("BROWSE", 30f + halfW / 2f, bbY + 40f, p)

            canvas.drawRoundRect(40f + halfW, bbY, uiW - 30f, bbY + 60f, 12f, 12f,
                Paint().apply { color = if (isBack) 0x60EC4899.toInt() else 0x18EC4899.toInt() })
            p.color = if (isBack) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
            canvas.drawText("BACK", 40f + halfW + (uiW - 70f - halfW) / 2f, bbY + 40f, p)
            p.textAlign = Paint.Align.LEFT

            drawLaserCursorOverlay()
            pendingUiBitmap = bitmap
            return
        }

        // ═══ Save Name Editor ═══
        if (saveNameMode) {
            val headerPaint = Paint().apply {
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
                if (isCursor) {
                    val cursorBg = Paint().apply { color = 0xFF8B5CF6.toInt() }
                    canvas.drawRoundRect(nameStartX + i * charW - 2f, nameY - 32f,
                        nameStartX + i * charW + charW - 4f, nameY + 6f, 4f, 4f, cursorBg)
                }
                val charPaint = Paint().apply {
                    isAntiAlias = true; textSize = 36f
                    color = if (isCursor) 0xFFFFFFFF.toInt() else if (i < saveNameLen) 0xFFF3F4F6.toInt() else 0xFF3A3A42.toInt()
                    isFakeBoldText = isCursor
                }
                canvas.drawText(ch.toString(), nameStartX + i * charW, nameY, charPaint)
            }

            val underPaint = Paint().apply { color = 0x408B5CF6.toInt(); strokeWidth = 2f }
            canvas.drawLine(nameStartX, nameY + 8f, nameStartX + 20 * charW, nameY + 8f, underPaint)

            val hintPaint = Paint().apply {
                isAntiAlias = true; textSize = 20f; color = 0xFF58585F.toInt()
            }
            canvas.drawText("Stick \u2195:letter  \u2194:cursor  A:backspace", 50f, nameY + 30f, hintPaint)

            // SAVE button
            val saveBtnY = 200f
            val isSaveHovered = hoveredSaveButton == 0
            val saveBg = Paint().apply {
                color = if (isSaveHovered) 0x808B5CF6.toInt() else 0x308B5CF6.toInt()
            }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, saveBg)
            val saveBorder = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = if (isSaveHovered) 3f else 1.5f
                color = 0xFF8B5CF6.toInt(); isAntiAlias = true
            }
            canvas.drawRoundRect(30f, saveBtnY, uiW - 30f, saveBtnY + 50f, 10f, 10f, saveBorder)
            val saveTxt = Paint().apply {
                isAntiAlias = true; textSize = 28f; textAlign = Paint.Align.CENTER
                color = if (isSaveHovered) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); isFakeBoldText = true
            }
            @Suppress("NAME_SHADOWING")
            val displayName = String(saveNameChars, 0, saveNameLen).trim().ifEmpty { "untitled" }
            canvas.drawText("SAVE \"$displayName\"", uiW / 2f, saveBtnY + 34f, saveTxt)

            // Existing scenes (overwrite targets)
            activity.refreshSceneList()
            val scenes = savedSceneFiles
            if (scenes.isNotEmpty()) {
                val secHeader = Paint().apply {
                    isAntiAlias = true; textSize = 24f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("Or overwrite existing:", 50f, 290f, secHeader)

                val rowH = 55f
                val startY = 310f
                val normalPaint = Paint().apply {
                    isAntiAlias = true; textSize = 30f; color = 0xFFF3F4F6.toInt()
                }
                val hoverPaint = Paint().apply {
                    isAntiAlias = true; textSize = 32f; color = 0xFF8B5CF6.toInt(); isFakeBoldText = true
                }
                val datePaint = Paint().apply {
                    isAntiAlias = true; textSize = 22f; color = 0xFF6B7280.toInt()
                }
                val hoverBg = Paint().apply { color = 0x208B5CF6.toInt() }

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
            val snBtnY = uiH - 80f
            val snBtnH = 60f
            val isBackHovered = hoveredSaveButton == 1
            val backBg = Paint().apply {
                color = if (isBackHovered) 0x708B5CF6.toInt() else 0x208B5CF6.toInt()
            }
            canvas.drawRoundRect(30f, snBtnY, uiW - 30f, snBtnY + snBtnH, 12f, 12f, backBg)
            val backText = Paint().apply {
                isAntiAlias = true; textSize = 30f; textAlign = Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFF8B5CF6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, snBtnY + 40f, backText)

            drawLaserCursorOverlay()
            pendingUiBitmap = bitmap
            return
        }

        // ═══ GLB Picker Sub-Menu ═══
        if (glbPickerMode) {
            val headerPaint = Paint().apply {
                isAntiAlias = true; textSize = 42f; color = 0xFF10B981.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Select a 3D Model", 70f, 112f, headerPaint)
            val lineGlow = Paint().apply {
                color = 0x4010B981.toInt(); strokeWidth = 2f; isAntiAlias = true
                maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawLine(70f, 120f, uiW - 70f, 120f, lineGlow)

            val files = availableGlbFiles
            if (files.isEmpty()) {
                val emptyPaint = Paint().apply {
                    isAntiAlias = true; textSize = 34f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("No .glb files found on device", 70f, 200f, emptyPaint)
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

                    val rowBg = Paint().apply {
                        isAntiAlias = true
                        color = when {
                            isHovered && isLoaded -> 0x3010B981.toInt()
                            isHovered -> 0x2010B981.toInt()
                            isLoaded -> 0x14EC4899.toInt()
                            vi % 2 == 0 -> 0x08FFFFFF.toInt()
                            else -> 0x00000000.toInt()
                        }
                    }
                    canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f, rowBg)

                    if (isHovered) {
                        canvas.drawRoundRect(30f, ry - 2f, uiW - 30f, ry + rowH - 10f, 10f, 10f,
                            Paint().apply {
                                style = Paint.Style.STROKE; strokeWidth = 1.5f
                                color = 0x6010B981.toInt(); isAntiAlias = true
                                maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.OUTER)
                            })
                    }

                    if (isLoaded) {
                        canvas.drawCircle(46f, ry + 30f, 4f,
                            Paint().apply { color = 0xFFEC4899.toInt(); isAntiAlias = true })
                        canvas.drawCircle(46f, ry + 30f, 6f,
                            Paint().apply {
                                color = 0x40EC4899.toInt(); isAntiAlias = true
                                maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
                            })
                    }

                    val label = file.nameWithoutExtension
                    @Suppress("NAME_SHADOWING")
                    val displayName = if (label.length > 26) label.take(24) + ".." else label
                    val namePaint = Paint().apply {
                        isAntiAlias = true
                        textSize = if (isHovered) 38f else 36f
                        color = when {
                            isHovered -> 0xFF10B981.toInt()
                            isLoaded -> 0xFFD0C0E0.toInt()
                            else -> 0xFFE8EAF0.toInt()
                        }
                        isFakeBoldText = isHovered
                    }
                    canvas.drawText(displayName, if (isLoaded) 62f else 50f, ry + 34f, namePaint)

                    val sizeMB = file.length() / 1048576f
                    val sizeStr = if (sizeMB >= 10f) "%.0f MB".format(sizeMB) else "%.1f MB".format(sizeMB)
                    val badgeColor = when {
                        sizeMB > 50f -> 0xFFF04858.toInt()
                        sizeMB > 10f -> 0xFFFF9500.toInt()
                        else -> 0xFF6B7280.toInt()
                    }
                    val sizePaint = Paint().apply {
                        isAntiAlias = true; textSize = 22f; color = badgeColor
                    }
                    val sw = sizePaint.measureText(sizeStr)
                    val bx = uiW - 60f - sw - 16f
                    canvas.drawRoundRect(bx, ry + 18f, uiW - 50f, ry + 42f, 12f, 12f,
                        Paint().apply { color = (badgeColor and 0x00FFFFFF) or 0x18000000; isAntiAlias = true })
                    canvas.drawText(sizeStr, bx + 8f, ry + 37f, sizePaint)

                    val ext = file.extension.uppercase()
                    val extPaint = Paint().apply {
                        isAntiAlias = true; textSize = 16f; color = 0xFF8B5CF6.toInt()
                        letterSpacing = 0.05f
                    }
                    canvas.drawText(ext, if (isLoaded) 62f else 50f, ry + 54f, extPaint)
                }

                if (files.size > maxVisible) {
                    val page = glbPickerScrollOffset / maxVisible + 1
                    val totalPages = (files.size + maxVisible - 1) / maxVisible
                    val pagePaint = Paint().apply {
                        isAntiAlias = true; textSize = 22f; color = 0xFF505868.toInt()
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText("Page $page of $totalPages  (${files.size} files)", uiW / 2f,
                        startY + maxVisible * rowH + 16f, pagePaint)
                }
            }

            // BACK button
            val glbBtnY = uiH - 80f
            val glbBtnH = 60f
            val isBackHovered = hoveredActionButton == 103
            if (isBackHovered) {
                canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f,
                    Paint().apply {
                        color = 0xFFEC4899.toInt(); isAntiAlias = true
                        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.OUTER)
                        style = Paint.Style.STROKE; strokeWidth = 2f
                    })
            }
            canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f,
                Paint().apply {
                    color = if (isBackHovered) 0x60EC4899.toInt() else 0x18EC4899.toInt(); isAntiAlias = true
                })
            canvas.drawRoundRect(30f, glbBtnY, uiW - 30f, glbBtnY + glbBtnH, 12f, 12f,
                Paint().apply {
                    style = Paint.Style.STROKE; strokeWidth = if (isBackHovered) 2f else 1f
                    color = if (isBackHovered) 0xFFEC4899.toInt() else 0x50EC4899.toInt()
                    isAntiAlias = true
                })
            val backText = Paint().apply {
                isAntiAlias = true; textSize = 32f; textAlign = Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFFEC4899.toInt()
                isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, glbBtnY + 40f, backText)

            drawLaserCursorOverlay()
            pendingUiBitmap = bitmap
            return
        }

        // ═══ Scene Picker Sub-Menu ═══
        if (scenePickerMode) {
            val headerPaint = Paint().apply {
                isAntiAlias = true; textSize = 38f; color = 0xFF3B82F6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("Load Scene", 50f, 115f, headerPaint)

            val scenes = savedSceneFiles
            if (scenes.isEmpty()) {
                val emptyPaint = Paint().apply {
                    isAntiAlias = true; textSize = 32f; color = 0xFF6B7280.toInt()
                }
                canvas.drawText("No saved scenes", 50f, 200f, emptyPaint)
            } else {
                val maxVisible = 13
                val rowH = 60f
                val startY = 130f
                val normalPaint = Paint().apply {
                    isAntiAlias = true; textSize = 34f; color = 0xFFF3F4F6.toInt()
                }
                val hoverPaint = Paint().apply {
                    isAntiAlias = true; textSize = 36f; color = 0xFF3B82F6.toInt(); isFakeBoldText = true
                }
                val datePaint = Paint().apply {
                    isAntiAlias = true; textSize = 24f; color = 0xFF6B7280.toInt()
                }
                val hoverBg = Paint().apply { color = 0x203B82F6.toInt() }

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
            val scBtnY = uiH - 80f
            val scBtnH = 60f
            val isBackHovered = hoveredActionButton == 106
            val backBg = Paint().apply {
                color = if (isBackHovered) 0x703B82F6.toInt() else 0x203B82F6.toInt()
            }
            canvas.drawRoundRect(30f, scBtnY, uiW - 30f, scBtnY + scBtnH, 12f, 12f, backBg)
            val backBorder = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = if (isBackHovered) 3f else 1.5f
                color = if (isBackHovered) 0xFF3B82F6.toInt() else 0x603B82F6.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(30f, scBtnY, uiW - 30f, scBtnY + scBtnH, 12f, 12f, backBorder)
            val scBackText = Paint().apply {
                isAntiAlias = true; textSize = 30f; textAlign = Paint.Align.CENTER
                color = if (isBackHovered) 0xFFFFFFFF.toInt() else 0xFF3B82F6.toInt(); isFakeBoldText = true
            }
            canvas.drawText("\u25C0 BACK", uiW / 2f, scBtnY + 40f, scBackText)

            drawLaserCursorOverlay()
            pendingUiBitmap = bitmap
            return
        }

        // ── Status line ──
        val dim = Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF6B7280.toInt() }
        val teal = Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF10B981.toInt() }

        var y = 110f
        val luxStr = when {
            !autoAmbient -> "Manual"
            xrLightEstimateAvailable && xrSHAvailable -> "XR+SH"
            xrLightEstimateAvailable -> "XR Light"
            else -> "%.0f lux".format(roomLux)
        }
        val gridStr = if (gridVisible) "ON" else "OFF"
        canvas.drawText("${models.size} mdl  |  Grid: $gridStr  |  $luxStr", 50f, y, dim)
        y += 28f

        val model = models.getOrNull(selectedModelIndex)
        val modelName = model?.file?.nameWithoutExtension ?: "No selection"
        val namePaint = Paint().apply {
            isAntiAlias = true; textSize = 30f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        canvas.drawText(modelName, 50f, y, namePaint)
        y += 12f

        // ── Separator ──
        val sepPaint = Paint().apply { color = 0x30EC4899.toInt(); strokeWidth = 1f }
        canvas.drawLine(40f, y, uiW - 40f, y, sepPaint)
        y += 14f

        // ── Parameters ──
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
                val r2 = audioReactor
                if (r2 != null) "ON %.0f%%".format((r2.boxFillPct) * 100) else "ON"
            } else "OFF",
            "Foveation" to if (foveationAvailable) {
                arrayOf("OFF", "LOW", "MED", "HIGH")[foveationLevel]
            } else {
                "N/A"
            },
            "Tex Quality" to arrayOf("Auto", "4096", "2048", "1024")[textureQuality],
            "Show Planes" to if (activity.glesRenderer?.showPlaneVisualization == true) "ON" else "OFF",
            "Room Track" to if (activity.roomTrackingEnabled) "ON" else "OFF",
        )

        val rowH = 46f
        val normal = Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFFB0B8C4.toInt() }
        val highlight = Paint().apply {
            isAntiAlias = true; textSize = 30f; color = 0xFF30D8D0.toInt(); isFakeBoldText = true
        }
        val disabled = Paint().apply { isAntiAlias = true; textSize = 28f; color = 0xFF2A2A32.toInt() }
        val valuePaint = Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFD0D0D0.toInt() }
        val valueHighlight = Paint().apply { isAntiAlias = true; textSize = 22f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }

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

        // Section dividers
        val sectionLabelPaint = Paint().apply {
            isAntiAlias = true; textSize = 16f; color = 0xFF6B5080.toInt()
            letterSpacing = 0.15f; isFakeBoldText = true
        }
        val sections = mapOf(0 to "MODEL", 5 to "LIGHTING", 13 to "SYSTEM")

        for ((i, param) in params.withIndex()) {
            // Section header
            if (i in sections) {
                y += 4f  // extra padding before section header
                canvas.drawText(sections[i]!!, 56f, y + 2f, sectionLabelPaint)
                canvas.drawLine(56f + sectionLabelPaint.measureText(sections[i]!!) + 8f, y - 2f,
                    uiW - 40f, y - 2f, Paint().apply { color = 0x18EC4899.toInt(); strokeWidth = 1f })
                y += 6f
            }

            val rowTop = y - 4f
            val rowBot = y + rowH - 14f

            val isSelected = i == selectedParam
            val isHovered = i == hoveredMenuParam

            // Row background
            if (isSelected) {
                val glowPaint = Paint().apply {
                    isAntiAlias = true; color = 0x2030D8D0.toInt()
                    maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f, glowPaint)
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f,
                    Paint().apply { color = 0x2818C8C0.toInt() })
                val accentGlow = Paint().apply {
                    color = 0xFFEC4899.toInt(); isAntiAlias = true
                    maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f, accentGlow)
                canvas.drawRoundRect(24f, rowTop + 2f, 30f, rowBot - 2f, 3f, 3f,
                    Paint().apply { color = 0xFFEC4899.toInt() })
            } else if (isHovered) {
                canvas.drawRoundRect(28f, rowTop, uiW - 28f, rowBot, 6f, 6f,
                    Paint().apply { color = 0x14EC4899.toInt() })
            }

            val isPerModel = i <= 4
            val isDead = isPerModel && model == null
            val labelP = when {
                isDead -> disabled
                isSelected -> highlight
                isHovered -> Paint().apply {
                    isAntiAlias = true; textSize = 29f; color = 0xFFD8D0E0.toInt()
                }
                else -> normal
            }
            val arrow = if (isSelected) "\u25B6 " else "  "
            canvas.drawText("$arrow${param.first}", 44f, y + 14f, labelP)

            // Slider bar for continuous params (0-12)
            if (i <= 12) {
                val sliderLeft = 240f
                val sliderRight = uiW - 120f
                val sliderY = y + 6f
                val sliderH = 8f
                val range = paramRanges[i]
                val value = getParamValue(i)
                val t = ((value - range.first) / (range.second - range.first)).coerceIn(0f, 1f)
                val fillRight = sliderLeft + (sliderRight - sliderLeft) * t

                // Track
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f,
                    Paint().apply { color = 0xFF0E0E1C.toInt(); isAntiAlias = true })
                canvas.drawRoundRect(sliderLeft, sliderY, sliderRight, sliderY + sliderH, 4f, 4f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = 0.5f
                        color = 0xFF202035.toInt(); isAntiAlias = true
                    })

                if (!isDead) {
                    val fillPaint = Paint().apply { isAntiAlias = true }
                    if (isSelected) {
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF0A4040.toInt(), 0xFF30D8D0.toInt(),
                            Shader.TileMode.CLAMP)
                    } else if (isHovered) {
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF1A1028.toInt(), 0xFF9060B0.toInt(),
                            Shader.TileMode.CLAMP)
                    } else {
                        fillPaint.shader = LinearGradient(
                            sliderLeft, 0f, fillRight, 0f,
                            0xFF141424.toInt(), 0xFF3A4858.toInt(),
                            Shader.TileMode.CLAMP)
                    }
                    canvas.drawRoundRect(sliderLeft, sliderY, fillRight, sliderY + sliderH, 4f, 4f, fillPaint)

                    if (isSelected && t > 0.02f) {
                        val glowP = Paint().apply {
                            isAntiAlias = true; color = 0x6030D8D0.toInt()
                            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.drawCircle(fillRight, sliderY + sliderH / 2f, 6f, glowP)
                    }

                    // Thumb
                    val thumbX = fillRight.coerceIn(sliderLeft + 4f, sliderRight - 4f)
                    val thumbR = if (isSelected) 6f else 4f
                    if (isSelected) {
                        canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR + 3f,
                            Paint().apply {
                                isAntiAlias = true; color = 0x4030D8D0.toInt()
                                maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
                            })
                    }
                    canvas.drawCircle(thumbX, sliderY + sliderH / 2f, thumbR,
                        Paint().apply {
                            isAntiAlias = true
                            color = if (isSelected) 0xFFFFFFFF.toInt()
                                else if (isHovered) 0xFFC0B0D0.toInt()
                                else 0xFF707888.toInt()
                        })

                    val vp = if (isSelected) valueHighlight else valuePaint
                    val valStr = param.second
                    canvas.drawText(valStr, sliderRight + 10f, y + 16f, vp)
                } else {
                    canvas.drawText("---", sliderRight + 10f, y + 16f, disabled)
                }
            } else {
                // Toggle params (13-16)
                val valStr = param.second
                val vp = if (isSelected) valueHighlight else valuePaint
                val badgeLeft = uiW - 150f
                val badgeW = vp.measureText(valStr) + 24f
                val badgeY = y + 1f
                val badgeH = 20f
                val isOn = valStr.startsWith("ON") || valStr == "HIGH" || valStr == "MED" || valStr == "LOW"
                        || valStr == "4096" || valStr == "2048" || valStr == "1024"
                val badgeBg = if (isOn) {
                    if (isSelected) 0x4030D8D0.toInt() else 0x2010B981.toInt()
                } else {
                    0x18404050.toInt()
                }
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f,
                    Paint().apply { color = badgeBg; isAntiAlias = true })
                canvas.drawRoundRect(badgeLeft, badgeY, badgeLeft + badgeW, badgeY + badgeH, 10f, 10f,
                    Paint().apply {
                        style = Paint.Style.STROKE; strokeWidth = 0.8f
                        color = if (isOn && isSelected) 0x6030D8D0.toInt()
                            else if (isOn) 0x3010B981.toInt()
                            else 0x20505060.toInt()
                        isAntiAlias = true
                    })
                val textP = Paint().apply {
                    isAntiAlias = true; textSize = 18f; textAlign = Paint.Align.CENTER
                    color = if (isOn) {
                        if (isSelected) 0xFF30D8D0.toInt() else 0xFF10B981.toInt()
                    } else 0xFF606068.toInt()
                    isFakeBoldText = isSelected
                }
                canvas.drawText(valStr, badgeLeft + badgeW / 2f, badgeY + 15f, textP)
            }

            y += rowH
        }

        // Controls hint
        val hint = Paint().apply { isAntiAlias = true; textSize = 14f; color = 0xFF404048.toInt() }
        canvas.drawText("Stick:Adjust  A:Reset  B:Close  X:Gizmo  Y:Next  Grip:Grab", 40f, y + 4f, hint)

        // ── Action buttons (2 rows) ──
        val btnGap = 8f
        val btnH = 48f

        fun drawButton(bx1: Float, bx2: Float, by: Float, label: String, hovered: Boolean, normalColor: Int) {
            if (hovered) {
                val glow = Paint().apply {
                    style = Paint.Style.STROKE; strokeWidth = 4f; color = normalColor
                    maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.OUTER)
                    isAntiAlias = true
                }
                canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, glow)
            }
            val bg = Paint().apply {
                color = if (hovered) (normalColor and 0x00FFFFFF) or 0x70000000
                else (normalColor and 0x00FFFFFF) or 0x20000000
            }
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, bg)
            val border = Paint().apply {
                style = Paint.Style.STROKE; strokeWidth = if (hovered) 3f else 1.5f
                color = if (hovered) normalColor else (normalColor and 0x00FFFFFF) or 0x60000000.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(bx1, by, bx2, by + btnH, 10f, 10f, border)
            val text = Paint().apply {
                isAntiAlias = true; textSize = 24f; textAlign = Paint.Align.CENTER
                color = if (hovered) 0xFFFFFFFF.toInt() else normalColor; isFakeBoldText = true
            }
            canvas.drawText(label, (bx1 + bx2) / 2f, by + 35f, text)
        }

        // Row 1: SAVE / LOAD
        val row1Y = uiH - 170f
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

        // Row 3: AUDIO / FILE MENU / EXIT
        val row3Y = row2Y + btnH + btnGap
        drawButton(r2b1, r2b1 + btn3W, row3Y, "AUDIO", hoveredActionButton == 109, 0xFF8B5CF6.toInt())
        drawButton(r2b2, r2b2 + btn3W, row3Y, "FILE MENU", hoveredActionButton == 100, 0xFFEC4899.toInt())
        drawButton(r2b3, r2b3 + btn3W, row3Y, "EXIT", hoveredActionButton == 102, 0xFFF04858.toInt())

        // ── Sensor debug HUD overlay ──
        if (sensorHudVisible && sensorDebugStr.isNotEmpty()) {
            y = row1Y - 20f
            val sensorPaint = Paint().apply {
                isAntiAlias = true; textSize = 20f; color = 0xFF10B981.toInt()
                typeface = Typeface.MONOSPACE
            }
            val sensorBg = Paint().apply { color = 0xC0080810.toInt() }
            canvas.drawRoundRect(20f, y - 260f, uiW - 20f, y, 8f, 8f, sensorBg)
            var sy = y - 240f
            for (line in sensorDebugStr.lines()) {
                if (sy > y - 10f) break
                canvas.drawText(line, 30f, sy, sensorPaint)
                sy += 22f
            }
        }

        drawLaserCursorOverlay()
        pendingUiBitmap = bitmap
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
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        })

        layout.addView(TextView(activity).apply {
            text = "${models.size} model${if (models.size != 1) "s" else ""} in scene"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 16)
        })

        for ((i, model) in models.withIndex()) {
            val isSelected = i == selectedModelIndex
            layout.addView(LinearLayout(activity).apply {
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

                addView(TextView(activity).apply {
                    text = "${model.file.name} (${String.format("%.2f", model.scale)}x)"
                    textSize = 14f
                    setTextColor(0xFFE0E0E0.toInt())
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
                setBackgroundColor(if (activity.handsLocked) 0xFF1565C0.toInt() else 0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(20, 16, 20, 16)
                setOnClickListener {
                    activity.handsLocked = !activity.handsLocked
                    text = if (activity.handsLocked) "Unlock Hands" else "Lock Hands (walk around)"
                    setBackgroundColor(if (activity.handsLocked) 0xFF1565C0.toInt() else 0xFF333333.toInt())
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
                setBackgroundColor(0xFF333333.toInt())
                setTextColor(0xFFFFFFFF.toInt())
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
            setTextColor(0xFF666666.toInt())
        })

        layout.addView(makeSpacer(16))
        layout.addView(Button(activity).apply {
            text = "Back to Files"
            textSize = 16f
            setBackgroundColor(0xFF8B0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                activity.running = false
                activity.finish()
            }
        })

        scrollView.addView(layout)
        return scrollView
    }

    // ── UI Helpers ──

    fun showMessage(text: String) {
        activity.setContentView(TextView(activity).apply {
            this.text = text
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        })
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
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 8, 0, 4)
        }
    }

    fun makeSlider(label: String, min: Int, max: Int, initial: Int,
                   onChange: (Int) -> Unit): LinearLayout {
        val range = max - min
        val valueLabel = TextView(activity).apply {
            text = "$label: $initial"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
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
