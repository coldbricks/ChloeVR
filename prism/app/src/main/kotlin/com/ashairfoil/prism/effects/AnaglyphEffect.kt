package com.ashairfoil.prism.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.BaseGlShaderProgram

/**
 * AnaglyphEffect — Red/Cyan anaglyph 3D view for stereo alignment verification.
 */
class AnaglyphState {
    @Volatile var enabled: Boolean = false
    @Volatile var stereoLayout: Int = 0  // 0 = SBS, 1 = TB
    @Volatile var swapEyes: Boolean = false
    @Volatile var ghosting: Float = 0.0f  // 0-1
}

@UnstableApi
class AnaglyphEffect(private val state: AnaglyphState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AnaglyphShaderProgram(state)
    }
}

@UnstableApi
private class AnaglyphShaderProgram(
    private val state: AnaglyphState
) : BaseGlShaderProgram(false, 1) {

    companion object {
        private const val VERTEX_SHADER =
            "attribute vec4 aFramePosition;\n" +
                "uniform mat4 uTransformationMatrix;\n" +
                "uniform mat4 uTexTransformationMatrix;\n" +
                "varying vec2 vTexCoords;\n" +
                "void main() {\n" +
                "  gl_Position = uTransformationMatrix * aFramePosition;\n" +
                "  vec4 texCoord = uTexTransformationMatrix * vec4((aFramePosition.xy + 1.0) * 0.5, 0.0, 1.0);\n" +
                "  vTexCoords = texCoord.xy;\n" +
                "}\n"
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoords;
            uniform int uEnabled;
            uniform int uStereoLayout;
            uniform int uSwapEyes;
            uniform float uGhosting;

            void main() {
                if (uEnabled == 0) {
                    gl_FragColor = texture2D(uTexSampler, vTexCoords);
                    return;
                }
                vec2 leftUV, rightUV;
                if (uStereoLayout == 0) {
                    leftUV = vec2(vTexCoords.x * 0.5, vTexCoords.y);
                    rightUV = vec2(vTexCoords.x * 0.5 + 0.5, vTexCoords.y);
                } else {
                    leftUV = vec2(vTexCoords.x, vTexCoords.y * 0.5 + 0.5);
                    rightUV = vec2(vTexCoords.x, vTexCoords.y * 0.5);
                }
                if (uSwapEyes == 1) {
                    vec2 temp = leftUV; leftUV = rightUV; rightUV = temp;
                }
                vec4 l = texture2D(uTexSampler, leftUV);
                vec4 r = texture2D(uTexSampler, rightUV);
                float red = l.r * (1.0 - uGhosting) + r.r * uGhosting;
                float grn = r.g * (1.0 - uGhosting) + l.g * uGhosting;
                float blu = r.b * (1.0 - uGhosting) + l.b * uGhosting;
                gl_FragColor = vec4(red, grn, blu, 1.0);
            }
        """
    }

    private var glProgram: GlProgram? = null
    private val identityMatrix = GlUtil.create4x4IdentityMatrix()

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setIntUniform("uEnabled", if (state.enabled) 1 else 0)
        program.setIntUniform("uStereoLayout", state.stereoLayout)
        program.setIntUniform("uSwapEyes", if (state.swapEyes) 1 else 0)
        program.setFloatUniform("uGhosting", state.ghosting)
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatsUniform("uTransformationMatrix", identityMatrix)
        program.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        try {
            glProgram?.delete()
            glProgram = null
        } finally {
            super.release()
        }
    }
}
