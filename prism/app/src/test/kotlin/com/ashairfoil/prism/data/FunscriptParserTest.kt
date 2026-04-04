package com.ashairfoil.prism.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for FunscriptParser — JSON parsing, position interpolation,
 * binary search range queries, and intensity calculation.
 */
@RunWith(RobolectricTestRunner::class)
class FunscriptParserTest {

    private lateinit var parser: FunscriptParser

    @Before
    fun setUp() {
        parser = FunscriptParser()
    }

    // ═══════════════════════════════════════════════════════════
    // parseJson
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseJson_validActions_returnsCorrectFunscript() {
        val json = JSONObject().apply {
            put("version", "1.0")
            put("inverted", false)
            put("range", 100)
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 50))
                put(JSONObject().put("at", 1000).put("pos", 100))
                put(JSONObject().put("at", 2000).put("pos", 0))
            })
        }.toString()

        val result = parser.parseJson(json, "test")
        assertNotNull(result)
        assertEquals(3, result!!.actions.size)
        assertEquals("1.0", result.version)
        assertFalse(result.inverted)
        assertEquals(100, result.range)
        assertEquals("test", result.source)

        assertEquals(0L, result.actions[0].atMs)
        assertEquals(50, result.actions[0].position)
        assertEquals(1000L, result.actions[1].atMs)
        assertEquals(100, result.actions[1].position)
        assertEquals(2000L, result.actions[2].atMs)
        assertEquals(0, result.actions[2].position)
    }

    @Test
    fun parseJson_emptyActions_returnsEmptyList() {
        val json = JSONObject().apply {
            put("actions", JSONArray())
        }.toString()

        val result = parser.parseJson(json)
        assertNotNull(result)
        assertTrue(result!!.actions.isEmpty())
        assertTrue(result.isEmpty)
        assertEquals(0L, result.durationMs)
    }

    @Test
    fun parseJson_missingActionsKey_returnsNull() {
        val json = JSONObject().apply {
            put("version", "1.0")
            // no "actions" key
        }.toString()

        val result = parser.parseJson(json)
        assertNull(result)
    }

    @Test
    fun parseJson_malformedJson_returnsNull() {
        val result = parser.parseJson("{this is not valid json!!!")
        assertNull(result)
    }

    @Test
    fun parseJson_emptyString_returnsNull() {
        val result = parser.parseJson("")
        assertNull(result)
    }

    @Test
    fun parseJson_actionsSortedByTimestamp() {
        val json = JSONObject().apply {
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 3000).put("pos", 30))
                put(JSONObject().put("at", 1000).put("pos", 10))
                put(JSONObject().put("at", 2000).put("pos", 20))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(1000L, result.actions[0].atMs)
        assertEquals(2000L, result.actions[1].atMs)
        assertEquals(3000L, result.actions[2].atMs)
    }

    @Test
    fun parseJson_positionClampedTo0_100() {
        val json = JSONObject().apply {
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", -50))
                put(JSONObject().put("at", 1000).put("pos", 200))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(0, result.actions[0].position)
        assertEquals(100, result.actions[1].position)
    }

    @Test
    fun parseJson_invertedFlag() {
        val json = JSONObject().apply {
            put("inverted", true)
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 50))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertTrue(result.inverted)
    }

    @Test
    fun parseJson_rangeField() {
        val json = JSONObject().apply {
            put("range", 80)
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 50))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(80, result.range)
    }

    @Test
    fun parseJson_metadata() {
        val json = JSONObject().apply {
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 50))
            })
            put("metadata", JSONObject().apply {
                put("creator", "test_creator")
                put("title", "test_title")
                put("duration", 60000)
                put("type", "advanced")
                put("performers", JSONArray().apply {
                    put("performer1")
                    put("performer2")
                })
                put("tags", JSONArray().apply {
                    put("tag1")
                    put("tag2")
                })
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals("test_creator", result.metadata.creator)
        assertEquals("test_title", result.metadata.title)
        assertEquals(60000L, result.metadata.duration)
        assertEquals("advanced", result.metadata.type)
        assertEquals(listOf("performer1", "performer2"), result.metadata.performers)
        assertEquals(listOf("tag1", "tag2"), result.metadata.tags)
    }

    @Test
    fun parseJson_missingMetadata_usesDefaults() {
        val json = JSONObject().apply {
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 50))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals("", result.metadata.creator)
        assertEquals("", result.metadata.title)
        assertEquals(0L, result.metadata.duration)
        assertEquals("basic", result.metadata.type)
        assertTrue(result.metadata.performers.isEmpty())
        assertTrue(result.metadata.tags.isEmpty())
    }

    @Test
    fun parseJson_durationMs_reportsLastActionTime() {
        val json = JSONObject().apply {
            put("actions", JSONArray().apply {
                put(JSONObject().put("at", 0).put("pos", 0))
                put(JSONObject().put("at", 5000).put("pos", 100))
                put(JSONObject().put("at", 12000).put("pos", 50))
            })
        }.toString()

        val result = parser.parseJson(json)!!
        assertEquals(12000L, result.durationMs)
    }

    // ═══════════════════════════════════════════════════════════
    // positionAt — interpolation
    // ═══════════════════════════════════════════════════════════

    private fun buildFunscript(
        actions: List<Pair<Long, Int>>,
        range: Int = 100,
        inverted: Boolean = false
    ): FunscriptParser.Funscript {
        return FunscriptParser.Funscript(
            actions = actions.map { (at, pos) ->
                FunscriptParser.FunscriptAction(atMs = at, position = pos)
            },
            range = range,
            inverted = inverted,
        )
    }

    @Test
    fun positionAt_emptyActions_returns50() {
        val fs = buildFunscript(emptyList())
        assertEquals(50, fs.positionAt(5000))
    }

    @Test
    fun positionAt_singleAction_returnsThatPosition() {
        val fs = buildFunscript(listOf(1000L to 75))
        assertEquals(75, fs.positionAt(1000))
        // Before first action
        assertEquals(75, fs.positionAt(0))
        // After last action
        assertEquals(75, fs.positionAt(5000))
    }

    @Test
    fun positionAt_beforeFirstAction_returnsFirstPosition() {
        val fs = buildFunscript(listOf(1000L to 30, 2000L to 80))
        assertEquals(30, fs.positionAt(0))
        assertEquals(30, fs.positionAt(500))
        assertEquals(30, fs.positionAt(1000))
    }

    @Test
    fun positionAt_afterLastAction_returnsLastPosition() {
        val fs = buildFunscript(listOf(1000L to 30, 2000L to 80))
        assertEquals(80, fs.positionAt(2000))
        assertEquals(80, fs.positionAt(5000))
        assertEquals(80, fs.positionAt(999999))
    }

    @Test
    fun positionAt_exactMatch_returnsExactPosition() {
        val fs = buildFunscript(listOf(
            0L to 0,
            1000L to 50,
            2000L to 100,
        ))
        assertEquals(0, fs.positionAt(0))
        assertEquals(50, fs.positionAt(1000))
        assertEquals(100, fs.positionAt(2000))
    }

    @Test
    fun positionAt_interpolationMidpoint() {
        // 0->100 over 1000ms. At 500ms should be ~50.
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100))
        assertEquals(50, fs.positionAt(500))
    }

    @Test
    fun positionAt_interpolationQuarter() {
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100))
        assertEquals(25, fs.positionAt(250))
    }

    @Test
    fun positionAt_interpolationThreeQuarter() {
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100))
        assertEquals(75, fs.positionAt(750))
    }

    @Test
    fun positionAt_interpolationDescending() {
        // 100->0 over 1000ms. At 500ms should be ~50.
        val fs = buildFunscript(listOf(0L to 100, 1000L to 0))
        assertEquals(50, fs.positionAt(500))
    }

    @Test
    fun positionAt_withRange80_scalesDown() {
        // range=80 means output is scaled by 0.8
        // Action at pos=100, range=80 -> scaled = 80
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100), range = 80)
        // At 1000ms: rawPos=100, scaled = 100 * 80/100 = 80
        assertEquals(80, fs.positionAt(1000))
        // At 0ms: rawPos=0, scaled = 0 * 80/100 = 0
        assertEquals(0, fs.positionAt(0))
        // At 500ms: rawPos=50, scaled = 50 * 80/100 = 40
        assertEquals(40, fs.positionAt(500))
    }

    @Test
    fun positionAt_withRange50_scalesDown() {
        val fs = buildFunscript(listOf(0L to 100, 1000L to 100), range = 50)
        // rawPos=100, scaled = 100 * 50/100 = 50
        assertEquals(50, fs.positionAt(500))
    }

    @Test
    fun positionAt_invertedTrue_invertsResult() {
        // With inverted=true, result = 100 - scaledPos
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100), inverted = true)
        // At 0ms: rawPos=0, scaled=0, inverted=100
        assertEquals(100, fs.positionAt(0))
        // At 1000ms: rawPos=100, scaled=100, inverted=0
        assertEquals(0, fs.positionAt(1000))
        // At 500ms: rawPos=50, scaled=50, inverted=50
        assertEquals(50, fs.positionAt(500))
    }

    @Test
    fun positionAt_invertedWithRange() {
        val fs = buildFunscript(listOf(0L to 0, 1000L to 100), range = 80, inverted = true)
        // At 1000ms: rawPos=100, scaled=80, inverted=20
        assertEquals(20, fs.positionAt(1000))
        // At 0ms: rawPos=0, scaled=0, inverted=100
        assertEquals(100, fs.positionAt(0))
    }

    @Test
    fun positionAt_multipleSegments() {
        val fs = buildFunscript(listOf(
            0L to 0,
            1000L to 100,
            2000L to 0,
            3000L to 100,
        ))
        assertEquals(50, fs.positionAt(500))
        assertEquals(100, fs.positionAt(1000))
        assertEquals(50, fs.positionAt(1500))
        assertEquals(0, fs.positionAt(2000))
        assertEquals(50, fs.positionAt(2500))
    }

    // ═══════════════════════════════════════════════════════════
    // actionsInRange — binary search
    // ═══════════════════════════════════════════════════════════

    @Test
    fun actionsInRange_emptyActions_returnsEmpty() {
        val fs = buildFunscript(emptyList())
        assertTrue(fs.actionsInRange(0, 10000).isEmpty())
    }

    @Test
    fun actionsInRange_allContained() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
            3000L to 30,
        ))
        val range = fs.actionsInRange(0, 5000)
        assertEquals(3, range.size)
    }

    @Test
    fun actionsInRange_noneContained() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
            3000L to 30,
        ))
        val range = fs.actionsInRange(5000, 10000)
        assertTrue(range.isEmpty())
    }

    @Test
    fun actionsInRange_noneContained_before() {
        val fs = buildFunscript(listOf(
            5000L to 10,
            6000L to 20,
        ))
        val range = fs.actionsInRange(0, 1000)
        assertTrue(range.isEmpty())
    }

    @Test
    fun actionsInRange_exactBoundaries() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
            3000L to 30,
        ))
        // startMs=1000 and endMs=3000 — actions at boundaries should be included
        val range = fs.actionsInRange(1000, 3000)
        assertEquals(3, range.size)
    }

    @Test
    fun actionsInRange_partialOverlap() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
            3000L to 30,
            4000L to 40,
            5000L to 50,
        ))
        val range = fs.actionsInRange(2000, 4000)
        assertEquals(3, range.size)
        assertEquals(2000L, range[0].atMs)
        assertEquals(3000L, range[1].atMs)
        assertEquals(4000L, range[2].atMs)
    }

    @Test
    fun actionsInRange_singleActionInRange() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
            3000L to 30,
        ))
        val range = fs.actionsInRange(1500, 2500)
        assertEquals(1, range.size)
        assertEquals(2000L, range[0].atMs)
    }

    @Test
    fun actionsInRange_startAfterEnd_returnsEmpty() {
        val fs = buildFunscript(listOf(
            1000L to 10,
            2000L to 20,
        ))
        val range = fs.actionsInRange(5000, 1000)
        assertTrue(range.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // intensityAt
    // ═══════════════════════════════════════════════════════════

    @Test
    fun intensityAt_rapidActions_highIntensity() {
        // Create very rapid actions — big position changes in short time
        val actions = mutableListOf<Pair<Long, Int>>()
        for (i in 0..20) {
            val pos = if (i % 2 == 0) 0 else 100
            actions.add((5000L + i * 100L) to pos) // 100ms apart, 0->100 swings
        }
        val fs = buildFunscript(actions)
        val intensity = fs.intensityAt(6000, 5000)
        assertTrue("Rapid actions should produce high intensity, got $intensity", intensity > 50)
    }

    @Test
    fun intensityAt_noNearbyActions_returnsZero() {
        val fs = buildFunscript(listOf(
            100000L to 50,
            200000L to 100,
        ))
        // Query far from any actions
        assertEquals(0, fs.intensityAt(0, 5000))
    }

    @Test
    fun intensityAt_singleNearbyAction_returnsZero() {
        // Only one action in window — need at least 2 for speed calculation
        val fs = buildFunscript(listOf(
            5000L to 50,
        ))
        assertEquals(0, fs.intensityAt(5000, 5000))
    }

    @Test
    fun intensityAt_slowActions_lowIntensity() {
        // Slow actions — small changes over long time
        val fs = buildFunscript(listOf(
            0L to 50,
            2500L to 51,
            5000L to 52,
            7500L to 53,
            10000L to 54,
        ))
        val intensity = fs.intensityAt(5000, 10000)
        assertTrue("Slow actions should produce low intensity, got $intensity", intensity < 10)
    }

    @Test
    fun intensityAt_clampedTo100() {
        // Even with extremely rapid actions, intensity is capped at 100
        val actions = mutableListOf<Pair<Long, Int>>()
        for (i in 0..100) {
            val pos = if (i % 2 == 0) 0 else 100
            actions.add((5000L + i * 10L) to pos) // 10ms apart, 0->100 swings
        }
        val fs = buildFunscript(actions)
        val intensity = fs.intensityAt(5500, 2000)
        assertTrue("Intensity should be capped at 100, got $intensity", intensity <= 100)
    }

    // ═══════════════════════════════════════════════════════════
    // generateHeatmap
    // ═══════════════════════════════════════════════════════════

    @Test
    fun generateHeatmap_emptyActions_returnsEmpty() {
        val fs = buildFunscript(emptyList())
        assertTrue(fs.generateHeatmap(60000).isEmpty())
    }

    @Test
    fun generateHeatmap_zeroDuration_returnsEmpty() {
        val fs = buildFunscript(listOf(0L to 50))
        assertTrue(fs.generateHeatmap(0).isEmpty())
    }

    @Test
    fun generateHeatmap_correctSegmentCount() {
        val fs = buildFunscript(listOf(
            0L to 0,
            30000L to 100,
            60000L to 0,
        ))
        val heatmap = fs.generateHeatmap(60000, segments = 50)
        assertEquals(50, heatmap.size)
    }

    @Test
    fun generateHeatmap_normalizedPositions() {
        val fs = buildFunscript(listOf(
            0L to 0,
            10000L to 100,
        ))
        val heatmap = fs.generateHeatmap(10000, segments = 10)
        // First segment position should be near 0.0
        assertEquals(0f, heatmap.first().first, 0.01f)
        // Last segment position should be near 0.9 (segment 9 / 10)
        assertEquals(0.9f, heatmap.last().first, 0.01f)
    }

    // ═══════════════════════════════════════════════════════════
    // isEmpty / durationMs properties
    // ═══════════════════════════════════════════════════════════

    @Test
    fun isEmpty_true_whenNoActions() {
        val fs = buildFunscript(emptyList())
        assertTrue(fs.isEmpty)
    }

    @Test
    fun isEmpty_false_whenHasActions() {
        val fs = buildFunscript(listOf(0L to 50))
        assertFalse(fs.isEmpty)
    }

    @Test
    fun durationMs_zero_whenEmpty() {
        val fs = buildFunscript(emptyList())
        assertEquals(0L, fs.durationMs)
    }

    @Test
    fun durationMs_returnsLastActionTime() {
        val fs = buildFunscript(listOf(0L to 0, 5000L to 100, 10000L to 50))
        assertEquals(10000L, fs.durationMs)
    }

    @Test
    fun positionAt_withRangeZero_clampsToMinimumRange() {
        // range=0 is clamped to 1 in parseJson, so positions should be near-zero but not exactly zero
        val json = """{"range": 0, "actions": [{"at": 0, "pos": 50}, {"at": 1000, "pos": 100}]}"""
        val fs = parser.parseJson(json, "test")!!
        // range is clamped to 1 in parseJson, so 50 * 1/100 = 0 (truncated)
        assertEquals(1, fs.range)  // verify clamping worked
    }
}
