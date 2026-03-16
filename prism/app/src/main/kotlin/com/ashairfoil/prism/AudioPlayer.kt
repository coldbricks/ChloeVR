package com.ashairfoil.prism

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@UnstableApi
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "ChloeVR-AudioPlayer"
        val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val SPEED_LABELS = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
    }

    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isPaused: Boolean get() = player?.playWhenReady == false && player?.playbackState == Player.STATE_READY
    val currentPositionMs: Long get() = player?.currentPosition ?: 0
    val durationMs: Long get() = player?.duration?.let { if (it > 0) it else 0 } ?: 0
    val audioSessionId: Int get() = player?.audioSessionId ?: 0
    var currentFile: File? = null; private set

    // A/B loop
    var loopA: Long = -1; private set
    var loopB: Long = -1; private set

    // Speed
    var speedIndex = 2  // default 1.0x
        private set

    // EQ
    enum class EqPreset(val label: String) { FLAT("Flat"), BASS_BOOST("Bass+"), VOCAL("Vocal"), TREBLE_BOOST("Treble+") }
    var eqPreset = EqPreset.FLAT; private set

    // Repeat
    enum class RepeatMode(val label: String) { OFF("OFF"), ONE("ONE"), ALL("ALL") }
    var repeatMode = RepeatMode.OFF; private set

    fun play(file: File) {
        release()
        currentFile = file
        Log.i(TAG, "Playing: ${file.name}")
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        when (this@AudioPlayer.repeatMode) {
                            RepeatMode.ONE -> this@AudioPlayer.seekTo(0)
                            RepeatMode.OFF -> {}
                            RepeatMode.ALL -> {}
                        }
                    }
                }
            })
        }
        // Apply current speed
        player?.playbackParameters = PlaybackParameters(SPEED_OPTIONS[speedIndex])
        // Init EQ
        initEq()
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }

    fun seekTo(posMs: Long) {
        player?.seekTo(posMs.coerceIn(0, durationMs))
    }

    fun seekBy(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }

    // Speed control
    fun setSpeedIndex(idx: Int) {
        speedIndex = idx.coerceIn(0, SPEED_OPTIONS.size - 1)
        player?.playbackParameters = PlaybackParameters(SPEED_OPTIONS[speedIndex])
    }

    fun cycleSpeed(forward: Boolean = true) {
        setSpeedIndex(if (forward) (speedIndex + 1) % SPEED_OPTIONS.size
            else (speedIndex - 1 + SPEED_OPTIONS.size) % SPEED_OPTIONS.size)
    }

    // A/B loop
    fun setLoopA() { loopA = currentPositionMs; Log.i(TAG, "Loop A: ${loopA}ms") }
    fun setLoopB() {
        loopB = currentPositionMs
        if (loopA >= 0 && loopB < loopA) { val tmp = loopA; loopA = loopB; loopB = tmp }
        Log.i(TAG, "Loop B: ${loopB}ms (A=${loopA}ms)")
    }
    fun clearLoop() { loopA = -1; loopB = -1 }
    fun hasLoop(): Boolean = loopA >= 0 && loopB > loopA

    /** Call per-frame to enforce A/B loop */
    fun updateLoop() {
        if (hasLoop() && isPlaying && currentPositionMs >= loopB) {
            player?.seekTo(loopA)
        }
    }

    // Repeat
    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.OFF
        }
        player?.repeatMode = when (repeatMode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    // EQ
    private fun initEq() {
        try {
            val sid = player?.audioSessionId ?: return
            equalizer?.release()
            val eq = Equalizer(0, sid)
            eq.enabled = true
            equalizer = eq
            applyEqPreset()
            Log.i(TAG, "EQ initialized: ${eq.numberOfBands} bands, session=$sid")
        } catch (e: Exception) {
            Log.w(TAG, "EQ init failed: ${e.message}")
        }
    }

    fun setEqPresetByIndex(idx: Int) {
        val presets = EqPreset.entries
        if (idx in presets.indices) {
            eqPreset = presets[idx]
            applyEqPreset()
        }
    }

    fun cycleEqPreset() {
        val presets = EqPreset.entries
        eqPreset = presets[(eqPreset.ordinal + 1) % presets.size]
        applyEqPreset()
    }

    private fun applyEqPreset() {
        val eq = equalizer ?: return
        val bands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange // [min, max] in millibels
        val max = range[1].toInt()
        val min = range[0].toInt()
        val mid = 0

        // Distribute bands: assume 5 bands (60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz typical)
        when (eqPreset) {
            EqPreset.FLAT -> for (i in 0 until bands) eq.setBandLevel(i.toShort(), mid.toShort())
            EqPreset.BASS_BOOST -> {
                val levels = intArrayOf(max, max * 2 / 3, mid, mid, mid)
                for (i in 0 until bands) eq.setBandLevel(i.toShort(),
                    (levels.getOrElse(i) { mid }).coerceIn(min, max).toShort())
            }
            EqPreset.VOCAL -> {
                val levels = intArrayOf(min / 3, mid, max * 2 / 3, max / 2, mid)
                for (i in 0 until bands) eq.setBandLevel(i.toShort(),
                    (levels.getOrElse(i) { mid }).coerceIn(min, max).toShort())
            }
            EqPreset.TREBLE_BOOST -> {
                val levels = intArrayOf(mid, mid, mid, max * 2 / 3, max)
                for (i in 0 until bands) eq.setBandLevel(i.toShort(),
                    (levels.getOrElse(i) { mid }).coerceIn(min, max).toShort())
            }
        }
    }

    fun release() {
        equalizer?.release(); equalizer = null
        player?.release(); player = null
        currentFile = null
    }
}
