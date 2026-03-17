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
 * Shared mutable state for IPD and stereo alignment correction.
 * Updated from UI thread, read by the GL shader each frame.
 *
 * How it works for SBS (side-by-side) stereo content:
 * - The left half of the texture is the left eye, right half is the right eye.
 * - horizontalOffset shifts the left eye UVs to the left and right eye UVs to the right
 *   (positive = increase IPD / eyes further apart; negative = decrease).
 * - verticalOffset shifts one eye up and the other down to correct camera misalignment.
 *
 * For top-bottom stereo, the same logic applies but on the vertical axis halves.
 * The stereoLayout field tells the shader which layout is in use.
 */
class StereoAdjustmentState {
    /** Horizontal IPD offset in UV units. Range: -0.05 to 0.05. Positive = increase separation. */
    @Volatile var horizontalOffset: Float = 0f
    /** Vertical alignment offset in UV units. Range: -0.05 to 0.05. Positive = shift right/bottom eye down. */
    @Volatile var verticalOffset: Float = 0f
    /** Stereo layout: 0 = side-by-side, 1 = top-bottom. */
    @Volatile var stereoLayout: Int = 0
    /** Master enable/disable. */
    @Volatile var enabled: Boolean = false
}

@UnstableApi
class StereoAdjustmentEffect(
    val state: StereoAdjustmentState
) : GlEffect {

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        return StereoAdjustmentShaderProgram(state)
    }
}

@UnstableApi
private class StereoAdjustmentShaderProgram(
    private val state: StereoAdjustmentState
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

        program.setFloatUniform("uHorizontalOffset", state.horizontalOffset)
        program.setFloatUniform("uVerticalOffset", state.verticalOffset)
        program.setFloatUniform("uStereoLayout", state.stereoLayout.toFloat())
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

        // IPD and stereo alignment correction.
        //
        // Side-by-side layout: left eye occupies x=[0, 0.5], right eye occupies x=[0.5, 1.0].
        // Top-bottom layout: top eye occupies y=[0.5, 1.0], bottom eye occupies y=[0, 0.5].
        //
        // For SBS:
        //   Left eye:  shift UV.x by -horizontalOffset, UV.y by -verticalOffset
        //   Right eye: shift UV.x by +horizontalOffset, UV.y by +verticalOffset
        //
        // For TB:
        //   Top eye:   shift UV.x by -horizontalOffset, UV.y by +verticalOffset
        //   Bottom eye: shift UV.x by +horizontalOffset, UV.y by -verticalOffset
        //
        // Out-of-bounds sampling clamps to the eye's own half (no bleed into other eye).
        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "varying vec2 vTexSamplingCoord;\n" +
                "uniform sampler2D uTexSampler;\n" +
                "uniform float uHorizontalOffset;\n" +
                "uniform float uVerticalOffset;\n" +
                "uniform float uStereoLayout;\n" +
                "uniform float uEnabled;\n" +
                "\n" +
                "void main() {\n" +
                "  if (uEnabled < 0.5) {\n" +
                "    gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);\n" +
                "    return;\n" +
                "  }\n" +
                "\n" +
                "  vec2 uv = vTexSamplingCoord;\n" +
                "\n" +
                // Side-by-side stereo layout
                "  if (uStereoLayout < 0.5) {\n" +
                "    bool isLeftEye = uv.x < 0.5;\n" +
                "    if (isLeftEye) {\n" +
                "      uv.x -= uHorizontalOffset;\n" +
                "      uv.y -= uVerticalOffset;\n" +
                // Clamp to left half [0, 0.5]
                "      uv.x = clamp(uv.x, 0.0, 0.5);\n" +
                "    } else {\n" +
                "      uv.x += uHorizontalOffset;\n" +
                "      uv.y += uVerticalOffset;\n" +
                // Clamp to right half [0.5, 1.0]
                "      uv.x = clamp(uv.x, 0.5, 1.0);\n" +
                "    }\n" +
                "    uv.y = clamp(uv.y, 0.0, 1.0);\n" +
                "  }\n" +
                // Top-bottom stereo layout
                "  else {\n" +
                "    bool isTopEye = uv.y > 0.5;\n" +
                "    if (isTopEye) {\n" +
                "      uv.x -= uHorizontalOffset;\n" +
                "      uv.y += uVerticalOffset;\n" +
                // Clamp to top half [0.5, 1.0]
                "      uv.y = clamp(uv.y, 0.5, 1.0);\n" +
                "    } else {\n" +
                "      uv.x += uHorizontalOffset;\n" +
                "      uv.y -= uVerticalOffset;\n" +
                // Clamp to bottom half [0, 0.5]
                "      uv.y = clamp(uv.y, 0.0, 0.5);\n" +
                "    }\n" +
                "    uv.x = clamp(uv.x, 0.0, 1.0);\n" +
                "  }\n" +
                "\n" +
                "  gl_FragColor = texture2D(uTexSampler, uv);\n" +
                "}\n"
    }
}
