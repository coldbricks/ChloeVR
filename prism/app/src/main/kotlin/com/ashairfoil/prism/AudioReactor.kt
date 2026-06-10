package com.ashairfoil.prism

import android.media.audiofx.Visualizer
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

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
    // AtomicReference ensures the render thread always sees a complete buffer,
    // never a half-written one (prevents torn reads across FFT/render threads).
    private val spectrumBinsRef = AtomicReference(FloatArray(DISPLAY_BINS))
    var spectrumBins: FloatArray
        get() = spectrumBinsRef.get()
        set(value) { spectrumBinsRef.set(value) }

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

    // ── Musical amplitude: per-frame "breath" signal ──
    // RMS of raw bass/mid/high bands, envelope-followed (fast attack, slow
    // release). Decoupled from boxFillPct so breakdowns visibly quiet down
    // without perturbing BPM lock or the box-gated output. Consumers that
    // were "constant per beat" (impact kicks, haptic amp in BPM-lock mode,
    // beat wash, bloom, dance ampGain) multiply by this so the whole scene
    // breathes with the track instead of pulsing at full magnitude forever.
    // Defaults: floor 0.15 keeps motion alive during quiet parts; dynamics
    // 1.0 = full range. Set floor to 1.0 to disable musical breathing.
    @Volatile var musicalLevel: Float = 1f; private set
    @Volatile var silenceFloor: Float = 0.15f
    @Volatile var musicalDynamics: Float = 1.0f
    private var musicalLevelSmooth: Float = 0f
    @Volatile var beatHue = 330f
    enum class ColorMode { FIXED, CYCLE, FLASH }
    enum class BlendMode { NORMAL, ADD, MULTIPLY, SCREEN }
    enum class WashScope { MODELS, ROOM, BOTH }
    @Volatile var blendMode = BlendMode.ADD
    @Volatile var washScope = WashScope.MODELS
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

    // BPM detection: when enabled, releaseMs is overridden by the detected
    // quarter-note interval (60000 / BPM). Lets the gate fall in tempo so a
    // four-on-the-floor kick naturally triggers at the exact downbeat.
    @Volatile var autoBpm = true
    @Volatile var detectedBpm = 0f; private set
    /** Quantization rate: 1 = whole, 2 = half, 4 = quarter, 8 = eighth, 16 = sixteenth.
     *  When autoBpm is on, one full A↔B oscillation cycle = (1/bpmRate) note. */
    @Volatile var bpmRate: Int = 4
    // Absolute time epoch, set once when BPM is first locked. Oscillator phase
    // is computed as (nowMs - bpmEpochMs) mod cycleMs — so it never accumulates
    // floating-point drift no matter how long the track plays.
    @Volatile private var bpmEpochMs: Long = 0L
    // Once the detector has seen N consistent intervals (low std-dev), BPM is
    // LOCKED — further triggers don't nudge it. This matches the "find the
    // click track, then trust it" mental model and prevents fills/breakdowns
    // from dragging the tempo around.
    @Volatile var bpmLockedStable = false; private set
    /** Reset the BPM epoch and detected value — call when toggling LOCK BPM
     *  on/off so the next detection starts fresh. */
    fun resetBpmDetection() {
        bpmEpochMs = 0L
        detectedBpm = 0f
        bpmLockedStable = false
        triggerTimesFilled = 0
        triggerTimesIdx = 0
        lastTriggerMs = 0L
    }
    /** Absolute-time phase (0..1) for an axis at the given subdivision rate.
     *  Requires autoBpm + detectedBpm. Returns 0 when not yet locked. */
    fun phaseAt(rate: Int): Float {
        if (!autoBpm || detectedBpm <= 0f || bpmEpochMs == 0L) return 0f
        val cycleMs = (60000f / detectedBpm) * 4f / rate.coerceAtLeast(1)
        val elapsedMs = (System.currentTimeMillis() - bpmEpochMs).toFloat()
        return ((elapsedMs / cycleMs) % 1f + 1f) % 1f
    }

    /** Absolute-time phase (0..1) over an N-bar cycle. Used for accent timing,
     *  mood arcs, fill cycles — anything that needs a long-form musical phase. */
    fun phaseInBars(bars: Int): Float {
        if (!autoBpm || detectedBpm <= 0f || bpmEpochMs == 0L) return 0f
        val barMs = (60000f / detectedBpm) * 4f
        val cycleMs = barMs * bars.coerceAtLeast(1)
        val elapsedMs = (System.currentTimeMillis() - bpmEpochMs).toFloat()
        return ((elapsedMs / cycleMs) % 1f + 1f) % 1f
    }

    /** Monotonic counter of beat triggers — a model that tracks "last seen"
     *  can detect exactly when a new beat fires (for impact kicks, fills). */
    @Volatile var beatCounter: Long = 0L; private set

    // Bass-band kick detector (20–150 Hz RMS rising edges). Independent of the
    // user's BOX settings — so changing BOX for the visual reactor no longer
    // affects BPM detection. Uses hysteresis (fire on crossing up, reset only
    // after dropping to 60% of threshold) + min-interval to reject doubles.
    @Volatile var bassKickThreshold: Float = 0.30f
    private var bassAboveThresh = false
    private var lastBassRiseMs = 0L
    private fun detectBassKick() {
        val nowMs = android.os.SystemClock.uptimeMillis()
        val bv = rawBass
        if (bv > bassKickThreshold && !bassAboveThresh) {
            if (nowMs - lastBassRiseMs > 180L) {
                recordTrigger()
                lastBassRiseMs = nowMs
            }
            bassAboveThresh = true
        } else if (bv < bassKickThreshold * 0.6f) {
            bassAboveThresh = false
        }
    }
    private val triggerTimesMs = LongArray(8)
    private var triggerTimesIdx = 0
    private var triggerTimesFilled = 0
    private var lastTriggerMs = 0L
    private fun recordTrigger() {
        val nowMs = android.os.SystemClock.uptimeMillis()
        beatCounter++  // Monotonic pulse for per-model consumers (impact kicks)
        // Once locked, stop updating — the click track is found.
        if (bpmLockedStable) {
            lastTriggerMs = nowMs
            return
        }
        if (lastTriggerMs > 0L) {
            val interval = nowMs - lastTriggerMs
            // Reject intervals outside a wide musical range (180 ms = 333 BPM to
            // 900 ms = 67 BPM raw). Anything shorter is a double-trigger from the
            // same hit; anything longer is a drop/fill gap.
            if (interval in 180L..900L) {
                triggerTimesMs[triggerTimesIdx] = interval
                triggerTimesIdx = (triggerTimesIdx + 1) % triggerTimesMs.size
                if (triggerTimesFilled < triggerTimesMs.size) triggerTimesFilled++
                if (triggerTimesFilled >= 3) {
                    // Mode-like cluster: bucket intervals by nearest-50ms and
                    // pick the bucket with the most members. Dramatically more
                    // robust than median against songs with fills / off-beats.
                    val buckets = HashMap<Long, MutableList<Long>>()
                    for (i in 0 until triggerTimesFilled) {
                        val v = triggerTimesMs[i]
                        val key = v / 50 * 50
                        buckets.getOrPut(key) { mutableListOf() }.add(v)
                    }
                    val best = buckets.values.maxByOrNull { it.size } ?: listOf()
                    val bestMs = if (best.isNotEmpty()) best.average().toFloat() else {
                        triggerTimesMs.copyOf(triggerTimesFilled).also { it.sort() }[triggerTimesFilled / 2].toFloat()
                    }
                    // Octave fold into 70-140 BPM comfort zone
                    var bpm = 60000f / bestMs
                    while (bpm > 140f) bpm /= 2f
                    while (bpm < 70f) bpm *= 2f
                    detectedBpm = bpm.coerceIn(60f, 200f)

                    // Lock once we have 6+ samples tightly clustered (std-dev
                    // under 20ms = the cluster is a real tempo, not noise).
                    if (best.size >= 6) {
                        val avg = best.average()
                        var v = 0.0
                        for (x in best) { val d = x - avg; v += d * d }
                        val sd = kotlin.math.sqrt(v / best.size)
                        if (sd < 20.0) {
                            bpmLockedStable = true
                            android.util.Log.i("AudioReactor", "BPM LOCKED at ${detectedBpm.toInt()} (sd=${"%.1f".format(sd)}ms over ${best.size} samples)")
                        }
                    }
                }
            } else if (interval > 2000L) {
                // Long gap = song break. Reset detection.
                triggerTimesFilled = 0
                triggerTimesIdx = 0
            }
        }
        lastTriggerMs = nowMs
    }

    /** Manual octave correction — user taps ÷2 or ×2 when the detector landed
     *  on the wrong octave (e.g. double-tempo snare ghost notes). */
    fun halveBpm() {
        if (detectedBpm > 0f) {
            detectedBpm = (detectedBpm / 2f).coerceIn(60f, 200f)
            bpmEpochMs = System.currentTimeMillis()
        }
    }
    fun doubleBpm() {
        if (detectedBpm > 0f) {
            detectedBpm = (detectedBpm * 2f).coerceIn(60f, 200f)
            bpmEpochMs = System.currentTimeMillis()
        }
    }

    // ── Tap tempo — user-provided reference, always accurate ──
    private val tapTimes = LongArray(6)
    private var tapIdx = 0
    private var tapFilled = 0
    private var lastTapMs = 0L
    /** Tap on each beat. After 3+ taps within the last 2 s, compute BPM from
     *  tap intervals and lock. Feels like a metronome's tap input. */
    fun tapTempo() {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (lastTapMs > 0L && nowMs - lastTapMs > 2000L) {
            // Too long since last tap — start over.
            tapIdx = 0; tapFilled = 0
        }
        if (lastTapMs > 0L && tapFilled < tapTimes.size + 1) {
            val iv = nowMs - lastTapMs
            if (iv in 200L..1500L) {
                tapTimes[tapIdx] = iv
                tapIdx = (tapIdx + 1) % tapTimes.size
                if (tapFilled < tapTimes.size) tapFilled++
            }
        }
        lastTapMs = nowMs
        if (tapFilled >= 3) {
            val sorted = tapTimes.copyOf(tapFilled).also { it.sort() }
            val medianMs = sorted[tapFilled / 2].toFloat()
            detectedBpm = (60000f / medianMs).coerceIn(60f, 200f)
            bpmEpochMs = System.currentTimeMillis()
            bpmLockedStable = true
            autoBpm = true
            android.util.Log.i("AudioReactor", "TAP TEMPO: ${detectedBpm.toInt()} BPM (from $tapFilled taps)")
        }
    }
    /** ms for one note of bpmRate at detectedBpm; manual releaseMs when lock off. */
    private fun effectiveReleaseMs(): Float {
        return if (autoBpm && detectedBpm > 0f) {
            val quarterMs = 60000f / detectedBpm
            (quarterMs * 4f / bpmRate.coerceAtLeast(1)).coerceIn(50f, 4000f)
        } else releaseMs
    }

    // Anti-dropout
    @Volatile private var fftFrameStamp = 0
    @Volatile private var updateFrameCount = 0

    // BOOM detector: tracks per-bin variance to find biggest swings
    private val binMean = FloatArray(DISPLAY_BINS)
    private val binVariance = FloatArray(DISPLAY_BINS)
    @Volatile var boomLeft = 0f; private set   // detected boom range
    @Volatile var boomRight = 0.35f; private set
    @Volatile var boomReady = false; private set

    // Graphic limiter
    @Volatile var graphicLimiter = true  // soft-clip display so pinned bars still show detail

    // ── Chloe-Vibes advanced engine (optional) ──
    val vibesEngine = com.ashairfoil.prism.haptics.ChloeVibesEngine()
    @Volatile var useVibesEngine = false  // toggle between simple/advanced pipeline

    // ── Input ──
    @Volatile var sensitivity = 3.0f  // display gain — FFT bytes are tiny, needs to be high
    @Volatile var enabled = false

    // ── Internal ──
    private var visualizer: Visualizer? = null
    private var started = false
    // Why the Visualizer is dead, for on-panel display. The Visualizer needs the
    // RECORD_AUDIO runtime permission, which this app never requests (permission
    // dialogs kill the OpenXR session) — without it start() fails SILENTLY and
    // the spectrum/trigger box just looks broken. Surface the reason instead.
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastCaptureMs = 0L
        private set
    private var smoothedOutput = 0f  // the envelope-followed output (before dynRange)
    private var smoothedOutput2 = 0f // envelope for box B
    private var prevFillPct2 = 0f

    // Temp buffer to avoid allocation in FFT callback
    private val tempBins = FloatArray(DISPLAY_BINS)
    private val tempBinMax = FloatArray(DISPLAY_BINS)
    // Double-buffered spectrum output — atomic swap prevents torn reads
    private val specBufA = FloatArray(DISPLAY_BINS)
    private val specBufB = FloatArray(DISPLAY_BINS)
    @Volatile private var specWriteToA = true
    // Pre-allocated beat color result
    private val beatColorBuf = FloatArray(3)
    // Pre-allocated buffer for Chloe-Vibes FFT magnitudes (avoids per-callback alloc)
    private var vibesMagBuf: FloatArray? = null
    // Frame delta tracking for update() — replaces hardcoded 1/72 assumption
    private var lastUpdateNanos = 0L

    // Per-band raw values (written by FFT thread, read by render thread)
    @Volatile private var rawBass = 0f
    @Volatile private var rawMid = 0f
    @Volatile private var rawHigh = 0f

    private var currentSessionId = 0

    @Synchronized
    fun start(audioSessionId: Int = 0): Boolean {
        if (started) return true
        currentSessionId = audioSessionId
        // Only one Visualizer may bind a given audio session at a time. If a
        // stale one survives (release/re-create race when the beat reactor is
        // opened mid-playback), construction fails with error -3 and its native
        // capture thread can SIGSEGV on freed buffers. Fully tear down first.
        teardownVisualizer()
        var vis: Visualizer? = null
        try {
            Log.i(TAG, "Creating Visualizer($audioSessionId) for ${if (audioSessionId == 0) "system audio" else "session"}...")
            vis = Visualizer(audioSessionId)
            vis.captureSize = CAPTURE_SIZE

            val maxRate = Visualizer.getMaxCaptureRate()
            Log.i(TAG, "Visualizer capture size=$CAPTURE_SIZE, maxRate=$maxRate")

            var callCount = 0
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft != null) {
                        lastCaptureMs = System.currentTimeMillis()
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
            lastError = null
            Log.i(TAG, "AudioReactor started, enabled=${vis.enabled}")
            return true
        } catch (e: Exception) {
            // Construction or setup failed (e.g. -3 = session already bound).
            // Release the half-initialized object without ever capturing from it.
            Log.e(TAG, "Failed to start Visualizer: ${e.message}", e)
            lastError = when {
                e is SecurityException -> "MIC PERMISSION DENIED"
                e.message?.contains("-3") == true -> "AUDIO SESSION BUSY (-3)"
                else -> "VISUALIZER FAILED: ${e.message}"
            }
            try {
                vis?.setDataCaptureListener(null, Visualizer.getMaxCaptureRate(), false, false)
            } catch (ignored: Exception) {}
            try { vis?.enabled = false } catch (ignored: Exception) {}
            try { vis?.release() } catch (ignored: Exception) {}
            visualizer = null
            started = false
            return false
        }
    }

    /** Fully tear down the active Visualizer in a SIGSEGV-safe order: unregister
     *  the native capture callback FIRST (stops the capture thread before the
     *  engine is freed), then disable, release, and null the field. Safe to call
     *  when no Visualizer exists. @Synchronized so create/teardown can't race. */
    @Synchronized
    private fun teardownVisualizer() {
        val vis = visualizer ?: return
        // Unregister the native capture callback BEFORE release() so the capture
        // thread can't dereference freed buffers. This is the key SIGSEGV fix.
        try {
            vis.setDataCaptureListener(null, Visualizer.getMaxCaptureRate(), false, false)
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing capture listener: ${e.message}")
        }
        try { vis.enabled = false } catch (e: Exception) { Log.w(TAG, "Error disabling: ${e.message}") }
        try { vis.release() } catch (e: Exception) { Log.w(TAG, "Error releasing: ${e.message}") }
        visualizer = null
    }

    @Synchronized
    fun stop() {
        teardownVisualizer()
        started = false
        lastCaptureMs = 0L
        lastUpdateNanos = 0L
        bass = 0f; mid = 0f; high = 0f
        boxFillPct = 0f; smoothedOutput = 0f; prevFillPct = 0f
        box2FillPct = 0f; smoothedOutput2 = 0f; prevFillPct2 = 0f
    }

    /** Restart with a different audio session (e.g., switch to app audio player) */
    @Synchronized
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

        // Pre-compute FFT magnitudes into vibesMagBuf so we can reuse them
        // for both the display bin mapping and the Chloe-Vibes engine (avoids
        // computing sqrt(real*real + imag*imag) twice when useVibesEngine is on).
        var mags = vibesMagBuf
        if (mags == null || mags.size != binCount) {
            mags = FloatArray(binCount)
            vibesMagBuf = mags
        }
        mags[0] = 0f
        for (i in 1 until binCount) {
            val real = fft[2 * i].toFloat()
            val imag = fft[2 * i + 1].toFloat()
            mags[i] = kotlin.math.sqrt(real * real + imag * imag)
        }

        for (i in 1 until binCount) {
            // Normalize and apply sensitivity as display gain
            val norm = (mags[i] / normValue) * sensitivity

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
        // Double-buffer: write to inactive buffer, then atomically publish via AtomicReference
        val buf = if (specWriteToA) specBufA else specBufB
        for (j in 0 until DISPLAY_BINS) {
            buf[j] = tempBinMax[j].coerceAtLeast(0f)
        }
        specWriteToA = !specWriteToA
        spectrumBinsRef.set(buf)

        // Per-band values (average, clamped)
        rawBass = if (bassCnt > 0) (bassE / bassCnt).coerceIn(0f, 1f) else 0f
        rawMid = if (midCnt > 0) (midE / midCnt).coerceIn(0f, 1f) else 0f
        rawHigh = if (highCnt > 0) (highE / highCnt).coerceIn(0f, 1f) else 0f
        fftFrameStamp = updateFrameCount  // mark that we got fresh data

        // Feed Chloe-Vibes engine with FFT magnitudes (if enabled).
        // Magnitudes already computed into vibesMagBuf above — just normalize
        // for the vibes engine (/ 128 * sensitivity) and pass through.
        if (useVibesEngine) {
            // Scale raw magnitudes to the normalized form the vibes engine expects.
            // We write in-place since the display bin loop above already consumed
            // the raw values it needed via (mags[i] / normValue) * sensitivity.
            for (i in 1 until binCount) {
                mags[i] = mags[i] / 128f * sensitivity
            }
            vibesEngine.processVisualizerFFT(mags)
        }
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
            // Reactor off ⇒ musical breathing inert (no gating).
            musicalLevel = 1f
            musicalLevelSmooth = 0f
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
        val now = System.nanoTime()
        val dt = if (lastUpdateNanos == 0L) 1f / 72f else (now - lastUpdateNanos) / 1_000_000_000f
        lastUpdateNanos = now
        val safeDt = dt.coerceIn(0.001f, 0.1f)
        val atkAlpha = (1f - kotlin.math.exp(-safeDt / (attackMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.01f, 1f)
        val relAlpha = (1f - kotlin.math.exp(-safeDt / (releaseMs / 1000f).coerceAtLeast(0.001f))).coerceIn(0.005f, 1f)

        // Musical amplitude — RMS envelope of raw bands, independent of BOX.
        // 50ms attack keeps beat punch; 800ms release lets breakdowns settle.
        // Value passes through above silenceFloor so loud passages read their
        // true level (not a rescale), and pins at floor when the track is quiet.
        val rmsInst = kotlin.math.sqrt(
            (rawBass * rawBass + rawMid * rawMid + rawHigh * rawHigh) / 3f
        ).coerceIn(0f, 1f)
        val mlAtk = 1f - kotlin.math.exp(-safeDt / 0.05f)
        val mlRel = 1f - kotlin.math.exp(-safeDt / 0.8f)
        val mlAlpha = if (rmsInst > musicalLevelSmooth) mlAtk else mlRel
        musicalLevelSmooth += (rmsInst - musicalLevelSmooth) * mlAlpha
        val mlHeadroom = (musicalLevelSmooth - silenceFloor).coerceAtLeast(0f) * musicalDynamics
        musicalLevel = (silenceFloor + mlHeadroom).coerceIn(0f, 1f)
        // When BPM is locked (user has tap-tempoed or auto-BPM caught),
        // floor musicalLevel at 0.5 so dance effects still fire when the
        // user is using the metronome without music playing. Without this
        // floor, quiet passages silence every dance effect because dance
        // amplitudes are multiplied by musicalLevel.
        if (bpmLockedStable) {
            musicalLevel = musicalLevel.coerceAtLeast(0.5f)
        }

        // BPM tracker runs off rawBass (bass-band RMS), NOT the user's BOX
        // settings. The box can be anywhere in the spectrum without affecting
        // tempo lock.
        detectBassKick()

        if (autoBpm) {
            // "Her ass is the metronome" — once we've found the click track (BPM),
            // the oscillator free-runs from the clock. Kicks only feed the BPM
            // estimator; they never touch phase. That way the sweep stays smooth
            // through breakdowns and off-beat fills and never jitters mid-cycle.
            // (No longer call recordTrigger here — detectBassKick handles it.)
            if (detectedBpm > 0f) {
                if (bpmEpochMs == 0L) bpmEpochMs = System.currentTimeMillis()
                // Absolute-time phase: no accumulated drift.
                val phaseNorm = phaseAt(bpmRate)
                smoothedOutput = 0.5f * (1f - kotlin.math.cos(2.0 * Math.PI * phaseNorm).toFloat())
            } else {
                // Still searching for a click track — hold silent rather than
                // falling back to the envelope (keeps the UX unambiguous).
                smoothedOutput = 0f
            }
        } else if (rolloff == Rolloff.THROB) {
            // THROB: one-shot burst. Trigger fires, runs FULL decay cycle,
            // cannot retrigger until cycle is complete.
            if (!throbLocked && rawFill > threshold) {
                // Trigger! Start the burst. (BPM is now tracked via detectBassKick
                // at the top of update(), independent of box settings.)
                smoothedOutput = 1f
                throbLocked = true
                hardKneeTimer = 0f
            }
            if (throbLocked) {
                // In decay cycle — count down, ignore all input
                hardKneeTimer += safeDt
                val releaseSec = effectiveReleaseMs() / 1000f
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
                    // Attack: track fill upward (BPM tracked via bass kick, not here)
                    if (rawFill > smoothedOutput) {
                        smoothedOutput += (rawFill - smoothedOutput) * atkAlpha
                        hardKneeTriggered = true
                        hardKneeTimer = 0f
                    } else if (hardKneeTriggered) {
                        // Linear decay from peak over releaseMs (tempo-locked when autoBpm)
                        hardKneeTimer += safeDt
                        val progress = (hardKneeTimer / (effectiveReleaseMs() / 1000f)).coerceIn(0f, 1f)
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

        // Smoother: one-pole LPF with a time constant that scales up to ~2 s
        // at max. That's long enough to average 4 beats @ 120 BPM — so cranking
        // this to the right "locks" the pulse onto a four-on-the-floor rhythm
        // instead of tracking every transient. Quadratic curve keeps the lower
        // half of the slider subtle.
        val smoothTauSec = smootherAmount * smootherAmount * 2.0f  // 0…2 seconds
        val smoothAlpha = if (smoothTauSec < 0.001f) 1f
            else (1f - kotlin.math.exp(-safeDt / smoothTauSec)).coerceIn(0.001f, 1f)
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
        when (colorMode) {
            ColorMode.CYCLE -> {
                cycleHue = (cycleHue + cycleSpeed * safeDt) % 360f
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

    /** Full auto-snap: horizontal (BOOM zone) PLUS vertical bounds derived
     *  from the peak bin's mean and stddev. Gives the user a one-tap "find
     *  the rhythm" when manual drag is too fiddly. Requires ~1.2s of audio
     *  history so the EMA has stabilized. */
    fun autoSnapBox(): Boolean {
        if (!boomReady) return false
        // Horizontal from BOOM expansion
        boxLeft = boomLeft
        boxRight = boomRight
        // Vertical from the peak-variance bin's stats — span mean ± 1.2σ.
        // Floor the bottom slightly below the trough so the threshold catches
        // the full kick cycle, top just above peak so dynamic tips trigger.
        var maxVar = 0f
        var peakBin = 0
        for (j in 0 until DISPLAY_BINS) {
            if (binVariance[j] > maxVar) { maxVar = binVariance[j]; peakBin = j }
        }
        val peakMean = binMean[peakBin]
        val peakStd = kotlin.math.sqrt(maxVar)
        val top = (peakMean + peakStd * 1.2f).coerceIn(0.1f, 1f)
        val bot = (peakMean - peakStd * 0.8f).coerceIn(0f, 0.9f)
        // Guarantee at least a 15% tall box so the trigger has headroom.
        if (top - bot < 0.15f) {
            val mid = (top + bot) * 0.5f
            boxTop = (mid + 0.09f).coerceAtMost(1f)
            boxBottom = (mid - 0.09f).coerceAtLeast(0f)
        } else {
            boxTop = top
            boxBottom = bot
        }
        return true
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

    // ── FrameSnapshot: per-frame read-once struct for consumers ─────────────
    // Dance loop reads this ONCE per frame via `snapshot(out)` then consults
    // the fields instead of hitting @Volatile getters + phaseAt() N times per
    // model per frame. Caller supplies + reuses the buffer — zero allocation.
    class FrameSnapshot {
        var nowMs: Long = 0L
        var boxFillPct: Float = 0f
        var beatCounter: Long = 0L
        var bpm: Float = 0f
        var autoBpm: Boolean = false
        var bandBass: Float = 0f
        var bandMid: Float = 0f
        var bandHigh: Float = 0f
        var phaseAt2: Float = 0f
        var phaseAt4: Float = 0f
        var phaseAt8: Float = 0f
        var phaseAt16: Float = 0f
        var phase1bar: Float = 0f
        var phase4bar: Float = 0f
        var phase16bar: Float = 0f
        var musicalLevel: Float = 1f
    }

    fun snapshot(out: FrameSnapshot) {
        val now = System.currentTimeMillis()
        out.nowMs = now
        out.boxFillPct = boxFillPct
        out.beatCounter = beatCounter
        out.bpm = detectedBpm
        out.autoBpm = autoBpm
        out.bandBass = bass
        out.bandMid = mid
        out.bandHigh = high
        out.musicalLevel = musicalLevel
        if (autoBpm && detectedBpm > 0f && bpmEpochMs != 0L) {
            val quarter = 60000f / detectedBpm
            val barMs = quarter * 4f
            val elapsed = (now - bpmEpochMs).toFloat()
            out.phaseAt2 = ((elapsed / (barMs / 2f)) % 1f + 1f) % 1f
            out.phaseAt4 = ((elapsed / quarter) % 1f + 1f) % 1f
            out.phaseAt8 = ((elapsed / (quarter / 2f)) % 1f + 1f) % 1f
            out.phaseAt16 = ((elapsed / (quarter / 4f)) % 1f + 1f) % 1f
            out.phase1bar = ((elapsed / barMs) % 1f + 1f) % 1f
            out.phase4bar = ((elapsed / (barMs * 4f)) % 1f + 1f) % 1f
            out.phase16bar = ((elapsed / (barMs * 16f)) % 1f + 1f) % 1f
        } else {
            out.phaseAt2 = 0f; out.phaseAt4 = 0f; out.phaseAt8 = 0f; out.phaseAt16 = 0f
            out.phase1bar = 0f; out.phase4bar = 0f; out.phase16bar = 0f
        }
    }

    /** Dispatch helper: return cached phase for a supported rate without an extra
     *  System.currentTimeMillis() call. Falls back to phaseAt(rate) only if the
     *  rate is unsupported (which is never, for our 2/4/8/16 scheme). */
    fun phaseAtSnap(s: FrameSnapshot, rate: Int): Float = when (rate) {
        2 -> s.phaseAt2
        4 -> s.phaseAt4
        8 -> s.phaseAt8
        16 -> s.phaseAt16
        else -> phaseAt(rate)
    }
}
