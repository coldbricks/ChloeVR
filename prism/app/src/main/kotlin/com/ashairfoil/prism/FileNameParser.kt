package com.ashairfoil.prism

import java.io.File

enum class StereoMode { MONO, SIDE_BY_SIDE, TOP_BOTTOM }

data class VideoMetadata(
    val file: File,
    val stereoMode: StereoMode,
    val hasAlpha: Boolean,
    val hasSpatialAudio: Boolean
)

object FileNameParser {

    private val SBS_PATTERN = Regex("""[_.\-](SBS|LR|3DH)[_.\-]""", RegexOption.IGNORE_CASE)
    private val TB_PATTERN = Regex("""[_.\-](TB|3DV|OverUnder)[_.\-]""", RegexOption.IGNORE_CASE)
    private val ALPHA_PATTERN = Regex("""_ALPHA""", RegexOption.IGNORE_CASE)
    private val FB360_PATTERN = Regex("""_FB360""", RegexOption.IGNORE_CASE)

    fun parse(file: File): VideoMetadata {
        val name = "_${file.nameWithoutExtension}_" // pad so delimiters always match
        val stereoMode = when {
            SBS_PATTERN.containsMatchIn(name) -> StereoMode.SIDE_BY_SIDE
            TB_PATTERN.containsMatchIn(name) -> StereoMode.TOP_BOTTOM
            else -> StereoMode.MONO
        }
        return VideoMetadata(
            file = file,
            stereoMode = stereoMode,
            hasAlpha = ALPHA_PATTERN.containsMatchIn(name),
            hasSpatialAudio = FB360_PATTERN.containsMatchIn(name)
        )
    }
}
