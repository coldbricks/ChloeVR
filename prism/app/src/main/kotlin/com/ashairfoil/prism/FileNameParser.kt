package com.ashairfoil.prism

import java.io.File

enum class StereoMode { MONO, SIDE_BY_SIDE, TOP_BOTTOM }

enum class ScreenType {
    FLAT, DOME_180, SPHERE_360, FISHEYE, MKX200, RF52, VRCA220;

    val displayName: String get() = when (this) {
        FLAT -> "Flat"
        DOME_180 -> "180°"
        SPHERE_360 -> "360°"
        FISHEYE -> "Fisheye"
        MKX200 -> "MKX200"
        RF52 -> "RF52"
        VRCA220 -> "VRCA220"
    }
}

data class VideoMetadata(
    val file: File,
    val stereoMode: StereoMode,
    val screenType: ScreenType,
    val hasAlpha: Boolean,
    val hasSpatialAudio: Boolean
)

object FileNameParser {

    private val SBS_PATTERN = Regex("""[_.\-](SBS|LR|3DH)[_.\-]""", RegexOption.IGNORE_CASE)
    private val TB_PATTERN = Regex("""[_.\-](TB|3DV|OverUnder)[_.\-]""", RegexOption.IGNORE_CASE)
    private val ALPHA_PATTERN = Regex("""_ALPHA""", RegexOption.IGNORE_CASE)
    private val FB360_PATTERN = Regex("""[_\-]FB360""", RegexOption.IGNORE_CASE)

    // Projection patterns — more specific first
    private val VRCA220_PATTERN = Regex("""_vrca220""", RegexOption.IGNORE_CASE)
    private val MKX200_PATTERN = Regex("""_mkx200""", RegexOption.IGNORE_CASE)
    private val FISHEYE190_PATTERN = Regex("""_fisheye190""", RegexOption.IGNORE_CASE)
    private val FISHEYE_PATTERN = Regex("""_fisheye""", RegexOption.IGNORE_CASE)
    private val P360_PATTERN = Regex("""_360""", RegexOption.IGNORE_CASE)
    private val P180_PATTERN = Regex("""_180""", RegexOption.IGNORE_CASE)

    fun parse(file: File): VideoMetadata {
        val name = "_${file.nameWithoutExtension}_"

        val stereoMode = when {
            SBS_PATTERN.containsMatchIn(name) -> StereoMode.SIDE_BY_SIDE
            TB_PATTERN.containsMatchIn(name) -> StereoMode.TOP_BOTTOM
            else -> StereoMode.MONO
        }

        val screenType = when {
            VRCA220_PATTERN.containsMatchIn(name) -> ScreenType.VRCA220
            MKX200_PATTERN.containsMatchIn(name) -> ScreenType.MKX200
            FISHEYE190_PATTERN.containsMatchIn(name) -> ScreenType.RF52
            FISHEYE_PATTERN.containsMatchIn(name) -> ScreenType.FISHEYE
            P360_PATTERN.containsMatchIn(name) -> ScreenType.SPHERE_360
            P180_PATTERN.containsMatchIn(name) -> ScreenType.DOME_180
            // Stereo content without projection flag → assume 180°
            else -> if (stereoMode != StereoMode.MONO) ScreenType.DOME_180 else ScreenType.FLAT
        }

        return VideoMetadata(
            file = file,
            stereoMode = stereoMode,
            screenType = screenType,
            hasAlpha = ALPHA_PATTERN.containsMatchIn(name),
            hasSpatialAudio = FB360_PATTERN.containsMatchIn(name)
        )
    }
}
