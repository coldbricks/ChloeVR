package com.ashairfoil.prism.haptics

import org.junit.Assert.*
import org.junit.Test

/**
 * Test suite for math helper functions in SpectralAnalyzer.kt:
 * lerp(), applyCurve(), smoothStep().
 *
 * These are used across the entire signal processing pipeline --
 * gate blending, envelope curves, spectral analysis.
 */
class MathHelpersTest {

    private val delta = 0.0001f

    // ═══════════════════════════════════════════════════════════════════
    //  lerp() -- linear interpolation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `lerp at t=0 returns a`() {
        assertEquals(0f, lerp(0f, 10f, 0f), delta)
    }

    @Test
    fun `lerp at t=1 returns b`() {
        assertEquals(10f, lerp(0f, 10f, 1f), delta)
    }

    @Test
    fun `lerp at t=0_5 returns midpoint`() {
        assertEquals(5f, lerp(0f, 10f, 0.5f), delta)
    }

    @Test
    fun `lerp at t=0_25 returns quarter point`() {
        assertEquals(2.5f, lerp(0f, 10f, 0.25f), delta)
    }

    @Test
    fun `lerp at t=0_75 returns three-quarter point`() {
        assertEquals(7.5f, lerp(0f, 10f, 0.75f), delta)
    }

    @Test
    fun `lerp with equal a and b returns that value`() {
        assertEquals(5f, lerp(5f, 5f, 0.5f), delta)
    }

    @Test
    fun `lerp with negative values`() {
        assertEquals(-5f, lerp(-10f, 0f, 0.5f), delta)
    }

    @Test
    fun `lerp with reversed range`() {
        assertEquals(7.5f, lerp(10f, 0f, 0.25f), delta)
    }

    @Test
    fun `lerp extrapolates beyond t=1`() {
        // lerp does not clamp -- t > 1 extrapolates
        assertEquals(20f, lerp(0f, 10f, 2f), delta)
    }

    @Test
    fun `lerp extrapolates below t=0`() {
        assertEquals(-10f, lerp(0f, 10f, -1f), delta)
    }

    @Test
    fun `lerp identity at t=0 for any range`() {
        assertEquals(42f, lerp(42f, 100f, 0f), delta)
        assertEquals(-17f, lerp(-17f, 50f, 0f), delta)
    }

    @Test
    fun `lerp identity at t=1 for any range`() {
        assertEquals(100f, lerp(42f, 100f, 1f), delta)
        assertEquals(50f, lerp(-17f, 50f, 1f), delta)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  smoothStep() -- Hermite smooth step (3t^2 - 2t^3)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `smoothStep at 0 returns 0`() {
        assertEquals(0f, smoothStep(0f), delta)
    }

    @Test
    fun `smoothStep at 1 returns 1`() {
        assertEquals(1f, smoothStep(1f), delta)
    }

    @Test
    fun `smoothStep at 0_5 returns 0_5`() {
        assertEquals(0.5f, smoothStep(0.5f), delta)
    }

    @Test
    fun `smoothStep at 0_25 returns correct value`() {
        // 3*(0.25)^2 - 2*(0.25)^3 = 3*0.0625 - 2*0.015625 = 0.1875 - 0.03125 = 0.15625
        assertEquals(0.15625f, smoothStep(0.25f), delta)
    }

    @Test
    fun `smoothStep at 0_75 returns correct value`() {
        // 3*(0.75)^2 - 2*(0.75)^3 = 3*0.5625 - 2*0.421875 = 1.6875 - 0.84375 = 0.84375
        assertEquals(0.84375f, smoothStep(0.75f), delta)
    }

    @Test
    fun `smoothStep clamps negative input to 0`() {
        assertEquals(0f, smoothStep(-1f), delta)
        assertEquals(0f, smoothStep(-0.5f), delta)
    }

    @Test
    fun `smoothStep clamps input above 1 to 1`() {
        assertEquals(1f, smoothStep(2f), delta)
        assertEquals(1f, smoothStep(1.5f), delta)
    }

    @Test
    fun `smoothStep is monotonically increasing`() {
        var prev = smoothStep(0f)
        for (i in 1..100) {
            val t = i / 100f
            val current = smoothStep(t)
            assertTrue(
                "smoothStep should be monotonic: smoothStep(${(i-1)/100f})=$prev <= smoothStep($t)=$current",
                current >= prev
            )
            prev = current
        }
    }

    @Test
    fun `smoothStep is symmetric around 0_5`() {
        // smoothStep(0.5 - x) + smoothStep(0.5 + x) = 1.0
        for (i in 0..50) {
            val x = i / 100f
            val low = smoothStep(0.5f - x)
            val high = smoothStep(0.5f + x)
            assertEquals(
                "smoothStep should be symmetric: f(${0.5f - x}) + f(${0.5f + x}) should equal 1.0",
                1f, low + high, 0.001f
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  applyCurve() -- power curve
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `applyCurve linear exponent=1 returns input`() {
        assertEquals(0.5f, applyCurve(0.5f, 1f), delta)
    }

    @Test
    fun `applyCurve quadratic exponent=2`() {
        assertEquals(0.25f, applyCurve(0.5f, 2f), delta)
    }

    @Test
    fun `applyCurve cubic exponent=3`() {
        assertEquals(0.125f, applyCurve(0.5f, 3f), delta)
    }

    @Test
    fun `applyCurve square root exponent=0_5`() {
        // 0.25^0.5 = 0.5
        assertEquals(0.5f, applyCurve(0.25f, 0.5f), delta)
    }

    @Test
    fun `applyCurve at 0 returns 0 for any exponent`() {
        assertEquals(0f, applyCurve(0f, 1f), delta)
        assertEquals(0f, applyCurve(0f, 2f), delta)
        assertEquals(0f, applyCurve(0f, 0.5f), delta)
    }

    @Test
    fun `applyCurve at 1 returns 1 for any exponent`() {
        assertEquals(1f, applyCurve(1f, 1f), delta)
        assertEquals(1f, applyCurve(1f, 2f), delta)
        assertEquals(1f, applyCurve(1f, 0.5f), delta)
        assertEquals(1f, applyCurve(1f, 3f), delta)
    }

    @Test
    fun `applyCurve clamps negative input to 0`() {
        assertEquals(0f, applyCurve(-0.5f, 1f), delta)
        assertEquals(0f, applyCurve(-1f, 2f), delta)
    }

    @Test
    fun `applyCurve clamps input above 1 to 1`() {
        assertEquals(1f, applyCurve(1.5f, 1f), delta)
        assertEquals(1f, applyCurve(2f, 2f), delta)
    }

    @Test
    fun `applyCurve high exponent compresses low values`() {
        // With exponent > 1, low input values get pushed closer to 0
        val linear = applyCurve(0.3f, 1f)
        val curved = applyCurve(0.3f, 3f)
        assertTrue(
            "High exponent should compress: linear=$linear > curved=$curved",
            linear > curved
        )
    }

    @Test
    fun `applyCurve low exponent expands low values`() {
        // With exponent < 1, low input values get pushed closer to 1
        val linear = applyCurve(0.3f, 1f)
        val curved = applyCurve(0.3f, 0.5f)
        assertTrue(
            "Low exponent should expand: curved=$curved > linear=$linear",
            curved > linear
        )
    }

    @Test
    fun `applyCurve is monotonically increasing for positive exponents`() {
        val exponents = floatArrayOf(0.5f, 1f, 2f, 3f)
        for (exp in exponents) {
            var prev = applyCurve(0f, exp)
            for (i in 1..100) {
                val v = i / 100f
                val current = applyCurve(v, exp)
                assertTrue(
                    "applyCurve should be monotonic for exp=$exp: f(${(i-1)/100f})=$prev <= f($v)=$current",
                    current >= prev
                )
                prev = current
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Cross-function consistency
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `lerp at t=0 and t=1 matches applyCurve endpoints`() {
        // applyCurve(0, any) = 0, applyCurve(1, any) = 1
        // lerp(0, 1, 0) = 0, lerp(0, 1, 1) = 1
        assertEquals(applyCurve(0f, 2f), lerp(0f, 1f, 0f), delta)
        assertEquals(applyCurve(1f, 2f), lerp(0f, 1f, 1f), delta)
    }

    @Test
    fun `smoothStep passes through 0 and 1`() {
        assertEquals(applyCurve(0f, 1f), smoothStep(0f), delta)
        assertEquals(applyCurve(1f, 1f), smoothStep(1f), delta)
    }
}
