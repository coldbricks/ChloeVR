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
 * Shared mutable state for color grading parameters.
 * Updated from UI thread, read by the GL shader each frame.
 * All fields are @Volatile for safe cross-thread access.
 */
class ColorGradingState {
    /** Brightness offset: -1.0 (black) to 1.0 (white). Default 0. */
    @Volatile var brightness: Float = 0f
    /** Contrast multiplier: 0.0 (flat grey) to 2.0 (extreme). Default 1.0 (neutral). */
    @Volatile var contrast: Float = 1f
    /** Saturation multiplier: 0.0 (grayscale) to 2.0 (oversaturated). Default 1.0. */
    @Volatile var saturation: Float = 1f
    /** Sharpening strength: 0.0 (off) to 1.0 (maximum). Default 0. */
    @Volatile var sharpening: Float = 0f
    /** Gamma curve: 0.2 (very bright) to 3.0 (very dark). Default 1.0 (linear). */
    @Volatile var gamma: Float = 1f
    /** Hue rotation in degrees: -180 to 180. Default 0. */
    @Volatile var hueShift: Float = 0f
    /** Tone mapping mode: 0=none, 1=Reinhard, 2=ACES. */
    @Volatile var toneMapMode: Int = 0
    /** Master enable/disable. When false, shader passes through unchanged. */
    @Volatile var enabled: Boolean = false
}

@UnstableApi
class ColorGradingEffect(
    val state: ColorGradingState
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradingShaderProgram(state)
    }
}

@UnstableApi
private class ColorGradingShaderProgram(
    private val state: ColorGradingState
) : BaseGlShaderProgram(false, 1) {

    private var glProgram: GlProgram? = null
    private val identityMatrix = GlUtil.create4x4IdentityMatrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        // Store dimensions for texel size calculation in sharpening
        cachedWidth = inputWidth
        cachedHeight = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private var cachedWidth = 1920
    private var cachedHeight = 1080

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)

        program.setFloatUniform("uBrightness", state.brightness)
        program.setFloatUniform("uContrast", state.contrast)
        program.setFloatUniform("uSaturation", state.saturation)
        program.setFloatUniform("uSharpening", state.sharpening)
        program.setFloatUniform("uGamma", state.gamma)
        program.setFloatUniform("uHueShift", state.hueShift)
        program.setFloatUniform("uToneMapMode", state.toneMapMode.toFloat())
        program.setFloatUniform("uEnabled", if (state.enabled) 1f else 0f)
        program.setFloatsUniform("uTexelSize", floatArrayOf(
            1f / cachedWidth.toFloat(),
            1f / cachedHeight.toFloat()
        ))

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

        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform float uBrightness;\n" +
                "uniform float uContrast;\n" +
                "uniform float uSaturation;\n" +
                "uniform float uSharpening;\n" +
                "uniform float uGamma;\n" +
                "uniform float uHueShift;\n" +
                "uniform float uToneMapMode;\n" +
                "uniform float uEnabled;\n" +
                "uniform vec2 uTexelSize;\n" +
                "\n" +
                // ── RGB <-> HSV conversion ──
                "vec3 rgb2hsv(vec3 c) {\n" +
                "  vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);\n" +
                "  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
                "  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
                "  float d = q.x - min(q.w, q.y);\n" +
                "  float e = 1.0e-10;\n" +
                "  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n" +
                "}\n" +
                "\n" +
                "vec3 hsv2rgb(vec3 c) {\n" +
                "  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);\n" +
                "  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n" +
                "  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n" +
                "}\n" +
                "\n" +
                // ── Tone mapping functions ──
                "vec3 reinhardToneMap(vec3 c) {\n" +
                "  return c / (c + vec3(1.0));\n" +
                "}\n" +
                "\n" +
                "vec3 acesToneMap(vec3 c) {\n" +
                "  float a = 2.51;\n" +
                "  float b = 0.03;\n" +
                "  float d = 0.59;\n" +
                "  float e = 0.14;\n" +
                "  return clamp((c * (a * c + b)) / (c * (d * c + e) + vec3(0.0001)), 0.0, 1.0);\n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "\n" +
                "  if (uEnabled < 0.5) {\n" +
                "    gl_FragColor = color;\n" +
                "    return;\n" +
                "  }\n" +
                "\n" +
                // ── Sharpening (unsharp mask) ──
                // Sample 4 neighbors, compute blurred version, subtract to sharpen
                "  if (uSharpening > 0.001) {\n" +
                "    vec3 n  = texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0,  uTexelSize.y)).rgb;\n" +
                "    vec3 s  = texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0, -uTexelSize.y)).rgb;\n" +
                "    vec3 e  = texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelSize.x,  0.0)).rgb;\n" +
                "    vec3 w  = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x,  0.0)).rgb;\n" +
                "    vec3 blurred = (n + s + e + w) * 0.25;\n" +
                "    vec3 sharp = color.rgb + (color.rgb - blurred) * uSharpening * 2.0;\n" +
                "    color.rgb = clamp(sharp, 0.0, 1.0);\n" +
                "  }\n" +
                "\n" +
                // ── Brightness ──
                "  color.rgb += uBrightness;\n" +
                "\n" +
                // ── Contrast (pivot around 0.5) ──
                "  color.rgb = (color.rgb - 0.5) * uContrast + 0.5;\n" +
                "\n" +
                // ── Saturation (luminance-preserving) ──
                "  float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));\n" +
                "  color.rgb = mix(vec3(luma), color.rgb, uSaturation);\n" +
                "\n" +
                // ── Gamma ──
                "  color.rgb = pow(max(color.rgb, 0.0), vec3(1.0 / uGamma));\n" +
                "\n" +
                // ── Hue shift ──
                "  if (abs(uHueShift) > 0.5) {\n" +
                "    vec3 hsv = rgb2hsv(color.rgb);\n" +
                "    hsv.x = fract(hsv.x + uHueShift / 360.0);\n" +
                "    color.rgb = hsv2rgb(hsv);\n" +
                "  }\n" +
                "\n" +
                // ── Tone mapping ──
                "  if (uToneMapMode > 0.5 && uToneMapMode < 1.5) {\n" +
                "    color.rgb = reinhardToneMap(color.rgb);\n" +
                "  } else if (uToneMapMode > 1.5) {\n" +
                "    color.rgb = acesToneMap(color.rgb);\n" +
                "  }\n" +
                "\n" +
                "  gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);\n" +
                "}\n"
    }
}
