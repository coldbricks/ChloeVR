package com.ashairfoil.prism

import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Shared mutable state for chroma key parameters.
 * Updated from UI, read by the shader each frame.
 */
class ChromaKeyState {
    @Volatile var keyR = 0f
    @Volatile var keyG = 1f
    @Volatile var keyB = 0f
    @Volatile var tolerance = 0.30f
    @Volatile var softness = 0.10f
    @Volatile var enabled = false
}

@UnstableApi
class ChromaKeyEffect(
    val state: ChromaKeyState
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        return ChromaKeyShaderProgram(state)
    }
}

@UnstableApi
private class ChromaKeyShaderProgram(
    private val state: ChromaKeyState
) : BaseGlShaderProgram(false, 1) {

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
        program.setFloatsUniform("uKeyColor", floatArrayOf(state.keyR, state.keyG, state.keyB))
        program.setFloatUniform("uTolerance", state.tolerance)
        program.setFloatUniform("uSoftness", state.softness)
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

        // Chroma key in YCbCr-ish space for better green/blue screen handling
        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform vec3 uKeyColor;\n" +
                "uniform float uTolerance;\n" +
                "uniform float uSoftness;\n" +
                "uniform float uEnabled;\n" +
                "\n" +
                "void main() {\n" +
                "  vec4 color = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "  if (uEnabled < 0.5) {\n" +
                "    gl_FragColor = color;\n" +
                "    return;\n" +
                "  }\n" +
                // Use chroma distance (Cb/Cr difference) for better keying
                "  float yPix  = 0.299 * color.r + 0.587 * color.g + 0.114 * color.b;\n" +
                "  float cbPix = 0.5 * (color.b - yPix) / (1.0 - 0.114);\n" +
                "  float crPix = 0.5 * (color.r - yPix) / (1.0 - 0.299);\n" +
                "  float yKey  = 0.299 * uKeyColor.r + 0.587 * uKeyColor.g + 0.114 * uKeyColor.b;\n" +
                "  float cbKey = 0.5 * (uKeyColor.b - yKey) / (1.0 - 0.114);\n" +
                "  float crKey = 0.5 * (uKeyColor.r - yKey) / (1.0 - 0.299);\n" +
                "  float dist = distance(vec2(cbPix, crPix), vec2(cbKey, crKey));\n" +
                "  float alpha = smoothstep(uTolerance, uTolerance + uSoftness, dist);\n" +
                "  gl_FragColor = vec4(color.rgb, color.a * alpha);\n" +
                "}\n"
    }
}
