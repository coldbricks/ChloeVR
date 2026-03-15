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

    // ── Dynamics (applied to OUTPUT, not to spectrum) ──
    @Volatile var attack = 0.7f       // how fast output RISES toward raw fill (1=instant)
    @Volatile var release = 0.12f     // how fast output FALLS when raw drops (0.01=slow pump)
    @Volatile var dynRange = 2.0f     // >1 = expand range, <1 = compress
    @Volatile var outputFloor = 0.0f
    @Volatile var outputCeiling = 1.0f

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
        boxFillPct = 0f; smoothedOutput = 0f
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
            bass = 0f; mid = 0f; high = 0f; boxFillPct = 0f; smoothedOutput = 0f
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

        // ── Step 2: Attack/Release envelope on the OUTPUT ──
        // This shapes how fast the output follows the raw fill.
        // It does NOT cause accumulation — smoothedOutput always converges toward rawFill.
        if (rawFill > smoothedOutput) {
            // Rising: attack controls how fast we chase upward
            smoothedOutput += (rawFill - smoothedOutput) * attack
        } else {
            // Falling: release controls how fast we drop
            smoothedOutput += (rawFill - smoothedOutput) * release
        }

        // ── Step 3: Dynamic range scaling ──
        // pow(value, 1/dynRange) expands the range when dynRange > 1
        // (makes quiet parts relatively louder, expanding the usable range)
        val scaled = if (smoothedOutput > 0f && dynRange > 0f) {
            Math.pow(smoothedOutput.toDouble(), 1.0 / dynRange.toDouble()).toFloat()
        } else {
            0f
        }

        // ── Step 4: Clamp to floor/ceiling ──
        boxFillPct = scaled.coerceIn(outputFloor, outputCeiling)
    }

    val isActive: Boolean get() = started && enabled

    /** Status string for the main menu HUD */
    fun statusString(): String {
        if (!enabled) return "OFF"
        return "%.0f%% A:%.0f R:%.0f S:%.1f".format(
            boxFillPct * 100, attack * 100, release * 100, dynRange
        )
    }
}
