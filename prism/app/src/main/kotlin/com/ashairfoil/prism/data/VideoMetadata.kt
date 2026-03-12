package com.ashairfoil.prism.data

/**
 * VideoMetadata — Per-video persistent settings and state.
 *
 * Every video can have its own saved adjustments (zoom, tilt, height, rotation,
 * color grading, IPD offset, lens correction). These persist across sessions
 * and are automatically applied when the video is played again.
 *
 * This matches HereSphere's keyframed settings concept, but simplified to
 * per-video rather than per-timestamp (keyframes are Phase 3).
 */
data class PerVideoSettings(
    // Screen position/orientation
    val zoom: Float = 1.0f,
    val tilt: Float = 0.0f,         // degrees
    val height: Float = 0.0f,       // offset in meters
    val yaw: Float = 0.0f,          // rotation degrees
    val roll: Float = 0.0f,         // roll degrees

    // Projection overrides (null = use auto-detected)
    val projectionOverride: String? = null,
    val stereoModeOverride: String? = null,
    val fovOverride: Float? = null,

    // Color grading
    val brightness: Float = 0.0f,   // -1 to 1
    val contrast: Float = 1.0f,     // 0 to 2
    val saturation: Float = 1.0f,   // 0 to 2
    val sharpening: Float = 0.0f,   // 0 to 1
    val gamma: Float = 1.0f,        // 0.2 to 3
    val hueShift: Float = 0.0f,     // -180 to 180

    // IPD / stereo
    val ipdOffset: Float = 0.0f,    // horizontal stereo offset
    val verticalOffset: Float = 0.0f, // vertical stereo alignment

    // Lens distortion
    val lensPreset: String = "none",
    val lensK1: Float = 0.0f,
    val lensK2: Float = 0.0f,
    val lensCenterX: Float = 0.0f,
    val lensCenterY: Float = 0.0f,

    // Chroma key
    val chromaKeyEnabled: Boolean = false,
    val chromaKeyColor: Int = 0xFF00FF00.toInt(), // green
    val chromaKeyTolerance: Float = 0.3f,
    val chromaKeySoftness: Float = 0.1f,

    // Playback
    val lastSpeed: Float = 1.0f,
    val abRepeatStart: Long = -1,
    val abRepeatEnd: Long = -1,

    // 6DOF
    val depth6dofX: Float = 0.2f,
    val depth6dofY: Float = 0.1f,
    val depth6dofZ: Float = 0.2f,

    // Spatial audio
    val spatialAudioEnabled: Boolean = false,
    val spatialAudioStrength: Float = 0.7f,
) {
    companion object {
        val DEFAULT = PerVideoSettings()
    }
}

/**
 * Lens distortion presets for common VR cameras.
 * k1 and k2 are Brown-Conrady radial distortion coefficients.
 * fov is the nominal field of view in degrees.
 */
data class LensPreset(
    val name: String,
    val displayName: String,
    val k1: Float,
    val k2: Float,
    val fov: Float,
    val description: String
)

val LENS_PRESETS = listOf(
    LensPreset("none", "No Correction", 0f, 0f, 180f, "Standard equirectangular, no distortion correction"),
    LensPreset("mkx200", "iZugar MKX200", -0.18f, 0.04f, 200f, "iZugar MKX200 lens (200° FOV)"),
    LensPreset("mkx220", "iZugar MKX220", -0.22f, 0.06f, 220f, "iZugar MKX220 lens (220° FOV)"),
    LensPreset("vrca220", "VRCA220", -0.20f, 0.05f, 220f, "VRCA 220° lens"),
    LensPreset("rf52", "Canon RF 5.2mm", -0.12f, 0.02f, 190f, "Canon RF 5.2mm dual fisheye"),
    LensPreset("fisheye180", "Standard Fisheye 180°", -0.10f, 0.01f, 180f, "Generic 180° fisheye"),
    LensPreset("fisheye190", "Standard Fisheye 190°", -0.14f, 0.03f, 190f, "Generic 190° fisheye"),
    LensPreset("insta360", "Insta360 EVO", -0.15f, 0.03f, 200f, "Insta360 EVO fisheye"),
    LensPreset("vuze", "Vuze XR", -0.16f, 0.04f, 180f, "Vuze XR dual lens"),
    LensPreset("qoocam", "QooCam 8K", -0.13f, 0.02f, 200f, "Kandao QooCam 8K lens"),
    LensPreset("z_cam", "Z CAM K2 Pro", -0.11f, 0.02f, 190f, "Z CAM K2 Pro VR180"),
)
