package com.ashairfoil.prism.playback

/**
 * SubtitleStyle — immutable style descriptor for a single run of ASS/SSA text.
 *
 * Created by the parser per-span as override tags (`{\b1}`, `{\c&H...&}`, etc.) are
 * encountered; consumed by the renderer to configure a reusable Paint object.
 *
 * All color values are ARGB ints (the ASS BGR byte order is normalized during parse).
 * Alpha is split out so {\alpha} and per-channel {\Nc} overrides can compose cleanly.
 *
 * Default color constants are ARGB literals to keep this file dependency-free
 * for fast unit testing (no android.graphics stubs required).
 */
data class SubtitleStyle(
    val fontName: String = "Arial",
    val fontSize: Float = 22f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    val secondaryColor: Int = 0xFFFFFF00.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
    val shadowColor: Int = 0xFF000000.toInt(),
    val primaryAlpha: Int = 255,
    val secondaryAlpha: Int = 255,
    val outlineAlpha: Int = 255,
    val shadowAlpha: Int = 255,
    val borderWidth: Float = 2f,
    val shadowOffset: Float = 0f,
    val blurStrength: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val spacing: Float = 0f,
    val angle: Float = 0f
) {
    companion object {
        val DEFAULT = SubtitleStyle()
    }
}

/**
 * StyledSpan — a contiguous run of text sharing the same style.
 * The parser emits a list of these per dialogue line; the renderer iterates them.
 */
data class StyledSpan(val text: String, val style: SubtitleStyle)

/**
 * Alignment keypad values per ASS spec (numeric keypad layout).
 *   7 8 9        top-left, top-center, top-right
 *   4 5 6        middle-left, middle-center, middle-right
 *   1 2 3        bottom-left, bottom-center, bottom-right
 * Legacy SSA \a values (1,2,3,5,6,7,9,10,11) are translated at parse time.
 */
object SubtitleAlignment {
    const val BOTTOM_LEFT = 1
    const val BOTTOM_CENTER = 2
    const val BOTTOM_RIGHT = 3
    const val MIDDLE_LEFT = 4
    const val MIDDLE_CENTER = 5
    const val MIDDLE_RIGHT = 6
    const val TOP_LEFT = 7
    const val TOP_CENTER = 8
    const val TOP_RIGHT = 9
}

/**
 * StyledLine — a parsed dialogue event ready for rendering.
 *
 * If posX/posY are non-null, alignment anchors the text around that point.
 * Otherwise the renderer uses alignment + margins against the play-res rectangle.
 *
 * fadeInMs/fadeOutMs are durations (not absolute times); layer controls z-order.
 */
data class StyledLine(
    val startMs: Long,
    val endMs: Long,
    val spans: List<StyledSpan>,
    val alignment: Int = SubtitleAlignment.BOTTOM_CENTER,
    val posX: Float? = null,
    val posY: Float? = null,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    val marginVertical: Int = 0,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val layer: Int = 0
) {
    /** Returns true if this line has any renderable content. */
    fun hasText(): Boolean = spans.any { it.text.isNotEmpty() }

    /** Flatten spans to a plain-text fallback (used when styled render is disabled). */
    fun plainText(): String = spans.joinToString("") { it.text }
}

/**
 * ScriptHeader — values parsed from `[Script Info]`. ResX/ResY define the virtual
 * canvas that `\pos(x,y)` coordinates are relative to; the renderer scales into
 * the view's actual pixel size.
 */
data class ScriptHeader(
    val playResX: Int = 384,
    val playResY: Int = 288,
    val wrapStyle: Int = 0,
    val scaledBorderAndShadow: Boolean = true
)

/**
 * AssStyle — a named entry from the `[V4+ Styles]` section. Serves as the base
 * style for Dialogue lines that reference it via the `Style:` field; override tags
 * mutate a copy of this style, not the named entry itself.
 */
data class AssStyle(
    val name: String,
    val style: SubtitleStyle,
    val alignment: Int = SubtitleAlignment.BOTTOM_CENTER,
    val marginLeft: Int = 20,
    val marginRight: Int = 20,
    val marginVertical: Int = 20
) {
    companion object {
        val DEFAULT = AssStyle(
            name = "Default",
            style = SubtitleStyle.DEFAULT
        )
    }
}
