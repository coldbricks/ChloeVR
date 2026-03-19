package com.ashairfoil.prism.effects

import android.media.audiofx.Virtualizer
import android.util.Log
import kotlin.math.sin

/**
 * SpatialAudioEffect — Emulated spatial audio for VR video.
 *
 * Shifts the stereo audio balance based on the viewer's head orientation
 * relative to the video, creating a sense that the audio source is fixed
 * in VR space while the viewer rotates.
 *
 * For VR180 content: audio should feel "in front" — rotating left should
 * make sound louder in the right ear (sound stays at the screen position).
 *
 * For VR360 content: full spatial mapping — sound rotates with the scene.
 *
 * Implementation:
 * - Uses ExoPlayer's audio session ID to attach effects
 * - Adjusts stereo balance via AudioTrack volume or custom DSP
 * - Head yaw angle drives the left/right balance shift
 * - Configurable effect strength (0 = no spatial, 1 = full)
 *
 * Usage:
 *   val spatial = SpatialAudioEffect()
 *   spatial.attach(player.audioSessionId)
 *   // In head tracking loop:
 *   spatial.updateHeadYaw(yawRadians)
 *   // On release:
 *   spatial.release()
 */
class SpatialAudioEffect {

    companion object {
        private const val TAG = "SpatialAudio"
    }

    // State
    @Volatile var enabled: Boolean = false
    @Volatile var strength: Float = 0.7f // 0.0 to 1.0
    @Volatile var is360: Boolean = false  // true = full wrap, false = front-facing

    @Volatile private var virtualizer: Virtualizer? = null
    private var audioSessionId: Int = 0
    private var lastYaw: Float = 0f

    // Computed balance values (read by audio thread)
    @Volatile var leftGain: Float = 1.0f
        private set
    @Volatile var rightGain: Float = 1.0f
        private set

    /**
     * Attach to an ExoPlayer audio session.
     * Call after player is prepared.
     */
    fun attach(sessionId: Int) {
        if (sessionId == 0) return
        audioSessionId = sessionId

        try {
            // Release previous Virtualizer if one exists
            virtualizer?.release()
            // Try to use Android's built-in Virtualizer for spatial audio
            virtualizer = Virtualizer(0, sessionId).apply {
                if (strengthSupported) {
                    setStrength((strength * 1000).toInt().coerceIn(0, 1000).toShort())
                    enabled = this@SpatialAudioEffect.enabled
                }
            }
            Log.i(TAG, "Virtualizer attached to session $sessionId, strength supported: ${virtualizer?.strengthSupported}")
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer not available: ${e.message}")
            virtualizer = null
        }
    }

    /**
     * Update head yaw angle (in radians, 0 = forward, positive = right).
     * Call this from the controller/head tracking loop.
     */
    fun updateHeadYaw(yawRadians: Float) {
        if (!enabled) {
            leftGain = 1.0f
            rightGain = 1.0f
            return
        }

        lastYaw = yawRadians

        // Calculate stereo balance from head yaw
        // When head turns right (positive yaw), sound source is now to the left
        // So left ear should be louder

        val effectiveYaw = if (is360) {
            // 360: full range mapping
            yawRadians
        } else {
            // 180: only apply effect within ±90° of center
            yawRadians.coerceIn(-Math.PI.toFloat() / 2, Math.PI.toFloat() / 2)
        }

        // Balance: cos(yaw) for the "forward" ear, reduced for the "away" ear
        // At yaw=0 (looking straight): both ears equal (1.0, 1.0)
        // At yaw=+90° (looking right): left louder (1.0, 0.3)
        // At yaw=-90° (looking left): right louder (0.3, 1.0)
        val pan = sin(effectiveYaw) * strength // -1 (full left) to +1 (full right)
        val minGain = 1.0f - strength * 0.7f   // Don't fully mute either channel

        if (pan >= 0) {
            // Head turned right → sound is left → boost left
            leftGain = 1.0f
            rightGain = (1.0f - pan).coerceAtLeast(minGain)
        } else {
            // Head turned left → sound is right → boost right
            leftGain = (1.0f + pan).coerceAtLeast(minGain)
            rightGain = 1.0f
        }
    }

    /**
     * Enable/disable the virtualizer effect.
     */
    fun updateEnabled(enable: Boolean) {
        enabled = enable
        virtualizer?.enabled = enable
        if (!enable) {
            leftGain = 1.0f
            rightGain = 1.0f
        }
    }

    /**
     * Set effect strength (0.0 to 1.0).
     */
    fun updateStrength(value: Float) {
        strength = value.coerceIn(0f, 1f)
        virtualizer?.let {
            if (it.strengthSupported) {
                try {
                    it.setStrength((strength * 1000).toInt().coerceIn(0, 1000).toShort())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set virtualizer strength: ${e.message}")
                }
            }
        }
    }

    /**
     * Release audio resources.
     */
    fun release() {
        try {
            virtualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing virtualizer: ${e.message}")
        }
        virtualizer = null
        audioSessionId = 0
        leftGain = 1.0f
        rightGain = 1.0f
    }
}
