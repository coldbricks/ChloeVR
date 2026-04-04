package com.ashairfoil.prism.haptics

import org.junit.Assert.*
import org.junit.Test

/**
 * Test suite for LovenseProtocol BLE command builder.
 *
 * Every command is an ASCII string terminated with ';'.
 * Vibration levels are 0-20, clamped. Presets are 1-10, clamped.
 */
class LovenseProtocolTest {

    // ═══════════════════════════════════════════════════════════════════
    //  vibrate() -- single motor intensity
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `vibrate 0 returns Vibrate 0`() {
        assertEquals("Vibrate:0;", LovenseProtocol.vibrate(0))
    }

    @Test
    fun `vibrate 20 returns Vibrate 20`() {
        assertEquals("Vibrate:20;", LovenseProtocol.vibrate(20))
    }

    @Test
    fun `vibrate 10 returns Vibrate 10`() {
        assertEquals("Vibrate:10;", LovenseProtocol.vibrate(10))
    }

    @Test
    fun `vibrate 1 returns Vibrate 1`() {
        assertEquals("Vibrate:1;", LovenseProtocol.vibrate(1))
    }

    @Test
    fun `vibrate negative clamps to 0`() {
        assertEquals("Vibrate:0;", LovenseProtocol.vibrate(-1))
    }

    @Test
    fun `vibrate large negative clamps to 0`() {
        assertEquals("Vibrate:0;", LovenseProtocol.vibrate(-100))
    }

    @Test
    fun `vibrate 25 clamps to 20`() {
        assertEquals("Vibrate:20;", LovenseProtocol.vibrate(25))
    }

    @Test
    fun `vibrate large positive clamps to 20`() {
        assertEquals("Vibrate:20;", LovenseProtocol.vibrate(999))
    }

    @Test
    fun `vibrate Int MIN_VALUE clamps to 0`() {
        assertEquals("Vibrate:0;", LovenseProtocol.vibrate(Int.MIN_VALUE))
    }

    @Test
    fun `vibrate Int MAX_VALUE clamps to 20`() {
        assertEquals("Vibrate:20;", LovenseProtocol.vibrate(Int.MAX_VALUE))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  vibrate2() -- dual motor intensity (Nora, etc.)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `vibrate2 normal values`() {
        assertEquals("Vibrate1:10;Vibrate2:15;", LovenseProtocol.vibrate2(10, 15))
    }

    @Test
    fun `vibrate2 both zero`() {
        assertEquals("Vibrate1:0;Vibrate2:0;", LovenseProtocol.vibrate2(0, 0))
    }

    @Test
    fun `vibrate2 both max`() {
        assertEquals("Vibrate1:20;Vibrate2:20;", LovenseProtocol.vibrate2(20, 20))
    }

    @Test
    fun `vibrate2 motor1 clamped high`() {
        assertEquals("Vibrate1:20;Vibrate2:5;", LovenseProtocol.vibrate2(25, 5))
    }

    @Test
    fun `vibrate2 motor2 clamped low`() {
        assertEquals("Vibrate1:5;Vibrate2:0;", LovenseProtocol.vibrate2(5, -3))
    }

    @Test
    fun `vibrate2 both clamped`() {
        assertEquals("Vibrate1:20;Vibrate2:0;", LovenseProtocol.vibrate2(50, -50))
    }

    @Test
    fun `vibrate2 asymmetric motors`() {
        assertEquals("Vibrate1:3;Vibrate2:18;", LovenseProtocol.vibrate2(3, 18))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  rotate() -- rotation speed (Nora)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `rotate normal value`() {
        assertEquals("Rotate:10;", LovenseProtocol.rotate(10))
    }

    @Test
    fun `rotate zero`() {
        assertEquals("Rotate:0;", LovenseProtocol.rotate(0))
    }

    @Test
    fun `rotate max`() {
        assertEquals("Rotate:20;", LovenseProtocol.rotate(20))
    }

    @Test
    fun `rotate negative clamps to 0`() {
        assertEquals("Rotate:0;", LovenseProtocol.rotate(-5))
    }

    @Test
    fun `rotate over max clamps to 20`() {
        assertEquals("Rotate:20;", LovenseProtocol.rotate(30))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  stop() -- all motors off
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `stop returns Vibrate 0`() {
        assertEquals("Vibrate:0;", LovenseProtocol.stop())
    }

    @Test
    fun `stop is consistent with vibrate 0`() {
        assertEquals(LovenseProtocol.vibrate(0), LovenseProtocol.stop())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Query commands
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `battery returns Battery command`() {
        assertEquals("Battery;", LovenseProtocol.battery())
    }

    @Test
    fun `deviceType returns DeviceType command`() {
        assertEquals("DeviceType;", LovenseProtocol.deviceType())
    }

    @Test
    fun `deviceInfo returns DeviceInfo command`() {
        assertEquals("DeviceInfo;", LovenseProtocol.deviceInfo())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  powerOff()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `powerOff returns PowerOff command`() {
        assertEquals("PowerOff;", LovenseProtocol.powerOff())
    }

    // ═══════════════════════════════════════════════════════════════════
    //  preset() -- pattern selection (1-10)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `preset 1 returns Preset 1`() {
        assertEquals("Preset:1;", LovenseProtocol.preset(1))
    }

    @Test
    fun `preset 10 returns Preset 10`() {
        assertEquals("Preset:10;", LovenseProtocol.preset(10))
    }

    @Test
    fun `preset 5 returns Preset 5`() {
        assertEquals("Preset:5;", LovenseProtocol.preset(5))
    }

    @Test
    fun `preset 0 clamps to 1`() {
        assertEquals("Preset:1;", LovenseProtocol.preset(0))
    }

    @Test
    fun `preset negative clamps to 1`() {
        assertEquals("Preset:1;", LovenseProtocol.preset(-5))
    }

    @Test
    fun `preset 11 clamps to 10`() {
        assertEquals("Preset:10;", LovenseProtocol.preset(11))
    }

    @Test
    fun `preset large value clamps to 10`() {
        assertEquals("Preset:10;", LovenseProtocol.preset(999))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  All commands end with semicolon
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `all commands end with semicolon`() {
        val commands = listOf(
            LovenseProtocol.vibrate(10),
            LovenseProtocol.vibrate2(5, 10),
            LovenseProtocol.rotate(10),
            LovenseProtocol.stop(),
            LovenseProtocol.battery(),
            LovenseProtocol.deviceType(),
            LovenseProtocol.deviceInfo(),
            LovenseProtocol.powerOff(),
            LovenseProtocol.preset(3)
        )
        for (cmd in commands) {
            assertTrue("Command '$cmd' must end with ';'", cmd.endsWith(";"))
        }
    }

    @Test
    fun `all commands are ASCII only`() {
        val commands = listOf(
            LovenseProtocol.vibrate(10),
            LovenseProtocol.vibrate2(5, 10),
            LovenseProtocol.rotate(10),
            LovenseProtocol.stop(),
            LovenseProtocol.battery(),
            LovenseProtocol.deviceType(),
            LovenseProtocol.deviceInfo(),
            LovenseProtocol.powerOff(),
            LovenseProtocol.preset(3)
        )
        for (cmd in commands) {
            for (ch in cmd) {
                assertTrue("Command '$cmd' contains non-ASCII char: $ch", ch.code in 0..127)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Full range sweep
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `vibrate full range 0 to 20 produces valid commands`() {
        for (i in 0..20) {
            assertEquals("Vibrate:$i;", LovenseProtocol.vibrate(i))
        }
    }

    @Test
    fun `preset full range 1 to 10 produces valid commands`() {
        for (i in 1..10) {
            assertEquals("Preset:$i;", LovenseProtocol.preset(i))
        }
    }
}
