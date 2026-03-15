package com.ashairfoil.prism

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * AudioReactor — BeatReactor-style audio analysis for driving lighting parameters.
 *
 * Inspired by Boris FX BCC BeatReactor:
 *   - Captures system audio output via Android Visualizer API
 *   - User-tunable frequency band ranges (not just fixed bass/mid/high)
 *   - Multiple output modes: Magnitude, Gate (on/off trigger), Ramp
 *   - Per-band attack/decay envelope with floor/ceiling clamp
 *   - Configurable sensitivity and frequency cutoffs
 *   - Beat detection with threshold (gate mode)
 *
 * Usage:
 *   val reactor = AudioReactor()
 *   reactor.start()
 *   // Each frame on render thread:
 *   reactor.update()
 *   val bass = reactor.bass   // 0..1 smoothed
 *   reactor.stop()
 */
class AudioReactor {

    companion object {
        private const val TAG = "AudioReactor"
        private const val CAPTURE_SIZE = 1024 // more FFT bins = better frequency resolution
    }

    // ── Output values (read from render thread) ──
    @Volatile var bass = 0f; private set
    @Volatile var mid = 0f; private set
    @Volatile var high = 0f; private set
    @Volatile var overall = 0f; private set
    @Volatile var boxFillPct = 0f; private set  // THE output: % of bounding box filled

    // ── Bounding box (user adjustable on spectrum) ──
    // Normalized 0..1 coordinates on the spectrum display
    @Volatile var boxLeft = 0.0f     // frequency range left (0=20Hz)
    @Volatile var boxRight = 0.4f    // frequency range right (1=20kHz)
    @Volatile var boxBottom = 0.1f   // amplitude floor (0=silence)
    @Volatile var boxTop = 1.0f      // amplitude ceiling (1=max)

    // ── Output controls ──
    @Volatile var outputScale = 1.5f   // multiply the box fill % (>1 = expand, <1 = compress)
    @Volatile var outputFloor = 0.0f   // minimum output value
    @Volatile var outputCeiling = 1.0f // maximum output value

    // ── Global config ──
    @Volatile var enabled = false
    @Volatile var sensitivity = 1.2f

    // ── Per-band configuration (BeatReactor-style) ──
    data class BandConfig(
        var freqLow: Float,        // low cutoff Hz
        var freqHigh: Float,       // high cutoff Hz
        var attack: Float = 0.4f,  // 0..1, how fast values rise
        var decay: Float = 0.08f,  // 0..1, how fast values fall
        var floor: Float = 0f,     // output minimum (clamp)
        var ceiling: Float = 1f,   // output maximum (clamp)
        var mode: OutputMode = OutputMode.MAGNITUDE,
        var falloff: Falloff = Falloff.QUADRATIC_SOFT,
        var falloffTime: Float = 0.3f,  // seconds — duration of the falloff tail
        var gateThreshold: Float = 0.3f,
        var weight: Float = 1f,
        var scaleOutput: Float = 1f  // final multiplier (like BeatReactor's Scale Output %)
    )

    enum class OutputMode {
        MAGNITUDE,  // continuous 0..1 envelope following energy
        GATE,       // binary 0 or 1 when energy crosses threshold
        RAMP_UP,    // on beat: ramp from 0 to 1, then hold
        RAMP_DOWN   // on beat: jump to 1, then ramp down to 0
    }

    /** BeatReactor-style falloff shapes for the decay tail after a beat */
    enum class Falloff {
        IMMEDIATE,       // no falloff — drops as soon as energy drops
        LINEAR,          // straight line decay
        QUADRATIC_HARD,  // fast start, ease toward end
        QUADRATIC_SOFT,  // ease at peak, accelerate toward end
        SUSTAIN          // hold peak value for falloffTime, then drop
    }

    val bassConfig = BandConfig(freqLow = 20f, freqHigh = 150f, attack = 0.6f, decay = 0.25f)
    val midConfig = BandConfig(freqLow = 150f, freqHigh = 2000f, attack = 0.5f, decay = 0.3f)
    val highConfig = BandConfig(freqLow = 2000f, freqHigh = 8000f, attack = 0.45f, decay = 0.35f)

    // ── Internal state ──
    private var visualizer: Visualizer? = null
    @Volatile private var rawBass = 0f
    @Volatile private var rawMid = 0f
    @Volatile private var rawHigh = 0f
    private var started = false

    // Gate state (edge detection for beat triggers)
    private var bassGateOpen = false
    private var midGateOpen = false
    private var highGateOpen = false

    // Ramp state
    private var bassRamp = 0f
    private var midRamp = 0f
    private var highRamp = 0f

    // Falloff state — tracks time since last beat peak for shaped decay
    private var bassPeakVal = 0f; private var bassFalloffT = 0f
    private var midPeakVal = 0f; private var midFalloffT = 0f
    private var highPeakVal = 0f; private var highFalloffT = 0f

    // Delay buffer (BeatReactor Delay feature — offset reaction from audio)
    @Volatile var delaySeconds = 0.05f  // small positive = reaction slightly after beat (feels causal)
    private val delayBufferSize = 30  // ~0.4s at 72fps
    private val bassDelayBuf = FloatArray(delayBufferSize)
    private val midDelayBuf = FloatArray(delayBufferSize)
    private val highDelayBuf = FloatArray(delayBufferSize)
    private var delayWriteIdx = 0

    // Peak hold for auto-gain (adapts to volume level)
    private var peakEnergy = 0.01f
    @Volatile var autoGain = true

    fun start(): Boolean {
        if (started) return true
        try {
            Log.i(TAG, "Attempting Visualizer(0) for system audio capture...")
            val vis = Visualizer(0) // session 0 = system output mix
            Log.i(TAG, "Visualizer created, setting capture size=$CAPTURE_SIZE")
            vis.captureSize = CAPTURE_SIZE

            val maxRate = Visualizer.getMaxCaptureRate()
            Log.i(TAG, "Max capture rate: $maxRate, setting listener...")

            var fftCallCount = 0
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) {
                        fftCallCount++
                        if (fftCallCount <= 3 || fftCallCount % 100 == 0) {
                            Log.i(TAG, "FFT callback #$fftCallCount: ${fft.size} bytes, rate=$samplingRate")
                        }
                        processFft(fft, samplingRate)
                    }
                }
            }, maxRate, false, true)

            Log.i(TAG, "Enabling Visualizer...")
            vis.enabled = true
            Log.i(TAG, "Visualizer enabled=${vis.enabled}, samplingRate=${vis.samplingRate}")

            visualizer = vis
            started = true
            Log.i(TAG, "AudioReactor started OK")
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
        bass = 0f; mid = 0f; high = 0f; overall = 0f
        peakEnergy = 0.01f
    }

    /**
     * FFT callback from Visualizer thread.
     * FFT format: [real0, imag0, real1, imag1, ...] as signed bytes.
     */
    // Raw per-bin magnitudes for spectrum visualization (written by FFT thread)
    @Volatile var spectrumBins = FloatArray(64)  // reduced to 64 bars for display
    private var spectrumBinsTemp = FloatArray(64)

    private fun processFft(fft: ByteArray, samplingRate: Int) {
        if (!enabled) return

        val binCount = fft.size / 2
        val freqPerBin = (samplingRate / 1000f) / (2f * CAPTURE_SIZE) // Hz per bin

        // Max possible magnitude from signed bytes: sqrt(127^2 + 127^2) ≈ 180
        val maxMag = 180f

        var bassE = 0f; var bassBins = 0
        var midE = 0f; var midBins = 0
        var highE = 0f; var highBins = 0

        // Build spectrum bars (64 display bins, log-spaced)
        val displayBins = spectrumBinsTemp
        for (j in displayBins.indices) displayBins[j] = 0f
        var displayBinCounts = IntArray(64)

        for (i in 1 until binCount) {
            val freq = i * freqPerBin
            val real = fft[2 * i].toFloat()
            val imag = fft[2 * i + 1].toFloat()
            val mag = kotlin.math.sqrt(real * real + imag * imag)

            // Percentage of max — this is BeatReactor "box percentage" style
            val pct = (mag / maxMag) * sensitivity

            // Band totals
            if (freq >= bassConfig.freqLow && freq < bassConfig.freqHigh) { bassE += pct; bassBins++ }
            if (freq >= midConfig.freqLow && freq < midConfig.freqHigh) { midE += pct; midBins++ }
            if (freq >= highConfig.freqLow && freq < highConfig.freqHigh) { highE += pct; highBins++ }

            // Map to display bin (log scale: bin 0=~20Hz, bin 63=~20kHz)
            if (freq > 10f) {
                val logFreq = kotlin.math.ln(freq / 10f) / kotlin.math.ln(2000f) // 0..1 log scale
                val dBin = (logFreq * 63f).toInt().coerceIn(0, 63)
                displayBins[dBin] += pct
                displayBinCounts[dBin]++
            }
        }

        // Average each display bin
        for (j in displayBins.indices) {
            if (displayBinCounts[j] > 0) displayBins[j] /= displayBinCounts[j]
        }
        spectrumBins = displayBins.copyOf()

        // Band outputs: simple average percentage (0..1), no accumulation
        rawBass = if (bassBins > 0) (bassE / bassBins).coerceIn(0f, 1f) else 0f
        rawMid = if (midBins > 0) (midE / midBins).coerceIn(0f, 1f) else 0f
        rawHigh = if (highBins > 0) (highE / highBins).coerceIn(0f, 1f) else 0f
    }

    /**
     * Call once per frame. Computes bounding box fill percentage.
     * This IS the output — percentage of the box area filled by spectrum bars.
     */
    fun update() {
        if (!enabled) {
            bass = 0f; mid = 0f; high = 0f; overall = 0f; boxFillPct = 0f
            return
        }

        // Pass-through band values (light smoothing for display only)
        val s = 0.3f
        bass = bass * s + rawBass * (1f - s)
        mid = mid * s + rawMid * (1f - s)
        high = high * s + rawHigh * (1f - s)

        // ── Box fill percentage (the real output) ──
        // For each spectrum bin inside the box's X range,
        // compute how much of the bar fills the box's Y range
        val bins = spectrumBins
        val boxH = (boxTop - boxBottom).coerceAtLeast(0.01f)
        var fillSum = 0f
        var binsInBox = 0

        for (i in bins.indices) {
            val binNormX = i.toFloat() / bins.size  // 0..1 position
            if (binNormX < boxLeft || binNormX > boxRight) continue
            binsInBox++

            val barHeight = bins[i].coerceIn(0f, 1f)
            // How much of this bar is within the box's Y range?
            val fillInBox = ((barHeight - boxBottom) / boxH).coerceIn(0f, 1f)
            fillSum += fillInBox
        }

        val rawFill = if (binsInBox > 0) fillSum / binsInBox else 0f

        // Apply scale (expand/compress) and clamp to floor/ceiling
        boxFillPct = (rawFill * outputScale).coerceIn(outputFloor, outputCeiling)

        // Overall = boxFillPct (this drives the lighting)
        overall = boxFillPct
    }

    private inline fun processBand(
        current: Float, raw: Float, config: BandConfig,
        gateOpen: kotlin.reflect.KMutableProperty0<Boolean>,
        ramp: kotlin.reflect.KMutableProperty0<Float>,
        peakVal: kotlin.reflect.KMutableProperty0<Float>,
        falloffT: kotlin.reflect.KMutableProperty0<Float>,
        dt: Float
    ): Float {
        val clamped = raw.coerceAtMost(1f)

        // Detect new peak (for falloff shaping)
        if (clamped > current + 0.05f) {
            peakVal.set(clamped)
            falloffT.set(0f)
        } else {
            falloffT.set(falloffT.get() + dt)
        }

        val output = when (config.mode) {
            OutputMode.MAGNITUDE -> {
                // Apply shaped falloff instead of simple exponential decay
                val rising = clamped > current
                if (rising) {
                    current + (clamped - current) * config.attack
                } else {
                    applyFalloff(current, peakVal.get(), config.falloff, falloffT.get(), config.falloffTime, config.decay)
                }
            }

            OutputMode.GATE -> {
                val wasOpen = gateOpen.get()
                val nowOpen = clamped > config.gateThreshold
                gateOpen.set(nowOpen)
                if (nowOpen) 1f else {
                    applyFalloff(current, 1f, config.falloff, falloffT.get(), config.falloffTime, config.decay)
                }
            }

            OutputMode.RAMP_UP -> {
                val wasOpen = gateOpen.get()
                val nowOpen = clamped > config.gateThreshold
                gateOpen.set(nowOpen)
                if (nowOpen && !wasOpen) ramp.set(0f)
                if (nowOpen || ramp.get() < 1f) {
                    val r = (ramp.get() + config.attack * 0.5f).coerceAtMost(1f)
                    ramp.set(r); r
                } else {
                    applyFalloff(ramp.get(), 1f, config.falloff, falloffT.get(), config.falloffTime, config.decay)
                        .also { ramp.set(it) }
                }
            }

            OutputMode.RAMP_DOWN -> {
                val wasOpen = gateOpen.get()
                val nowOpen = clamped > config.gateThreshold
                gateOpen.set(nowOpen)
                if (nowOpen && !wasOpen) { ramp.set(1f); peakVal.set(1f); falloffT.set(0f); 1f }
                else {
                    applyFalloff(ramp.get(), 1f, config.falloff, falloffT.get(), config.falloffTime, config.decay)
                        .also { ramp.set(it) }
                }
            }
        }

        // Apply scale output and floor/ceiling
        return (output * config.scaleOutput).coerceIn(config.floor, config.ceiling)
    }

    /** BeatReactor-style shaped falloff from peak value */
    private fun applyFalloff(current: Float, peak: Float, falloff: Falloff,
                             elapsed: Float, duration: Float, decayRate: Float): Float {
        if (duration <= 0f || peak <= 0f) return (current - current * decayRate).coerceAtLeast(0f)
        val t = (elapsed / duration).coerceIn(0f, 1f) // 0=just peaked, 1=falloff complete

        return when (falloff) {
            Falloff.IMMEDIATE -> (current - current * decayRate).coerceAtLeast(0f)

            Falloff.LINEAR -> peak * (1f - t)

            Falloff.QUADRATIC_HARD -> {
                // Fast start, ease toward end: 1 - t^2
                val ft = 1f - t
                peak * ft * ft
            }

            Falloff.QUADRATIC_SOFT -> {
                // Ease at peak, accelerate toward end: (1-t)^0.5 shaped
                peak * (1f - t * t)
            }

            Falloff.SUSTAIN -> {
                // Hold peak for duration, then instant drop
                if (t < 1f) peak else 0f
            }
        }.coerceAtLeast(0f)
    }

    private fun envelope(current: Float, target: Float, atk: Float, dec: Float): Float {
        return if (target > current) {
            current + (target - current) * atk
        } else {
            current + (target - current) * dec
        }
    }

    val isActive: Boolean get() = started && enabled

    /** Quick status string for debug HUD */
    fun statusString(): String {
        if (!enabled) return "OFF"
        return "B:%.0f M:%.0f H:%.0f pk:%.2f %s".format(
            bass * 100, mid * 100, high * 100, peakEnergy,
            if (autoGain) "AG" else ""
        )
    }
}
