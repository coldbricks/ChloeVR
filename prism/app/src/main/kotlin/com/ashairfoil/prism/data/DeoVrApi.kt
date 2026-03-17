package com.ashairfoil.prism.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeoVrApi — DeoVR-compatible JSON API client for streaming site integration.
 *
 * DeoVR's JSON API is the de-facto standard for VR content sites (SLR, VRPorn,
 * Czech VR, etc.). HereSphere also supports it. By implementing this, ChloeVR
 * can browse and stream from any site that uses the DeoVR JSON format.
 *
 * API Format:
 * A DeoVR JSON endpoint returns a scene list:
 * {
 *   "scenes": [
 *     {
 *       "title": "Scene Title",
 *       "thumbnailUrl": "https://...",
 *       "video_url": "https://...",        // Direct stream URL
 *       "encodings": [                     // Multiple quality options
 *         {
 *           "name": "h265 8K",
 *           "videoSources": [
 *             {
 *               "resolution": 3840,
 *               "url": "https://...",
 *               "height": 1920
 *             }
 *           ]
 *         }
 *       ],
 *       "screenType": "dome",              // dome, sphere, flat
 *       "stereoMode": "sbs",              // sbs, tb, mono
 *       "is3d": true,
 *       "skipIntro": 0,
 *       "duration": 1800,
 *       "id": 12345,
 *       "videoPreview": "https://...",
 *       "corrections": {                   // HereSphere extension
 *         "x": 0, "y": 0, "z": 0,
 *         "brightness": 0, "contrast": 0, "saturation": 0
 *       },
 *       "hsp": "https://..."              // HereSphere script URL
 *     }
 *   ]
 * }
 */
class DeoVrApi {

    companion object {
        private const val TAG = "DeoVrApi"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 30_000
        private const val USER_AGENT = "ChloeVR/1.0 (Android XR; DeoVR-compatible)"
    }

    data class VideoSource(
        val url: String,
        val resolution: Int,       // Width (e.g., 3840 for 4K, 7680 for 8K)
        val height: Int,
        val encodingName: String   // e.g., "h265 8K", "h264 4K"
    )

    data class Scene(
        val id: Int,
        val title: String,
        val thumbnailUrl: String,
        val videoUrl: String,              // Primary/default stream URL
        val videoSources: List<VideoSource>, // All available qualities
        val screenType: String,            // dome, sphere, flat
        val stereoMode: String,            // sbs, tb, mono
        val is3d: Boolean,
        val duration: Int,                 // seconds
        val skipIntro: Int,                // seconds to skip at start
        val videoPreviewUrl: String,
        // HereSphere-compatible corrections
        val corrections: Corrections?,
        val hspUrl: String?                // HereSphere script
    )

    data class Corrections(
        val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
        val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f
    )

    data class ApiResponse(
        val scenes: List<Scene>,
        val totalCount: Int,
        val error: String? = null
    )

    /**
     * Fetch and parse a DeoVR-compatible JSON endpoint.
     * Runs on IO dispatcher.
     */
    suspend fun fetchScenes(apiUrl: String, authToken: String? = null): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (authToken != null) {
                    conn.setRequestProperty("Authorization", "Bearer $authToken")
                }

                try {
                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        return@withContext ApiResponse(
                            scenes = emptyList(), totalCount = 0,
                            error = "HTTP $responseCode"
                        )
                    }

                    val maxSize = 10 * 1024 * 1024 // 10 MB limit
                    val body = conn.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        var totalRead = 0
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            totalRead += n
                            if (totalRead > maxSize) {
                                throw IllegalStateException("Response exceeds 10 MB limit")
                            }
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }

                    parseResponse(body)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "API fetch error: ${e.message}")
                ApiResponse(scenes = emptyList(), totalCount = 0, error = e.message)
            }
        }
    }

    private fun parseResponse(json: String): ApiResponse {
        try {
            val root = JSONObject(json)
            val scenes = mutableListOf<Scene>()

            val scenesArray = root.optJSONArray("scenes") ?: JSONArray()
            for (i in 0 until scenesArray.length()) {
                val obj = scenesArray.getJSONObject(i)
                scenes.add(parseScene(obj))
            }

            return ApiResponse(
                scenes = scenes,
                totalCount = root.optInt("totalCount", scenes.size)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            return ApiResponse(scenes = emptyList(), totalCount = 0, error = "Parse error: ${e.message}")
        }
    }

    private fun parseScene(obj: JSONObject): Scene {
        // Parse video sources from encodings array
        val videoSources = mutableListOf<VideoSource>()
        val encodings = obj.optJSONArray("encodings")
        if (encodings != null) {
            for (i in 0 until encodings.length()) {
                val enc = encodings.getJSONObject(i)
                val encName = enc.optString("name", "")
                val sources = enc.optJSONArray("videoSources")
                if (sources != null) {
                    for (j in 0 until sources.length()) {
                        val src = sources.getJSONObject(j)
                        videoSources.add(VideoSource(
                            url = src.optString("url", ""),
                            resolution = src.optInt("resolution", 0),
                            height = src.optInt("height", 0),
                            encodingName = encName
                        ))
                    }
                }
            }
        }

        // Parse corrections (HereSphere extension)
        val correctionsObj = obj.optJSONObject("corrections")
        val corrections = if (correctionsObj != null) {
            Corrections(
                x = correctionsObj.optDouble("x", 0.0).toFloat(),
                y = correctionsObj.optDouble("y", 0.0).toFloat(),
                z = correctionsObj.optDouble("z", 0.0).toFloat(),
                brightness = correctionsObj.optDouble("brightness", 0.0).toFloat(),
                contrast = correctionsObj.optDouble("contrast", 0.0).toFloat(),
                saturation = correctionsObj.optDouble("saturation", 0.0).toFloat(),
            )
        } else null

        return Scene(
            id = obj.optInt("id", 0),
            title = obj.optString("title", "Untitled"),
            thumbnailUrl = obj.optString("thumbnailUrl", ""),
            videoUrl = obj.optString("video_url", obj.optString("videoUrl", "")),
            videoSources = videoSources.sortedByDescending { it.resolution },
            screenType = obj.optString("screenType", "dome"),
            stereoMode = obj.optString("stereoMode", "sbs"),
            is3d = obj.optBoolean("is3d", true),
            duration = obj.optInt("duration", 0),
            skipIntro = obj.optInt("skipIntro", 0),
            videoPreviewUrl = obj.optString("videoPreview", ""),
            corrections = corrections,
            hspUrl = obj.optString("hsp", null)
        )
    }

    /**
     * Map DeoVR screenType to ChloeVR projection.
     */
    fun mapScreenType(screenType: String): String {
        return when (screenType.lowercase()) {
            "dome" -> "180"
            "sphere" -> "360"
            "flat", "screen" -> "flat"
            "fisheye", "fisheye190" -> "fisheye190"
            "mkx200" -> "mkx200"
            "vrca220" -> "vrca220"
            "rf52" -> "rf52"
            else -> "180" // Default to 180 for VR content
        }
    }

    /**
     * Map DeoVR stereoMode to ChloeVR stereo mode.
     */
    fun mapStereoMode(stereoMode: String): String {
        return when (stereoMode.lowercase()) {
            "sbs", "lr", "3dh" -> "sbs"
            "tb", "3dv", "ou" -> "tb"
            "mono", "2d" -> "mono"
            else -> "sbs" // Default to SBS for VR content
        }
    }

    /**
     * Get the best available video source for given max resolution.
     * Returns the highest resolution source that doesn't exceed maxRes.
     */
    fun bestSource(scene: Scene, maxResolution: Int = 8192): VideoSource? {
        return scene.videoSources
            .filter { it.resolution <= maxResolution && it.url.isNotBlank() }
            .maxByOrNull { it.resolution }
            ?: scene.videoSources.firstOrNull { it.url.isNotBlank() }
    }
}
