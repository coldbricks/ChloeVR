package com.ashairfoil.prism.effects

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * AnaglyphEffect — Red/Cyan anaglyph 3D view for stereo alignment verification.
 *
 * HereSphere uses this to let users verify stereo camera alignment.
 * In anaglyph mode, the left eye is shown in red and the right eye in cyan.
 * If the stereo alignment is correct, the image should look 3D through
 * red/cyan glasses, and objects at the convergence plane should overlap perfectly.
 *
 * This effect takes an SBS or TB stereo frame and composites it as anaglyph.
 * When the stereo alignment is wrong, you'll see red/cyan fringing — the
 * StereoAdjustmentEffect can then be used to correct the offset.
 *
 * Also useful for:
 * - Verifying that left/right eyes aren't swapped
 * - Checking convergence distance
 * - Quick 3D preview without a headset (with anaglyph glasses)
 */
@UnstableApi
class AnaglyphEffect(private val state: AnaglyphState) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AnaglyphShaderProgram(state)
    }
}

class AnaglyphState {
    @Volatile var enabled: Boolean = false
    @Volatile var stereoLayout: Int = 0  // 0 = SBS, 1 = TB
    @Volatile var swapEyes: Boolean = false  // Swap left/right
    @Volatile var ghosting: Float = 0.0f  // 0-1, amount of opposite eye bleed-through
}

@UnstableApi
class AnaglyphShaderProgram(
    private val state: AnaglyphState
) : BaseGlShaderProgram(false, 1) {

    companion object {
        private const val FRAGMENT_SHADER = """
#version 300 es
precision mediump float;

uniform sampler2D uTexSampler0;
in vec2 vTexCoords;
out vec4 fragColor;

uniform int uEnabled;
uniform int uStereoLayout;  // 0 = SBS, 1 = TB
uniform int uSwapEyes;
uniform float uGhosting;

void main() {
    if (uEnabled == 0) {
        fragColor = texture(uTexSampler0, vTexCoords);
        return;
    }

    vec2 leftUV, rightUV;

    if (uStereoLayout == 0) {
        // Side-by-side: left half = left eye, right half = right eye
        leftUV = vec2(vTexCoords.x * 0.5, vTexCoords.y);
        rightUV = vec2(vTexCoords.x * 0.5 + 0.5, vTexCoords.y);
    } else {
        // Top-bottom: top half = left eye, bottom half = right eye
        leftUV = vec2(vTexCoords.x, vTexCoords.y * 0.5 + 0.5);
        rightUV = vec2(vTexCoords.x, vTexCoords.y * 0.5);
    }

    // Swap eyes if requested
    if (uSwapEyes == 1) {
        vec2 temp = leftUV;
        leftUV = rightUV;
        rightUV = temp;
    }

    vec4 leftColor = texture(uTexSampler0, leftUV);
    vec4 rightColor = texture(uTexSampler0, rightUV);

    // Anaglyph composite: Red channel from left eye, Green+Blue from right eye
    // With optional ghosting (bleed-through) for comfort
    float r = leftColor.r * (1.0 - uGhosting) + rightColor.r * uGhosting;
    float g = rightColor.g * (1.0 - uGhosting) + leftColor.g * uGhosting;
    float b = rightColor.b * (1.0 - uGhosting) + leftColor.b * uGhosting;

    fragColor = vec4(r, g, b, 1.0);
}
"""
    }

    private var enabledLoc = -1
    private var layoutLoc = -1
    private var swapLoc = -1
    private var ghostLoc = -1

    override fun configure(inputWidth: Int, inputHeight: Int): android.util.Pair<Int, Int> {
        enabledLoc = android.opengl.GLES20.glGetUniformLocation(glProgram, "uEnabled")
        layoutLoc = android.opengl.GLES20.glGetUniformLocation(glProgram, "uStereoLayout")
        swapLoc = android.opengl.GLES20.glGetUniformLocation(glProgram, "uSwapEyes")
        ghostLoc = android.opengl.GLES20.glGetUniformLocation(glProgram, "uGhosting")
        return android.util.Pair(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        super.drawFrame(inputTexId, presentationTimeUs)
        android.opengl.GLES20.glUniform1i(enabledLoc, if (state.enabled) 1 else 0)
        android.opengl.GLES20.glUniform1i(layoutLoc, state.stereoLayout)
        android.opengl.GLES20.glUniform1i(swapLoc, if (state.swapEyes) 1 else 0)
        android.opengl.GLES20.glUniform1f(ghostLoc, state.ghosting)
    }
}
