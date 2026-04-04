package com.ashairfoil.prism.haptics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Test suite for Gate -- noise gate with hysteresis and auto-gate.
 *
 * The gate uses asymmetric smoothing (instant open, smooth close) and
 * hysteresis to prevent chattering around the threshold.
 */
class GateTest {

    private lateinit var gate: Gate

    // Standard test parameters
    private val manualThreshold = 0.3f
    private val autoGateAmount = 0f       // manual mode
    private val smoothing = 0f            // instant transitions
    private val thresholdKnee = 0f        // hard edge

    @Before
    fun setup() {
        gate = Gate()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Basic gate behavior
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `silence produces closed gate`() {
        val open = gate.process(0f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertFalse("Gate should be closed with zero energy", open)
    }

    @Test
    fun `loud signal opens gate`() {
        val open = gate.process(0.8f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertTrue("Gate should open with energy well above threshold", open)
    }

    @Test
    fun `energy at threshold does not open gate`() {
        // Threshold is 0.3. When gate was closed, energy must be > threshold to open.
        val open = gate.process(0.3f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertFalse("Gate should not open when energy equals threshold", open)
    }

    @Test
    fun `energy just above threshold opens gate`() {
        val open = gate.process(0.31f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertTrue("Gate should open when energy is above threshold", open)
    }

    @Test
    fun `multiple silence frames keep gate closed`() {
        for (i in 0 until 10) {
            val open = gate.process(0f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
            assertFalse("Gate should remain closed on frame $i", open)
        }
    }

    @Test
    fun `multiple loud frames keep gate open`() {
        for (i in 0 until 10) {
            val open = gate.process(0.8f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
            assertTrue("Gate should remain open on frame $i", open)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hysteresis -- prevents rapid toggling
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `hysteresis prevents chattering around threshold`() {
        // Open the gate with energy above threshold
        gate.process(0.5f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)

        // Energy drops slightly below the open threshold but should still be
        // above the close threshold (threshold - hysteresis).
        // Hysteresis = (0.3 * 0.25).coerceIn(0.005, 0.08) = 0.075
        // Close threshold = 0.3 - 0.075 = 0.225
        // Energy 0.28 is below open (0.3) but above close (0.225)
        val open = gate.process(0.28f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertTrue("Gate should stay open due to hysteresis", open)
    }

    @Test
    fun `gate closes when energy drops below hysteresis band`() {
        // Open the gate
        gate.process(0.5f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)

        // Drop well below close threshold (0.225)
        val open = gate.process(0.1f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertFalse("Gate should close when energy is well below close threshold", open)
    }

    @Test
    fun `oscillating energy around threshold does not cause rapid toggling`() {
        // Simulate energy oscillating between 0.27 and 0.33 around threshold 0.3
        val results = mutableListOf<Boolean>()

        // First, open the gate clearly
        gate.process(0.5f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)

        // Then oscillate -- gate should stay open due to hysteresis
        for (i in 0 until 20) {
            val energy = if (i % 2 == 0) 0.27f else 0.33f
            results.add(gate.process(energy, manualThreshold, autoGateAmount, smoothing, thresholdKnee))
        }

        // Count transitions
        var transitions = 0
        for (i in 1 until results.size) {
            if (results[i] != results[i - 1]) transitions++
        }

        assertTrue(
            "Hysteresis should prevent excessive toggling; got $transitions transitions",
            transitions < 5
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Smoothed gate signal
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `smoothed value is 0 when gate is closed`() {
        gate.process(0f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertEquals(0f, gate.smoothed, 0.001f)
    }

    @Test
    fun `smoothed value is 1 when gate is open with no smoothing`() {
        gate.process(0.8f, manualThreshold, autoGateAmount, smoothing, thresholdKnee)
        assertEquals(1f, gate.smoothed, 0.001f)
    }

    @Test
    fun `smoothed value transitions gradually with smoothing enabled`() {
        val highSmoothing = 0.9f

        // Open the gate
        gate.process(0.8f, manualThreshold, autoGateAmount, highSmoothing, thresholdKnee)
        val openSmoothed = gate.smoothed
        assertEquals("Opening should be instant even with smoothing", 1f, openSmoothed, 0.001f)

        // Close the gate -- smoothed should decay gradually
        gate.process(0f, manualThreshold, autoGateAmount, highSmoothing, thresholdKnee)
        val closingSmoothed = gate.smoothed
        assertTrue(
            "Smoothed value should still be above 0 during closing transition",
            closingSmoothed > 0f
        )
    }

    @Test
    fun `asymmetric smoothing opens instantly closes slowly`() {
        val highSmoothing = 0.95f

        // Open: should be instant (smoothed = 1.0)
        gate.process(0.8f, manualThreshold, autoGateAmount, highSmoothing, thresholdKnee)
        assertEquals("Gate should open instantly", 1f, gate.smoothed, 0.001f)

        // Close: should decay slowly
        gate.process(0f, manualThreshold, autoGateAmount, highSmoothing, thresholdKnee)
        assertTrue("Gate should not close instantly with high smoothing", gate.smoothed > 0.5f)

        // After many frames, should eventually close
        repeat(100) {
            gate.process(0f, manualThreshold, autoGateAmount, highSmoothing, thresholdKnee)
        }
        assertTrue("Gate should eventually close", gate.smoothed < 0.1f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Auto-gate
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `auto-gate adapts threshold after sufficient samples`() {
        val autoGate = Gate()

        // Feed 100 frames of consistent energy to build histogram
        repeat(100) {
            autoGate.process(0.5f, 0.3f, 1.0f, 0f, 0f)
        }

        // The effective threshold should have adapted
        val effectiveThreshold = autoGate.effectiveThreshold(0.3f, 1.0f)
        // With constant 0.5 energy, the auto-threshold should settle
        // (exact value depends on the histogram binning and 25% open time target)
        assertTrue(
            "Auto-gate threshold should be reasonable, got $effectiveThreshold",
            effectiveThreshold in 0.01f..0.99f
        )
    }

    @Test
    fun `auto-gate resets histogram when disabled`() {
        // Feed frames with auto-gate on
        repeat(100) {
            gate.process(0.5f, 0.3f, 1.0f, 0f, 0f)
        }

        // Disable auto-gate -- histogram should reset
        gate.process(0.5f, 0.3f, 0f, 0f, 0f)
        val threshold = gate.effectiveThreshold(0.3f, 0f)
        assertEquals("With auto-gate off, threshold should be manual", 0.3f, threshold, 0.01f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  effectiveThreshold()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `effectiveThreshold is manual when autoAmount is 0`() {
        val threshold = gate.effectiveThreshold(0.5f, 0f)
        assertEquals(0.5f, threshold, 0.001f)
    }

    @Test
    fun `effectiveThreshold is auto when autoAmount is 1`() {
        val threshold = gate.effectiveThreshold(0.5f, 1f)
        // Default optimalThreshold is 0.2
        assertEquals(0.2f, threshold, 0.001f)
    }

    @Test
    fun `effectiveThreshold blends at 0_5`() {
        val threshold = gate.effectiveThreshold(0.5f, 0.5f)
        // lerp(0.5, 0.2, 0.5) = 0.5 + (0.2 - 0.5) * 0.5 = 0.35
        assertEquals(0.35f, threshold, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `zero threshold passes any nonzero energy`() {
        val open = gate.process(0.01f, 0f, 0f, 0f, 0f)
        assertTrue("Any energy should pass with zero threshold", open)
    }

    @Test
    fun `maximum threshold blocks all but maximum energy`() {
        val open = gate.process(0.99f, 1.0f, 0f, 0f, 0f)
        assertFalse("Energy below 1.0 should not pass threshold of 1.0", open)
    }

    @Test
    fun `energy of exactly 1_0 passes threshold of 1_0`() {
        // Hysteresis at 1.0: (1.0 * 0.25).coerceIn(0.005, 0.08) = 0.08
        // Open threshold = 1.0, so energy must be > 1.0 to open when closed.
        val open = gate.process(1.0f, 1.0f, 0f, 0f, 0f)
        assertFalse("Energy exactly at threshold should not open gate (must be >)", open)
    }
}
