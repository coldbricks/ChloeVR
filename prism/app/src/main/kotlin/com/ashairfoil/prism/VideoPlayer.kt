package com.ashairfoil.prism

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class VideoPlayer(private val context: Context) {

    private var player: ExoPlayer? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val currentPositionMs: Long get() = player?.currentPosition ?: 0
    val durationMs: Long get() = player?.duration ?: 0

    fun start(file: File, surface: Surface) {
        release()
        player = ExoPlayer.Builder(context).build().apply {
            setVideoSurface(surface)
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            prepare()
            playWhenReady = true
        }
    }

    fun togglePlayPause() {
        player?.let { it.playWhenReady = !it.isPlaying }
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
