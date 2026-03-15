package com.ashairfoil.prism

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * AudioReactor — BCC BeatReactor clone for driving lighting parameters.
 *
 * How it works:
 *   1. FFT from Android Visualizer -> 64 log-frequency display bins (spectrumBins)
 *   2. A bounding box sits on the spectrum display
 *   3. Output = percentage of the box area filled by the spectrum bars
 *   4. That raw fill goes through: attack/release envelope -> dynamic range -> floor/ceiling
 *   5. Final boxFillPct (0..1) drives whatever parameter it's mapped to
 *
 * The spectrum display and the output are SEPARATE concerns:
 *   - spectrumBins are for display (sensitivity is a display gain)
 *   - boxFillPct is the shaped output (attack/release/dynRange shape it)
 */
class AudioReactor {

    companion object {
        private const val TAG = "AudioReactor"
        private const val CAPTURE_SIZE = 1024
        private const val DISPLAY_BINS = 64
    }

    // ── THE output: 0..1, drives lighting ──
    @Volatile var boxFillPct = 0f; private set

    // ── Spectrum display bins (for UI visualization) ──
    @Volatile var spectrumBins = FloatArray(DISPLAY_BINS)

    // ── Per-band meters (for B/M/H display) ──
    @Volatile var bass = 0f; private set
    @Volatile var mid = 0f; private set
    @Volatile var high = 0f; private set

    // ── Bounding box (0..1 normalized on spectrum display) ──
    @Volatile var boxLeft = 0.0f
    @Volatile var boxRight = 0.35f
    @Volatile var boxBottom = 0.05f
    @Volatile var boxTop = 0.85f

    // ── Roll-off mode ──
    enum class Rolloff { INSTANT, HARD_KNEE, SOFT_KNEE }
    @Volatile var rolloff = Rolloff.SOFT_KNEE

    // ── Dynamics ──
    @Volatile var attackMs = 20f      // rise time in milliseconds
    @Volatile var releaseMs = 150f    // fall time in milliseconds
    @Volatile var dynRange = 2.0f
    @Volatile var outputFloor = 0.0f
    @Volatile var outputCeiling = 1.0f
    @Volatile var threshold = 0.15f
    @Volatile var trim = 1.0f
    @Volatile var beatHue = 330f
    @Volatile var smootherAmount = 0.3f
    @Volatile var specZoom = 1.0f     // horizontal zoom (1=full, 8=8x)
    @Volatile var specViewCenter = 0.5f  // persisted view center for smooth scrolling
    private var prevFillPct = 0f

    // Hard knee state
    private var hardKneeTimer = 0f
    private var hardKneeTriggered = false

    // BPM detection
    @Volatile var detectedBpm = 0f; private set
    private val bpmBufferSize = 216   // ~3 seconds at 72fps
    private val bpmBuffer = FloatArray(bpmBufferSize)
    private var bpmWriteIdx = 0
    private var bpmFrameCount = 0
    private var lastBeatFrame = -1
    private val beatIntervals = mutableListOf<Int>()  // frames between beats

    // ── Input ──
    @Volatile var sensitivity = 3.0f  // display gain — FFT bytes are tiny, needs to be high
    @Volatile var enabled = false

    // ── Internal ──
    private var visualizer: Visualizer? = null
    private var started = false
    private var smoothedOutput = 0f  // the envelope-followed output (before dynRange)

    // Temp buffer to avoid allocation in FFT callback
    private val tempBins = FloatArray(DISPLAY_BINS)
    private val tempBinMax = FloatArray(DISPLAY_BINS)

    // Per-band raw values (written by FFT thread, read by render thread)
    @Volatile private var rawBass = 0f
    @Volatile private var rawMid = 0f
    @Volatile private var rawHigh = 0f

    fun start(): Boolean {
        if (started) return true
        try {
            Log.i(TAG, "Creating Visualizer(0) for system audio...")
            val vis = Visualizer(0)
            vis.captureSize = CAPTURE_SIZE

            val maxRate = Visualizer.getMaxCaptureRate()
            Log.i(TAG, "Visualizer capture size=$CAPTURE_SIZE, maxRate=$maxRate")

            var callCount = 0
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) {
                        callCount++
                        if (callCount <= 3 || callCount % 200 == 0) {
                            Log.i(TAG, "FFT #$callCount: ${fft.size} bytes, rate=$samplingRate")
                        }
                        processFft(fft, samplingRate)
                    }
                }
            }, maxRate, false, true)

            vis.enabled = true
            visualizer = vis
            started = true
            Log.i(TAG, "AudioReactor started, enabled=${vis.enabled}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Visualizer: ${e.message}", e)
            return false
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping: ${e.message}")
        }
        visualizer = null
        started = false
        bass = 0f; mid = 0f; high = 0f
        boxFillPct = 0f; smoothedOutput = 0f; prevFillPct = 0f
    }

    /**
     * FFT callback — runs on Visualizer thread.
     * FFT format: [real0, imag0, real1, imag1, ...] as signed bytes.
     *
     * Builds 64 log-frequency display bins using MAX of contributing FFT bins
     * (not average — average kills peaks and makes bars barely move).
     */
    private fun processFft(fft: ByteArray, samplingRate: Int) {
        if (!enabled) return

        val binCount = fft.size / 2
        // samplingRate from Visualizer is in milliHz, convert to Hz
        val nyquist = (samplingRate / 1000f) / 2f
        val freqPerBin = nyquist / binCount

        // Max theoretical magnitude: sqrt(127^2 + 127^2) ~ 180
        // Use a moderate normalization so sensitivity can boost it
        val normValue = 128f

        // Clear temp bins
        for (j in 0 until DISPLAY_BINS) { tempBins[j] = 0f; tempBinMax[j] = 0f }

        var bassE = 0f; var bassCnt = 0
        var midE = 0f; var midCnt = 0
        var highE = 0f; var highCnt = 0

        for (i in 1 until binCount) {
            val real = fft[2 * i].toFloat()
            val imag = fft[2 * i + 1].toFloat()
            val mag = kotlin.math.sqrt(real * real + imag * imag)

            // Normalize and apply sensitivity as display gain
            val norm = (mag / normValue) * sensitivity

            val freq = i * freqPerBin

            // Per-band accumulation for B/M/H meters
            if (freq in 20f..150f) { bassE += norm; bassCnt++ }
            if (freq in 150f..2000f) { midE += norm; midCnt++ }
            if (freq in 2000f..8000f) { highE += norm; highCnt++ }

            // Map FFT bin to one of 64 display bins (log frequency scale)
            // log scale: bin 0 ~ 20Hz, bin 63 ~ 20kHz
            if (freq > 10f) {
                val logPos = kotlin.math.ln(freq / 20f) / kotlin.math.ln(1000f) // 0..1 across 20Hz-20kHz
                val dBin = (logPos * (DISPLAY_BINS - 1)).toInt().coerceIn(0, DISPLAY_BINS - 1)
                // Use MAX of contributing bins (preserves peaks, makes bars actually move)
                if (norm > tempBinMax[dBin]) tempBinMax[dBin] = norm
            }
        }

        // Clamp display bins to 0..1 for rendering
        val output = FloatArray(DISPLAY_BINS)
        for (j in 0 until DISPLAY_BINS) {
            output[j] = tempBinMax[j].coerceIn(0f, 1f)
        }
        spectrumBins = output

        // Per-band values (average, clamped)
        rawBass = if (bassCnt > 0) (bassE / bassCnt).coerceIn(0f, 1f) else 0f
        rawMid = if (midCnt > 0) (midE / midCnt).coerceIn(0f, 1f) else 0f
        rawHigh = if (highCnt > 0) (highE / highCnt).coerceIn(0f, 1f) else 0f
    }

    /**
     * Call once per frame on the render thread.
     *
     * 1. Compute raw box fill percentage from spectrum bins
     * 2. Apply attack/release envelope (shapes the output response)
     * 3. Apply dynamic range scaling
     * 4. Clamp to floor/ceiling
     * 5. Store as boxFillPct
     *
     * The output NEVER accumulates. It tracks the audio and drops when audio drops.
     * Attack/release only shape HOW FAST the output follows, not how high it goes.
     */
    fun update() {
        if (!enabled) {
            bass = 0f; mid = 0f; high = 0f; boxFillPct = 0f; smoothedOutput = 0f; prevFillPct = 0f
            return
        }

        // Light smoothing on band meters (display only, not used for output)
        val s = 0.3f
        bass = bass * s + rawBass * (1f - s)
        mid = mid * s + rawMid * (1f - s)
        high = high * s + rawHigh * (1f - s)

        // ── Step 1: Raw box fill percentage ──
        // For each display bin inside the box's X range,
        // measure how much of the bar fills the box's Y range.
        val bins = spectrumBins
        val boxH = (boxTop - boxBottom).coerceAtLeast(0.001f)
        var fillSum = 0f
        var binsInBox = 0

        for (i in bins.indices) {
            val binNormX = i.toFloat() / bins.size
            if (binNormX < boxLeft || binNormX > boxRight) continue
            binsInBox++

            val barHeight = bins[i].coerceIn(0f, 1f)
            // How much of this bar is within the box's Y range?
            // If bar is below boxBottom, fill = 0
            // If bar extends above boxTop, fill = 1 (fully fills the box height)
            val fillInBox = ((barHeight - boxBottom) / boxH).coerceIn(0f, 1f)
            fillSum += fillInBox
        }

        val rawFill = if (binsInBox > 0) fillSum / binsInBox else 0f

        // ── Step 2: Mode-specific processing ──
        // Convert ms to per-frame alpha: alpha = 1 - exp(-dt/tau)
        val dt = 1f / 72f  // frame time ~14ms
        val atkAlpha = (1f - kotlin.math.exp(-dt / (attackMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.01f, 1f)
        val relAlpha = (1f - kotlin.math.exp(-dt / (releaseMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.005f, 1f)

        // Attack: snap up fast when signal exceeds current output
        if (rawFill > smoothedOutput) {
            smoothedOutput += (rawFill - smoothedOutput) * atkAlpha
            hardKneeTriggered = true
            hardKneeTimer = 0f
        } else {
            // Roll-off: how the output decays after the beat
            when (rolloff) {
                Rolloff.INSTANT -> {
                    // Binary: above threshold = on, below = off, no decay
                    smoothedOutput = if (rawFill > threshold) rawFill else 0f
                }
                Rolloff.HARD_KNEE -> {
                    // Linear decay from peak over releaseMs
                    // Straight line from current to 0 over the release time
                    if (hardKneeTriggered) {
                        hardKneeTimer += dt
                        val releaseSec = releaseMs / 1000f
                        val progress = (hardKneeTimer / releaseSec).coerceIn(0f, 1f)
                        smoothedOutput = smoothedOutput * (1f - progress)
                        if (progress >= 1f) { smoothedOutput = 0f; hardKneeTriggered = false }
                    }
                }
                Rolloff.SOFT_KNEE -> {
                    // Exponential decay (smooth, musical)
                    smoothedOutput += (rawFill - smoothedOutput) * relAlpha
                }
            }
        }

        // EXPAND: higher = more dramatic swing
        // Simple gain with clamp — peaks hit ceiling, valleys stay low
        // dynRange=1: identity, dynRange=2: 2x gain (more swing), dynRange=0.5: half (less swing)
        val scaled = (smoothedOutput * dynRange * trim).coerceAtMost(1f)

        boxFillPct = scaled.coerceIn(outputFloor, outputCeiling)

        // Smoother
        val smoothAlpha = 1f - smootherAmount * 0.9f
        boxFillPct = prevFillPct + (boxFillPct - prevFillPct) * smoothAlpha
        prevFillPct = boxFillPct

        // BPM detection: track bass peaks and measure intervals
        bpmFrameCount++
        bpmBuffer[bpmWriteIdx] = bass
        bpmWriteIdx = (bpmWriteIdx + 1) % bpmBufferSize

        // Detect beat: bass crosses above threshold and is a local peak
        val prev2 = bpmBuffer[(bpmWriteIdx - 3 + bpmBufferSize) % bpmBufferSize]
        val prev1 = bpmBuffer[(bpmWriteIdx - 2 + bpmBufferSize) % bpmBufferSize]
        val curr = bass
        if (prev1 > threshold && prev1 > prev2 && prev1 >= curr && bpmFrameCount - lastBeatFrame > 10) {
            // Found a beat peak
            if (lastBeatFrame > 0) {
                val interval = bpmFrameCount - lastBeatFrame
                beatIntervals.add(interval)
                if (beatIntervals.size > 16) beatIntervals.removeAt(0)

                // Average interval → BPM
                if (beatIntervals.size >= 4) {
                    val avgFrames = beatIntervals.average()
                    detectedBpm = (72f * 60f / avgFrames.toFloat())  // 72fps
                    // Sanity: typical music is 60-200 BPM
                    if (detectedBpm < 40f || detectedBpm > 220f) detectedBpm = 0f
                }
            }
            lastBeatFrame = bpmFrameCount
        }
    }

    val isActive: Boolean get() = started && enabled

    /** Convert beatHue to RGB floats (0..1 each) for lighting */
    fun getBeatColor(): FloatArray {
        val h = beatHue / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        val (r, g, b) = when {
            h < 1f -> Triple(1f, x, 0f)
            h < 2f -> Triple(x, 1f, 0f)
            h < 3f -> Triple(0f, 1f, x)
            h < 4f -> Triple(0f, x, 1f)
            h < 5f -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        return floatArrayOf(r, g, b)
    }

    /** Status string for the main menu HUD */
    fun statusString(): String {
        if (!enabled) return "OFF"
        val modeStr = when (rolloff) { Rolloff.INSTANT -> "INS"; Rolloff.HARD_KNEE -> "HRD"; Rolloff.SOFT_KNEE -> "SFT" }
        val bpmStr = if (detectedBpm > 0f) " %.0fBPM".format(detectedBpm) else ""
        return "$modeStr %.0f%%$bpmStr".format(boxFillPct * 100)
    }
}
