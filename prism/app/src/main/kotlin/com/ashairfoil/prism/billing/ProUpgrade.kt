package com.ashairfoil.prism.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.ashairfoil.prism.BuildConfig

/**
 * ProUpgrade — Freemium gating for ChloeVR Pro features.
 *
 * Free tier: Basic playback, SBS/TB/mono, 180/360, basic controls, file picker
 * Pro tier ($7.99 one-time): Color grading, lens distortion, IPD adjust, subtitles,
 *           streaming API, custom presets, depth simulation, eye tracking autofocus,
 *           spatial audio, advanced sharpening, video info overlay
 *
 * This module handles:
 * 1. Checking if the user has purchased Pro
 * 2. Launching the purchase flow
 * 3. Caching the purchase state locally
 *
 * For development/testing: Set DEBUG_PRO_UNLOCKED = true to bypass billing.
 *
 * Google Play Billing Library integration:
 * Add to build.gradle.kts:
 *   implementation("com.android.billingclient:billing-ktx:7.1.1")
 *
 * Then replace the stub methods below with actual BillingClient calls.
 */
object ProUpgrade {

    private const val TAG = "ProUpgrade"
    private const val PREFS_NAME = "chloe_billing"
    private const val KEY_PRO_PURCHASED = "pro_purchased"
    private const val PRODUCT_ID = "chloe_pro_unlock"

    // Set to true during development to bypass billing.
    // val (not var) — prevents runtime modification of billing bypass.
    val DEBUG_PRO_UNLOCKED = BuildConfig.DEBUG

    private var cachedIsPro: Boolean? = null

    /**
     * Check if Pro features are unlocked.
     * Fast: reads from cache/SharedPreferences. No network call.
     */
    fun isPro(context: Context): Boolean {
        if (DEBUG_PRO_UNLOCKED) {
            Log.w(TAG, "isPro(): returning true via DEBUG_PRO_UNLOCKED bypass — not valid for production")
            return true
        }

        cachedIsPro?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val purchased = prefs.getBoolean(KEY_PRO_PURCHASED, false)
        cachedIsPro = purchased
        return purchased
    }

    /**
     * Gate a pro feature. Returns true if the feature is available.
     * If not pro, can optionally show the upgrade prompt.
     */
    fun requirePro(context: Context, featureName: String = "", showPrompt: Boolean = true): Boolean {
        if (isPro(context)) return true

        if (showPrompt) {
            Log.i(TAG, "Pro feature '$featureName' requires upgrade")
            // In production: show upgrade dialog
            // For now: log and return false
        }
        return false
    }

    /**
     * Launch the Google Play purchase flow.
     *
     * POST-LAUNCH: Replace with BillingClient implementation.
     * In debug builds, DEBUG_PRO_UNLOCKED bypasses this entirely.
     * In release builds, this is a no-op until billing is wired.
     */
    fun launchPurchaseFlow(activity: Activity) {
        Log.i(TAG, "Launch purchase flow for $PRODUCT_ID")

        if (DEBUG_PRO_UNLOCKED) {
            markPurchased(activity)
        }
    }

    /**
     * Verify purchase state with Google Play.
     * Called on app startup to sync cached state.
     *
     * POST-LAUNCH: Replace body with BillingClient.queryPurchasesAsync() to
     * verify purchases server-side. Current implementation reads SharedPreferences only.
     */
    fun verifyPurchaseState(context: Context) {
        if (DEBUG_PRO_UNLOCKED) {
            cachedIsPro = true
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedIsPro = prefs.getBoolean(KEY_PRO_PURCHASED, false)
    }

    /**
     * Mark Pro as purchased (called after successful billing flow).
     */
    fun markPurchased(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRO_PURCHASED, true)
            .apply()
        cachedIsPro = true
        Log.i(TAG, "Pro upgrade purchased and cached")
    }

    /**
     * List of features gated behind Pro.
     * Used in the upgrade prompt to show what the user gets.
     */
    val PRO_FEATURES = listOf(
        "Color Grading" to "Brightness, contrast, saturation, sharpening, gamma, hue shift",
        "Lens Correction" to "10+ camera lens profiles with custom distortion curves",
        "IPD Adjustment" to "Software interpupillary distance + stereo alignment",
        "Subtitles" to "SRT and ASS/SSA subtitle support",
        "Streaming" to "DeoVR-compatible API for streaming sites",
        "Custom Presets" to "Save unlimited color grading presets",
        "6DOF Depth" to "Head-tracking depth simulation",
        "Eye Tracking" to "Gaze-based autofocus",
        "Spatial Audio" to "Head-tracked stereo balance",
        "Advanced Sharpening" to "Clarity and fine-detail enhancement",
        "Video Info" to "Technical metadata overlay (codec, bitrate, resolution)",
        "Tone Mapping" to "Reinhard, ACES Film, and custom LUT support",
    )

    /**
     * Check if a specific feature requires Pro.
     */
    fun isProFeature(feature: String): Boolean {
        return when (feature.lowercase()) {
            "color_grading", "color", "grading" -> true
            "lens_distortion", "lens", "distortion" -> true
            "ipd", "stereo_adjust" -> true
            "subtitles", "srt", "ass" -> true
            "streaming", "deovr_api" -> true
            "presets", "custom_presets" -> true
            "depth_6dof", "depth" -> true
            "eye_tracking", "gaze", "autofocus" -> true
            "spatial_audio" -> true
            "sharpening_advanced", "clarity", "detail" -> true
            "video_info" -> true
            "tone_mapping" -> true
            else -> false
        }
    }
}
