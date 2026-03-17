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
 * SharpeningEffect — Dedicated high-quality sharpening for VR video.
 * Implements Unsharp Mask with clarity (midtone contrast) and detail enhancement.
 */
class SharpeningState {
    @Volatile var sharpness: Float = 0f      // 0 to 2.0
    @Volatile var clarity: Float = 0f        // -1 to 1
    @Volatile var detail: Float = 0f         // 0 to 1
    @Volatile var radius: Float = 1.0f       // 0.5 to 3.0
}

@UnstableApi
class SharpeningEffect(private val state: SharpeningState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SharpeningShaderProgram(state)
    }
}

@UnstableApi
private class SharpeningShaderProgram(
    private val state: SharpeningState
) : BaseGlShaderProgram(false, 1) {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoords;
            void main() {
                gl_Position = vec4(aFramePosition.xy, 0.0, 1.0);
                vTexCoords = aFramePosition.zw;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoords;
            uniform float uSharpness;
            uniform float uClarity;
            uniform float uRadius;
            uniform vec2 uTexelSize;

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void main() {
                vec4 center = texture2D(uTexSampler, vTexCoords);
                if (uSharpness < 0.01 && abs(uClarity) < 0.01) {
                    gl_FragColor = center;
                    return;
                }
                vec2 off = uTexelSize * uRadius;
                vec4 left = texture2D(uTexSampler, vTexCoords + vec2(-off.x, 0.0));
                vec4 right = texture2D(uTexSampler, vTexCoords + vec2(off.x, 0.0));
                vec4 up = texture2D(uTexSampler, vTexCoords + vec2(0.0, off.y));
                vec4 down = texture2D(uTexSampler, vTexCoords + vec2(0.0, -off.y));
                vec4 blur = (left + right + up + down) * 0.25;
                vec3 sharpened = center.rgb + (center.rgb - blur.rgb) * uSharpness;
                if (abs(uClarity) > 0.01) {
                    float l = luma(sharpened);
                    float mask = 4.0 * l * (1.0 - l);
                    float boost = mask * uClarity * 0.5;
                    sharpened = mix(sharpened, sharpened * (1.0 + boost), mask);
                }
                gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0), center.a);
            }
        """
    }

    private var glProgram: GlProgram? = null
    private var inputWidth = 1
    private var inputHeight = 1

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatUniform("uSharpness", state.sharpness)
        program.setFloatUniform("uClarity", state.clarity)
        program.setFloatUniform("uRadius", state.radius)
        program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
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
