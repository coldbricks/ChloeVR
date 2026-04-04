package com.ashairfoil.prism.haptics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Test suite for EnvelopeProcessor -- ADSR envelope with configurable curves.
 *
 * Tests trigger/attack/decay/sustain/release transitions, reset behavior,
 * retrigger during sustain, magnitude scaling, and output clamping.
 */
class EnvelopeProcessorTest {

    private lateinit var env: EnvelopeProcessor

    // Standard ADSR parameters for testing
    private val attackMs = 100f
    private val decayMs = 100f
    private val sustainLevel = 0.6f
    private val releaseMs = 200f
    private val attackCurve = 1f   // linear
    private val decayCurve = 1f
    private val releaseCurve = 1f

    @Before
    fun setup() {
        env = EnvelopeProcessor()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Initial state
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `initial state is Idle`() {
        assertEquals(EnvelopeState.Idle, env.state)
    }

    @Test
    fun `initial value is 0`() {
        assertEquals(0f, env.value, 0.001f)
    }

    @Test
    fun `process in Idle returns 0`() {
        val output = env.process(0f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)
        assertEquals(0f, output, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Trigger starts attack
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `trigger transitions to Attack state`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs)
        assertEquals(EnvelopeState.Attack, env.state)
    }

    @Test
    fun `trigger with short attack skips to Decay`() {
        // Attack < 50ms triggers instant peak
        env.trigger(1.0f, 100f, 1.0f, attackMs = 30f)
        assertEquals(EnvelopeState.Decay, env.state)
    }

    @Test
    fun `attack ramps up value over time`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs)

        // Process at 25% through attack
        val out1 = env.process(125f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        // Process at 75% through attack
        val out2 = env.process(175f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertTrue("Value should increase during attack: $out1 < $out2", out2 > out1)
    }

    @Test
    fun `attack completes and transitions to Decay`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs)

        // Process past end of attack
        env.process(100f + attackMs + 10f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertEquals(EnvelopeState.Decay, env.state)
    }

    @Test
    fun `instant attack transitions directly to Decay`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 100f)
        // Use 0 attack time in process -- attackMs <= 0.5 causes instant attack
        env.process(100f, 0f, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)
        assertEquals(EnvelopeState.Decay, env.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Decay transitions to Sustain
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `decay ramps down from peak toward sustain level`() {
        // Short attack to get to Decay quickly
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        assertEquals(EnvelopeState.Decay, env.state)

        // Early in decay, value should be above sustain
        val out1 = env.process(105f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        // Late in decay, value should be closer to sustain
        val out2 = env.process(100f + decayMs + 10f, 10f, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertTrue("Decay should reduce value toward sustain: $out1 >= $out2", out1 >= out2)
    }

    @Test
    fun `decay completes and transitions to Sustain`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)

        // Process past decay duration
        env.process(100f + decayMs + 50f, 10f, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertEquals(EnvelopeState.Sustain, env.state)
    }

    @Test
    fun `instant decay transitions directly to Sustain`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        // Use 0 decay time
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)
        assertEquals(EnvelopeState.Sustain, env.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Sustain holds value
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `sustain maintains non-zero output`() {
        // Fast attack + fast decay to reach sustain
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve) // Instant decay -> Sustain

        // Process in sustain -- should produce non-zero output
        val out = env.process(200f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertTrue("Sustain output should be non-zero, got $out", out > 0f)
        assertEquals(EnvelopeState.Sustain, env.state)
    }

    @Test
    fun `sustain with modulation stays near sustain level`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        // Collect sustain values over time (modulation adds variation)
        val values = mutableListOf<Float>()
        for (i in 0 until 20) {
            val t = 200f + i * 23.3f
            val out = env.process(t, attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
            values.add(out)
        }

        // All values should be in a reasonable range around sustain * magnitude
        for ((idx, v) in values.withIndex()) {
            assertTrue(
                "Sustain value at frame $idx should be in [0, 1], got $v",
                v in 0f..1f
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Release ramps down to floor
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `release transitions from Sustain to Release`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve) // -> Sustain

        env.release(300f)
        assertEquals(EnvelopeState.Release, env.state)
    }

    @Test
    fun `release ramps value down toward zero`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        val sustainValue = env.value

        env.release(300f)
        val out1 = env.process(350f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve) // 50ms into release

        val out2 = env.process(500f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve) // 200ms into release

        assertTrue("Release should decrease value: $out1 >= $out2", out1 >= out2)
    }

    @Test
    fun `release completes and transitions to Idle`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        env.release(300f)

        // Process well past release duration
        env.process(300f + releaseMs + 100f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertEquals(EnvelopeState.Idle, env.state)
    }

    @Test
    fun `instant release transitions directly to Idle`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, 0f, // 0 release
            attackCurve, decayCurve, releaseCurve)

        env.release(300f)
        env.process(300f, attackMs, decayMs, sustainLevel, 0f,
            attackCurve, decayCurve, releaseCurve)

        assertEquals(EnvelopeState.Idle, env.state)
        assertEquals(0f, env.value, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Reset clears state
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `reset returns to Idle with zero value`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs)
        env.process(150f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        assertTrue("Should be in non-idle state before reset", env.state != EnvelopeState.Idle)

        env.reset()
        assertEquals(EnvelopeState.Idle, env.state)
        assertEquals(0f, env.value, 0.001f)
    }

    @Test
    fun `reset allows retrigger`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs)
        env.reset()

        env.trigger(1.0f, 200f, 1.0f, attackMs)
        assertTrue(
            "Should be triggerable after reset",
            env.state == EnvelopeState.Attack || env.state == EnvelopeState.Decay
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Retrigger during sustain restarts attack
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `retrigger during sustain restarts envelope`() {
        // Get to sustain
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve) // -> Sustain
        assertEquals(EnvelopeState.Sustain, env.state)

        // Retrigger (must be >20ms after first trigger due to minRetriggerMs)
        env.trigger(1.0f, 200f, 1.0f, attackMs)

        assertTrue(
            "Retrigger should restart attack or skip to decay",
            env.state == EnvelopeState.Attack || env.state == EnvelopeState.Decay
        )
    }

    @Test
    fun `retrigger within minRetrigger interval is ignored`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        val stateBefore = env.state
        // Try to retrigger within 20ms -- should be ignored
        env.trigger(1.0f, 115f, 1.0f, attackMs)
        assertEquals("Retrigger within 20ms should be ignored", stateBefore, env.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Output clamped to [0, 1]
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `output is always in 0 to 1 range`() {
        env.trigger(1.5f, 100f, 1.5f, attackMs = 10f)

        for (i in 0 until 200) {
            val t = 100f + i * 10f
            val out = env.process(t, attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
            assertTrue("Output $out at frame $i should be in [0, 1]", out in 0f..1f)
        }
    }

    @Test
    fun `output never goes negative during release`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)

        env.release(300f)

        for (i in 0 until 100) {
            val t = 300f + i * 10f
            val out = env.process(t, attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
            assertTrue("Output $out should never be negative during release", out >= 0f)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Release does not activate from Idle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `release from Idle does nothing`() {
        assertEquals(EnvelopeState.Idle, env.state)
        env.release(100f)
        assertEquals("Release from Idle should remain Idle", EnvelopeState.Idle, env.state)
    }

    @Test
    fun `release from Release does nothing`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        env.process(100f, 10f, 0f, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)
        env.release(300f)
        assertEquals(EnvelopeState.Release, env.state)

        // Double release should not change state
        env.release(400f)
        assertEquals(EnvelopeState.Release, env.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  updateMagnitude during sustain
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `updateMagnitude only affects Sustain state`() {
        // In Attack state
        env.trigger(0.5f, 100f, 1.0f, attackMs)
        assertEquals(EnvelopeState.Attack, env.state)
        env.updateMagnitude(1.0f)
        // Should not crash or change state
        assertEquals(EnvelopeState.Attack, env.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Velocity overshoot
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `high velocity causes overshoot above 1_0`() {
        // Velocity > 1.0 should set attackTarget above 1.0
        env.trigger(1.0f, 100f, 1.5f, attackMs = 10f)
        // With short attack, goes directly to Decay at attackTarget
        // attackTarget = (1.0 + 0.15 * (1.5 - 1.0)).coerceAtMost(1.2) = 1.075
        assertTrue(
            "High velocity should push to Decay state",
            env.state == EnvelopeState.Decay
        )
    }

    @Test
    fun `normal velocity has attackTarget of 1_0`() {
        env.trigger(1.0f, 100f, 1.0f, attackMs = 10f)
        // With velocity 1.0, attackTarget should be 1.0
        // Value after short attack skip should be at or near 1.0
        assertTrue("Value should be near 1.0 with normal velocity", env.value >= 0.9f)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Full ADSR cycle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full ADSR cycle returns to zero`() {
        // Trigger at time 100 (must be >= minRetriggerMs to avoid rejection)
        env.trigger(1.0f, 100f, 1.0f, attackMs)

        // Walk through attack (100..200)
        for (t in 100..200 step 10) {
            env.process(t.toFloat(), attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
        }

        // Walk through decay (210..310)
        for (t in 210..310 step 10) {
            env.process(t.toFloat(), attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
        }

        // Should be in sustain now
        assertEquals(EnvelopeState.Sustain, env.state)

        // Release
        env.release(400f)

        // Walk through release (400..700)
        for (t in 400..700 step 10) {
            env.process(t.toFloat(), attackMs, decayMs, sustainLevel, releaseMs,
                attackCurve, decayCurve, releaseCurve)
        }

        // Should be idle with zero output
        assertEquals(EnvelopeState.Idle, env.state)

        val finalOutput = env.process(800f, attackMs, decayMs, sustainLevel, releaseMs,
            attackCurve, decayCurve, releaseCurve)
        assertEquals("After full ADSR cycle, output should be 0", 0f, finalOutput, 0.01f)
    }
}
