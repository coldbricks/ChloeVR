package com.ashairfoil.prism.ui

import android.util.Log

/**
 * EnvironmentManager — Virtual environment settings for the VR space.
 *
 * Controls what surrounds the video screen when not in full-immersive mode:
 * - Void (pure black — default, lowest distraction)
 * - Theater (simulated movie theater with dim ambient)
 * - Living Room (warm ambient lighting, virtual furniture)
 * - Passthrough (real world visible around the screen)
 * - Custom color (user-selected ambient color)
 *
 * For non-immersive (flat screen) playback, the environment is visible
 * around the floating quad screen. For immersive (180/360), the video
 * fills the entire view and environment is not visible.
 *
 * Implementation:
 * - Void: Just set passthrough opacity to 0 (default)
 * - Passthrough: Set passthrough opacity to 1
 * - Theater/Living Room: Future — would need 3D environment models
 *   For now: use a colored ambient light via SpatialEnvironment API
 * - Custom: Set a background color via the XR environment
 */
class EnvironmentManager {

    companion object {
        private const val TAG = "EnvironmentManager"
    }

    enum class Environment(val displayName: String, val description: String) {
        VOID("Void", "Pure black — zero distraction"),
        PASSTHROUGH("Passthrough", "See the real world around the screen"),
        DARK_THEATER("Dark Theater", "Dim ambient theater lighting"),
        WARM_ROOM("Warm Room", "Warm amber ambient for relaxed viewing"),
        COOL_ROOM("Cool Room", "Cool blue ambient for focused viewing"),
        SUNRISE("Sunrise", "Warm golden hour ambient"),
        MIDNIGHT("Midnight", "Deep blue night ambient"),
        CUSTOM("Custom Color", "Your chosen ambient color"),
    }

    data class EnvironmentState(
        val environment: Environment = Environment.VOID,
        val passthroughOpacity: Float = 0f,       // 0 = opaque VR, 1 = full passthrough
        val ambientColorR: Float = 0f,             // 0-1 range
        val ambientColorG: Float = 0f,
        val ambientColorB: Float = 0f,
        val ambientIntensity: Float = 0.1f,        // 0-1 range
        val customColorR: Float = 0.5f,
        val customColorG: Float = 0.3f,
        val customColorB: Float = 0.1f,
    )

    var state: EnvironmentState = EnvironmentState()
        private set

    /**
     * Set environment. Returns the new state for the caller to apply.
     */
    fun setEnvironment(env: Environment): EnvironmentState {
        state = when (env) {
            Environment.VOID -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientIntensity = 0f
            )
            Environment.PASSTHROUGH -> EnvironmentState(
                environment = env, passthroughOpacity = 1f,
                ambientIntensity = 0f
            )
            Environment.DARK_THEATER -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = 0.02f, ambientColorG = 0.02f, ambientColorB = 0.03f,
                ambientIntensity = 0.15f
            )
            Environment.WARM_ROOM -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = 0.12f, ambientColorG = 0.06f, ambientColorB = 0.02f,
                ambientIntensity = 0.2f
            )
            Environment.COOL_ROOM -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = 0.02f, ambientColorG = 0.04f, ambientColorB = 0.1f,
                ambientIntensity = 0.15f
            )
            Environment.SUNRISE -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = 0.15f, ambientColorG = 0.08f, ambientColorB = 0.02f,
                ambientIntensity = 0.25f
            )
            Environment.MIDNIGHT -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = 0.01f, ambientColorG = 0.01f, ambientColorB = 0.06f,
                ambientIntensity = 0.1f
            )
            Environment.CUSTOM -> EnvironmentState(
                environment = env, passthroughOpacity = 0f,
                ambientColorR = state.customColorR,
                ambientColorG = state.customColorG,
                ambientColorB = state.customColorB,
                ambientIntensity = 0.2f,
                customColorR = state.customColorR,
                customColorG = state.customColorG,
                customColorB = state.customColorB,
            )
        }

        Log.i(TAG, "Environment set to ${env.displayName}")
        return state
    }

    /**
     * Set custom ambient color (for CUSTOM environment).
     */
    fun setCustomColor(r: Float, g: Float, b: Float) {
        state = state.copy(
            customColorR = r.coerceIn(0f, 1f),
            customColorG = g.coerceIn(0f, 1f),
            customColorB = b.coerceIn(0f, 1f),
        )
        if (state.environment == Environment.CUSTOM) {
            state = state.copy(
                ambientColorR = r.coerceIn(0f, 1f),
                ambientColorG = g.coerceIn(0f, 1f),
                ambientColorB = b.coerceIn(0f, 1f),
            )
        }
    }

    /**
     * Set ambient intensity (0-1).
     */
    fun setIntensity(intensity: Float) {
        state = state.copy(ambientIntensity = intensity.coerceIn(0f, 1f))
    }

    /**
     * Cycle through environments (for quick-switch button).
     */
    fun cycleEnvironment(): EnvironmentState {
        val envs = Environment.values()
        val nextIdx = (envs.indexOf(state.environment) + 1) % envs.size
        return setEnvironment(envs[nextIdx])
    }

    /**
     * Get passthrough opacity (for SurfaceEntity.preferredPassthroughOpacity).
     * Also returns 1.0 when alpha/chroma content is active.
     */
    fun getPassthroughOpacity(hasAlphaContent: Boolean = false): Float {
        if (hasAlphaContent) return 1f
        return state.passthroughOpacity
    }
}
