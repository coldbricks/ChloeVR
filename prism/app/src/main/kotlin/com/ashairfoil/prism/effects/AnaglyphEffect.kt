package com.ashairfoil.prism.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
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
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoords;
            varying vec2 vTexCoords;
            void main() {
                gl_Position = aPosition;
                vTexCoords = aTexCoords;
            }
        """
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

    private val glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setIntUniform("uEnabled", if (state.enabled) 1 else 0)
        glProgram.setIntUniform("uStereoLayout", state.stereoLayout)
        glProgram.setIntUniform("uSwapEyes", if (state.swapEyes) 1 else 0)
        glProgram.setFloatUniform("uGhosting", state.ghosting)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        glProgram.delete()
    }
}
