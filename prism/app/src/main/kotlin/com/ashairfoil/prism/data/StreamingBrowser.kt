package com.ashairfoil.prism.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * StreamingBrowser — DeoVR-compatible streaming site browser.
 *
 * Manages a list of streaming endpoints (DeoVR API URLs) that the user has
 * added. Fetches scene lists from each endpoint and presents them in the
 * file picker alongside local files.
 *
 * Supported sites (any site with DeoVR JSON API):
 * - SLR (sexlikereal.com)
 * - VRPorn.com
 * - Czech VR
 * - POVR
 * - WankzVR
 * - Naughty America VR
 * - BaDoink VR
 * - Any custom DeoVR-compatible endpoint
 *
 * Usage:
 *   val browser = StreamingBrowser(context)
 *   browser.addEndpoint("My SLR", "https://api.sexlikereal.com/deovr", token)
 *   val scenes = browser.fetchAll()
 */
class StreamingBrowser(private val context: Context) {

    companion object {
        private const val TAG = "StreamingBrowser"
        private const val PREFS_NAME = "chloe_streaming"
    }

    data class StreamingEndpoint(
        val id: String,
        val name: String,
        val apiUrl: String,
        val authToken: String? = null,
        val isEnabled: Boolean = true
    )

    data class StreamingScene(
        val endpoint: StreamingEndpoint,
        val scene: DeoVrApi.Scene
    )

    private val api = DeoVrApi()
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences failed, falling back to standard prefs", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var endpoints: MutableList<StreamingEndpoint> = mutableListOf()
    private var cachedScenes: List<StreamingScene> = emptyList()
    private var lastFetchTime: Long = 0

    init {
        loadEndpoints()
    }

    // -----------------------------------------------------------------------
    // Endpoint management
    // -----------------------------------------------------------------------

    fun addEndpoint(name: String, apiUrl: String, authToken: String? = null): StreamingEndpoint {
        val ep = StreamingEndpoint(
            id = "ep_${System.currentTimeMillis()}",
            name = name,
            apiUrl = apiUrl,
            authToken = authToken
        )
        endpoints.add(ep)
        saveEndpoints()
        return ep
    }

    fun removeEndpoint(id: String) {
        endpoints.removeAll { it.id == id }
        saveEndpoints()
    }

    fun toggleEndpoint(id: String): Boolean {
        val ep = endpoints.find { it.id == id } ?: return false
        val idx = endpoints.indexOf(ep)
        endpoints[idx] = ep.copy(isEnabled = !ep.isEnabled)
        saveEndpoints()
        return endpoints[idx].isEnabled
    }

    fun getEndpoints(): List<StreamingEndpoint> = endpoints.toList()

    // -----------------------------------------------------------------------
    // Fetching
    // -----------------------------------------------------------------------

    /**
     * Fetch scenes from all enabled endpoints.
     * Returns a flat list of all scenes across all endpoints.
     */
    suspend fun fetchAll(forceRefresh: Boolean = false): List<StreamingScene> {
        // Cache for 5 minutes
        if (!forceRefresh && cachedScenes.isNotEmpty() &&
            System.currentTimeMillis() - lastFetchTime < 300_000) {
            return cachedScenes
        }

        val results = mutableListOf<StreamingScene>()

        coroutineScope {
            val jobs = endpoints.filter { it.isEnabled }.map { ep ->
                async(Dispatchers.IO) {
                    try {
                        val response = api.fetchScenes(ep.apiUrl, ep.authToken)
                        if (response.error != null) {
                            Log.w(TAG, "Error fetching ${ep.name}: ${response.error}")
                            emptyList()
                        } else {
                            response.scenes.map { StreamingScene(ep, it) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception fetching ${ep.name}: ${e.message}")
                        emptyList()
                    }
                }
            }
            jobs.forEach { results.addAll(it.await()) }
        }

        cachedScenes = results
        lastFetchTime = System.currentTimeMillis()
        Log.i(TAG, "Fetched ${results.size} scenes from ${endpoints.count { it.isEnabled }} endpoints")
        return results
    }

    /**
     * Search cached scenes.
     */
    fun search(query: String): List<StreamingScene> {
        if (query.isBlank()) return cachedScenes
        val q = query.trim().lowercase()
        return cachedScenes.filter { it.scene.title.lowercase().contains(q) }
    }

    /**
     * Get the best streaming URL for a scene at the given max resolution.
     */
    fun getStreamUrl(scene: DeoVrApi.Scene, maxRes: Int = 8192): String {
        val best = api.bestSource(scene, maxRes)
        return best?.url ?: scene.videoUrl
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private fun saveEndpoints() {
        val arr = JSONArray()
        for (ep in endpoints) {
            arr.put(JSONObject().apply {
                put("id", ep.id)
                put("name", ep.name)
                put("apiUrl", ep.apiUrl)
                if (ep.authToken != null) put("authToken", ep.authToken)
                put("isEnabled", ep.isEnabled)
            })
        }
        prefs.edit().putString("endpoints_v2", arr.toString()).apply()
    }

    private fun loadEndpoints() {
        // Try v2 JSON format first, fall back to legacy tab-delimited
        val jsonStr = prefs.getString("endpoints_v2", null)
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                endpoints = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    StreamingEndpoint(
                        id = obj.optString("id", "ep_${System.currentTimeMillis()}"),
                        name = obj.optString("name", "Unknown"),
                        apiUrl = obj.optString("apiUrl", ""),
                        authToken = if (obj.has("authToken")) obj.getString("authToken") else null,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                }.toMutableList()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse v2 endpoints, trying legacy", e)
            }
        }

        // Legacy tab-delimited format (migrate on next save)
        val legacy = prefs.getString("endpoints", "") ?: ""
        endpoints = legacy.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size >= 3) {
                StreamingEndpoint(
                    id = parts[0],
                    name = parts[1],
                    apiUrl = parts[2],
                    authToken = parts.getOrNull(3)?.ifBlank { null },
                    isEnabled = parts.getOrNull(4)?.toBooleanStrictOrNull() ?: true
                )
            } else null
        }.toMutableList()
        // Migrate to v2 format
        if (endpoints.isNotEmpty()) saveEndpoints()
    }
}
