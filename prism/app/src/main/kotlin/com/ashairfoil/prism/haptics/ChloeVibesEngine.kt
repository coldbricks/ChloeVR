package com.ashairfoil.prism.haptics

import kotlin.math.roundToInt

/**
 * ChloeVibesEngine — Integration wrapper that connects ChloeVR's AudioReactor
 * (Visualizer FFT) to the full Chloe-Vibes signal processing pipeline.
 *
 * Signal chain: Visualizer FFT magnitudes → SpectralAnalyzer (flux/bands)
 *   → Gate → BeatDetector → EnvelopeProcessor → ClimaxEngine → Output
 *
 * This runs on the AudioReactor's update thread (~72Hz).
 */
class ChloeVibesEngine {

    private val analyzer = SpectralAnalyzer(48000f)
    private val gate = Gate()
    private val beatDetector = BeatDetector()
    private val envelope = EnvelopeProcessor()
    private val climax = ClimaxEngine()

    // Dithering state for sub-step resolution
    private var ditherError: Float = 0f
    private var ditherError2: Float = 0f

    // ── Tunable parameters (exposed for UI control) ──

    // Gate
    @Volatile var gateThreshold = 0.15f
    @Volatile var autoGateAmount = 0.5f
    @Volatile var gateSmoothing = 0.3f

    // Trigger
    @Volatile var triggerMode = TriggerMode.Dynamic
    @Volatile var dynamicCurve = 1.0f
    @Volatile var binaryLevel = 0.8f
    @Volatile var hybridBlend = 0.5f

    // Envelope
    @Volatile var attackMs = 30f
    @Volatile var decayMs = 80f
    @Volatile var sustainLevel = 0.6f
    @Volatile var releaseMs = 200f
    @Volatile var attackCurve = 0.8f
    @Volatile var decayCurve = 1.2f
    @Volatile var releaseCurve = 1.5f

    // Output
    @Volatile var outputGain = 1.0f
    @Volatile var outputFloor = 0.0f
    @Volatile var outputCeiling = 1.0f

    // Climax
    @Volatile var climaxEnabled = false
    @Volatile var climaxIntensity = 0.6f
    @Volatile var climaxBuildUpMs = 60000f
    @Volatile var climaxTeaseRatio = 0.2f
    @Volatile var climaxTeaseDrop = 0.5f
    @Volatile var climaxSurgeBoost = 0.8f
    @Volatile var climaxPulseDepth = 0.25f
    @Volatile var climaxPattern = ClimaxPattern.Wave

    // Frequency mode
    @Volatile var frequencyMode = FrequencyMode.Full
    @Volatile var targetFrequency = 200f

    // ── Output state (read by render/haptic threads) ──
    @Volatile var output = 0f; private set
    @Volatile var motor2Output = 0f; private set
    @Volatile var isGateOpen = false; private set
    @Volatile var envelopeState = EnvelopeState.Idle; private set

    // Band energies for visualization
    private val bandEnergiesRef = FloatArray(NUM_BANDS)
    @Volatile var bandEnergies: FloatArray = bandEnergiesRef
        private set

    // Pre-allocated SpectralData to avoid per-frame allocation in extractBandEnergies
    private val scratchBandEnergies = FloatArray(NUM_BANDS)
    private val scratchSpectralData = SpectralData(bandEnergies = scratchBandEnergies)

    /** Start time for envelope timing. */
    private var startTimeMs = System.currentTimeMillis()

    /**
     * Process one frame using raw FFT magnitudes from the Android Visualizer.
     * Called from AudioReactor's update thread (~72Hz).
     *
     * @param magnitudes array of FFT magnitude values from Visualizer
     */
    fun processVisualizerFFT(magnitudes: FloatArray) {
        val currentTimeMs = (System.currentTimeMillis() - startTimeMs).toFloat()

        // Compute spectral flux from Visualizer magnitudes
        val spectralFlux = analyzer.computeFluxFrom(magnitudes)

        // Extract band energies from the Visualizer FFT
        // Map Visualizer bins to 8 perceptual bands
        val numBins = magnitudes.size
        val sampleRate = 48000f
        val binRes = sampleRate / (numBins * 2f) // Visualizer returns N/2 bins
        val data = extractBandEnergies(magnitudes, binRes)

        // Copy for visualization
        data.bandEnergies.copyInto(bandEnergiesRef)
        bandEnergies = bandEnergiesRef

        // Extract energy based on frequency mode
        val energy = SpectralAnalyzer.extractEnergy(data, frequencyMode, targetFrequency)

        // Gate
        isGateOpen = gate.process(energy, gateThreshold, autoGateAmount, gateSmoothing, 0.05f)

        // Beat detection
        val (isOnset, onsetStrength) = beatDetector.process(spectralFlux, currentTimeMs)

        // Envelope
        val envelopeOutput = envelope.drive(
            gateOpen = isGateOpen,
            energy = energy,
            isOnset = isOnset,
            onsetStrength = onsetStrength,
            currentTimeMs = currentTimeMs,
            triggerMode = triggerMode,
            threshold = gateThreshold,
            thresholdKnee = 0.05f,
            dynamicCurve = dynamicCurve,
            binaryLevel = binaryLevel,
            hybridBlend = hybridBlend,
            attackMs = attackMs,
            decayMs = decayMs,
            sustainLevel = sustainLevel,
            releaseMs = releaseMs,
            attackCurve = attackCurve,
            decayCurve = decayCurve,
            releaseCurve = releaseCurve,
            spectralCentroid = data.spectralCentroid
        )
        envelopeState = envelope.state

        // Climax engine
        val climaxOutput = climax.process(
            input = envelopeOutput,
            energy = energy,
            gateOpen = isGateOpen,
            isOnset = isOnset,
            onsetStrength = onsetStrength,
            currentTimeMs = currentTimeMs,
            enabled = climaxEnabled,
            intensity = climaxIntensity,
            buildUpMs = climaxBuildUpMs,
            teaseRatio = climaxTeaseRatio,
            teaseDrop = climaxTeaseDrop,
            surgeBoost = climaxSurgeBoost,
            pulseDepth = climaxPulseDepth,
            pattern = climaxPattern
        )

        // Apply output gain + floor/ceiling
        val gained = climaxOutput * outputGain
        output = (outputFloor + gained * (outputCeiling - outputFloor)).coerceIn(0f, 1f)
        motor2Output = (outputFloor + climax.motor2Output * outputGain * (outputCeiling - outputFloor)).coerceIn(0f, 1f)
    }

    /**
     * Get dithered Lovense level (0-20) with temporal dithering.
     * Call this at BLE write rate (~20Hz).
     */
    fun getDitheredLevel(): Int {
        val raw = output * 20f + ditherError
        val quantized = raw.roundToInt().coerceIn(0, 20)
        ditherError = raw - quantized
        return quantized
    }

    /** Get dithered motor 2 level (0-20). */
    fun getDitheredLevel2(): Int {
        val raw = motor2Output * 20f + ditherError2
        val quantized = raw.roundToInt().coerceIn(0, 20)
        ditherError2 = raw - quantized
        return quantized
    }

    fun reset() {
        envelope.reset()
        val now = (System.currentTimeMillis() - startTimeMs).toFloat()
        climax.reset(now)
        output = 0f
        motor2Output = 0f
        ditherError = 0f
        ditherError2 = 0f
    }

    /**
     * Extract band energies from Visualizer FFT magnitude array.
     * Maps arbitrary-length magnitude array to 8 perceptual bands.
     * Populates the pre-allocated scratchSpectralData to avoid per-frame allocation.
     */
    private fun extractBandEnergies(magnitudes: FloatArray, binResolution: Float): SpectralData {
        val bandEnergies = scratchBandEnergies
        val numBins = magnitudes.size

        for (i in 0 until NUM_BANDS) {
            val startBin = (BAND_EDGES[i] / binResolution).roundToInt().coerceIn(0, numBins)
            val endBin = (BAND_EDGES[i + 1] / binResolution).roundToInt().coerceIn(0, numBins)
            if (endBin > startBin) {
                var energy = 0f
                for (b in startBin until endBin) {
                    if (b < numBins) energy += magnitudes[b] * magnitudes[b]
                }
                bandEnergies[i] = kotlin.math.sqrt(energy / (endBin - startBin))
            } else {
                bandEnergies[i] = 0f
            }
        }

        // Compute spectral centroid
        var weightedSum = 0f
        var totalMag = 0f
        for (i in magnitudes.indices) {
            val freq = i * binResolution
            weightedSum += freq * magnitudes[i]
            totalMag += magnitudes[i]
        }

        scratchSpectralData.rmsPower = 0f
        scratchSpectralData.spectralCentroid = if (totalMag > 1e-10f) weightedSum / totalMag else 0f
        scratchSpectralData.spectralFlux = 0f
        scratchSpectralData.dominantFrequency = 0f
        return scratchSpectralData
    }
}
