package com.ashairfoil.prism.scene

import android.content.Context
import android.util.Log
import com.ashairfoil.prism.GlesModelRenderer
import java.io.File

/**
 * Manages the 3D model scene: loading/unloading GLB models, saving/loading scene JSON,
 * and computing model transforms. Extracted from FilamentModelActivity to keep the
 * activity focused on input handling and UI rendering.
 */
class SceneManager(
    private val context: Context,
    private val renderer: GlesModelRenderer
) {
    companion object {
        private const val TAG = "ChloeVR-SceneManager"
    }

    // ── PlacedModel ──

    data class PlacedModel(
        val file: File,
        val asset: Any?,
        var gpuModelId: Int = -1,
        var scale: Float = 1f,
        val baseScale: Float = 1f,
        var posX: Float = 0f,
        var posY: Float = 0f,
        var posZ: Float = -1f,
        var rotX: Float = 0f,
        var rotY: Float = 0f,
        var rotZ: Float = 0f,
        var rotW: Float = 1f,
        var metallic: Float = 0f,
        var roughness: Float = 0.9f,
        var exposure: Float = 0f,
        var contrast: Float = 1f,
        var saturation: Float = 1f
    )

    // ── Yeet animation ──

    data class YeetingModel(
        val gpuModelId: Int,
        var posX: Float, var posY: Float, var posZ: Float,
        var velX: Float, var velY: Float, var velZ: Float,
        var scale: Float,
        var spin: Float = 0f,
        var timer: Float = 0f
    )

    // ── Public state ──

    val models = mutableListOf<PlacedModel>()
    var selectedModelIndex = -1
    val yeetingModels = mutableListOf<YeetingModel>()

    /** Callback for posting work to the UI thread (set by the activity). */
    var runOnUiThread: (Runnable) -> Unit = {}

    /** Callback for showing a toast/message (set by the activity). */
    var showMessage: (String) -> Unit = {}

    // ── Model loading ──

    /**
     * Load a GLB file and add it to the scene.
     * Must be called on a thread with a current GL context.
     *
     * @param detectedFloorY current detected floor Y for auto-snap, or Float.MIN_VALUE if unknown
     */
    fun loadModel(file: File, offsetIndex: Int = 0, detectedFloorY: Float = Float.MIN_VALUE) {
        try {
            val bytes = file.readBytes()
            Log.i(TAG, "Loading GLB: ${file.name} (${bytes.size} bytes)")

            val gpuId = renderer.loadGlb(bytes)
            if (gpuId < 0) {
                Log.e(TAG, "Failed to load GLB: ${file.name}")
                runOnUiThread(Runnable { showMessage("Failed to load 3D model: ${file.name}") })
                return
            }

            val gpuModel = renderer.getModel(gpuId) ?: return

            val autoScale = 0.75f
            // Offset new models so they don't overlap
            val offsetX = if (models.isEmpty()) 0f else offsetIndex * 1.0f

            val placed = PlacedModel(
                file = file,
                asset = null,
                gpuModelId = gpuId,
                scale = autoScale,
                baseScale = autoScale,
                posX = offsetX,
                posZ = -1f,
                metallic = gpuModel.metallic,
                roughness = gpuModel.roughness
            )
            models.add(placed)
            selectedModelIndex = models.size - 1

            // Auto-snap to detected floor if available
            if (detectedFloorY != Float.MIN_VALUE) {
                val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                placed.posY += (detectedFloorY - worldMinY)
            } else if (renderer.gridHeight != 0f) {
                val worldMinY = placed.posY + gpuModel.boundsMinY * placed.scale
                placed.posY += (renderer.gridHeight - worldMinY)
            }

            updateModelTransform(placed)

            Log.i(TAG, "Model loaded: ${file.name}, scale=$autoScale, gpuId=$gpuId, floorY=$detectedFloorY")
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading model: ${file.name}", e)
            runOnUiThread(Runnable { showMessage("Error: ${e.message}") })
        }
    }

    // ── Transform ──

    fun updateModelTransform(model: PlacedModel) {
        val gpuModel = renderer.getModel(model.gpuModelId) ?: return

        val s = model.scale
        val x = model.rotX; val y = model.rotY; val z = model.rotZ; val w = model.rotW

        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2

        gpuModel.modelMatrix = floatArrayOf(
            (1f - (yy + zz)) * s, (xy + wz) * s, (xz - wy) * s, 0f,
            (xy - wz) * s, (1f - (xx + zz)) * s, (yz + wx) * s, 0f,
            (xz + wy) * s, (yz - wx) * s, (1f - (xx + yy)) * s, 0f,
            model.posX, model.posY, model.posZ, 1f
        )
    }

    // ── Reload ──

    /**
     * Reload all models from disk, preserving transforms and material settings.
     * Useful when texture quality changes.
     */
    fun reloadAllModels(textureQuality: Int) {
        // Snapshot current state
        val snapshots = models.map { m ->
            Triple(m.file, m.copy(), m.gpuModelId)
        }
        // Remove old GPU models
        for ((_, _, gpuId) in snapshots) renderer.removeModel(gpuId)
        models.clear()
        selectedModelIndex = -1
        // Pass quality setting to renderer
        renderer.textureMaxSize = when (textureQuality) {
            1 -> 4096; 2 -> 2048; 3 -> 1024; else -> 0 // 0 = auto
        }
        // Reload each model preserving transforms
        for ((file, snap, _) in snapshots) {
            if (!file.exists()) continue
            try {
                val bytes = file.readBytes()
                val gpuId = renderer.loadGlb(bytes)
                if (gpuId < 0) continue
                val placed = snap.copy(gpuModelId = gpuId)
                models.add(placed)
                updateModelTransform(placed)
                val gpuModel = renderer.getModel(gpuId) ?: continue
                gpuModel.metallic = placed.metallic
                gpuModel.roughness = placed.roughness
                gpuModel.exposure = placed.exposure
                gpuModel.contrast = placed.contrast
                gpuModel.saturation = placed.saturation
            } catch (e: Exception) {
                Log.e(TAG, "Reload failed: ${file.name}", e)
            }
        }
        if (models.isNotEmpty()) selectedModelIndex = 0
        Log.i(TAG, "Reloaded ${models.size} models (texQuality=$textureQuality)")
    }

    // ── Scene Save/Load ──

    fun getScenesDir(): File {
        val dir = File(context.filesDir, "scenes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Saved scene file list, populated by [refreshSceneList]. */
    var savedSceneFiles: List<File> = emptyList()
        private set

    fun refreshSceneList() {
        savedSceneFiles = getScenesDir().listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Save the current scene (models + lighting) to a JSON file.
     *
     * @param autoAmbient current auto-ambient flag from the activity
     * @param gridVisible current grid visibility flag from the activity
     */
    fun saveScene(name: String, autoAmbient: Boolean, gridVisible: Boolean) {
        try {
            val scene = org.json.JSONObject()

            // Global lighting
            val lighting = org.json.JSONObject()
            lighting.put("lightIntensity", renderer.lightIntensity.toDouble())
            lighting.put("fillLightIntensity", renderer.fillLightIntensity.toDouble())
            lighting.put("ambientIntensity", renderer.ambientIntensity.toDouble())
            lighting.put("lightAngleDeg", renderer.lightAngleDeg.toDouble())
            lighting.put("lightElevDeg", renderer.lightElevDeg.toDouble())
            lighting.put("shadowDarkness", renderer.shadowDarkness.toDouble())
            lighting.put("shadowSoftness", renderer.shadowSoftness.toDouble())
            lighting.put("shadowSpread", renderer.shadowSpread.toDouble())
            lighting.put("autoAmbient", autoAmbient)
            lighting.put("gridVisible", gridVisible)
            lighting.put("gridHeight", renderer.gridHeight.toDouble())
            scene.put("lighting", lighting)

            // Models
            val modelsArr = org.json.JSONArray()
            for (m in models) {
                val obj = org.json.JSONObject()
                obj.put("path", m.file.absolutePath)
                obj.put("scale", m.scale.toDouble())
                obj.put("posX", m.posX.toDouble())
                obj.put("posY", m.posY.toDouble())
                obj.put("posZ", m.posZ.toDouble())
                obj.put("rotX", m.rotX.toDouble())
                obj.put("rotY", m.rotY.toDouble())
                obj.put("rotZ", m.rotZ.toDouble())
                obj.put("rotW", m.rotW.toDouble())
                obj.put("metallic", m.metallic.toDouble())
                obj.put("roughness", m.roughness.toDouble())
                obj.put("exposure", m.exposure.toDouble())
                obj.put("contrast", m.contrast.toDouble())
                obj.put("saturation", m.saturation.toDouble())
                modelsArr.put(obj)
            }
            scene.put("models", modelsArr)

            val file = File(getScenesDir(), "$name.json")
            file.writeText(scene.toString(2))
            Log.i(TAG, "Scene saved: ${file.absolutePath} (${models.size} models)")
            runOnUiThread(Runnable { showMessage("Scene saved: $name") })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scene", e)
            runOnUiThread(Runnable { showMessage("Save failed: ${e.message}") })
        }
    }

    /**
     * Load a scene from a JSON file. Must be called on the render thread (needs GL context).
     *
     * @return a [SceneLoadResult] containing the restored autoAmbient and gridVisible values,
     *         or null if the load failed.
     */
    data class SceneLoadResult(val autoAmbient: Boolean, val gridVisible: Boolean)

    fun loadScene(sceneFile: File): SceneLoadResult? {
        // This must be called on the render thread (needs GL context for loadModel)
        try {
            val json = org.json.JSONObject(sceneFile.readText())

            // Clear existing models
            for (m in models) {
                renderer.removeModel(m.gpuModelId)
            }
            models.clear()
            selectedModelIndex = -1

            var restoredAutoAmbient = true
            var restoredGridVisible = true

            // Restore lighting
            val lighting = json.optJSONObject("lighting")
            if (lighting != null) {
                renderer.lightIntensity = lighting.optDouble("lightIntensity", 2.0).toFloat()
                renderer.fillLightIntensity = lighting.optDouble("fillLightIntensity", 0.5).toFloat()
                renderer.ambientIntensity = lighting.optDouble("ambientIntensity", 1.0).toFloat()
                renderer.lightAngleDeg = lighting.optDouble("lightAngleDeg", 0.0).toFloat()
                renderer.lightElevDeg = lighting.optDouble("lightElevDeg", 60.0).toFloat()
                renderer.updateLightDirFromAngles()
                renderer.shadowDarkness = lighting.optDouble("shadowDarkness", 0.7).toFloat()
                renderer.shadowSoftness = lighting.optDouble("shadowSoftness", 2.0).toFloat()
                renderer.shadowSpread = lighting.optDouble("shadowSpread", 8.0).toFloat()
                restoredAutoAmbient = lighting.optBoolean("autoAmbient", true)
                restoredGridVisible = lighting.optBoolean("gridVisible", true)
                renderer.gridHeight = lighting.optDouble("gridHeight", 0.0).toFloat()
            }

            // Load models
            val modelsArr = json.optJSONArray("models")
            if (modelsArr != null) {
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.getJSONObject(i)
                    val file = File(obj.getString("path"))
                    if (!file.exists()) {
                        Log.w(TAG, "Scene model not found: ${file.absolutePath}")
                        continue
                    }
                    val bytes = file.readBytes()
                    val gpuId = renderer.loadGlb(bytes)
                    if (gpuId < 0) continue

                    val gpuModel = renderer.getModel(gpuId) ?: continue
                    val placed = PlacedModel(
                        file = file, asset = null, gpuModelId = gpuId,
                        scale = obj.optDouble("scale", 0.75).toFloat(),
                        baseScale = obj.optDouble("scale", 0.75).toFloat(),
                        posX = obj.optDouble("posX", 0.0).toFloat(),
                        posY = obj.optDouble("posY", 0.0).toFloat(),
                        posZ = obj.optDouble("posZ", -1.0).toFloat(),
                        rotX = obj.optDouble("rotX", 0.0).toFloat(),
                        rotY = obj.optDouble("rotY", 0.0).toFloat(),
                        rotZ = obj.optDouble("rotZ", 0.0).toFloat(),
                        rotW = obj.optDouble("rotW", 1.0).toFloat(),
                        metallic = obj.optDouble("metallic", 0.0).toFloat(),
                        roughness = obj.optDouble("roughness", 0.9).toFloat(),
                        exposure = obj.optDouble("exposure", 0.0).toFloat(),
                        contrast = obj.optDouble("contrast", 1.0).toFloat(),
                        saturation = obj.optDouble("saturation", 1.0).toFloat()
                    )
                    gpuModel.metallic = placed.metallic
                    gpuModel.roughness = placed.roughness
                    gpuModel.exposure = placed.exposure
                    gpuModel.contrast = placed.contrast
                    gpuModel.saturation = placed.saturation
                    models.add(placed)
                    updateModelTransform(placed)
                }
            }

            if (models.isNotEmpty()) selectedModelIndex = 0
            Log.i(TAG, "Scene loaded: ${sceneFile.name} (${models.size} models)")
            runOnUiThread(Runnable { showMessage("Scene loaded: ${sceneFile.nameWithoutExtension}") })

            return SceneLoadResult(restoredAutoAmbient, restoredGridVisible)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scene", e)
            runOnUiThread(Runnable { showMessage("Load failed: ${e.message}") })
            return null
        }
    }
}
