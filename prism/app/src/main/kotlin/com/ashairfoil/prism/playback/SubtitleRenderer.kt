package com.ashairfoil.prism.playback

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * SubtitleRenderer — SRT and full ASS/SSA subtitle parsing and display.
 *
 * Pipeline:
 *   - SRT files → TextView overlay (simple plain-text path)
 *   - ASS/SSA files → StyledSubtitleView overlay with Canvas rendering
 *     (full override-tag support: colors, positioning, bold/italic, fades, etc.)
 *
 * Scans for subtitle files alongside the video file (same basename, .srt/.ass/.ssa
 * extension). Parses cue timing, renders as an overlay positioned at the bottom
 * of the viewport or wherever `\pos`/`\an` dictate.
 *
 * Usage:
 *   val renderer = SubtitleRenderer(parentLayout)
 *   renderer.loadForVideo(File("/storage/.../video.mp4"))
 *   // In playback loop:
 *   renderer.update(currentPositionMs)
 *   // On stop:
 *   renderer.clear()
 */
class SubtitleRenderer(private val parent: ViewGroup) {

    companion object {
        private const val TAG = "SubtitleRenderer"
        private val SRT_EXTENSIONS = listOf(".srt", ".SRT")
        private val ASS_EXTENSIONS = listOf(".ass", ".ssa", ".ASS", ".SSA")
        private val SRT_TIME_PATTERN: Pattern = Pattern.compile(
            "(\\d{2}):(\\d{2}):(\\d{2})[,.]?(\\d{0,3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.]?(\\d{0,3})"
        )
        private const val MAX_SUBTITLE_SIZE = 10L * 1024 * 1024 // 10 MB
    }

    /** Plain-text cue, used for SRT and as a fallback projection of styled lines. */
    data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private var cues: List<SubtitleCue> = emptyList()
    private var styledLines: List<StyledLine> = emptyList()
    private var scriptHeader: ScriptHeader = ScriptHeader()
    private var currentCueIndex: Int = -1
    private var currentStyledIndexes: IntArray = IntArray(0)
    private var subtitleView: TextView? = null
    private var styledView: StyledSubtitleView? = null
    private var fontManager: FontManager? = null
    private var isLoaded: Boolean = false
    private var isStyledMode: Boolean = false

    // Settings
    var fontSize: Float = 22f
        set(value) { field = value; subtitleView?.textSize = value }
    var fontColor: Int = Color.WHITE
        set(value) { field = value; subtitleView?.setTextColor(value) }
    var backgroundColor: Int = Color.argb(128, 0, 0, 0)
        set(value) { field = value; subtitleView?.setBackgroundColor(value) }
    var bottomMarginDp: Int = 40
    var isVisible: Boolean = true
        set(value) {
            field = value
            subtitleView?.visibility = if (value && currentCueIndex >= 0) View.VISIBLE else View.GONE
            styledView?.visibility = if (value && currentStyledIndexes.isNotEmpty()) View.VISIBLE else View.GONE
        }

    /**
     * Scan for subtitle files matching the video filename and load if found.
     * Returns true if subtitles were found and loaded.
     */
    fun loadForVideo(videoFile: File): Boolean {
        clear()
        val baseName = videoFile.nameWithoutExtension
        val dir = videoFile.parentFile ?: return false

        // Try SRT first (simpler, plain-text path), then ASS/SSA (styled path)
        for (ext in SRT_EXTENSIONS) {
            val subFile = File(dir, "$baseName$ext")
            if (subFile.exists() && subFile.canRead()) {
                if (subFile.length() > MAX_SUBTITLE_SIZE) {
                    Log.w(TAG, "Subtitle file too large (${subFile.length()} bytes), skipping: ${subFile.name}")
                    continue
                }
                cues = parseSrt(subFile)
                if (cues.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${cues.size} SRT cues from ${subFile.name}")
                    isLoaded = true
                    isStyledMode = false
                    ensureView()
                    return true
                }
            }
        }
        for (ext in ASS_EXTENSIONS) {
            val subFile = File(dir, "$baseName$ext")
            if (subFile.exists() && subFile.canRead()) {
                if (subFile.length() > MAX_SUBTITLE_SIZE) {
                    Log.w(TAG, "Subtitle file too large (${subFile.length()} bytes), skipping: ${subFile.name}")
                    continue
                }
                if (!loadAssStyled(subFile)) continue
                return true
            }
        }

        Log.i(TAG, "No subtitle files found for ${videoFile.name}")
        return false
    }

    private fun loadAssStyled(file: File): Boolean {
        return try {
            val result = SubtitleStyleParser().parseFile(file)
            if (result.lines.isEmpty()) return false
            styledLines = result.lines.sortedBy { it.startMs }
            scriptHeader = result.header
            // Flat cues preserved for hasSubtitles() callers and a safety-net fallback
            cues = styledLines.map { SubtitleCue(it.startMs, it.endMs, it.plainText()) }
            Log.i(TAG, "Loaded ${styledLines.size} styled ASS lines from ${file.name}")
            isLoaded = true
            isStyledMode = true
            ensureView()
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASS styled load failed: ${e.message}")
            false
        }
    }

    /**
     * Update subtitle display based on current playback position.
     * Call this from the playback loop (every ~100ms is fine).
     */
    fun update(positionMs: Long) {
        if (!isLoaded) return
        if (isStyledMode) updateStyled(positionMs) else updatePlain(positionMs)
    }

    private fun updatePlain(positionMs: Long) {
        if (cues.isEmpty()) return
        val cueIndex = findCueAt(positionMs)
        if (cueIndex != currentCueIndex) {
            currentCueIndex = cueIndex
            if (cueIndex >= 0) showText(cues[cueIndex].text) else hideText()
        }
    }

    private fun updateStyled(positionMs: Long) {
        if (styledLines.isEmpty()) return
        val active = collectActiveLines(positionMs)
        if (!sameAs(active, currentStyledIndexes)) {
            currentStyledIndexes = active
            if (active.isEmpty()) {
                styledView?.setActiveLines(emptyList(), 0L, ScriptHeader())
                styledView?.visibility = View.GONE
            } else {
                val lines = ArrayList<StyledLine>(active.size)
                for (i in active) lines.add(styledLines[i])
                styledView?.setActiveLines(lines, positionMs, scriptHeader)
                styledView?.visibility = if (isVisible) View.VISIBLE else View.GONE
            }
        } else if (active.isNotEmpty()) {
            styledView?.updatePosition(positionMs)
        }
    }

    private fun sameAs(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    private fun collectActiveLines(positionMs: Long): IntArray {
        // ASS lines can overlap — all lines whose [start,end] spans positionMs are active.
        // This is rare (usually 1 line) but the spec allows it.
        var count = 0
        val tmp = IntArray(styledLines.size)
        for (i in styledLines.indices) {
            val line = styledLines[i]
            if (positionMs in line.startMs..line.endMs) tmp[count++] = i
        }
        return tmp.copyOf(count)
    }

    fun clear() {
        cues = emptyList()
        styledLines = emptyList()
        currentCueIndex = -1
        currentStyledIndexes = IntArray(0)
        isLoaded = false
        isStyledMode = false
        hideText()
        styledView?.setActiveLines(emptyList(), 0L, ScriptHeader())
        styledView?.visibility = View.GONE
    }

    fun hasSubtitles(): Boolean = isLoaded && (cues.isNotEmpty() || styledLines.isNotEmpty())

    /** Release font cache. Called from Activity.onDestroy when the renderer is discarded. */
    fun release() {
        clear()
        fontManager?.clear()
        fontManager = null
    }

    // -----------------------------------------------------------------------
    // SRT Parser (unchanged — styled path handles ASS)
    // -----------------------------------------------------------------------

    private fun parseSrt(file: File): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
                var line: String?
                var state = 0 // 0=index, 1=timing, 2=text
                var startMs = 0L
                var endMs = 0L
                val textLines = mutableListOf<String>()

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()

                    when (state) {
                        0 -> {
                            if (trimmed.matches(Regex("\\d+"))) state = 1
                        }
                        1 -> {
                            val matcher = SRT_TIME_PATTERN.matcher(trimmed)
                            if (matcher.find()) {
                                startMs = srtTimeToMs(
                                    matcher.group(1)!!, matcher.group(2)!!,
                                    matcher.group(3)!!, matcher.group(4) ?: "0"
                                )
                                endMs = srtTimeToMs(
                                    matcher.group(5)!!, matcher.group(6)!!,
                                    matcher.group(7)!!, matcher.group(8) ?: "0"
                                )
                                textLines.clear()
                                state = 2
                            }
                        }
                        2 -> {
                            if (trimmed.isEmpty()) {
                                flushSrtCue(textLines, startMs, endMs, result)
                                state = 0
                            } else {
                                textLines.add(trimmed)
                            }
                        }
                    }
                }

                if (state == 2) flushSrtCue(textLines, startMs, endMs, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SRT: ${e.message}")
        }
        return result.sortedBy { it.startMs }
    }

    private fun flushSrtCue(
        textLines: MutableList<String>,
        startMs: Long,
        endMs: Long,
        out: MutableList<SubtitleCue>
    ) {
        if (textLines.isEmpty()) return
        val text = textLines.joinToString("\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\{[^}]*\\}"), "")
            .trim()
        if (text.isNotEmpty()) out.add(SubtitleCue(startMs, endMs, text))
    }

    private fun srtTimeToMs(h: String, m: String, s: String, ms: String): Long {
        val msStr = ms.padEnd(3, '0').take(3)
        return h.toLong() * 3600000 + m.toLong() * 60000 + s.toLong() * 1000 + msStr.toLong()
    }

    // -----------------------------------------------------------------------
    // Binary search for active plain cue
    // -----------------------------------------------------------------------

    private fun findCueAt(positionMs: Long): Int {
        if (cues.isEmpty()) return -1

        if (currentCueIndex in cues.indices) {
            val current = cues[currentCueIndex]
            if (positionMs in current.startMs..current.endMs) return currentCueIndex
        }

        val next = currentCueIndex + 1
        if (next in cues.indices) {
            val nextCue = cues[next]
            if (positionMs in nextCue.startMs..nextCue.endMs) return next
        }

        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    // -----------------------------------------------------------------------
    // View management — plain and styled
    // -----------------------------------------------------------------------

    private fun ensureView() {
        if (isStyledMode) ensureStyledView() else ensureTextView()
    }

    private fun ensureTextView() {
        styledView?.visibility = View.GONE
        if (subtitleView != null) return

        val tv = TextView(parent.context).apply {
            textSize = fontSize
            setTextColor(fontColor)
            setBackgroundColor(backgroundColor)
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            maxLines = 4
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (bottomMarginDp * parent.context.resources.displayMetrics.density).toInt()
        }

        val addAction = Runnable {
            parent.addView(tv, lp)
            subtitleView = tv
        }
        if (Looper.myLooper() == Looper.getMainLooper()) addAction.run() else parent.post(addAction)
    }

    private fun ensureStyledView() {
        subtitleView?.visibility = View.GONE
        if (styledView != null) return

        val fm = fontManager ?: FontManager(parent.context).also { fontManager = it }
        val sv = StyledSubtitleView(parent.context, fm)
        sv.visibility = View.GONE

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val addAction = Runnable {
            parent.addView(sv, lp)
            styledView = sv
        }
        if (Looper.myLooper() == Looper.getMainLooper()) addAction.run() else parent.post(addAction)
    }

    private fun showText(text: String) {
        subtitleView?.let {
            it.text = text
            it.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun hideText() {
        subtitleView?.visibility = View.GONE
    }
}

/**
 * StyledSubtitleView — Canvas-backed View that renders a set of active StyledLines.
 *
 * Paint, Path, and BlurMaskFilter objects are reused across draws. Only the Typeface
 * (served by FontManager cache) and BlurMaskFilter (cached per-strength) ever allocate,
 * and both are warmed on first use per style combo.
 *
 * Positioning honors ResX/ResY from the script header by scaling to the View's actual
 * pixel size. Alignment (1-9 keypad) anchors the text block; \pos overrides anchor.
 */
internal class StyledSubtitleView(
    context: Context,
    private val fontManager: FontManager
) : View(context) {

    // --- Reusable paints (zero per-draw allocation) ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val blurCache = HashMap<Int, BlurMaskFilter>(4)
    private val fontMetricsScratch = Paint.FontMetrics()

    // --- Active state (precomputed per line-change, not per onDraw) ---
    private var activeLines: List<StyledLine> = emptyList()
    private var activeLayouts: List<LineLayout> = emptyList()
    private var positionMs: Long = 0L
    private var header: ScriptHeader = ScriptHeader()

    init {
        outlinePaint.style = Paint.Style.STROKE
        textPaint.style = Paint.Style.FILL
        shadowPaint.style = Paint.Style.FILL
    }

    fun setActiveLines(lines: List<StyledLine>, positionMs: Long, header: ScriptHeader) {
        this.activeLines = lines.sortedBy { it.layer }
        this.activeLayouts = this.activeLines.map { LineLayout(it, splitIntoVisualLines(it.spans)) }
        this.positionMs = positionMs
        this.header = header
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        // Only re-invalidate if a line has active fade — otherwise no visual change
        val hasFade = activeLines.any { it.fadeInMs > 0 || it.fadeOutMs > 0 }
        this.positionMs = positionMs
        if (hasFade) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (activeLayouts.isEmpty()) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scaleX = viewW / header.playResX.toFloat()
        val scaleY = viewH / header.playResY.toFloat()

        for (layout in activeLayouts) drawLayout(canvas, layout, scaleX, scaleY, viewW, viewH)
    }

    private fun drawLayout(
        canvas: Canvas,
        layout: LineLayout,
        scaleX: Float,
        scaleY: Float,
        viewW: Float,
        viewH: Float
    ) {
        val line = layout.line
        val alphaMul = computeFadeAlpha(line)
        if (alphaMul <= 0.001f) return

        val firstStyle = line.spans.firstOrNull()?.style ?: SubtitleStyle.DEFAULT
        configurePaintForMeasure(firstStyle, scaleY)
        textPaint.getFontMetrics(fontMetricsScratch)
        val lineHeight = fontMetricsScratch.descent - fontMetricsScratch.ascent
        val ascent = fontMetricsScratch.ascent

        val blocks = layout.visualLines
        measureAllBlocks(blocks, scaleY, layout.measureScratch)
        val maxWidth = layout.maxWidth()
        val blockHeight = lineHeight * blocks.size

        val anchor = resolveAnchor(line, scaleX, scaleY, viewW, viewH, maxWidth, blockHeight)
        var y = anchor.second - ascent
        for (i in blocks.indices) {
            val lineWidth = layout.measureScratch[i]
            val x = anchor.first + horizontalIndent(line.alignment, maxWidth, lineWidth)
            drawVisualLine(canvas, blocks[i], x, y, scaleY, alphaMul)
            y += lineHeight
        }
    }

    private fun measureAllBlocks(blocks: List<List<StyledSpan>>, scaleY: Float, out: FloatArray) {
        for (i in blocks.indices) out[i] = measureVisualLine(blocks[i], scaleY)
    }

    private fun configurePaintForMeasure(style: SubtitleStyle, scaleY: Float) {
        textPaint.typeface = fontManager.resolve(style.fontName, style.isBold, style.isItalic)
        textPaint.textSize = style.fontSize * scaleY * style.scaleY
        textPaint.textScaleX = style.scaleX
        textPaint.letterSpacing = (style.spacing / style.fontSize).coerceIn(-0.5f, 1.0f)
        textPaint.isUnderlineText = style.isUnderline
        textPaint.isStrikeThruText = style.isStrikethrough
    }

    private fun measureVisualLine(spans: List<StyledSpan>, scaleY: Float): Float {
        var total = 0f
        for (span in spans) {
            if (span.text.isEmpty()) continue
            configurePaintForMeasure(span.style, scaleY)
            total += textPaint.measureText(span.text)
        }
        return total
    }

    /** Break spans at embedded newlines into visual lines, preserving style per-run. */
    private fun splitIntoVisualLines(spans: List<StyledSpan>): List<List<StyledSpan>> {
        val out = ArrayList<MutableList<StyledSpan>>()
        var current: MutableList<StyledSpan> = ArrayList()
        out.add(current)
        for (span in spans) {
            val parts = span.text.split('\n')
            for (pIdx in parts.indices) {
                val part = parts[pIdx]
                if (part.isNotEmpty()) current.add(StyledSpan(part, span.style))
                if (pIdx < parts.size - 1) {
                    current = ArrayList()
                    out.add(current)
                }
            }
        }
        return out
    }

    private fun resolveAnchor(
        line: StyledLine,
        scaleX: Float,
        scaleY: Float,
        viewW: Float,
        viewH: Float,
        textW: Float,
        textH: Float
    ): Pair<Float, Float> {
        val posX = line.posX
        val posY = line.posY
        if (posX != null && posY != null) {
            return anchorFromPos(line.alignment, posX * scaleX, posY * scaleY, textW, textH)
        }
        return anchorFromAlignment(line, scaleX, scaleY, viewW, viewH, textW, textH)
    }

    private fun anchorFromPos(
        alignment: Int,
        x: Float,
        y: Float,
        textW: Float,
        textH: Float
    ): Pair<Float, Float> {
        val anchorX = when (horizontalOf(alignment)) {
            Horizontal.LEFT -> x
            Horizontal.CENTER -> x - textW / 2f
            Horizontal.RIGHT -> x - textW
        }
        val anchorY = when (verticalOf(alignment)) {
            Vertical.TOP -> y
            Vertical.MIDDLE -> y - textH / 2f
            Vertical.BOTTOM -> y - textH
        }
        return Pair(anchorX, anchorY)
    }

    private fun anchorFromAlignment(
        line: StyledLine,
        scaleX: Float,
        scaleY: Float,
        viewW: Float,
        viewH: Float,
        textW: Float,
        textH: Float
    ): Pair<Float, Float> {
        val mL = line.marginLeft * scaleX
        val mR = line.marginRight * scaleX
        val mV = line.marginVertical * scaleY
        val anchorX = when (horizontalOf(line.alignment)) {
            Horizontal.LEFT -> mL
            Horizontal.CENTER -> (viewW - textW) / 2f
            Horizontal.RIGHT -> viewW - textW - mR
        }
        val anchorY = when (verticalOf(line.alignment)) {
            Vertical.TOP -> mV
            Vertical.MIDDLE -> (viewH - textH) / 2f
            Vertical.BOTTOM -> viewH - textH - mV
        }
        return Pair(anchorX, anchorY)
    }

    private fun horizontalIndent(alignment: Int, blockWidth: Float, lineWidth: Float): Float {
        return when (horizontalOf(alignment)) {
            Horizontal.LEFT -> 0f
            Horizontal.CENTER -> (blockWidth - lineWidth) / 2f
            Horizontal.RIGHT -> blockWidth - lineWidth
        }
    }

    private fun drawVisualLine(
        canvas: Canvas,
        spans: List<StyledSpan>,
        startX: Float,
        baselineY: Float,
        scaleY: Float,
        alphaMul: Float
    ) {
        var x = startX
        for (span in spans) {
            if (span.text.isEmpty()) continue
            x = drawSpan(canvas, span, x, baselineY, scaleY, alphaMul)
        }
    }

    private fun drawSpan(
        canvas: Canvas,
        span: StyledSpan,
        x: Float,
        baselineY: Float,
        scaleY: Float,
        alphaMul: Float
    ): Float {
        val style = span.style
        val text = span.text

        configurePaintForMeasure(style, scaleY)
        val spanWidth = textPaint.measureText(text)

        // Draw order (back-to-front): shadow, outline, fill
        if (style.shadowOffset > 0f) drawShadow(canvas, text, style, x, baselineY, scaleY, alphaMul)
        if (style.borderWidth > 0f) drawOutline(canvas, text, style, x, baselineY, scaleY, alphaMul)
        drawFill(canvas, text, style, x, baselineY, alphaMul)
        return x + spanWidth
    }

    private fun drawShadow(
        canvas: Canvas,
        text: String,
        style: SubtitleStyle,
        x: Float,
        baselineY: Float,
        scaleY: Float,
        alphaMul: Float
    ) {
        shadowPaint.typeface = textPaint.typeface
        shadowPaint.textSize = textPaint.textSize
        shadowPaint.textScaleX = textPaint.textScaleX
        shadowPaint.letterSpacing = textPaint.letterSpacing
        shadowPaint.color = applyAlpha(style.shadowColor, style.shadowAlpha, alphaMul)
        shadowPaint.maskFilter = blurFor(style.blurStrength)
        val off = style.shadowOffset * scaleY
        canvas.drawText(text, x + off, baselineY + off, shadowPaint)
    }

    private fun drawOutline(
        canvas: Canvas,
        text: String,
        style: SubtitleStyle,
        x: Float,
        baselineY: Float,
        scaleY: Float,
        alphaMul: Float
    ) {
        outlinePaint.typeface = textPaint.typeface
        outlinePaint.textSize = textPaint.textSize
        outlinePaint.textScaleX = textPaint.textScaleX
        outlinePaint.letterSpacing = textPaint.letterSpacing
        outlinePaint.strokeWidth = (style.borderWidth * scaleY * 2f).coerceAtLeast(1f)
        outlinePaint.color = applyAlpha(style.outlineColor, style.outlineAlpha, alphaMul)
        outlinePaint.maskFilter = blurFor(style.blurStrength)
        outlinePaint.isUnderlineText = style.isUnderline
        outlinePaint.isStrikeThruText = style.isStrikethrough
        canvas.drawText(text, x, baselineY, outlinePaint)
    }

    private fun drawFill(
        canvas: Canvas,
        text: String,
        style: SubtitleStyle,
        x: Float,
        baselineY: Float,
        alphaMul: Float
    ) {
        textPaint.color = applyAlpha(style.primaryColor, style.primaryAlpha, alphaMul)
        textPaint.maskFilter = null
        canvas.drawText(text, x, baselineY, textPaint)
    }

    /** Fetch or construct a blur mask filter for the given integer-rounded strength. */
    private fun blurFor(strength: Float): BlurMaskFilter? {
        if (strength <= 0f) return null
        val key = (strength * 2f).toInt().coerceAtLeast(1)
        blurCache[key]?.let { return it }
        val filter = BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL)
        blurCache[key] = filter
        return filter
    }

    private fun applyAlpha(color: Int, channelAlpha: Int, multiplier: Float): Int {
        val baseA = (color ushr 24) and 0xFF
        val effective = ((channelAlpha * multiplier).coerceIn(0f, 255f)).toInt()
        val finalA = (baseA * effective / 255).coerceIn(0, 255)
        return (finalA shl 24) or (color and 0x00FFFFFF)
    }

    private fun computeFadeAlpha(line: StyledLine): Float {
        val fi = line.fadeInMs
        val fo = line.fadeOutMs
        if (fi <= 0 && fo <= 0) return 1f
        val elapsed = positionMs - line.startMs
        val remaining = line.endMs - positionMs
        val fadeIn = if (fi > 0 && elapsed < fi) elapsed.toFloat() / fi else 1f
        val fadeOut = if (fo > 0 && remaining < fo) remaining.toFloat() / fo else 1f
        return (fadeIn.coerceIn(0f, 1f) * fadeOut.coerceIn(0f, 1f))
    }

    // --- Alignment helpers ---

    private enum class Horizontal { LEFT, CENTER, RIGHT }
    private enum class Vertical { TOP, MIDDLE, BOTTOM }

    private fun horizontalOf(alignment: Int): Horizontal = when (alignment % 3) {
        1 -> Horizontal.LEFT
        0 -> Horizontal.RIGHT
        else -> Horizontal.CENTER
    }

    private fun verticalOf(alignment: Int): Vertical = when (alignment) {
        in 1..3 -> Vertical.BOTTOM
        in 4..6 -> Vertical.MIDDLE
        in 7..9 -> Vertical.TOP
        else -> Vertical.BOTTOM
    }

    /**
     * LineLayout — precomputed per-line data (split visual-lines, measure scratch).
     * Allocated once in setActiveLines, reused across draws so onDraw is allocation-free.
     */
    private class LineLayout(
        val line: StyledLine,
        val visualLines: List<List<StyledSpan>>
    ) {
        val measureScratch: FloatArray = FloatArray(visualLines.size)
        fun maxWidth(): Float {
            var m = 0f
            for (v in measureScratch) if (v > m) m = v
            return m
        }
    }
}
