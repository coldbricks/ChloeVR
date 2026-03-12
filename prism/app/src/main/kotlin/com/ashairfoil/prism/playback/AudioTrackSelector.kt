package com.ashairfoil.prism.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/**
 * AudioTrackSelector — Multi-audio-track selection for VR video.
 *
 * Many VR videos (especially those from professional studios) have multiple
 * audio tracks: commentary, music-only, spatial audio variants, different
 * languages, or director's cut audio.
 *
 * This provides a clean interface over ExoPlayer's track selection API.
 *
 * Usage:
 *   val selector = AudioTrackSelector()
 *   selector.inspect(player)  // Discover available tracks
 *   val tracks = selector.availableTracks  // Show in UI
 *   selector.selectTrack(player, trackIndex)  // Switch track
 */
class AudioTrackSelector {

    data class AudioTrack(
        val index: Int,
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,        // User-friendly label
        val language: String,     // ISO 639 code
        val codec: String,        // e.g., "AAC", "Opus", "AC-3"
        val channels: Int,        // Number of channels
        val sampleRate: Int,      // Hz
        val bitrate: Int,         // bps
        val isSelected: Boolean,
    ) {
        val channelLayout: String get() = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1 Surround"
            8 -> "7.1 Surround"
            else -> "${channels}ch"
        }

        val displayLabel: String get() {
            val parts = mutableListOf<String>()
            if (label.isNotBlank()) parts.add(label)
            if (language.isNotBlank() && language != "und") {
                val locale = Locale(language)
                parts.add(locale.displayLanguage)
            }
            parts.add(channelLayout)
            if (codec.isNotBlank()) parts.add(codec)
            if (bitrate > 0) parts.add("${bitrate / 1000}kbps")
            return parts.joinToString(" · ")
        }
    }

    var availableTracks: List<AudioTrack> = emptyList()
        private set

    var selectedIndex: Int = -1
        private set

    /**
     * Inspect the player's current media to discover audio tracks.
     * Call after the player has prepared the media item.
     */
    fun inspect(player: ExoPlayer): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        var trackCounter = 0

        val trackGroups = player.currentTracks
        for (group in trackGroups.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue

            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)

                tracks.add(AudioTrack(
                    index = trackCounter,
                    groupIndex = trackGroups.groups.indexOf(group),
                    trackIndex = i,
                    label = format.label ?: "",
                    language = format.language ?: "und",
                    codec = codecName(format),
                    channels = format.channelCount,
                    sampleRate = format.sampleRate,
                    bitrate = format.bitrate,
                    isSelected = isSelected,
                ))

                if (isSelected) selectedIndex = trackCounter
                trackCounter++
            }
        }

        availableTracks = tracks
        Log.i("AudioTrackSelector", "Found ${tracks.size} audio tracks, selected: $selectedIndex")
        return tracks
    }

    /**
     * Select a specific audio track by index.
     */
    fun selectTrack(player: ExoPlayer, index: Int) {
        val track = availableTracks.getOrNull(index) ?: return

        val trackGroups = player.currentTracks
        val groups = trackGroups.groups.toList()
        if (track.groupIndex >= groups.size) return

        val group = groups[track.groupIndex]
        val override = TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(override)
            .build()

        selectedIndex = index
        Log.i("AudioTrackSelector", "Selected audio track $index: ${track.displayLabel}")
    }

    /**
     * Reset to default (auto) track selection.
     */
    fun resetToAuto(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        selectedIndex = -1
    }

    fun hasMultipleTracks(): Boolean = availableTracks.size > 1

    private fun codecName(format: Format): String {
        val mime = format.sampleMimeType ?: return ""
        return when {
            mime.contains("aac") -> "AAC"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") -> "Vorbis"
            mime.contains("ac3") || mime.contains("eac3") -> "AC-3"
            mime.contains("flac") -> "FLAC"
            mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
            mime.contains("pcm") || mime.contains("wav") -> "PCM"
            mime.contains("dts") -> "DTS"
            else -> mime.substringAfter("audio/")
        }
    }
}
