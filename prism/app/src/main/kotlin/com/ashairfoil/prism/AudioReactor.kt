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

    // ── Bounding box A (0..1 normalized on spectrum display) ──
    @Volatile var boxLeft = 0.0f
    @Volatile var boxRight = 0.35f
    @Volatile var boxBottom = 0.05f
    @Volatile var boxTop = 0.85f

    // ── Bounding box B (second box for dual-motor split) ──
    @Volatile var box2Left = 0.4f
    @Volatile var box2Right = 0.75f
    @Volatile var box2Bottom = 0.05f
    @Volatile var box2Top = 0.85f
    @Volatile var box2FillPct = 0f; private set

    // ── Roll-off mode ──
    enum class Rolloff { INSTANT, HARD_KNEE, SOFT_KNEE, THROB }
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
    enum class ColorMode { FIXED, CYCLE, FLASH }
    enum class BlendMode { NORMAL, ADD, MULTIPLY, SCREEN }
    enum class WashScope { MODELS, ROOM, BOTH }
    @Volatile var blendMode = BlendMode.ADD
    @Volatile var washScope = WashScope.BOTH
    @Volatile var colorMode = ColorMode.CYCLE
    @Volatile var cycleSpeed = 60f     // degrees per second for CYCLE mode
    private var cycleHue = 0f
    private var flashHue = 330f
    @Volatile var smootherAmount = 0.3f
    @Volatile var specZoom = 1.0f     // horizontal zoom (1=full, 8=8x)
    @Volatile var specVZoom = 1.0f    // vertical zoom (amplifies bar heights for display)
    @Volatile var specViewCenter = 0.5f
    private var prevFillPct = 0f

    // Hard knee / throb state
    private var hardKneeTimer = 0f
    private var hardKneeTriggered = false
    private var throbLocked = false  // true = in decay cycle, cannot retrigger

    // Anti-dropout
    @Volatile private var fftFrameStamp = 0
    private var updateFrameCount = 0

    // BOOM detector: tracks per-bin variance to find biggest swings
    private val binMean = FloatArray(DISPLAY_BINS)
    private val binVariance = FloatArray(DISPLAY_BINS)
    @Volatile var boomLeft = 0f; private set   // detected boom range
    @Volatile var boomRight = 0.35f; private set
    @Volatile var boomReady = false; private set

    // Graphic limiter
    @Volatile var graphicLimiter = true  // soft-clip display so pinned bars still show detail

    // ── Input ──
    @Volatile var sensitivity = 3.0f  // display gain — FFT bytes are tiny, needs to be high
    @Volatile var enabled = false

    // ── Internal ──
    private var visualizer: Visualizer? = null
    private var started = false
    private var smoothedOutput = 0f  // the envelope-followed output (before dynRange)
    private var smoothedOutput2 = 0f // envelope for box B
    private var prevFillPct2 = 0f

    // Temp buffer to avoid allocation in FFT callback
    private val tempBins = FloatArray(DISPLAY_BINS)
    private val tempBinMax = FloatArray(DISPLAY_BINS)
    // Double-buffered spectrum output to avoid torn reads across threads
    private val specBufA = FloatArray(DISPLAY_BINS)
    private val specBufB = FloatArray(DISPLAY_BINS)
    @Volatile private var specWriteToA = true
    // Pre-allocated beat color result
    private val beatColorBuf = FloatArray(3)

    // Per-band raw values (written by FFT thread, read by render thread)
    @Volatile private var rawBass = 0f
    @Volatile private var rawMid = 0f
    @Volatile private var rawHigh = 0f

    private var currentSessionId = 0

    fun start(audioSessionId: Int = 0): Boolean {
        if (started) return true
        currentSessionId = audioSessionId
        try {
            Log.i(TAG, "Creating Visualizer($audioSessionId) for ${if (audioSessionId == 0) "system audio" else "session"}...")
            val vis = Visualizer(audioSessionId)
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
        box2FillPct = 0f; smoothedOutput2 = 0f; prevFillPct2 = 0f
    }

    /** Restart with a different audio session (e.g., switch to app audio player) */
    fun restart(newSessionId: Int) {
        if (newSessionId == currentSessionId && started) return
        stop()
        start(newSessionId)
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

        // Store raw bin values UNCLAMPED — fill calc needs values above 1.0
        // to reach 100% fill with tight boxes. Display will clamp for rendering.
        // Double-buffer: write to inactive buffer, then swap pointer atomically
        val buf = if (specWriteToA) specBufA else specBufB
        for (j in 0 until DISPLAY_BINS) {
            buf[j] = tempBinMax[j].coerceAtLeast(0f)
        }
        specWriteToA = !specWriteToA
        spectrumBins = buf

        // Per-band values (average, clamped)
        rawBass = if (bassCnt > 0) (bassE / bassCnt).coerceIn(0f, 1f) else 0f
        rawMid = if (midCnt > 0) (midE / midCnt).coerceIn(0f, 1f) else 0f
        rawHigh = if (highCnt > 0) (highE / highCnt).coerceIn(0f, 1f) else 0f
        fftFrameStamp = updateFrameCount  // mark that we got fresh data
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
            box2FillPct = 0f; smoothedOutput2 = 0f; prevFillPct2 = 0f
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

            // RAW bar height — no expand, no zoom. Just the actual FFT level.
            // Display transforms are for SEEING. Fill is the ACTUAL percentage.
            val barHeight = bins[i]
            // What fraction of the box height does this bar fill?
            val fillInBox = ((barHeight - boxBottom) / boxH).coerceIn(0f, 1f)
            fillSum += fillInBox
        }

        val rawFill = if (binsInBox > 0) fillSum / binsInBox else 0f

        // ── Step 2: Mode-specific processing ──
        // Convert ms to per-frame alpha: alpha = 1 - exp(-dt/tau)
        val dt = 1f / 72f  // frame time ~14ms
        val atkAlpha = (1f - kotlin.math.exp(-dt / (attackMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.01f, 1f)
        val relAlpha = (1f - kotlin.math.exp(-dt / (releaseMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.005f, 1f)

        if (rolloff == Rolloff.THROB) {
            // THROB: one-shot burst. Trigger fires, runs FULL decay cycle,
            // cannot retrigger until cycle is complete.
            if (!throbLocked && rawFill > threshold) {
                // Trigger! Start the burst
                smoothedOutput = 1f
                throbLocked = true
                hardKneeTimer = 0f
            }
            if (throbLocked) {
                // In decay cycle — count down, ignore all input
                hardKneeTimer += dt
                val releaseSec = releaseMs / 1000f
                val progress = (hardKneeTimer / releaseSec).coerceIn(0f, 1f)
                smoothedOutput = 1f - progress  // linear ramp down
                if (progress >= 1f) {
                    smoothedOutput = 0f
                    throbLocked = false  // cycle complete, can retrigger
                }
            }
        } else {
            throbLocked = false

            // Output = actual box fill percentage. Trim the box tight to get 100%.
            // Only THROB hard-snaps to 100%. Everything else tracks the real fill.
            when (rolloff) {
                Rolloff.INSTANT -> {
                    // Direct: output = box fill %. Below threshold = 0.
                    smoothedOutput = if (rawFill > threshold) rawFill else 0f
                }
                Rolloff.HARD_KNEE -> {
                    // Attack: track fill upward
                    if (rawFill > smoothedOutput) {
                        smoothedOutput += (rawFill - smoothedOutput) * atkAlpha
                        hardKneeTriggered = true
                        hardKneeTimer = 0f
                    } else if (hardKneeTriggered) {
                        // Linear decay from peak over releaseMs
                        hardKneeTimer += dt
                        val progress = (hardKneeTimer / (releaseMs / 1000f)).coerceIn(0f, 1f)
                        smoothedOutput = smoothedOutput * (1f - progress * 0.3f)  // gradual
                        if (progress >= 1f) { smoothedOutput = 0f; hardKneeTriggered = false }
                    }
                }
                Rolloff.SOFT_KNEE -> {
                    // Envelope follower: tracks fill up (attack) and down (release)
                    if (rawFill > smoothedOutput) {
                        smoothedOutput += (rawFill - smoothedOutput) * atkAlpha
                    } else {
                        smoothedOutput += (rawFill - smoothedOutput) * relAlpha
                    }
                }
                else -> {}
            }
        }

        // Output trim only (EXPAND already applied to bar heights for box fill)
        val scaled = (smoothedOutput * trim).coerceAtMost(1f)

        boxFillPct = scaled.coerceIn(outputFloor, outputCeiling)

        // Smoother
        val smoothAlpha = 1f - smootherAmount * 0.9f
        boxFillPct = prevFillPct + (boxFillPct - prevFillPct) * smoothAlpha
        prevFillPct = boxFillPct

        // Anti-dropout
        updateFrameCount++
        if (updateFrameCount - fftFrameStamp > 8) {
            boxFillPct = prevFillPct
        }

        // ── Box B fill (same envelope logic, independent region) ──
        val box2H = (box2Top - box2Bottom).coerceAtLeast(0.001f)
        var fill2Sum = 0f
        var bins2InBox = 0
        for (i in bins.indices) {
            val binNormX = i.toFloat() / bins.size
            if (binNormX < box2Left || binNormX > box2Right) continue
            bins2InBox++
            val barHeight = bins[i]
            val fillInBox = ((barHeight - box2Bottom) / box2H).coerceIn(0f, 1f)
            fill2Sum += fillInBox
        }
        val rawFill2 = if (bins2InBox > 0) fill2Sum / bins2InBox else 0f

        // Box B uses same rolloff/envelope as box A
        when (rolloff) {
            Rolloff.INSTANT -> smoothedOutput2 = if (rawFill2 > threshold) rawFill2 else 0f
            Rolloff.THROB -> smoothedOutput2 = smoothedOutput // mirror box A throb behavior
            Rolloff.HARD_KNEE -> {
                if (rawFill2 > smoothedOutput2) {
                    smoothedOutput2 += (rawFill2 - smoothedOutput2) * atkAlpha
                } else {
                    smoothedOutput2 += (rawFill2 - smoothedOutput2) * relAlpha
                }
            }
            Rolloff.SOFT_KNEE -> {
                if (rawFill2 > smoothedOutput2) {
                    smoothedOutput2 += (rawFill2 - smoothedOutput2) * atkAlpha
                } else {
                    smoothedOutput2 += (rawFill2 - smoothedOutput2) * relAlpha
                }
            }
        }
        val scaled2 = (smoothedOutput2 * trim).coerceAtMost(1f)
        box2FillPct = scaled2.coerceIn(outputFloor, outputCeiling)
        box2FillPct = prevFillPct2 + (box2FillPct - prevFillPct2) * smoothAlpha
        prevFillPct2 = box2FillPct
        if (updateFrameCount - fftFrameStamp > 8) {
            box2FillPct = prevFillPct2
        }

        // BOOM detector: track variance per bin, find biggest consistent swings
        val boomBins = spectrumBins
        val decay = 0.97f; val rise = 0.03f
        var maxVar = 0f; var maxVarBin = 0
        for (j in 0 until DISPLAY_BINS) {
            binMean[j] = binMean[j] * decay + boomBins[j] * rise
            val diff = boomBins[j] - binMean[j]
            binVariance[j] = binVariance[j] * decay + diff * diff * rise
            if (binVariance[j] > maxVar) { maxVar = binVariance[j]; maxVarBin = j }
        }
        // Expand from peak variance bin outward (bins with >40% of max variance)
        if (maxVar > 0.001f && updateFrameCount > 72) {
            val threshold40 = maxVar * 0.4f
            var lo = maxVarBin; var hi = maxVarBin
            while (lo > 0 && binVariance[lo - 1] > threshold40) lo--
            while (hi < DISPLAY_BINS - 1 && binVariance[hi + 1] > threshold40) hi++
            // Add 1-bin padding
            lo = (lo - 1).coerceAtLeast(0)
            hi = (hi + 1).coerceAtMost(DISPLAY_BINS - 1)
            boomLeft = lo.toFloat() / DISPLAY_BINS
            boomRight = (hi + 1).toFloat() / DISPLAY_BINS
            boomReady = true
        }

        // Color cycling
        val dt2 = 1f / 72f
        when (colorMode) {
            ColorMode.CYCLE -> {
                cycleHue = (cycleHue + cycleSpeed * dt2) % 360f
            }
            ColorMode.FLASH -> {
                // New random color on each throb/beat trigger
                if (smoothedOutput > 0.9f && prevFillPct < 0.5f) {
                    flashHue = (Math.random() * 360f).toFloat()
                }
            }
            ColorMode.FIXED -> {} // use beatHue directly
        }
    }

    /** Snap the box to the detected BOOM zone */
    fun lockBoom() {
        if (boomReady) {
            boxLeft = boomLeft
            boxRight = boomRight
        }
    }

    /** Apply graphic limiter: soft-clip so pinned bars still show detail */
    fun limitDisplay(rawLevel: Float): Float {
        if (!graphicLimiter) return rawLevel
        // Soft-clip curve: 1 - exp(-x * 2.5) — always below 1.0, preserves detail in hot signals
        return (1f - kotlin.math.exp(-rawLevel * 2.5f))
    }

    val isActive: Boolean get() = started && enabled

    /** Get the active beat color as RGB floats (0..1 each). Returns shared buffer — use immediately. */
    fun getBeatColor(): FloatArray {
        val activeHue = when (colorMode) {
            ColorMode.FIXED -> beatHue
            ColorMode.CYCLE -> cycleHue
            ColorMode.FLASH -> flashHue
        }
        val h = activeHue / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        when {
            h < 1f -> { beatColorBuf[0]=1f; beatColorBuf[1]=x; beatColorBuf[2]=0f }
            h < 2f -> { beatColorBuf[0]=x; beatColorBuf[1]=1f; beatColorBuf[2]=0f }
            h < 3f -> { beatColorBuf[0]=0f; beatColorBuf[1]=1f; beatColorBuf[2]=x }
            h < 4f -> { beatColorBuf[0]=0f; beatColorBuf[1]=x; beatColorBuf[2]=1f }
            h < 5f -> { beatColorBuf[0]=x; beatColorBuf[1]=0f; beatColorBuf[2]=1f }
            else ->   { beatColorBuf[0]=1f; beatColorBuf[1]=0f; beatColorBuf[2]=x }
        }
        return beatColorBuf
    }

    /** Status string for the main menu HUD */
    fun statusString(): String {
        if (!enabled) return "OFF"
        val modeStr = when (rolloff) { Rolloff.INSTANT -> "INS"; Rolloff.HARD_KNEE -> "HRD"; Rolloff.SOFT_KNEE -> "SFT"; Rolloff.THROB -> "THR" }
        return "$modeStr %.0f%%".format(boxFillPct * 100)
    }
}
