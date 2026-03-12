package com.ashairfoil.prism.ui

import android.graphics.Color

/**
 * ThemeManager — Centralized color and styling constants for ChloeVR UI.
 *
 * All programmatic UI should reference these colors instead of hardcoding hex values.
 * This enables future theme switching and maintains visual consistency.
 *
 * Design language: Dark VR-optimized UI with accent colors for interactive elements.
 * Low brightness to minimize eye strain in headset. High contrast for readability.
 */
object ThemeManager {

    // ── Primary backgrounds ──
    val BG_PRIMARY = Color.parseColor("#0D0D0F")       // Near-black
    val BG_SECONDARY = Color.parseColor("#161618")      // Slightly lighter
    val BG_TERTIARY = Color.parseColor("#1E1E22")       // Cards, panels
    val BG_INPUT = Color.parseColor("#0A0A0C")          // Input fields
    val BG_OVERLAY = Color.argb(200, 0, 0, 0)           // Semi-transparent overlays

    // ── Text ──
    val TEXT_PRIMARY = Color.parseColor("#E8E8EC")       // Bright text
    val TEXT_SECONDARY = Color.parseColor("#9898A0")     // Dimmed text
    val TEXT_MUTED = Color.parseColor("#58585F")         // Very dim labels
    val TEXT_ON_ACCENT = Color.WHITE

    // ── Accent colors ──
    val ACCENT = Color.parseColor("#7C5CFC")            // Purple — primary accent
    val ACCENT_DIM = Color.argb(30, 124, 92, 252)       // Accent background
    val ACCENT_HOVER = Color.parseColor("#9B7FFF")       // Lighter hover state

    val GREEN = Color.parseColor("#3AD67C")              // Success, online, play
    val RED = Color.parseColor("#F04858")                // Error, stop, delete
    val BLUE = Color.parseColor("#4DA6FF")               // Info, links
    val YELLOW = Color.parseColor("#F0B838")             // Warning, stars/ratings
    val CYAN = Color.parseColor("#30D8D0")               // Active, highlight
    val PINK = Color.parseColor("#E060A0")               // Favorite
    val ORANGE = Color.parseColor("#F08030")             // Seek, scrub

    // ── Borders ──
    val BORDER = Color.parseColor("#2A2A30")
    val BORDER_LIGHT = Color.parseColor("#3A3A42")
    val BORDER_ACCENT = Color.argb(80, 124, 92, 252)

    // ── Specific UI elements ──
    val SCRUB_BAR_BG = Color.parseColor("#2A2A30")
    val SCRUB_BAR_PROGRESS = ACCENT
    val SCRUB_BAR_BUFFER = Color.parseColor("#4A4A50")
    val SCRUB_HANDLE = Color.WHITE

    val SLIDER_TRACK = Color.parseColor("#2A2A30")
    val SLIDER_ACTIVE = ACCENT
    val SLIDER_THUMB = Color.WHITE

    val FILE_ITEM_BG = Color.parseColor("#141416")
    val FILE_ITEM_SELECTED = Color.argb(40, 124, 92, 252)
    val FILE_ITEM_HOVER = Color.argb(20, 255, 255, 255)

    val TOOLTIP_BG = Color.argb(230, 20, 20, 24)
    val TOOLTIP_TEXT = TEXT_SECONDARY

    // ── Control panel sections ──
    val SECTION_HEADER = ACCENT
    val SECTION_BG = BG_SECONDARY
    val SECTION_BORDER = BORDER

    // ── Typography (sizes in sp) ──
    const val TEXT_SIZE_TITLE = 18f
    const val TEXT_SIZE_HEADING = 14f
    const val TEXT_SIZE_BODY = 12f
    const val TEXT_SIZE_CAPTION = 10f
    const val TEXT_SIZE_MICRO = 8f

    // ── Spacing (in dp) ──
    const val PADDING_LARGE = 16
    const val PADDING_MEDIUM = 10
    const val PADDING_SMALL = 6
    const val PADDING_TINY = 3

    const val CORNER_RADIUS = 8f
    const val CORNER_RADIUS_SMALL = 4f

    // ── File picker badges ──
    val BADGE_VR180 = Color.parseColor("#4DA6FF")
    val BADGE_VR360 = Color.parseColor("#7C5CFC")
    val BADGE_FISHEYE = Color.parseColor("#30D8D0")
    val BADGE_SBS = Color.parseColor("#F0B838")
    val BADGE_TB = Color.parseColor("#F08030")
    val BADGE_ALPHA = Color.parseColor("#E060A0")
    val BADGE_FAVORITE = PINK
    val BADGE_RESUME = GREEN
    val BADGE_SUBTITLE = BLUE

    // ── Rating stars ──
    val STAR_FILLED = YELLOW
    val STAR_EMPTY = Color.parseColor("#3A3A42")

    // ── Status indicators ──
    val STATUS_PLAYING = GREEN
    val STATUS_PAUSED = YELLOW
    val STATUS_BUFFERING = BLUE
    val STATUS_ERROR = RED

    /**
     * Get a projection type badge color.
     */
    fun projectionColor(projection: String): Int {
        return when {
            projection.contains("360", ignoreCase = true) -> BADGE_VR360
            projection.contains("180", ignoreCase = true) -> BADGE_VR180
            projection.contains("fisheye", ignoreCase = true) -> BADGE_FISHEYE
            else -> TEXT_MUTED
        }
    }

    /**
     * Get a stereo mode badge color.
     */
    fun stereoColor(stereoMode: String): Int {
        return when {
            stereoMode.contains("sbs", ignoreCase = true) ||
            stereoMode.contains("lr", ignoreCase = true) -> BADGE_SBS
            stereoMode.contains("tb", ignoreCase = true) -> BADGE_TB
            else -> TEXT_MUTED
        }
    }
}
