package com.ashairfoil.prism.effects

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Common VR camera lens presets with Brown-Conrady distortion coefficients.
 * k1 and k2 are radial distortion coefficients; fov is the lens field of view.
 */
enum class LensPreset(
    val displayName: String,
    val k1: Float,
    val k2: Float,
    val fov: Float
) {
    NONE("None (disabled)", 0f, 0f, 180f),
    MKX200("Entaniya MKX200 (200°)", -0.20f, 0.05f, 200f),
    VRCA220("VRCA220 (220°)", -0.28f, 0.08f, 220f),
    RF52("Canon RF 5.2mm (190°)", -0.18f, 0.04f, 190f),
    STANDARD_FISHEYE("Standard Fisheye (180°)", -0.15f, 0.03f, 180f),
    WIDE_FISHEYE("Wide Fisheye (220°)", -0.30f, 0.10f, 220f),
    INSTA360_EVO("Insta360 EVO (200°)", -0.22f, 0.06f, 200f),
    KANDAO_QT("Kandao QooCam (195°)", -0.19f, 0.045f, 195f),
    VUZE_XR("Vuze XR (180°)", -0.16f, 0.035f, 180f)
}

/**
 * Shared mutable state for lens distortion correction.
 * Updated from UI thread, read by the GL shader each frame.
 */
class LensDistortionState {
    /** Brown-Conrady radial distortion coefficient k1. Typical range: -0.5 to 0.5. */
    @Volatile var k1: Float = 0f
    /** Brown-Conrady radial distortion coefficient k2. Typical range: -0.2 to 0.2. */
    @Volatile var k2: Float = 0f
    /** Lens field of view in degrees. Range: 150 to 230. */
    @Volatile var fov: Float = 180f
    /** Center X offset (0.0 to 1.0, default 0.5 = centered). */
    @Volatile var centerX: Float = 0.5f
    /** Center Y offset (0.0 to 1.0, default 0.5 = centered). */
    @Volatile var centerY: Float = 0.5f
    /** Master enable/disable. */
    @Volatile var enabled: Boolean = false

    /** Apply a preset's values. */
    fun applyPreset(preset: LensPreset) {
        k1 = preset.k1
        k2 = preset.k2
        fov = preset.fov
        centerX = 0.5f
        centerY = 0.5f
        enabled = preset != LensPreset.NONE
    }
}

@UnstableApi
class LensDistortionEffect(
    val state: LensDistortionState
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        return LensDistortionShaderProgram(state)
    }
}

@UnstableApi
private class LensDistortionShaderProgram(
    private val state: LensDistortionState
) : BaseGlShaderProgram(false, 1) {

    private var glProgram: GlProgram? = null
    private val identityMatrix = GlUtil.create4x4IdentityMatrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        cachedAspect = inputWidth.toFloat() / inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    private var cachedAspect = 16f / 9f

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)

        program.setFloatUniform("uK1", state.k1)
        program.setFloatUniform("uK2", state.k2)
        program.setFloatUniform("uFov", state.fov)
        program.setFloatsUniform("uCenter", floatArrayOf(state.centerX, state.centerY))
        program.setFloatUniform("uAspect", cachedAspect)
        program.setFloatUniform("uEnabled", if (state.enabled) 1f else 0f)

        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatsUniform("uTransformationMatrix", identityMatrix)
        program.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
        program.bindAttributesAndUniforms()

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun release() {
        try {
            glProgram?.delete()
            glProgram = null
        } finally {
            super.release()
        }
    }

    private companion object {
        private const val VERTEX_SHADER =
            "attribute vec4 aFramePosition;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "uniform mat4 uTexTransformationMatrix;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "  vec4 texCoord = uTexTransformationMatrix * vec4((aFramePosition.xy + 1.0) * 0.5, 0.0, 1.0);\n" +
                "  vTexSamplingCoord = texCoord.xy;\n" +
                "}\n"

        // Brown-Conrady radial distortion model:
        //   r_corrected = r * (1 + k1*r^2 + k2*r^4)
        // This INVERTS the lens distortion to produce a rectilinear output.
        // The FOV parameter scales the coordinate space to match the lens coverage.
        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform float uK1;\n" +
                "uniform float uK2;\n" +
                "uniform float uFov;\n" +
                "uniform vec2 uCenter;\n" +
                "uniform float uAspect;\n" +
                "uniform float uEnabled;\n" +
                "\n" +
                "void main() {\n" +
                "  if (uEnabled < 0.5) {\n" +
                "    gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "    return;\n" +
                "  }\n" +
                "\n" +
                // Shift UV to be centered on the lens optical center
                "  vec2 uv = vTexSamplingCoord - uCenter;\n" +
                "\n" +
                // Correct for aspect ratio so distortion is circular, not elliptical
                "  uv.x *= uAspect;\n" +
                "\n" +
                // Scale by FOV: wider FOV = larger coordinate range before distortion
                // Note: FOV clamped to 179 in shader. Presets with FOV > 179 (MKX200, VRCA220)
                // are effectively limited. Brown-Conrady model breaks down at ultra-wide FOV.
                "  float fovScale = tan(radians(clamp(uFov, 1.0, 179.0) * 0.5)) / tan(radians(90.0));\n" +
                "  uv /= fovScale;\n" +
                "\n" +
                // Compute radial distance from center
                "  float r2 = dot(uv, uv);\n" +
                "  float r4 = r2 * r2;\n" +
                "\n" +
                // Apply Brown-Conrady inverse distortion
                "  float distortion = 1.0 + uK1 * r2 + uK2 * r4;\n" +
                "  vec2 corrected = uv * distortion;\n" +
                "\n" +
                // Scale back and un-correct aspect ratio
                "  corrected *= fovScale;\n" +
                "  corrected.x /= uAspect;\n" +
                "  corrected += uCenter;\n" +
                "\n" +
                // Clamp to valid texture region; out-of-bounds = black
                "  if (corrected.x < 0.0 || corrected.x > 1.0 || corrected.y < 0.0 || corrected.y > 1.0) {\n" +
                "    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                "  } else {\n" +
                "    gl_FragColor = texture2D(uTexSampler, corrected);\n" +
                "  }\n" +
                "}\n"
    }
}
