package com.ashairfoil.prism.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for SubtitleStyleParser — override tag tokenization, style inheritance,
 * positioning, colors (BGR byte order), alignment translation, and structural parsing
 * of [Script Info], [V4+ Styles], and [Events] sections.
 *
 * Fixtures are inline strings so tests are fast and hermetic. No Android framework
 * stubs are touched (parser uses raw ARGB Ints, not android.graphics.Color methods).
 */
class SubtitleStyleParserTest {

    private lateinit var parser: SubtitleStyleParser

    @Before
    fun setUp() {
        parser = SubtitleStyleParser()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Section parsing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `parses empty string yields default style map and no lines`() {
        val result = parser.parseString("")
        assertEquals(1, result.styles.size)
        assertNotNull(result.styles["Default"])
        assertTrue(result.lines.isEmpty())
    }

    @Test
    fun `parses script header PlayResX and PlayResY`() {
        val input = """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080
        """.trimIndent()
        val result = parser.parseString(input)
        assertEquals(1920, result.header.playResX)
        assertEquals(1080, result.header.playResY)
    }

    @Test
    fun `defaults header to 384x288 when missing`() {
        val result = parser.parseString("[Events]\n")
        assertEquals(384, result.header.playResX)
        assertEquals(288, result.header.playResY)
    }

    @Test
    fun `parses V4+ Styles section with named style`() {
        val input = """
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment, MarginL, MarginR, MarginV
            Style: Default,Arial,24,&H00FFFFFF,-1,0,2,10,10,30
            Style: Pink,Comic Sans MS,36,&H00FFFF00,0,-1,5,0,0,0
        """.trimIndent()
        val result = parser.parseString(input)
        assertEquals(2, result.styles.size)
        val pink = result.styles["Pink"]
        assertNotNull(pink)
        assertEquals("Comic Sans MS", pink!!.style.fontName)
        assertEquals(36f, pink.style.fontSize, 0.001f)
        assertTrue(pink.style.isItalic)
        assertFalse(pink.style.isBold)
        assertEquals(SubtitleAlignment.MIDDLE_CENTER, pink.alignment)
    }

    @Test
    fun `Dialogue references named style`() {
        val input = """
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Alignment
            Style: Default,Arial,20,&H00FFFFFF,2
            Style: Red,Arial,30,&H000000FF,8
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Red,,0,0,0,,Hello world
        """.trimIndent()
        val result = parser.parseString(input)
        assertEquals(1, result.lines.size)
        val line = result.lines[0]
        assertEquals(SubtitleAlignment.TOP_CENTER, line.alignment)
        val span = line.spans[0]
        assertEquals("Hello world", span.text)
        assertEquals(30f, span.style.fontSize, 0.001f)
        // BGR 0x0000FF -> RGB red = 0xFFFF0000 ARGB
        assertEquals(0xFFFF0000.toInt(), span.style.primaryColor)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Color parsing (BGR byte order — the easy trap)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `primary color tag parses BGR hex correctly`() {
        val result = parser.parseString(dialogueWrap("{\\c&H0000FF&}red text"))
        val style = result.lines[0].spans[0].style
        // BGR: BB=00 GG=00 RR=FF -> red
        assertEquals(0xFFFF0000.toInt(), style.primaryColor)
    }

    @Test
    fun `primary color tag with 8-hex includes alpha`() {
        val result = parser.parseString(dialogueWrap("{\\c&H80FF0000&}half-blue"))
        val style = result.lines[0].spans[0].style
        // AABBGGRR: alpha raw 0x80 = transparency 128 -> opacity 127
        // BGR: BB=FF GG=00 RR=00 -> blue
        assertEquals(0xFF0000FF.toInt(), style.primaryColor)
        assertEquals(127, style.primaryAlpha)
    }

    @Test
    fun `secondary color tag 2c is separate channel`() {
        val result = parser.parseString(dialogueWrap("{\\2c&H00FF00&}x"))
        val style = result.lines[0].spans[0].style
        assertEquals(0xFF00FF00.toInt(), style.secondaryColor)
        // primary untouched
        assertEquals(0xFFFFFFFF.toInt(), style.primaryColor)
    }

    @Test
    fun `alpha tag sets all channels`() {
        val result = parser.parseString(dialogueWrap("{\\alpha&HFF&}gone"))
        val style = result.lines[0].spans[0].style
        // transparency 255 -> opacity 0
        assertEquals(0, style.primaryAlpha)
        assertEquals(0, style.secondaryAlpha)
        assertEquals(0, style.outlineAlpha)
        assertEquals(0, style.shadowAlpha)
    }

    @Test
    fun `per-channel alpha only affects that channel`() {
        val result = parser.parseString(dialogueWrap("{\\3a&H80&}outline-faded"))
        val style = result.lines[0].spans[0].style
        assertEquals(127, style.outlineAlpha)
        assertEquals(255, style.primaryAlpha)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Font size / bold / italic / underline / strike
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `bold toggle produces separate spans`() {
        val result = parser.parseString(dialogueWrap("plain{\\b1}bold{\\b0}plain2"))
        val spans = result.lines[0].spans
        assertEquals(3, spans.size)
        assertFalse(spans[0].style.isBold)
        assertTrue(spans[1].style.isBold)
        assertFalse(spans[2].style.isBold)
        assertEquals("plain", spans[0].text)
        assertEquals("bold", spans[1].text)
        assertEquals("plain2", spans[2].text)
    }

    @Test
    fun `italic underline strikethrough all apply`() {
        val result = parser.parseString(dialogueWrap("{\\i1\\u1\\s1}ALL"))
        val style = result.lines[0].spans[0].style
        assertTrue(style.isItalic)
        assertTrue(style.isUnderline)
        assertTrue(style.isStrikethrough)
    }

    @Test
    fun `fs tag changes font size mid-line`() {
        val result = parser.parseString(dialogueWrap("small{\\fs48}LARGE"))
        val spans = result.lines[0].spans
        assertEquals(2, spans.size)
        assertNotEquals(spans[0].style.fontSize, spans[1].style.fontSize)
        assertEquals(48f, spans[1].style.fontSize, 0.001f)
    }

    @Test
    fun `fn tag changes font name`() {
        val result = parser.parseString(dialogueWrap("{\\fnHelvetica}hi"))
        assertEquals("Helvetica", result.lines[0].spans[0].style.fontName)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Positioning and alignment
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `pos tag captures absolute position`() {
        val result = parser.parseString(dialogueWrap("{\\pos(100,200)}at a point"))
        val line = result.lines[0]
        assertEquals(100f, line.posX!!, 0.001f)
        assertEquals(200f, line.posY!!, 0.001f)
    }

    @Test
    fun `an tag sets alignment`() {
        val result = parser.parseString(dialogueWrap("{\\an7}top-left"))
        assertEquals(SubtitleAlignment.TOP_LEFT, result.lines[0].alignment)
    }

    @Test
    fun `legacy a tag translates to keypad alignment`() {
        // SSA legacy \a2 means bottom center; \a6 means top center; \a10 means middle center.
        assertEquals(SubtitleAlignment.BOTTOM_CENTER, parser.parseString(dialogueWrap("{\\a2}x")).lines[0].alignment)
        assertEquals(SubtitleAlignment.TOP_CENTER, parser.parseString(dialogueWrap("{\\a6}x")).lines[0].alignment)
        assertEquals(SubtitleAlignment.MIDDLE_CENTER, parser.parseString(dialogueWrap("{\\a10}x")).lines[0].alignment)
    }

    @Test
    fun `missing pos leaves posX and posY null`() {
        val result = parser.parseString(dialogueWrap("no position"))
        assertNull(result.lines[0].posX)
        assertNull(result.lines[0].posY)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Border / shadow / blur
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `bord shad blur tags parsed as floats`() {
        val result = parser.parseString(dialogueWrap("{\\bord3.5\\shad2\\blur4}edgy"))
        val s = result.lines[0].spans[0].style
        assertEquals(3.5f, s.borderWidth, 0.001f)
        assertEquals(2f, s.shadowOffset, 0.001f)
        assertEquals(4f, s.blurStrength, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Reset and nested overrides
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `r tag resets to base style`() {
        val result = parser.parseString(dialogueWrap("{\\b1\\i1}emphasized{\\r}plain"))
        val spans = result.lines[0].spans
        assertEquals(2, spans.size)
        assertTrue(spans[0].style.isBold)
        assertFalse(spans[1].style.isBold)
        assertFalse(spans[1].style.isItalic)
    }

    @Test
    fun `r tag with named style resets to that style`() {
        val input = """
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, Alignment
            Style: Default,Arial,20,&H00FFFFFF,2
            Style: Big,Arial,80,&H00FFFFFF,2
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,hi{\rBig}BIG
        """.trimIndent()
        val result = parser.parseString(input)
        val spans = result.lines[0].spans
        assertEquals(2, spans.size)
        assertEquals(20f, spans[0].style.fontSize, 0.001f)
        assertEquals(80f, spans[1].style.fontSize, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fade timing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `fad tag captures fade in out durations`() {
        val result = parser.parseString(dialogueWrap("{\\fad(250,500)}fade"))
        assertEquals(250, result.lines[0].fadeInMs)
        assertEquals(500, result.lines[0].fadeOutMs)
    }

    @Test
    fun `fade seven-arg form computes durations`() {
        // \fade(a1,a2,a3,t1,t2,t3,t4) — fadeIn = t2-t1 = 200; fadeOut = t4-t3 = 300
        val result = parser.parseString(dialogueWrap("{\\fade(255,0,255,0,200,700,1000)}x"))
        assertEquals(200, result.lines[0].fadeInMs)
        assertEquals(300, result.lines[0].fadeOutMs)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Text escapes
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `backslash-N and backslash-n produce newlines`() {
        val result = parser.parseString(dialogueWrap("line1\\Nline2\\nline3"))
        val text = result.lines[0].spans.joinToString("") { it.text }
        assertEquals("line1\nline2\nline3", text)
    }

    @Test
    fun `backslash-h produces non-breaking space`() {
        val result = parser.parseString(dialogueWrap("a\\hb"))
        val text = result.lines[0].spans.joinToString("") { it.text }
        assertEquals("a\u00A0b", text)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Time parsing
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `time parsing converts H_MM_SS_CC to ms`() {
        val input = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:02.50,0:01:04.00,Default,,0,0,0,,hi
        """.trimIndent()
        val result = parser.parseString(input)
        assertEquals(62500L, result.lines[0].startMs)
        assertEquals(64000L, result.lines[0].endMs)
    }

    @Test
    fun `zero duration lines dropped`() {
        val input = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:01.00,Default,,0,0,0,,hi
        """.trimIndent()
        val result = parser.parseString(input)
        assertTrue(result.lines.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tag robustness
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `unknown tags are ignored without corrupting output`() {
        val result = parser.parseString(dialogueWrap("{\\nonsense\\b1}bold{\\glorp42}still bold"))
        val spans = result.lines[0].spans
        // \b1 applies to "bold"; unknown \glorp42 drops; "still bold" shares same style
        assertTrue(spans.all { it.style.isBold })
        assertEquals("boldstill bold", spans.joinToString("") { it.text })
    }

    @Test
    fun `unterminated override block does not crash`() {
        val result = parser.parseString(dialogueWrap("text{\\b1 missing brace"))
        // We expect the unterminated block to swallow the remainder — no crash, parser returns something.
        assertNotNull(result.lines)
    }

    @Test
    fun `combined bold italic color pos produces single span with merged style`() {
        val result = parser.parseString(dialogueWrap("{\\b1\\i1\\c&H00FF00&\\pos(50,75)}fancy"))
        val line = result.lines[0]
        assertEquals(1, line.spans.size)
        val s = line.spans[0].style
        assertTrue(s.isBold)
        assertTrue(s.isItalic)
        assertEquals(0xFF00FF00.toInt(), s.primaryColor)
        assertEquals(50f, line.posX!!, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fixture: simulated full anime-style ASS file
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `complete anime-style fixture parses correctly`() {
        val input = """
            [Script Info]
            Title: Sample
            ScriptType: v4.00+
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
            Style: Sign,Arial,36,&H00FFFF00,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,1,5,0,0,0,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\i1}Hello{\i0} world
            Dialogue: 0,0:00:04.00,0:00:06.00,Sign,,0,0,0,,{\pos(960,200)\c&H00FF00&\fs64}TITLE
            Dialogue: 1,0:00:07.00,0:00:09.00,Default,,0,0,0,,{\fad(200,300)}fading text
        """.trimIndent()
        val result = parser.parseString(input)
        assertEquals(1920, result.header.playResX)
        assertEquals(1080, result.header.playResY)
        assertEquals(2, result.styles.size)
        assertEquals(3, result.lines.size)

        val hello = result.lines[0]
        assertEquals(48f, hello.spans[0].style.fontSize, 0.001f)
        assertTrue(hello.spans.any { it.style.isItalic && it.text == "Hello" })

        val title = result.lines[1]
        assertEquals(960f, title.posX!!, 0.001f)
        assertEquals(200f, title.posY!!, 0.001f)
        assertEquals(SubtitleAlignment.MIDDLE_CENTER, title.alignment)
        assertTrue(title.spans[0].style.isBold) // inherited from Sign style
        assertEquals(0xFF00FF00.toInt(), title.spans[0].style.primaryColor)
        assertEquals(64f, title.spans[0].style.fontSize, 0.001f)

        val fade = result.lines[2]
        assertEquals(200, fade.fadeInMs)
        assertEquals(300, fade.fadeOutMs)
        assertEquals(1, fade.layer)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun dialogueWrap(text: String): String = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,$text
    """.trimIndent()
}
