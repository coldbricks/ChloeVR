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
 * No-op GL effect that forces ExoPlayer through the DefaultVideoFrameProcessor
 * GL pipeline. On Galaxy XR, MediaCodec can't output directly to SurfaceEntity
 * surfaces — routing through GL fixes this.
 */
@UnstableApi
class PassthroughEffect : GlEffect {
    override fun toGlShaderProgram(context: android.content.Context, useHdr: Boolean): GlShaderProgram {
        return PassthroughShaderProgram(useHdr)
    }
}

@UnstableApi
private class PassthroughShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private var glProgram: GlProgram? = null

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (glProgram == null) {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = glProgram ?: return
        program.use()
        program.setSamplerTexIdUniform("uTex", inputTexId, 0)
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

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTexCoord);
            }
        """
    }
}
