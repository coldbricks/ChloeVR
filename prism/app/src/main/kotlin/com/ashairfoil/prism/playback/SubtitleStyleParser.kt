package com.ashairfoil.prism.playback

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.util.regex.Pattern

/**
 * SubtitleStyleParser — full ASS/SSA parser with override-tag tokenization.
 *
 * Pipeline:
 *   1. Read sections: `[Script Info]`, `[V4+ Styles]`/`[V4 Styles]`, `[Events]`
 *   2. Parse named styles into a `Map<String, AssStyle>`
 *   3. For each Dialogue line, resolve the base style then tokenize `{...}` override
 *      blocks into a sequence of StyledSpans as state mutates
 *
 * Producer/consumer split from SubtitleRenderer: this class knows nothing about
 * Canvas or Paint; the renderer knows nothing about ASS syntax.
 *
 * The inner override-tag grammar handled here:
 *   \b<0|1|weight>     bold
 *   \i<0|1>            italic
 *   \u<0|1>            underline
 *   \s<0|1>            strikethrough
 *   \c&H<BGR>&         primary color (alias of \1c)
 *   \1c, \2c, \3c, \4c primary/secondary/outline/shadow color
 *   \alpha&H<AA>&      all-channel alpha
 *   \1a, \2a, \3a, \4a per-channel alpha
 *   \fs<n>             font size
 *   \fn<name>          font name
 *   \bord<n>           outline width
 *   \shad<n>           shadow offset
 *   \blur<n>           gaussian blur (edge)
 *   \be<n>             edge blur (treated as blur)
 *   \fscx<n> \fscy<n>  x/y scale percent
 *   \fsp<n>            letter spacing
 *   \frz<n>            rotation (stored; renderer applies on Canvas rotate)
 *   \an<n>             alignment (1-9 keypad; line-level)
 *   \a<n>              legacy SSA alignment (line-level)
 *   \pos(x,y)          absolute position (line-level)
 *   \fad(in,out)       simple fade (line-level)
 *   \fade(...)         complex fade — first two values used
 *   \k / \K / \kf      karaoke (stored as marker in span metadata; optional)
 *   \t(...)            transforms (parsed shallowly — only final values honored)
 *   \r / \r<style>     reset to default / named style
 *
 * Everything else is silently dropped (logged at debug level once per file).
 */
class SubtitleStyleParser {

    companion object {
        private const val TAG = "SubtitleStyleParser"
        // Opaque ARGB defaults, kept here to avoid android.graphics.Color dep in the parser.
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_BLACK = 0xFF000000.toInt()
        private const val COLOR_YELLOW = 0xFFFFFF00.toInt()

        private val ASS_TIME_PATTERN: Pattern = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})"
        )
        private val COLOR_PATTERN: Pattern = Pattern.compile(
            "&H?([0-9A-Fa-f]{1,8})&?"
        )
        private val POS_PATTERN: Pattern = Pattern.compile(
            "pos\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)"
        )
        private val FAD_PATTERN: Pattern = Pattern.compile(
            "fad\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)"
        )
        private val FADE_PATTERN: Pattern = Pattern.compile(
            "fade\\(([^)]+)\\)"
        )
    }

    /** Result of parsing an ASS file end-to-end. */
    data class ParseResult(
        val header: ScriptHeader,
        val styles: Map<String, AssStyle>,
        val lines: List<StyledLine>
    )

    // -----------------------------------------------------------------------
    // Top-level entry points
    // -----------------------------------------------------------------------

    /** Parse an on-disk ASS/SSA file. Returns an empty result on I/O error. */
    fun parseFile(file: File): ParseResult {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use {
                parse(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseFile failed: ${e.message}")
            ParseResult(ScriptHeader(), mapOf("Default" to AssStyle.DEFAULT), emptyList())
        }
    }

    /** Parse from a string — handy for tests and inline fixtures. */
    fun parseString(content: String): ParseResult {
        return BufferedReader(StringReader(content)).use { parse(it) }
    }

    // -----------------------------------------------------------------------
    // Section dispatcher
    // -----------------------------------------------------------------------

    private fun parse(reader: Reader): ParseResult {
        val buf = if (reader is BufferedReader) reader else BufferedReader(reader)
        val state = ParseState()

        var line: String?
        while (buf.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("!:")) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                state.section = detectSection(trimmed)
                continue
            }

            when (state.section) {
                Section.SCRIPT_INFO -> parseHeaderLine(trimmed, state)
                Section.V4_STYLES -> parseStyleLine(trimmed, state)
                Section.EVENTS -> parseEventLine(trimmed, state)
                Section.OTHER -> {}
            }
        }

        if (state.styles.isEmpty()) state.styles["Default"] = AssStyle.DEFAULT
        return ParseResult(state.header, state.styles, state.lines)
    }

    private fun detectSection(header: String): Section {
        val name = header.substring(1, header.length - 1).trim().lowercase()
        return when {
            name == "script info" -> Section.SCRIPT_INFO
            name == "v4+ styles" || name == "v4 styles" || name == "v4+styles" -> Section.V4_STYLES
            name == "events" -> Section.EVENTS
            else -> Section.OTHER
        }
    }

    // -----------------------------------------------------------------------
    // Header ([Script Info])
    // -----------------------------------------------------------------------

    private fun parseHeaderLine(line: String, state: ParseState) {
        val colon = line.indexOf(':')
        if (colon < 0) return
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        when (key) {
            "playresx" -> state.playResX = value.toIntOrNull() ?: state.playResX
            "playresy" -> state.playResY = value.toIntOrNull() ?: state.playResY
            "wrapstyle" -> state.wrapStyle = value.toIntOrNull() ?: 0
            "scaledborderandshadow" -> state.scaledBorderAndShadow = value.equals("yes", ignoreCase = true)
        }
        state.header = ScriptHeader(state.playResX, state.playResY, state.wrapStyle, state.scaledBorderAndShadow)
    }

    // -----------------------------------------------------------------------
    // Styles ([V4+ Styles])
    // -----------------------------------------------------------------------

    private fun parseStyleLine(line: String, state: ParseState) {
        if (line.startsWith("Format:", ignoreCase = true)) {
            state.styleFormat = line.substringAfter(":").split(",").map { it.trim() }
            return
        }
        if (!line.startsWith("Style:", ignoreCase = true)) return
        val fields = line.substringAfter(":").split(",").map { it.trim() }
        val format = state.styleFormat
        if (format.isEmpty() || fields.size < 2) return

        val get = { key: String ->
            val idx = format.indexOfFirst { it.equals(key, ignoreCase = true) }
            if (idx in fields.indices) fields[idx] else ""
        }

        val name = get("Name").ifEmpty { "Default" }
        val style = SubtitleStyle(
            fontName = get("Fontname").ifEmpty { "Arial" },
            fontSize = get("Fontsize").toFloatOrNull() ?: 22f,
            isBold = get("Bold").toIntOrNull().let { it != null && it != 0 },
            isItalic = get("Italic").toIntOrNull().let { it != null && it != 0 },
            isUnderline = get("Underline").toIntOrNull().let { it != null && it != 0 },
            isStrikethrough = get("StrikeOut").toIntOrNull().let { it != null && it != 0 },
            primaryColor = parseAssColor(get("PrimaryColour"), COLOR_WHITE).first,
            primaryAlpha = parseAssColor(get("PrimaryColour"), COLOR_WHITE).second,
            secondaryColor = parseAssColor(get("SecondaryColour"), COLOR_YELLOW).first,
            secondaryAlpha = parseAssColor(get("SecondaryColour"), COLOR_YELLOW).second,
            outlineColor = parseAssColor(get("OutlineColour"), COLOR_BLACK).first,
            outlineAlpha = parseAssColor(get("OutlineColour"), COLOR_BLACK).second,
            shadowColor = parseAssColor(get("BackColour"), COLOR_BLACK).first,
            shadowAlpha = parseAssColor(get("BackColour"), COLOR_BLACK).second,
            borderWidth = get("Outline").toFloatOrNull() ?: 2f,
            shadowOffset = get("Shadow").toFloatOrNull() ?: 0f,
            scaleX = (get("ScaleX").toFloatOrNull() ?: 100f) / 100f,
            scaleY = (get("ScaleY").toFloatOrNull() ?: 100f) / 100f,
            spacing = get("Spacing").toFloatOrNull() ?: 0f,
            angle = get("Angle").toFloatOrNull() ?: 0f
        )
        val alignment = translateAlignment(get("Alignment").toIntOrNull() ?: 2, fromLegacy = false)
        val assStyle = AssStyle(
            name = name,
            style = style,
            alignment = alignment,
            marginLeft = get("MarginL").toIntOrNull() ?: 20,
            marginRight = get("MarginR").toIntOrNull() ?: 20,
            marginVertical = get("MarginV").toIntOrNull() ?: 20
        )
        state.styles[name] = assStyle
    }

    // -----------------------------------------------------------------------
    // Events ([Events])
    // -----------------------------------------------------------------------

    private fun parseEventLine(line: String, state: ParseState) {
        if (line.startsWith("Format:", ignoreCase = true)) {
            state.eventFormat = line.substringAfter(":").split(",").map { it.trim() }
            state.eventFieldIndex = indexEventFields(state.eventFormat)
            return
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true)) return
        val idx = state.eventFieldIndex ?: return
        val rawFields = line.substringAfter(":").split(",", limit = state.eventFormat.size)
        if (rawFields.size < idx.maxNeeded()) return

        val styleName = rawFields.getOrNull(idx.style)?.trim().orEmpty()
        val baseAss = state.styles[styleName] ?: state.styles["Default"] ?: AssStyle.DEFAULT
        val rawText = rawFields[idx.text].trim()
        val startMs = parseTimeMs(rawFields[idx.start].trim())
        val endMs = parseTimeMs(rawFields[idx.end].trim())
        if (endMs <= startMs) return

        val lineCtx = LineContext(
            baseStyle = baseAss,
            currentStyle = baseAss.style,
            alignment = baseAss.alignment,
            marginLeft = rawFields.getOrNull(idx.marginL)?.trim()?.toIntOrNull()?.takeIf { it != 0 } ?: baseAss.marginLeft,
            marginRight = rawFields.getOrNull(idx.marginR)?.trim()?.toIntOrNull()?.takeIf { it != 0 } ?: baseAss.marginRight,
            marginVertical = rawFields.getOrNull(idx.marginV)?.trim()?.toIntOrNull()?.takeIf { it != 0 } ?: baseAss.marginVertical,
            layer = rawFields.getOrNull(idx.layer)?.trim()?.toIntOrNull() ?: 0,
            knownStyles = state.styles
        )

        val spans = tokenizeOverrides(rawText, lineCtx)
        if (spans.none { it.text.isNotEmpty() }) return

        state.lines.add(
            StyledLine(
                startMs = startMs,
                endMs = endMs,
                spans = spans,
                alignment = lineCtx.alignment,
                posX = lineCtx.posX,
                posY = lineCtx.posY,
                marginLeft = lineCtx.marginLeft,
                marginRight = lineCtx.marginRight,
                marginVertical = lineCtx.marginVertical,
                fadeInMs = lineCtx.fadeInMs,
                fadeOutMs = lineCtx.fadeOutMs,
                layer = lineCtx.layer
            )
        )
    }

    private fun indexEventFields(format: List<String>): EventFieldIndex? {
        if (format.isEmpty()) return null
        val locate = { name: String ->
            format.indexOfFirst { it.equals(name, ignoreCase = true) }
        }
        return EventFieldIndex(
            layer = locate("Layer"),
            start = locate("Start"),
            end = locate("End"),
            style = locate("Style"),
            marginL = locate("MarginL"),
            marginR = locate("MarginR"),
            marginV = locate("MarginV"),
            text = locate("Text")
        ).takeIf { it.start >= 0 && it.end >= 0 && it.text >= 0 }
    }

    // -----------------------------------------------------------------------
    // Override tokenizer — the meat of the ASS spec
    // -----------------------------------------------------------------------

    private fun tokenizeOverrides(raw: String, ctx: LineContext): List<StyledSpan> {
        val spans = ArrayList<StyledSpan>(4)
        val textBuf = StringBuilder()
        var i = 0
        val n = raw.length

        while (i < n) {
            val c = raw[i]
            if (c == '{') {
                flushBuf(textBuf, ctx.currentStyle, spans)
                val close = raw.indexOf('}', i + 1)
                if (close < 0) { i = n; break }
                applyOverrideBlock(raw.substring(i + 1, close), ctx)
                i = close + 1
            } else if (c == '\\' && i + 1 < n) {
                val next = raw[i + 1]
                when (next) {
                    'N', 'n' -> { textBuf.append('\n'); i += 2 }
                    'h' -> { textBuf.append('\u00A0'); i += 2 }
                    else -> { textBuf.append(c); i++ }
                }
            } else {
                textBuf.append(c)
                i++
            }
        }
        flushBuf(textBuf, ctx.currentStyle, spans)
        return spans
    }

    private fun flushBuf(buf: StringBuilder, style: SubtitleStyle, out: MutableList<StyledSpan>) {
        if (buf.isEmpty()) return
        out.add(StyledSpan(buf.toString(), style))
        buf.setLength(0)
    }

    private fun applyOverrideBlock(block: String, ctx: LineContext) {
        // Split on backslashes, but preserve parenthesized args (e.g. pos(1,2), t(0,500,\c...))
        val tags = splitTags(block)
        for (tag in tags) {
            if (tag.isEmpty()) continue
            applySingleTag(tag, ctx)
        }
    }

    private fun splitTags(block: String): List<String> {
        val out = ArrayList<String>(4)
        var depth = 0
        var start = 0
        var i = 0
        while (i < block.length) {
            val c = block[i]
            when (c) {
                '(' -> depth++
                ')' -> depth--
                '\\' -> if (depth == 0) {
                    if (i > start) out.add(block.substring(start, i).trim())
                    start = i + 1
                }
            }
            i++
        }
        if (start < block.length) out.add(block.substring(start).trim())
        return out
    }

    private fun applySingleTag(tag: String, ctx: LineContext) {
        // Tag grammar is irregular — most tags are "[digit]?<letter>+ args" but a few
        // carry an alphanumeric/identifier arg whose grammar overlaps the name scan:
        //   \fn<fontname>    — fontname is any characters until end of tag
        //   \r<stylename>    — stylename is any characters until end of tag
        //   \iclip / \clip   — paren'd argument, name ends before "("
        // So we match those longest-first; fall back to generic letter scan otherwise.
        val (name, args) = extractNameAndArgs(tag)

        when (name) {
            "b" -> ctx.currentStyle = ctx.currentStyle.copy(isBold = parseIntArg(args) != 0)
            "i" -> ctx.currentStyle = ctx.currentStyle.copy(isItalic = parseIntArg(args) != 0)
            "u" -> ctx.currentStyle = ctx.currentStyle.copy(isUnderline = parseIntArg(args) != 0)
            "s" -> ctx.currentStyle = ctx.currentStyle.copy(isStrikethrough = parseIntArg(args) != 0)
            "fs" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(fontSize = it) }
            "fn" -> ctx.currentStyle = ctx.currentStyle.copy(fontName = args.trim().ifEmpty { "Arial" })
            "bord" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(borderWidth = it) }
            "shad" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(shadowOffset = it) }
            "blur", "be" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(blurStrength = it) }
            "fscx" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(scaleX = it / 100f) }
            "fscy" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(scaleY = it / 100f) }
            "fsp" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(spacing = it) }
            "frz", "fr" -> parseFloatArg(args)?.let { ctx.currentStyle = ctx.currentStyle.copy(angle = it) }
            "an" -> parseIntArg(args).takeIf { it in 1..9 }?.let { ctx.alignment = it }
            "a" -> ctx.alignment = translateAlignment(parseIntArg(args), fromLegacy = true)
            "pos" -> applyPos(args, ctx)
            "fad" -> applyFad(args, ctx)
            "fade" -> applyFade(args, ctx)
            "c", "1c" -> applyColor(args, 1, ctx)
            "2c" -> applyColor(args, 2, ctx)
            "3c" -> applyColor(args, 3, ctx)
            "4c" -> applyColor(args, 4, ctx)
            "alpha" -> applyAlpha(args, 0, ctx)
            "1a" -> applyAlpha(args, 1, ctx)
            "2a" -> applyAlpha(args, 2, ctx)
            "3a" -> applyAlpha(args, 3, ctx)
            "4a" -> applyAlpha(args, 4, ctx)
            "r" -> applyReset(args, ctx)
            "k", "K", "kf", "ko" -> { /* karaoke - span emitted by caller; ignored for now */ }
            "t" -> applyTransform(args, ctx)
            "q" -> { /* wrap style override - ignored, renderer uses header */ }
            "clip", "iclip" -> { /* clip regions - not implemented, deferred */ }
            "move" -> { /* animated movement - treated as static pos */ applyMove(args, ctx) }
            "org" -> { /* rotation origin - deferred */ }
            else -> { /* unknown tag - drop silently */ }
        }
    }

    // -----------------------------------------------------------------------
    // Tag-specific helpers
    // -----------------------------------------------------------------------

    /**
     * Split a tag into (name, args). Special-cases tags where the generic
     * letter-consuming scan would eat legitimate argument content.
     */
    private fun extractNameAndArgs(tag: String): Pair<String, String> {
        if (tag.isEmpty()) return Pair("", "")
        // fn: font name — "fn" + everything until end
        if (tag.length >= 2 && tag[0] == 'f' && tag[1] == 'n') {
            return Pair("fn", tag.substring(2))
        }
        // r: style reset — "r" followed by an optional style name. The style name
        // is anything that follows, taken verbatim.
        if (tag[0] == 'r') {
            return Pair("r", tag.substring(1))
        }
        // iclip / clip — paren args
        if (tag.startsWith("iclip")) return Pair("iclip", tag.substring(5))
        if (tag.startsWith("clip")) return Pair("clip", tag.substring(4))
        // Generic: [digit]?<letter>+ — digit prefix for \1c \2a \3c etc.
        var splitAt = 0
        if (tag[splitAt].isDigit()) splitAt++
        while (splitAt < tag.length && tag[splitAt].isLetter()) splitAt++
        return Pair(tag.substring(0, splitAt), tag.substring(splitAt))
    }

    private fun parseIntArg(args: String): Int {
        val trimmed = args.trim()
        if (trimmed.isEmpty()) return 1
        return trimmed.toIntOrNull() ?: if (trimmed == "0") 0 else 1
    }

    private fun parseFloatArg(args: String): Float? {
        return args.trim().toFloatOrNull()
    }

    private fun applyColor(args: String, channel: Int, ctx: LineContext) {
        val (color, alpha) = parseAssColor(args, ctx.currentStyle.primaryColor)
        ctx.currentStyle = when (channel) {
            1 -> ctx.currentStyle.copy(primaryColor = color)
            2 -> ctx.currentStyle.copy(secondaryColor = color)
            3 -> ctx.currentStyle.copy(outlineColor = color)
            4 -> ctx.currentStyle.copy(shadowColor = color)
            else -> ctx.currentStyle
        }
        // Color tag may carry 8-hex form "&HAABBGGRR&" — if so, alpha came along for the ride
        if (args.trim().removePrefix("&H").removePrefix("&").length >= 8) {
            ctx.currentStyle = when (channel) {
                1 -> ctx.currentStyle.copy(primaryAlpha = alpha)
                2 -> ctx.currentStyle.copy(secondaryAlpha = alpha)
                3 -> ctx.currentStyle.copy(outlineAlpha = alpha)
                4 -> ctx.currentStyle.copy(shadowAlpha = alpha)
                else -> ctx.currentStyle
            }
        }
    }

    private fun applyAlpha(args: String, channel: Int, ctx: LineContext) {
        val a = parseAlphaHex(args) ?: return
        ctx.currentStyle = when (channel) {
            0 -> ctx.currentStyle.copy(
                primaryAlpha = a,
                secondaryAlpha = a,
                outlineAlpha = a,
                shadowAlpha = a
            )
            1 -> ctx.currentStyle.copy(primaryAlpha = a)
            2 -> ctx.currentStyle.copy(secondaryAlpha = a)
            3 -> ctx.currentStyle.copy(outlineAlpha = a)
            4 -> ctx.currentStyle.copy(shadowAlpha = a)
            else -> ctx.currentStyle
        }
    }

    private fun applyReset(args: String, ctx: LineContext) {
        val name = args.trim()
        val target = if (name.isEmpty()) ctx.baseStyle else (ctx.knownStyles[name] ?: ctx.baseStyle)
        ctx.currentStyle = target.style
    }

    private fun applyPos(args: String, ctx: LineContext) {
        // args is like "(x,y)"; reconstruct a pattern-matchable slug
        val m = POS_PATTERN.matcher("pos$args")
        if (m.find()) {
            ctx.posX = m.group(1)?.toFloatOrNull()
            ctx.posY = m.group(2)?.toFloatOrNull()
        }
    }

    private fun applyMove(args: String, ctx: LineContext) {
        // \move(x1,y1,x2,y2[,t1,t2]) — anchor at final position for static render
        val inner = args.trim().removePrefix("(").removeSuffix(")")
        val parts = inner.split(",").map { it.trim() }
        if (parts.size >= 4) {
            ctx.posX = parts[2].toFloatOrNull() ?: ctx.posX
            ctx.posY = parts[3].toFloatOrNull() ?: ctx.posY
        }
    }

    private fun applyFad(args: String, ctx: LineContext) {
        val m = FAD_PATTERN.matcher("fad$args")
        if (m.find()) {
            ctx.fadeInMs = m.group(1)?.toIntOrNull() ?: 0
            ctx.fadeOutMs = m.group(2)?.toIntOrNull() ?: 0
        }
    }

    private fun applyFade(args: String, ctx: LineContext) {
        // \fade(a1,a2,a3,t1,t2,t3,t4) — 7 args; treat as fade in t2-t1, fade out t4-t3
        val m = FADE_PATTERN.matcher("fade$args")
        if (!m.find()) return
        val parts = m.group(1)!!.split(",").map { it.trim().toIntOrNull() ?: 0 }
        if (parts.size >= 7) {
            ctx.fadeInMs = (parts[4] - parts[3]).coerceAtLeast(0)
            ctx.fadeOutMs = (parts[6] - parts[5]).coerceAtLeast(0)
        }
    }

    private fun applyTransform(args: String, ctx: LineContext) {
        // \t([t1,t2,[accel,]]style) — we don't animate, just apply the final state.
        // Grab everything from the first backslash onward and run it through the
        // override tokenizer so any nested \c/\fs/etc. lands in currentStyle.
        val inner = args.trim().removePrefix("(").removeSuffix(")")
        val firstBackslash = inner.indexOf('\\')
        if (firstBackslash < 0) return
        applyOverrideBlock(inner.substring(firstBackslash + 1), ctx)
    }

    // -----------------------------------------------------------------------
    // Color / alpha parsing (BGR byte order → ARGB int)
    // -----------------------------------------------------------------------

    /**
     * Parse an ASS color expression. Returns (rgbColor, alpha).
     * Format variants:
     *   &H<BB><GG><RR>&       6 hex chars, alpha=255
     *   &H<AA><BB><GG><RR>&   8 hex chars, alpha explicit
     *   decimal int (SSA)     BGR packed
     */
    private fun parseAssColor(raw: String, default: Int): Pair<Int, Int> {
        val m = COLOR_PATTERN.matcher(raw.trim())
        if (!m.find()) return Pair(default, 255)
        val hex = m.group(1)!!.padStart(6, '0')
        return when {
            hex.length <= 6 -> {
                val v = hex.toLong(16).toInt()
                val b = (v shr 16) and 0xFF
                val g = (v shr 8) and 0xFF
                val r = v and 0xFF
                Pair(rgbInt(r, g, b), 255)
            }
            else -> {
                val v = hex.takeLast(8).toLong(16)
                // AABBGGRR — ASS alpha is "transparency", 0=opaque, 255=transparent
                val aRaw = ((v shr 24) and 0xFF).toInt()
                val b = ((v shr 16) and 0xFF).toInt()
                val g = ((v shr 8) and 0xFF).toInt()
                val r = (v and 0xFF).toInt()
                Pair(rgbInt(r, g, b), 255 - aRaw)
            }
        }
    }

    /** Opaque ARGB int from RGB bytes. Equivalent to Color.rgb but allocation-free and JVM-safe. */
    private fun rgbInt(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    private fun parseAlphaHex(raw: String): Int? {
        val m = COLOR_PATTERN.matcher(raw.trim())
        if (!m.find()) return null
        val transparency = m.group(1)!!.toLong(16).toInt() and 0xFF
        return 255 - transparency
    }

    // -----------------------------------------------------------------------
    // Alignment + time
    // -----------------------------------------------------------------------

    /**
     * Translate SSA legacy alignment values to the \an keypad numbering.
     * Legacy SSA uses 1-3 bottom, 5-7 top, 9-11 middle with +4/+8 flags.
     */
    private fun translateAlignment(value: Int, fromLegacy: Boolean): Int {
        if (!fromLegacy) return if (value in 1..9) value else SubtitleAlignment.BOTTOM_CENTER
        val base = value and 0x3
        val vertical = value and 0xC
        val col = when (base) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 1 }
        val row = when (vertical) { 4 -> 2; 8 -> 1; else -> 0 } // 0=bottom, 1=middle, 2=top
        return when (row) {
            0 -> 1 + col   // 1,2,3
            1 -> 4 + col   // 4,5,6
            2 -> 7 + col   // 7,8,9
            else -> SubtitleAlignment.BOTTOM_CENTER
        }
    }

    private fun parseTimeMs(s: String): Long {
        val m = ASS_TIME_PATTERN.matcher(s)
        if (!m.find()) return 0L
        val h = m.group(1)!!.toLong()
        val mm = m.group(2)!!.toLong()
        val ss = m.group(3)!!.toLong()
        val cs = m.group(4)!!.toLong()
        return h * 3600000 + mm * 60000 + ss * 1000 + cs * 10
    }

    // -----------------------------------------------------------------------
    // Internal state holders
    // -----------------------------------------------------------------------

    private enum class Section { SCRIPT_INFO, V4_STYLES, EVENTS, OTHER }

    private class ParseState {
        var section: Section = Section.OTHER
        var header: ScriptHeader = ScriptHeader()
        var playResX: Int = 384
        var playResY: Int = 288
        var wrapStyle: Int = 0
        var scaledBorderAndShadow: Boolean = true
        var styleFormat: List<String> = emptyList()
        var eventFormat: List<String> = emptyList()
        var eventFieldIndex: EventFieldIndex? = null
        val styles = LinkedHashMap<String, AssStyle>()
        val lines = ArrayList<StyledLine>()
    }

    private data class EventFieldIndex(
        val layer: Int,
        val start: Int,
        val end: Int,
        val style: Int,
        val marginL: Int,
        val marginR: Int,
        val marginV: Int,
        val text: Int
    ) {
        fun maxNeeded(): Int {
            val all = intArrayOf(start, end, style, text, marginL, marginR, marginV)
            var m = 0
            for (v in all) if (v > m) m = v
            return m + 1
        }
    }

    /** Mutable style state carried through override tokenization for a single line. */
    private class LineContext(
        val baseStyle: AssStyle,
        var currentStyle: SubtitleStyle,
        var alignment: Int,
        var marginLeft: Int,
        var marginRight: Int,
        var marginVertical: Int,
        var layer: Int,
        val knownStyles: Map<String, AssStyle>
    ) {
        var posX: Float? = null
        var posY: Float? = null
        var fadeInMs: Int = 0
        var fadeOutMs: Int = 0
    }
}
