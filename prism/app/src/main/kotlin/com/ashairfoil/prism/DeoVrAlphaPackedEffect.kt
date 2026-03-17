package com.ashairfoil.prism

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Decodes DeoVR packed alpha videos (red-mask layout) into a true alpha channel.
 *
 * Packing reference:
 * https://forum.deovr.com/d/9993-batch-script-to-use-ffmpeg-as-alpha-packer
 */
@UnstableApi
class DeoVrAlphaPackedEffect(
    private val alphaThreshold: Float = 0.02f
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DeoVrAlphaPackedShaderProgram(alphaThreshold)
    }
}

@UnstableApi
private class DeoVrAlphaPackedShaderProgram(
    private val alphaThreshold: Float
) : BaseGlShaderProgram(
    false,
    1
) {
    private var glProgram: GlProgram? = null
    private val identityMatrix = GlUtil.create4x4IdentityMatrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatUniform("uAlphaThreshold", alphaThreshold)
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
                "  vec4 texSamplingCoord = uTexTransformationMatrix * vec4((aFramePosition.xy + 1.0) * 0.5, 0.0, 1.0);\n" +
                "  vTexSamplingCoord = texSamplingCoord.xy;\n" +
                "}\n"

        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform float uAlphaThreshold;\n" +
                "\n" +
                "vec2 packedUvFromAlphaUv(vec2 alphaUv) {\n" +
                "  float ax = alphaUv.x;\n" +
                "  float ay = alphaUv.y;\n" +
                "  if (ax < 0.2) {\n" +
                "    if (ay < 0.2) return vec2(0.4 + ax, 0.8 + ay);\n" +
                "    return vec2(0.4 + ax, ay - 0.2);\n" +
                "  }\n" +
                "  if (ay < 0.2) {\n" +
                "    if (ax < 0.3) return vec2(0.9 + (ax - 0.2), 0.8 + ay);\n" +
                "    return vec2(ax - 0.3, 0.8 + ay);\n" +
                "  }\n" +
                "  if (ax < 0.3) return vec2(0.9 + (ax - 0.2), ay - 0.2);\n" +
                "  return vec2(ax - 0.3, ay - 0.2);\n" +
                "}\n" +
                "\n" +
                "float samplePackedAlpha(vec2 uv) {\n" +
                "  vec2 alphaUv = uv * 0.4;\n" +
                "  vec2 packedUv = packedUvFromAlphaUv(alphaUv);\n" +
                "  float alpha = texture2D(uTexSampler, packedUv).r;\n" +
                "  return alpha <= uAlphaThreshold ? 0.0 : alpha;\n" +
                "}\n" +
                "\n" +
                "bool isPackedMaskPatch(vec2 uv) {\n" +
                "  bool centerBands = uv.x >= 0.4 && uv.x < 0.6 && (uv.y < 0.2 || uv.y >= 0.8);\n" +
                "  bool leftBands = uv.x < 0.1 && (uv.y < 0.2 || uv.y >= 0.8);\n" +
                "  bool rightBands = uv.x >= 0.9 && (uv.y < 0.2 || uv.y >= 0.8);\n" +
                "  return centerBands || leftBands || rightBands;\n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "  float alphaNormalY = samplePackedAlpha(vTexSamplingCoord);\n" +
                "  float alphaFlippedY = samplePackedAlpha(vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y));\n" +
                "  float alpha = max(alphaNormalY, alphaFlippedY);\n" +
                "\n" +
                "  if (isPackedMaskPatch(vTexSamplingCoord)) {\n" +
                "    alpha = 0.0;\n" +
                "    color.rgb = vec3(0.0);\n" +
                "  }\n" +
                "\n" +
                "  gl_FragColor = vec4(color.rgb, alpha);\n" +
                "}\n"
    }
}
