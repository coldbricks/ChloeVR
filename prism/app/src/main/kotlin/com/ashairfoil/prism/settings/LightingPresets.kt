package com.ashairfoil.prism.settings

import com.ashairfoil.prism.GlesModelRenderer

/**
 * Lighting preset system for ChloeVR 3D model viewer.
 * Owns data model, built-in presets, persistence, and apply logic.
 *
 * Persistence follows the SettingsManager ColorGradingPreset pattern:
 * pipe-separated fields, ";;" between presets.
 */
object LightingPresets {

    data class LightingPreset(
        val name: String,
        val lightIntensity: Float,
        val fillLightIntensity: Float,
        val ambientIntensity: Float,
        val lightAngleDeg: Float,
        val lightElevDeg: Float,
        val shadowDarkness: Float,
        val shadowSoftness: Float,
        val shadowSpread: Float,
        val autoAmbient: Boolean,
        val isBuiltIn: Boolean = false
    )

    private const val KEY_LIGHTING_PRESETS = "lighting_presets"
    private const val KEY_DEFAULT_LIGHTING_PRESET = "default_lighting_preset"

    fun builtInPresets(): List<LightingPreset> = listOf(
        LightingPreset("Default", 2.0f, 0.5f, 1.0f, 0f, 60f, 0.7f, 2.0f, 8.0f, true, true),
        LightingPreset("Even", 0.3f, 0.3f, 3.0f, 0f, 90f, 0.0f, 2.0f, 8.0f, false, true),
        LightingPreset("Studio", 3.0f, 1.0f, 0.5f, 315f, 45f, 0.8f, 1.5f, 6.0f, false, true),
        LightingPreset("Dramatic", 4.0f, 0.1f, 0.2f, 270f, 30f, 0.9f, 1.0f, 10.0f, false, true),
        LightingPreset("Sunset", 2.5f, 0.8f, 0.6f, 250f, 15f, 0.6f, 3.0f, 12.0f, false, true),
        LightingPreset("Overcast", 0.5f, 0.5f, 2.5f, 0f, 80f, 0.2f, 4.0f, 15.0f, false, true)
    )

    fun getUserPresets(): List<LightingPreset> {
        val raw = SettingsManager.getString(KEY_LIGHTING_PRESETS, "")
        if (raw.isBlank()) return emptyList()
        return raw.split(";;").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size >= 10) {
                LightingPreset(
                    name = p[0],
                    lightIntensity = p[1].toFloatOrNull() ?: 2.0f,
                    fillLightIntensity = p[2].toFloatOrNull() ?: 0.5f,
                    ambientIntensity = p[3].toFloatOrNull() ?: 1.0f,
                    lightAngleDeg = p[4].toFloatOrNull() ?: 0f,
                    lightElevDeg = p[5].toFloatOrNull() ?: 60f,
                    shadowDarkness = p[6].toFloatOrNull() ?: 0.7f,
                    shadowSoftness = p[7].toFloatOrNull() ?: 2.0f,
                    shadowSpread = p[8].toFloatOrNull() ?: 8.0f,
                    autoAmbient = p[9].toBooleanStrictOrNull() ?: true,
                    isBuiltIn = false
                )
            } else null
        }
    }

    fun getAllPresets(): List<LightingPreset> =
        builtInPresets() + getUserPresets()

    fun saveCustomPreset(preset: LightingPreset) {
        val list = getUserPresets().toMutableList()
        val idx = list.indexOfFirst { it.name == preset.name }
        if (idx >= 0) list[idx] = preset else list.add(preset)
        SettingsManager.putString(KEY_LIGHTING_PRESETS, serializeList(list))
    }

    fun deleteCustomPreset(name: String) {
        val list = getUserPresets().filterNot { it.name == name }
        SettingsManager.putString(KEY_LIGHTING_PRESETS, serializeList(list))
    }

    private fun serializeList(list: List<LightingPreset>): String =
        list.joinToString(";;") { p ->
            val safeName = p.name.replace('|', '-')
            "$safeName|${p.lightIntensity}|${p.fillLightIntensity}|${p.ambientIntensity}" +
                "|${p.lightAngleDeg}|${p.lightElevDeg}|${p.shadowDarkness}" +
                "|${p.shadowSoftness}|${p.shadowSpread}|${p.autoAmbient}"
        }

    fun getDefaultPresetName(): String? {
        val name = SettingsManager.getString(KEY_DEFAULT_LIGHTING_PRESET, "")
        return if (name.isBlank()) null else name
    }

    fun setDefaultPresetName(name: String) {
        SettingsManager.putString(KEY_DEFAULT_LIGHTING_PRESET, name)
    }

    fun captureCurrentLighting(
        name: String,
        renderer: GlesModelRenderer,
        autoAmbient: Boolean
    ): LightingPreset = LightingPreset(
        name = name,
        lightIntensity = renderer.lightIntensity,
        fillLightIntensity = renderer.fillLightIntensity,
        ambientIntensity = renderer.ambientIntensity,
        lightAngleDeg = renderer.lightAngleDeg,
        lightElevDeg = renderer.lightElevDeg,
        shadowDarkness = renderer.shadowDarkness,
        shadowSoftness = renderer.shadowSoftness,
        shadowSpread = renderer.shadowSpread,
        autoAmbient = autoAmbient,
        isBuiltIn = false
    )

    fun applyPreset(
        preset: LightingPreset,
        renderer: GlesModelRenderer,
        setAutoAmbient: (Boolean) -> Unit
    ) {
        renderer.lightIntensity = preset.lightIntensity
        renderer.fillLightIntensity = preset.fillLightIntensity
        renderer.ambientIntensity = preset.ambientIntensity
        renderer.lightAngleDeg = preset.lightAngleDeg
        renderer.lightElevDeg = preset.lightElevDeg
        renderer.shadowDarkness = preset.shadowDarkness
        renderer.shadowSoftness = preset.shadowSoftness
        renderer.shadowSpread = preset.shadowSpread
        renderer.updateLightDirFromAngles()
        setAutoAmbient(preset.autoAmbient)
    }
}
