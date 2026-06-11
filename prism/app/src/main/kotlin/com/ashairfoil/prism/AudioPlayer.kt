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
    private var playerListener: Player.Listener? = null
    private var equalizer: Equalizer? = null

    // ── PCM tap ──
    // Quest zero-fills the Visualizer effects tap (energy=0 on every callback,
    // even on our own session), so the beat reactor takes raw PCM straight out
    // of the ExoPlayer pipeline — upstream of Horizon's spatializer.
    // Called on ExoPlayer's audio thread: (buffer, sampleRateHz, channelCount).
    var onPcmChunk: ((java.nio.ByteBuffer, Int, Int) -> Unit)? = null
    @Volatile private var teeSampleRate = 48000
    @Volatile private var teeChannels = 2
    private val teeSink = object : androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            teeSampleRate = sampleRateHz
            teeChannels = channelCount
        }
        override fun handleBuffer(buffer: java.nio.ByteBuffer) {
            onPcmChunk?.invoke(buffer, teeSampleRate, teeChannels)
        }
    }
    private val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): androidx.media3.exoplayer.audio.AudioSink {
            return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(
                    androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                        androidx.media3.exoplayer.audio.TeeAudioProcessor(teeSink)
                    )
                )
                .build()
        }
    }

    // ExoPlayer enforces application-thread access (main thread here), but the
    // menu bitmap now renders on a background thread. Cache position/duration
    // on a 30 Hz main-thread ticker so UI reads can't cross the thread boundary.
    @Volatile private var cachedPositionMs: Long = 0L
    @Volatile private var cachedDurationMs: Long = 0L
    @Volatile private var cachedIsPlaying: Boolean = false
    @Volatile private var cachedIsPaused: Boolean = false
    @Volatile private var cachedAudioSessionId: Int = 0
    private val cacheHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cacheTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                cachedPositionMs = p.currentPosition
                val d = p.duration
                cachedDurationMs = if (d > 0) d else 0
                cachedIsPlaying = p.isPlaying
                cachedIsPaused = !p.playWhenReady && p.playbackState == Player.STATE_READY
                cachedAudioSessionId = p.audioSessionId
                cacheHandler.postDelayed(this, 33L)  // ~30 Hz — smooth enough for a progress bar
            }
        }
    }
    private fun startCacheTicker() {
        cacheHandler.removeCallbacks(cacheTicker)
        cacheHandler.post(cacheTicker)
    }
    private fun stopCacheTicker() {
        cacheHandler.removeCallbacks(cacheTicker)
        cachedPositionMs = 0L; cachedDurationMs = 0L
        cachedIsPlaying = false; cachedIsPaused = false
        cachedAudioSessionId = 0
    }

    /** ExoPlayer is bound to the main thread — any call that touches [player] or
     *  its listeners must run there. This helper lets callers on any thread
     *  (render loop, background worker, etc.) invoke mutations safely. */
    private inline fun onMain(crossinline action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else cacheHandler.post { action() }
    }

    val isPlaying: Boolean get() = cachedIsPlaying
    val isPaused: Boolean get() = cachedIsPaused
    val currentPositionMs: Long get() = cachedPositionMs
    val durationMs: Long get() = cachedDurationMs
    val audioSessionId: Int get() = cachedAudioSessionId
    var currentFile: File? = null; private set

    // Playlist
    var playlist: List<File> = emptyList()
        private set
    var playlistIndex: Int = -1
        private set
    val hasNext: Boolean get() = playlist.isNotEmpty() && playlistIndex < playlist.size - 1
    val hasPrevious: Boolean get() = playlist.isNotEmpty() && playlistIndex > 0

    /** Called when track changes (auto-advance or manual). Use to restart AudioReactor. */
    var onTrackChanged: ((File) -> Unit)? = null

    /** Called when playback fails. Use to surface errors to the user. */
    var onError: ((String) -> Unit)? = null

    // A/B loop (volatile: read from render thread, written from UI thread)
    @Volatile var loopA: Long = -1; private set
    @Volatile var loopB: Long = -1; private set
    @Volatile private var loopSeekPending = false  // guard against seek storms

    // Speed
    var speedIndex = 2  // default 1.0x
        private set

    // EQ
    enum class EqPreset(val label: String) { FLAT("Flat"), BASS_BOOST("Bass+"), VOCAL("Vocal"), TREBLE_BOOST("Treble+") }
    var eqPreset = EqPreset.FLAT; private set

    // Repeat
    enum class RepeatMode(val label: String) { OFF("OFF"), ONE("ONE"), ALL("ALL") }
    var repeatMode = RepeatMode.OFF; private set

    // Volume (0.0 - 1.0)
    var volume = 1.0f
        private set

    /** Set playlist and play a specific track. */
    fun playFromPlaylist(files: List<File>, index: Int) {
        playlist = files
        playlistIndex = index.coerceIn(0, files.size - 1)
        play(files[playlistIndex])
    }

    fun play(file: File) = onMain {
        if (!file.exists()) {
            Log.e(TAG, "File not found: ${file.absolutePath}")
            onError?.invoke("Audio file not found: ${file.name}")
            return@onMain
        }
        // Remove listener BEFORE release to prevent stale STATE_ENDED cascade
        // (releasing ExoPlayer fires STATE_ENDED, which would queue playNext())
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        equalizer?.release(); equalizer = null
        player?.release(); player = null
        currentFile = file
        // Update playlist index if playing from existing playlist
        if (playlist.isNotEmpty()) {
            val idx = playlist.indexOf(file)
            if (idx >= 0) playlistIndex = idx
        }
        clearLoop()
        Log.i(TAG, "Playing: ${file.name} [${playlistIndex + 1}/${playlist.size}]")
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    when (this@AudioPlayer.repeatMode) {
                        RepeatMode.ONE -> {} // ExoPlayer handles loop
                        RepeatMode.ALL -> {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { playNext() }
                        }
                        RepeatMode.OFF -> {}
                    }
                }
                // Clear seek guard when player reaches READY after a seek
                if (state == Player.STATE_READY) loopSeekPending = false
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}", error)
                val userMsg = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "Audio file not found: ${file.name}"
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "Cannot play — unsupported audio format"
                    else ->
                        "Audio error: ${error.message ?: "unknown"}"
                }
                onError?.invoke(userMsg)
            }
        }
        playerListener = listener
        player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            this.repeatMode = when (this@AudioPlayer.repeatMode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            this.volume = this@AudioPlayer.volume
            prepare()
            addListener(listener)
        }
        // Apply current speed
        player?.playbackParameters = PlaybackParameters(SPEED_OPTIONS[speedIndex])
        // Init EQ
        initEq()
        // Seed cache synchronously so readers on non-main threads see a valid
        // session id / duration / etc immediately after play() returns on main.
        val p = player
        if (p != null) {
            cachedAudioSessionId = p.audioSessionId
            cachedPositionMs = p.currentPosition
            val d = p.duration; cachedDurationMs = if (d > 0) d else 0
            cachedIsPlaying = p.isPlaying
            cachedIsPaused = !p.playWhenReady && p.playbackState == Player.STATE_READY
        }
        startCacheTicker()
    }

    /** Advance to next track in playlist. Wraps around in ALL mode. */
    fun playNext(): Boolean {
        if (playlist.isEmpty()) return false
        val nextIdx = if (playlistIndex < playlist.size - 1) playlistIndex + 1
            else if (repeatMode == RepeatMode.ALL) 0 else return false
        val file = playlist[nextIdx]
        playlistIndex = nextIdx
        play(file)
        onTrackChanged?.invoke(file)
        return true
    }

    /** Go to previous track, or restart current if past 3 seconds. */
    fun playPrevious(): Boolean {
        if (currentPositionMs > 3000) { seekTo(0); return true }
        if (playlist.isEmpty() || playlistIndex <= 0) { seekTo(0); return true }
        val file = playlist[playlistIndex - 1]
        playlistIndex -= 1
        play(file)
        onTrackChanged?.invoke(file)
        return true
    }

    fun togglePlayPause() = onMain {
        val p = player
        if (p == null) {
            // Player released (after stop) — restart current track
            val file = currentFile ?: return@onMain
            play(file)
            return@onMain
        }
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            else -> p.play()
        }
    }

    fun pause() = onMain { player?.pause() }
    fun resume() = onMain { player?.play() }

    fun stop() = onMain {
        stopCacheTicker()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        player?.stop()
        player?.release()
        player = null
    }

    fun seekTo(posMs: Long) = onMain {
        player?.seekTo(posMs.coerceIn(0, durationMs))
    }

    fun seekBy(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }

    // Volume control
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        onMain { player?.volume = volume }
    }

    // Speed control
    fun setSpeedIndex(idx: Int) {
        speedIndex = idx.coerceIn(0, SPEED_OPTIONS.size - 1)
        onMain { player?.playbackParameters = PlaybackParameters(SPEED_OPTIONS[speedIndex]) }
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
    fun clearLoop() { loopA = -1; loopB = -1; loopSeekPending = false }
    fun hasLoop(): Boolean = loopA >= 0 && loopB > loopA

    /** Call from UI-thread Handler to enforce A/B loop. */
    fun updateLoop() = onMain {
        if (hasLoop() && isPlaying && !loopSeekPending && currentPositionMs >= loopB) {
            loopSeekPending = true
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
        // ONE = ExoPlayer loops internally. ALL = we handle via STATE_ENDED callback.
        onMain {
            player?.repeatMode = when (repeatMode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
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

    fun release() = onMain {
        stopCacheTicker()
        playerListener?.let { player?.removeListener(it) }
        playerListener = null
        equalizer?.release(); equalizer = null
        player?.release(); player = null
        currentFile = null
    }
}
