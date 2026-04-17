// ==========================================================================
// HapticScriptPlayer.kt — Wires funscript/.hsp files to the Lovense motor.
//
// Supports four modes:
//   Off       — no haptic output from this player.
//   Reactive  — ignore scripts; upstream audio-reactive path drives the motor.
//   Scripted  — funscript position (interpolated) drives both motors.
//   Mixed     — funscript drives motor 1; upstream reactive drives motor 2
//               (only meaningful on dual-motor devices).
//
// Scheduling strategy:
//   A single background thread polls the supplied positionProvider (typically
//   ExoPlayer.getCurrentPosition()) every ~25ms, interpolates the funscript
//   position at that timestamp via Funscript.positionAt, and submits a
//   dithered 0-20 intensity via BleDeviceManager.
//
// Thread-safety:
//   setMode, load, play, pause, stop, onSeek, release are safe to call from
//   the UI / activity threads. The worker thread observes @Volatile state
//   fields and the monitor lock `controlLock` for start/stop signaling.
//
// BLE discipline (see CLAUDE.md):
//   - 20Hz command cap is enforced by BleDeviceManager.sendCommand gating,
//     but we still dedupe to avoid spam.
//   - Vibrate:0; is issued on pause, stop, and release to halt the motor.
// ==========================================================================

package com.ashairfoil.prism.haptics

import android.util.Log
import com.ashairfoil.prism.data.FunscriptParser
import com.ashairfoil.prism.data.HspParser
import java.io.File
import kotlin.math.abs

/**
 * HapticScriptPlayer — scheduler that maps funscript position → Lovense intensity.
 *
 * Position semantics:
 *   Funscripts are stroke-position scripts (0 = bottom, 100 = top). For a
 *   vibrator we convert position changes into intensity by tracking stroke
 *   speed: fast movement = high intensity, still position = low intensity.
 *   This matches how Buttplug.io's "vibrate from funscript" strategies behave
 *   and feels substantially more natural than mapping absolute position.
 */
class HapticScriptPlayer(
    @Volatile var bleDeviceManager: BleDeviceManager?,
    /** Returns the current playback position in ms, or -1 if unavailable. */
    @Volatile var positionProvider: () -> Long,
    /** Returns true while the host player is actively playing. */
    @Volatile var isPlayingProvider: () -> Boolean = { true }
) {

    enum class Mode { Off, Reactive, Scripted, Mixed }

    @Volatile var mode: Mode = Mode.Reactive
        private set

    @Volatile var funscript: FunscriptParser.Funscript? = null
        private set
    @Volatile var hsp: HspParser.HspFile? = null
        private set
    @Volatile var currentVideoName: String = ""
        private set

    /** True when a worker thread is actively polling + writing. */
    @Volatile var isRunning: Boolean = false
        private set

    /** Last emitted Lovense intensity (0-20). -1 = never emitted. */
    @Volatile var lastIntensity: Int = -1
        private set

    /** Position reported by the provider on the most recent poll (ms). */
    @Volatile var lastPositionMs: Long = 0L
        private set

    private val controlLock = Object()
    private var workerThread: Thread? = null

    // Stroke-speed integrator state (worker-thread only).
    private var prevFsPos: Int = -1
    private var prevFsTimeMs: Long = -1L
    private var smoothedIntensity: Float = 0f

    // Dither accumulator to keep low intensities from rounding to zero.
    private var ditherError: Float = 0f

    companion object {
        private const val TAG = "HapticScriptPlayer"
        private const val POLL_INTERVAL_MS = 25L      // 40Hz loop — BLE gate caps writes at 20Hz
        private const val SEEK_JUMP_MS = 400L         // position delta treated as a seek
    }

    // ── Public API ──

    fun setMode(m: Mode) {
        val prev = mode
        mode = m
        Log.i(TAG, "Mode $prev -> $m")
        if (m == Mode.Off || m == Mode.Reactive) {
            halt()
        }
    }

    /** Convenience: accept a string from settings / UI. Unknown values fall back to Reactive. */
    fun setMode(name: String) {
        setMode(
            when (name) {
                "Off" -> Mode.Off
                "Scripted" -> Mode.Scripted
                "Mixed" -> Mode.Mixed
                else -> Mode.Reactive
            }
        )
    }

    /**
     * Look for `<videoname>.funscript` and `<videoname>.hsp` beside the video
     * file. Updates [funscript] and [hsp]; returns true if at least a funscript
     * was loaded.
     */
    fun loadForVideo(videoFile: File): Boolean {
        currentVideoName = videoFile.nameWithoutExtension
        val fs = runCatching { FunscriptParser().loadForVideo(videoFile) }.getOrNull()
        val hs = runCatching { HspParser().loadForVideo(videoFile) }.getOrNull()
        funscript = fs
        hsp = hs
        resetStrokeState()
        val hasFs = fs != null && !fs.isEmpty
        Log.i(
            TAG,
            "loadForVideo(${videoFile.name}): funscript=${if (hasFs) "${fs?.actions?.size} actions" else "none"}, hsp=${if (hs != null) "${hs.tags.size} tags" else "none"}"
        )
        return hasFs
    }

    /** Clear any previously loaded script. */
    fun clearScript() {
        funscript = null
        hsp = null
        currentVideoName = ""
        resetStrokeState()
    }

    /** Start the polling worker. Idempotent. */
    fun play() {
        synchronized(controlLock) {
            if (isRunning) return
            if (mode != Mode.Scripted && mode != Mode.Mixed) return
            if (funscript == null || funscript?.isEmpty == true) return
            isRunning = true
            val t = Thread({ runLoop() }, "HapticScriptPlayer")
            t.isDaemon = true
            workerThread = t
            t.start()
        }
    }

    /** Stop the worker and zero the motor. */
    fun pause() {
        synchronized(controlLock) {
            isRunning = false
            controlLock.notifyAll()
        }
        halt()
    }

    /** Called when the host player seeks — resets stroke-speed history. */
    fun onSeek() {
        resetStrokeState()
    }

    /** Full stop: halt worker, zero motor, wait for thread. */
    fun release() {
        synchronized(controlLock) {
            isRunning = false
            controlLock.notifyAll()
        }
        halt()
        val t = workerThread
        workerThread = null
        if (t != null && t.isAlive) t.join(500)
    }

    // ── Internals ──

    private fun resetStrokeState() {
        prevFsPos = -1
        prevFsTimeMs = -1L
        smoothedIntensity = 0f
        ditherError = 0f
    }

    /**
     * Send Vibrate:0 to halt the motor. Safe to call in any state — it's a
     * no-op if the player is Off or has already emitted 0. In Mixed mode we
     * only zero motor 1 since motor 2 is owned by the reactive path.
     */
    private fun halt() {
        val bm = bleDeviceManager ?: return
        if (lastIntensity == 0) return
        val m = mode
        if (m == Mode.Off) {
            // Not our motor — leave it alone.
            return
        }
        if (m == Mode.Mixed && bm.isDualMotor) {
            bm.sendCommand(LovenseProtocol.vibrate1(0))
        } else if (bm.isDualMotor) {
            bm.setDualIntensity(0, 0)
        } else {
            bm.setIntensity(0)
        }
        lastIntensity = 0
    }

    private fun runLoop() {
        Log.i(TAG, "worker start (mode=$mode)")
        try {
            while (true) {
                synchronized(controlLock) {
                    if (!isRunning) return
                }
                val m = mode
                if (m != Mode.Scripted && m != Mode.Mixed) {
                    halt()
                    return
                }
                val fs = funscript
                if (fs == null || fs.isEmpty) {
                    // Nothing to do — sleep longer to avoid CPU burn.
                    Thread.sleep(100)
                    continue
                }
                val playing = try { isPlayingProvider() } catch (_: Exception) { false }
                if (!playing) {
                    // Host is paused — zero motor, then idle until we resume.
                    halt()
                    Thread.sleep(100)
                    continue
                }

                val pos = try { positionProvider() } catch (_: Exception) { -1L }
                if (pos < 0) { Thread.sleep(POLL_INTERVAL_MS); continue }

                val prevPos = lastPositionMs
                lastPositionMs = pos
                // Detect large jumps (manual seek) and reset stroke history.
                if (prevPos > 0 && abs(pos - prevPos) > SEEK_JUMP_MS) {
                    resetStrokeState()
                }

                val fsPos = fs.positionAt(pos)
                emitIntensityFromFunscript(fsPos, pos, m)
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (ie: InterruptedException) {
            // Normal shutdown.
        } catch (t: Throwable) {
            Log.e(TAG, "worker crashed", t)
        } finally {
            Log.i(TAG, "worker exit")
        }
    }

    /**
     * Convert a funscript position snapshot into a Lovense 0-20 intensity by
     * measuring stroke speed (position-change per ms). Writes to BLE, deduped.
     *
     * Mixed mode sends funscript intensity to motor 1; motor 2 is left
     * untouched (so the upstream ChloeVibesEngine path writes it via
     * setDualIntensity elsewhere). For simplicity of the BLE-gate invariant we
     * issue a `Vibrate1:X;` style command through setDualIntensity and reuse
     * the last known motor 2 level of 0 — the host loop drives motor 2 when
     * the audio-reactive path is active.
     */
    private fun emitIntensityFromFunscript(fsPos: Int, posMs: Long, m: Mode) {
        val bm = bleDeviceManager ?: return
        if (bm.connectionState != ConnectionState.Ready) return

        val prevP = prevFsPos
        val prevT = prevFsTimeMs
        prevFsPos = fsPos
        prevFsTimeMs = posMs

        // On the very first sample we have no velocity — emit low intensity.
        val speed = if (prevP >= 0 && prevT > 0) {
            val dt = (posMs - prevT).coerceAtLeast(1L)
            val dp = abs(fsPos - prevP).toFloat()
            dp / dt.toFloat()  // positions per ms
        } else 0f

        // Normalize speed: typical fast stroke ~0.3 pos/ms → full intensity.
        val norm = (speed / 0.3f).coerceIn(0f, 1f)
        // Smooth with an asymmetric low-pass: fast attack, slower release.
        smoothedIntensity = if (norm > smoothedIntensity) {
            smoothedIntensity + (norm - smoothedIntensity) * 0.55f
        } else {
            smoothedIntensity + (norm - smoothedIntensity) * 0.18f
        }

        // Quantize to 0-20 with a small dither to preserve low-speed motion.
        val raw = smoothedIntensity * 20f + ditherError
        val intensity = raw.toInt().coerceIn(0, 20)
        ditherError = raw - intensity

        if (intensity == lastIntensity) return
        lastIntensity = intensity

        when {
            m == Mode.Mixed && bm.isDualMotor ->
                // Motor 2 is managed by the reactive path; we only own motor 1.
                // Sending both each update would fight the audio-reactor writer.
                // Instead send single-motor vibrate1 via the dual API with 0 on
                // motor 2 when the reactive path is inactive; otherwise leave
                // motor 2 alone by issuing a motor-1-only command.
                bm.sendCommand(LovenseProtocol.vibrate1(intensity))
            bm.isDualMotor -> bm.setDualIntensity(intensity, intensity)
            else -> bm.setIntensity(intensity)
        }
    }

    /** True when a funscript is currently loaded. */
    fun hasFunscript(): Boolean = funscript?.isEmpty == false
}
