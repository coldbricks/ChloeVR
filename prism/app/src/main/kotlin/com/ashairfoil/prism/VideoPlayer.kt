package com.ashairfoil.prism

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ashairfoil.prism.effects.ColorGradingEffect
import com.ashairfoil.prism.effects.ColorGradingState
import com.ashairfoil.prism.effects.LensDistortionEffect
import com.ashairfoil.prism.effects.LensDistortionState
import com.ashairfoil.prism.effects.StereoAdjustmentEffect
import com.ashairfoil.prism.effects.StereoAdjustmentState
import java.io.File

@UnstableApi
class VideoPlayer(private val context: Context) {

    private var player: ExoPlayer? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val currentPositionMs: Long get() = player?.currentPosition ?: 0
    val durationMs: Long get() = player?.duration ?: 0

    /**
     * Start playback of a video file with the full effects pipeline.
     *
     * Effects are chained in this order:
     * 1. DeoVR alpha unpacking (if alpha-packed content)
     * 2. Chroma key (if enabled)
     * 3. Lens distortion correction (if enabled)
     * 4. Color grading (brightness, contrast, saturation, sharpening, gamma, hue, tone map)
     * 5. Stereo/IPD adjustment (if enabled — must be last since it operates on the packed stereo layout)
     *
     * The order matters: lens correction undistorts before color grading processes the corrected image,
     * and stereo adjustment must come last because it shifts UV coordinates per-eye within the
     * packed SBS/TB layout.
     */
    fun start(
        file: File,
        surface: Surface,
        useDeoAlphaPacking: Boolean = false,
        chromaKeyState: ChromaKeyState? = null,
        colorGradingState: ColorGradingState? = null,
        lensDistortionState: LensDistortionState? = null,
        stereoAdjustmentState: StereoAdjustmentState? = null
    ) {
        release()
        player = ExoPlayer.Builder(context).build().apply {
            // Only add effects that are actually enabled.
            // When effects are added, ExoPlayer routes through DefaultVideoFrameProcessor.
            // On Galaxy XR, the FinalShaderWrapper drops frames if the output surface
            // isn't properly sized — so skip the effects pipeline entirely when nothing
            // is enabled. Each effect's shader has an early-return when disabled, but
            // just being in the pipeline causes the surface routing issue.
            val effects = mutableListOf<androidx.media3.common.Effect>()

            // 1. Alpha unpacking — must be first, restructures pixel layout
            if (useDeoAlphaPacking) {
                effects.add(DeoVrAlphaPackedEffect())
            }

            // 2. Chroma key — only when enabled
            if (chromaKeyState != null && chromaKeyState.enabled) {
                effects.add(ChromaKeyEffect(chromaKeyState))
            }

            // 3. Lens distortion correction — only when enabled
            if (lensDistortionState != null && lensDistortionState.enabled) {
                effects.add(LensDistortionEffect(lensDistortionState))
            }

            // 4. Color grading — only when enabled
            if (colorGradingState != null && colorGradingState.enabled) {
                effects.add(ColorGradingEffect(colorGradingState))
            }

            // 5. Stereo/IPD adjustment — only when enabled
            if (stereoAdjustmentState != null && stereoAdjustmentState.enabled) {
                effects.add(StereoAdjustmentEffect(stereoAdjustmentState))
            }

            if (effects.isNotEmpty()) {
                setVideoEffects(effects)
            }
            setVideoSurface(surface)
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            prepare()
            playWhenReady = true
        }
    }

    fun togglePlayPause() {
        player?.let { it.playWhenReady = !it.isPlaying }
    }

    fun play() {
        player?.playWhenReady = true
    }

    fun pause() {
        player?.playWhenReady = false
    }

    fun seekBy(deltaMs: Long) {
        player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    }

    fun seekTo(posMs: Long) {
        player?.seekTo(posMs.coerceAtLeast(0))
    }

    fun setSurface(surface: Surface) {
        player?.setVideoSurface(surface)
    }

    var speed: Float = 1f
        set(value) {
            field = value
            player?.playbackParameters = PlaybackParameters(value)
        }

    fun release() {
        player?.release()
        player = null
    }
}
