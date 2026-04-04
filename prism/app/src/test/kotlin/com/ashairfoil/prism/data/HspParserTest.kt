package com.ashairfoil.prism.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for HspParser — HSP JSON parsing, tag navigation (getTagAt,
 * getNextTag, getPreviousTag), and chapter marker generation.
 */
@RunWith(RobolectricTestRunner::class)
class HspParserTest {

    private lateinit var parser: HspParser

    @Before
    fun setUp() {
        parser = HspParser()
    }

    // ═══════════════════════════════════════════════════════════
    // parseJson
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseJson_validWithTags_returnsCorrectHspFile() {
        val json = JSONObject().apply {
            put("version", 1)
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 0)
                    put("name", "intro")
                    put("color", "#ff0000")
                })
                put(JSONObject().apply {
                    put("ts", 30000)
                    put("name", "scene1")
                    put("color", "#00ff00")
                })
                put(JSONObject().apply {
                    put("ts", 120000)
                    put("name", "scene2")
                })
            })
        }.toString()

        val result = parser.parseJson(json, "test.hsp")
        assertNotNull(result)
        assertEquals(1, result!!.version)
        assertEquals(3, result.tags.size)
        assertEquals("test.hsp", result.source)

        assertEquals(0L, result.tags[0].timestampMs)
        assertEquals("intro", result.tags[0].name)
        assertEquals("#ff0000", result.tags[0].color)

        assertEquals(30000L, result.tags[1].timestampMs)
        assertEquals("scene1", result.tags[1].name)

        assertEquals(120000L, result.tags[2].timestampMs)
        assertEquals("scene2", result.tags[2].name)
    }

    @Test
    fun parseJson_tagsSortedByTimestamp() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().put("ts", 50000).put("name", "third"))
                put(JSONObject().put("ts", 10000).put("name", "first"))
                put(JSONObject().put("ts", 30000).put("name", "second"))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals("first", result.tags[0].name)
        assertEquals("second", result.tags[1].name)
        assertEquals("third", result.tags[2].name)
    }

    @Test
    fun parseJson_corrections_parsedCorrectly() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
            put("corrections", JSONObject().apply {
                put("x", 1.5)
                put("y", -0.5)
                put("z", 0.0)
                put("brightness", 0.1)
                put("contrast", -0.2)
                put("saturation", 0.3)
                put("ipd", 64.0)
                put("fov", 180.0)
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertNotNull(result.corrections)
        val c = result.corrections!!
        assertEquals(1.5f, c.x, 0.001f)
        assertEquals(-0.5f, c.y, 0.001f)
        assertEquals(0.0f, c.z, 0.001f)
        assertEquals(0.1f, c.brightness, 0.001f)
        assertEquals(-0.2f, c.contrast, 0.001f)
        assertEquals(0.3f, c.saturation, 0.001f)
        assertEquals(64.0f, c.ipd, 0.001f)
        assertEquals(180.0f, c.fov, 0.001f)
    }

    @Test
    fun parseJson_noCorrections_returnsNull() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
        }.toString()

        val result = parser.parseJson(json)!!
        assertNull(result.corrections)
    }

    @Test
    fun parseJson_projectionAndStereo() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
            put("projection", "equirectangular180")
            put("stereo", "sbs")
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals("equirectangular180", result.projection)
        assertEquals("sbs", result.stereo)
    }

    @Test
    fun parseJson_noProjectionOrStereo_returnsNull() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
        }.toString()

        val result = parser.parseJson(json)!!
        assertNull(result.projection)
        assertNull(result.stereo)
    }

    @Test
    fun parseJson_rangeTag_endTimestampPopulated() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 10000)
                    put("end_ts", 20000)
                    put("name", "range_tag")
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(1, result.tags.size)
        assertEquals(10000L, result.tags[0].timestampMs)
        assertEquals(20000L, result.tags[0].endTimestampMs)
        assertEquals("range_tag", result.tags[0].name)
    }

    @Test
    fun parseJson_pointTag_endTimestampIsNull() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 10000)
                    put("name", "point_tag")
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertNull(result.tags[0].endTimestampMs)
    }

    @Test
    fun parseJson_emptyTags_returnsEmptyList() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
        }.toString()

        val result = parser.parseJson(json)!!
        assertTrue(result.tags.isEmpty())
    }

    @Test
    fun parseJson_noTagsKey_returnsEmptyList() {
        // When "tags" key is absent, parser uses JSONArray() fallback
        val json = JSONObject().apply {
            put("version", 1)
        }.toString()

        val result = parser.parseJson(json)!!
        assertTrue(result.tags.isEmpty())
    }

    @Test
    fun parseJson_malformedJson_returnsNull() {
        val result = parser.parseJson("{broken json!!!")
        assertNull(result)
    }

    @Test
    fun parseJson_emptyString_returnsNull() {
        val result = parser.parseJson("")
        assertNull(result)
    }

    @Test
    fun parseJson_tagWithCategory() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 5000)
                    put("name", "tagged")
                    put("category", "position")
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals("position", result.tags[0].category)
    }

    @Test
    fun parseJson_tagWithoutCategory_returnsNull() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 5000)
                    put("name", "no_category")
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertNull(result.tags[0].category)
    }

    @Test
    fun parseJson_tagWithNullColor_returnsNull() {
        val json = JSONObject().apply {
            put("tags", JSONArray().apply {
                put(JSONObject().apply {
                    put("ts", 5000)
                    put("name", "no_color")
                    put("color", JSONObject.NULL)
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertNull(result.tags[0].color)
    }

    @Test
    fun parseJson_defaultVersion() {
        val json = JSONObject().apply {
            put("tags", JSONArray())
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(1, result.version)
    }

    // ═══════════════════════════════════════════════════════════
    // getTagAt
    // ═══════════════════════════════════════════════════════════

    private fun buildHsp(tags: List<HspParser.HspTag>): HspParser.HspFile {
        return HspParser.HspFile(tags = tags.sortedBy { it.timestampMs })
    }

    private fun pointTag(ts: Long, name: String) = HspParser.HspTag(
        timestampMs = ts, endTimestampMs = null, name = name, color = null, category = null
    )

    private fun rangeTag(ts: Long, endTs: Long, name: String) = HspParser.HspTag(
        timestampMs = ts, endTimestampMs = endTs, name = name, color = null, category = null
    )

    @Test
    fun getTagAt_exactTimestamp_found() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        val tag = parser.getTagAt(hsp, 30000)
        assertNotNull(tag)
        assertEquals("scene1", tag!!.name)
    }

    @Test
    fun getTagAt_beforeFirstTag_returnsNull() {
        val hsp = buildHsp(listOf(
            pointTag(10000, "first"),
            pointTag(30000, "second"),
        ))
        // Before the first tag — no tag at or before position
        val tag = parser.getTagAt(hsp, 5000)
        assertNull(tag)
    }

    @Test
    fun getTagAt_betweenPointTags_returnsPrior() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        // Between intro and scene1 — should return intro (last tag <= positionMs)
        val tag = parser.getTagAt(hsp, 15000)
        assertNotNull(tag)
        assertEquals("intro", tag!!.name)
    }

    @Test
    fun getTagAt_afterLastTag_returnsLast() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        val tag = parser.getTagAt(hsp, 60000)
        assertNotNull(tag)
        assertEquals("scene1", tag!!.name)
    }

    @Test
    fun getTagAt_rangeTag_insideRange_found() {
        val hsp = buildHsp(listOf(
            rangeTag(10000, 20000, "range_scene"),
        ))
        val tag = parser.getTagAt(hsp, 15000)
        assertNotNull(tag)
        assertEquals("range_scene", tag!!.name)
    }

    @Test
    fun getTagAt_rangeTag_atStart_found() {
        val hsp = buildHsp(listOf(
            rangeTag(10000, 20000, "range_scene"),
        ))
        val tag = parser.getTagAt(hsp, 10000)
        assertNotNull(tag)
        assertEquals("range_scene", tag!!.name)
    }

    @Test
    fun getTagAt_rangeTag_atEnd_found() {
        val hsp = buildHsp(listOf(
            rangeTag(10000, 20000, "range_scene"),
        ))
        // At exactly endTimestampMs — positionMs (20000) is not > endTimestampMs (20000)
        val tag = parser.getTagAt(hsp, 20000)
        assertNotNull(tag)
        assertEquals("range_scene", tag!!.name)
    }

    @Test
    fun getTagAt_rangeTag_afterEnd_notFound() {
        val hsp = buildHsp(listOf(
            rangeTag(10000, 20000, "range_scene"),
        ))
        // After endTimestampMs — should skip the range tag
        val tag = parser.getTagAt(hsp, 20001)
        assertNull(tag)
    }

    @Test
    fun getTagAt_rangeTag_beforeStart_notFound() {
        val hsp = buildHsp(listOf(
            rangeTag(10000, 20000, "range_scene"),
        ))
        val tag = parser.getTagAt(hsp, 5000)
        assertNull(tag)
    }

    @Test
    fun getTagAt_mixedPointAndRangeTags() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            rangeTag(10000, 20000, "action_range"),
            pointTag(30000, "outro"),
        ))
        // Inside the range tag
        assertEquals("action_range", parser.getTagAt(hsp, 15000)!!.name)
        // After range ends but before outro — point tag "intro" is at 0 and <= 25000,
        // range tag at 10000 <= 25000 but 25000 > 20000 so range skipped,
        // result is "intro" (last point tag that satisfies condition)
        assertEquals("intro", parser.getTagAt(hsp, 25000)!!.name)
        // At outro
        assertEquals("outro", parser.getTagAt(hsp, 30000)!!.name)
    }

    @Test
    fun getTagAt_emptyTags_returnsNull() {
        val hsp = buildHsp(emptyList())
        assertNull(parser.getTagAt(hsp, 5000))
    }

    // ═══════════════════════════════════════════════════════════
    // getNextTag
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getNextTag_normalNavigation() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        val next = parser.getNextTag(hsp, 0)
        assertNotNull(next)
        assertEquals("scene1", next!!.name)
    }

    @Test
    fun getNextTag_fromMiddle() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        val next = parser.getNextTag(hsp, 15000)
        assertNotNull(next)
        assertEquals("scene1", next!!.name)
    }

    @Test
    fun getNextTag_atLastTag_returnsNull() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        val next = parser.getNextTag(hsp, 30000)
        assertNull(next)
    }

    @Test
    fun getNextTag_afterLastTag_returnsNull() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        val next = parser.getNextTag(hsp, 60000)
        assertNull(next)
    }

    @Test
    fun getNextTag_emptyTags_returnsNull() {
        val hsp = buildHsp(emptyList())
        assertNull(parser.getNextTag(hsp, 0))
    }

    @Test
    fun getNextTag_beforeFirstTag_returnsFirst() {
        val hsp = buildHsp(listOf(
            pointTag(10000, "first"),
            pointTag(30000, "second"),
        ))
        val next = parser.getNextTag(hsp, 0)
        assertNotNull(next)
        assertEquals("first", next!!.name)
    }

    // ═══════════════════════════════════════════════════════════
    // getPreviousTag
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getPreviousTag_normalNavigation() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        // At scene2 (60000), previous should skip the 1-second threshold
        // positionMs - 1000 = 59000, so lastOrNull { ts < 59000 } => scene1 (30000)
        val prev = parser.getPreviousTag(hsp, 60000)
        assertNotNull(prev)
        assertEquals("scene1", prev!!.name)
    }

    @Test
    fun getPreviousTag_within1SecondOfMarker_goesToPreviousPrevious() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        // At 30500 (500ms after scene1) — positionMs - 1000 = 29500
        // lastOrNull { ts < 29500 } => intro (0)
        // This implements the "tap previous again to go further back" behavior
        val prev = parser.getPreviousTag(hsp, 30500)
        assertNotNull(prev)
        assertEquals("intro", prev!!.name)
    }

    @Test
    fun getPreviousTag_well_pastMarker_returnsThatMarker() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        // At 35000 (5s after scene1) — positionMs - 1000 = 34000
        // lastOrNull { ts < 34000 } => scene1 (30000)
        val prev = parser.getPreviousTag(hsp, 35000)
        assertNotNull(prev)
        assertEquals("scene1", prev!!.name)
    }

    @Test
    fun getPreviousTag_atFirstTag_returnsNull() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        // At 0 — positionMs - 1000 = -1000, no tags with ts < -1000
        val prev = parser.getPreviousTag(hsp, 0)
        assertNull(prev)
    }

    @Test
    fun getPreviousTag_nearFirstTag_returnsNull() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        // At 500ms — positionMs - 1000 = -500, no tags with ts < -500
        val prev = parser.getPreviousTag(hsp, 500)
        assertNull(prev)
    }

    @Test
    fun getPreviousTag_emptyTags_returnsNull() {
        val hsp = buildHsp(emptyList())
        assertNull(parser.getPreviousTag(hsp, 30000))
    }

    @Test
    fun getPreviousTag_exactlyAt1SecondThreshold() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        // At 31000 — positionMs - 1000 = 30000
        // lastOrNull { ts < 30000 } => intro (0), since 30000 is NOT < 30000
        val prev = parser.getPreviousTag(hsp, 31000)
        assertNotNull(prev)
        assertEquals("intro", prev!!.name)
    }

    @Test
    fun getPreviousTag_justPast1SecondThreshold() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        // At 31001 — positionMs - 1000 = 30001
        // lastOrNull { ts < 30001 } => scene1 (30000), since 30000 < 30001
        val prev = parser.getPreviousTag(hsp, 31001)
        assertNotNull(prev)
        assertEquals("scene1", prev!!.name)
    }

    // ═══════════════════════════════════════════════════════════
    // getChapterMarkers
    // ═══════════════════════════════════════════════════════════

    @Test
    fun getChapterMarkers_multipleTags_correctPositions() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
            pointTag(60000, "scene2"),
        ))
        val markers = parser.getChapterMarkers(hsp, 120000)
        assertEquals(3, markers.size)

        assertEquals(0f, markers[0].first, 0.001f)
        assertEquals("intro", markers[0].second)

        assertEquals(0.25f, markers[1].first, 0.001f)
        assertEquals("scene1", markers[1].second)

        assertEquals(0.5f, markers[2].first, 0.001f)
        assertEquals("scene2", markers[2].second)
    }

    @Test
    fun getChapterMarkers_durationZero_returnsEmpty() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(30000, "scene1"),
        ))
        val markers = parser.getChapterMarkers(hsp, 0)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun getChapterMarkers_negativeDuration_returnsEmpty() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
        ))
        val markers = parser.getChapterMarkers(hsp, -1000)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun getChapterMarkers_emptyTags_returnsEmpty() {
        val hsp = buildHsp(emptyList())
        val markers = parser.getChapterMarkers(hsp, 120000)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun getChapterMarkers_tagAtDuration_positionIs1() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(60000, "end"),
        ))
        val markers = parser.getChapterMarkers(hsp, 60000)
        assertEquals(1.0f, markers[1].first, 0.001f)
    }

    @Test
    fun getChapterMarkers_tagBeyondDuration_clampedTo1() {
        val hsp = buildHsp(listOf(
            pointTag(0, "intro"),
            pointTag(120000, "beyond"),
        ))
        // durationMs=60000 but tag at 120000 — position should be clamped to 1.0
        val markers = parser.getChapterMarkers(hsp, 60000)
        assertEquals(1.0f, markers[1].first, 0.001f)
    }

    @Test
    fun getChapterMarkers_singleTag() {
        val hsp = buildHsp(listOf(
            pointTag(15000, "only"),
        ))
        val markers = parser.getChapterMarkers(hsp, 60000)
        assertEquals(1, markers.size)
        assertEquals(0.25f, markers[0].first, 0.001f)
        assertEquals("only", markers[0].second)
    }

    @Test
    fun getChapterMarkers_preservesTagOrder() {
        val hsp = buildHsp(listOf(
            pointTag(45000, "third"),
            pointTag(15000, "first"),
            pointTag(30000, "second"),
        ))
        // Tags are sorted in buildHsp, so order should be first, second, third
        val markers = parser.getChapterMarkers(hsp, 60000)
        assertEquals("first", markers[0].second)
        assertEquals("second", markers[1].second)
        assertEquals("third", markers[2].second)
    }
}
