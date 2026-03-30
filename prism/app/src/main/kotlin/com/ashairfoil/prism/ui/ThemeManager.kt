package com.ashairfoil.prism.ui

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ThemeManager — "Velvet Dark" Design System for ChloeVR
 *
 * Dark, warm, intimate. Premium adult VR content player aesthetic.
 * Hot pink accents on deep purple-black. Glass panels with subtle glow.
 *
 * All programmatic UI MUST reference these tokens instead of hardcoding hex values.
 */
object ThemeManager {

    // ══════════════════════════════════════════════════════
    //  BACKGROUNDS
    // ══════════════════════════════════════════════════════

    val BG_VOID       = Color.parseColor("#050508")    // deepest black
    val BG_PANEL      = Color.parseColor("#0C0A14")    // panel base (warm purple-black)
    val BG_SURFACE    = Color.parseColor("#14111E")    // elevated surface (cards, inputs)
    val BG_ELEVATED   = Color.parseColor("#1C1828")    // hover/active backgrounds
    val BG_GLASS      = Color.argb(0xCC, 0x14, 0x11, 0x1E) // 80% opacity glass

    // Legacy compat aliases
    val BG_PRIMARY    = BG_VOID
    val BG_SECONDARY  = BG_PANEL
    val BG_TERTIARY   = BG_SURFACE
    val BG_INPUT      = BG_PANEL
    val BG_OVERLAY    = Color.argb(200, 5, 5, 8)

    // ══════════════════════════════════════════════════════
    //  TEXT
    // ══════════════════════════════════════════════════════

    val TEXT_BRIGHT    = Color.parseColor("#F0EDF5")    // primary (warm white)
    val TEXT_MID       = Color.parseColor("#9890A8")    // secondary (muted lavender)
    val TEXT_DIM       = Color.parseColor("#504868")    // disabled/hint
    val TEXT_GLOW      = Color.parseColor("#FFB0D0")    // glowing pink-white
    val TEXT_ON_ACCENT = Color.WHITE

    // Legacy compat
    val TEXT_PRIMARY   = TEXT_BRIGHT
    val TEXT_SECONDARY = TEXT_MID
    val TEXT_MUTED     = TEXT_DIM

    // ══════════════════════════════════════════════════════
    //  ACCENT COLORS
    // ══════════════════════════════════════════════════════

    val PINK_HOT       = Color.parseColor("#FF2D7B")    // primary accent — buttons, active
    val PINK_SOFT      = Color.parseColor("#EC4899")    // secondary pink — titles, borders
    val PINK_GLOW      = Color.argb(0x40, 0xFF, 0x2D, 0x7B) // 25% glow
    val PINK_DARK      = Color.parseColor("#D91A65")    // gradient endpoint
    val PURPLE_DEEP    = Color.parseColor("#8B5CF6")    // purple — audio, secondary actions
    val PURPLE_GLOW    = Color.argb(0x30, 0x8B, 0x5C, 0xF6)
    val GOLD_WARM      = Color.parseColor("#FFB347")    // favorites, premium, ratings
    val CYAN_ICE       = Color.parseColor("#30D8D0")    // active params, highlights

    // Legacy compat
    val ACCENT         = PINK_HOT
    val ACCENT_DIM     = Color.argb(30, 0xFF, 0x2D, 0x7B)
    val ACCENT_HOVER   = Color.parseColor("#FF4D90")
    val GREEN          = Color.parseColor("#34D399")
    val RED            = Color.parseColor("#F04858")
    val BLUE           = Color.parseColor("#4DA6FF")
    val YELLOW         = GOLD_WARM
    val CYAN           = CYAN_ICE
    val PINK           = PINK_SOFT
    val ORANGE         = Color.parseColor("#F08030")

    // ══════════════════════════════════════════════════════
    //  STATUS
    // ══════════════════════════════════════════════════════

    val SUCCESS        = GREEN
    val ERROR          = RED
    val WARNING        = Color.parseColor("#F59E0B")

    val STATUS_PLAYING   = GREEN
    val STATUS_PAUSED    = GOLD_WARM
    val STATUS_BUFFERING = BLUE
    val STATUS_ERROR     = RED

    // ══════════════════════════════════════════════════════
    //  BORDERS
    // ══════════════════════════════════════════════════════

    val BORDER_SUBTLE  = Color.argb(0x0A, 0xFF, 0xFF, 0xFF) // 4% white
    val BORDER_SOFT    = Color.argb(0x15, 0xFF, 0xFF, 0xFF) // 8% white
    val BORDER_PINK    = Color.argb(0x30, 0xFF, 0x2D, 0x7B) // 18% pink
    val BORDER_GLOW    = Color.argb(0x60, 0xFF, 0x2D, 0x7B) // 37% pink

    // Legacy compat
    val BORDER         = Color.parseColor("#1C1828")
    val BORDER_LIGHT   = Color.parseColor("#2A2434")
    val BORDER_ACCENT  = BORDER_PINK

    // ══════════════════════════════════════════════════════
    //  UI ELEMENTS
    // ══════════════════════════════════════════════════════

    val SCRUB_BAR_BG       = BG_PANEL
    val SCRUB_BAR_PROGRESS = PINK_HOT
    val SCRUB_BAR_BUFFER   = Color.argb(0x14, 0xFF, 0xFF, 0xFF)
    val SCRUB_HANDLE       = Color.WHITE

    val SLIDER_TRACK  = BG_PANEL
    val SLIDER_ACTIVE = PINK_HOT
    val SLIDER_THUMB  = Color.WHITE

    val FILE_ITEM_BG       = BG_SURFACE
    val FILE_ITEM_SELECTED = Color.argb(40, 0xFF, 0x2D, 0x7B)
    val FILE_ITEM_HOVER    = BG_ELEVATED

    val TOOLTIP_BG   = Color.argb(230, 12, 10, 20)
    val TOOLTIP_TEXT  = TEXT_MID

    val SECTION_HEADER = PINK_SOFT
    val SECTION_BG     = BG_PANEL
    val SECTION_BORDER = BORDER_SUBTLE

    // ══════════════════════════════════════════════════════
    //  TYPOGRAPHY (sp for Views)
    // ══════════════════════════════════════════════════════

    const val TEXT_HERO    = 48f
    const val TEXT_TITLE   = 28f
    const val TEXT_HEADING = 22f
    const val TEXT_BODY    = 18f
    const val TEXT_LABEL   = 15f
    const val TEXT_CAPTION = 13f
    const val TEXT_MICRO   = 11f

    // Legacy compat
    const val TEXT_SIZE_TITLE   = TEXT_TITLE
    const val TEXT_SIZE_HEADING = TEXT_HEADING
    const val TEXT_SIZE_BODY    = TEXT_BODY
    const val TEXT_SIZE_CAPTION = TEXT_CAPTION
    const val TEXT_SIZE_MICRO   = TEXT_MICRO

    // ══════════════════════════════════════════════════════
    //  TYPOGRAPHY (px for Canvas bitmap — UiRenderer)
    // ══════════════════════════════════════════════════════

    const val PX_HERO    = 52f
    const val PX_TITLE   = 32f
    const val PX_HEADING = 26f
    const val PX_BODY    = 22f
    const val PX_LABEL   = 18f
    const val PX_CAPTION = 16f
    const val PX_MICRO   = 14f

    // ══════════════════════════════════════════════════════
    //  SPACING (dp)
    // ══════════════════════════════════════════════════════

    const val SP_2  =  2
    const val SP_4  =  4
    const val SP_8  =  8
    const val SP_12 = 12
    const val SP_16 = 16
    const val SP_24 = 24
    const val SP_32 = 32
    const val SP_48 = 48

    // Legacy compat
    const val PADDING_LARGE  = SP_16
    const val PADDING_MEDIUM = SP_12
    const val PADDING_SMALL  = SP_8
    const val PADDING_TINY   = SP_4

    // ══════════════════════════════════════════════════════
    //  CORNER RADII (dp)
    // ══════════════════════════════════════════════════════

    const val R_4    =  4f
    const val R_8    =  8f
    const val R_12   = 12f
    const val R_16   = 16f
    const val R_FULL = 999f

    // Legacy compat
    const val CORNER_RADIUS       = R_8
    const val CORNER_RADIUS_SMALL = R_4

    // ══════════════════════════════════════════════════════
    //  FILE PICKER BADGES
    // ══════════════════════════════════════════════════════

    val BADGE_VR180    = BLUE
    val BADGE_VR360    = PURPLE_DEEP
    val BADGE_FISHEYE  = CYAN_ICE
    val BADGE_SBS      = GOLD_WARM
    val BADGE_TB       = ORANGE
    val BADGE_ALPHA    = PINK_SOFT
    val BADGE_FAVORITE = GOLD_WARM
    val BADGE_RESUME   = PINK_HOT
    val BADGE_SUBTITLE = BLUE

    val STAR_FILLED = GOLD_WARM
    val STAR_EMPTY  = Color.parseColor("#2A2434")

    // ══════════════════════════════════════════════════════
    //  VIEW FACTORY HELPERS
    // ══════════════════════════════════════════════════════

    /** Convert dp to pixels */
    fun dp(ctx: Context, dp: Int): Int = (dp * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, dp: Int): Float = dp * ctx.resources.displayMetrics.density

    /** Create a rounded rect drawable with optional border */
    fun roundedBg(fill: Int, radiusDp: Float = R_8, borderColor: Int = 0, borderWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp
            if (borderColor != 0 && borderWidth > 0) {
                setStroke(borderWidth, borderColor)
            }
        }
    }

    /** Create a primary button (hot pink gradient) */
    fun makePrimaryButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = TEXT_BODY
            setTextColor(TEXT_BRIGHT)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            setPadding(dp(ctx, SP_16), dp(ctx, SP_12), dp(ctx, SP_16), dp(ctx, SP_12))
            background = makeButtonDrawable(ctx, PINK_HOT, PINK_DARK, R_8)
            setOnClickListener { onClick() }
        }
    }

    /** Create a secondary button (outlined, subtle) */
    fun makeSecondaryButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = TEXT_BODY
            setTextColor(TEXT_MID)
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            setPadding(dp(ctx, SP_16), dp(ctx, SP_12), dp(ctx, SP_16), dp(ctx, SP_12))
            background = roundedBg(BG_SURFACE, R_8, BORDER_SOFT, dp(ctx, 1))
            setOnClickListener { onClick() }
        }
    }

    /** Create a danger button (dark red) */
    fun makeDangerButton(ctx: Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = TEXT_BODY
            setTextColor(RED)
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 48)
            setPadding(dp(ctx, SP_16), dp(ctx, SP_12), dp(ctx, SP_16), dp(ctx, SP_12))
            background = roundedBg(Color.parseColor("#2A0A10"), R_8, Color.argb(0x4D, 0xF0, 0x48, 0x58), dp(ctx, 1))
            setOnClickListener { onClick() }
        }
    }

    /** Create a toggle pill button */
    fun makeTogglePill(ctx: Context, label: String, isActive: Boolean, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = label
            textSize = TEXT_LABEL
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 40)
            setPadding(dp(ctx, SP_16), dp(ctx, SP_8), dp(ctx, SP_16), dp(ctx, SP_8))
            if (isActive) {
                setTextColor(Color.WHITE)
                background = roundedBg(PINK_HOT, R_FULL)
            } else {
                setTextColor(TEXT_DIM)
                background = roundedBg(BG_SURFACE, R_FULL, BORDER_SOFT, dp(ctx, 1))
            }
            setOnClickListener { onClick() }
        }
    }

    /** Create a section header label */
    fun makeSectionHeader(ctx: Context, title: String, accentColor: Int = PINK_SOFT): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, SP_24), 0, dp(ctx, SP_8))

            // Accent dot
            addView(View(ctx).apply {
                background = roundedBg(accentColor, R_FULL)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6)).apply {
                    marginEnd = dp(ctx, SP_8)
                }
            })

            // Section text
            addView(TextView(ctx).apply {
                text = title.uppercase()
                textSize = TEXT_LABEL
                setTextColor(accentColor)
                setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.08f
            })
        }
    }

    /** Create a gradient divider line (transparent → accent → transparent) */
    fun makeDivider(ctx: Context, color: Int = BORDER_PINK): View {
        return View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)
            ).apply {
                topMargin = dp(ctx, SP_4)
                bottomMargin = dp(ctx, SP_4)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT)
            )
        }
    }

    /** Create a file item card background */
    fun makeFileCardBg(ctx: Context, isSelected: Boolean = false): GradientDrawable {
        return if (isSelected) {
            roundedBg(FILE_ITEM_SELECTED, R_8, PINK_HOT, dp(ctx, 1))
        } else {
            roundedBg(BG_SURFACE, R_8, BORDER_SUBTLE, dp(ctx, 1))
        }
    }

    /** Create a badge pill (small colored tag) */
    fun makeBadge(ctx: Context, label: String, color: Int): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = TEXT_MICRO
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, SP_8), dp(ctx, SP_2), dp(ctx, SP_8), dp(ctx, SP_2))
            background = roundedBg(Color.argb(0x28, Color.red(color), Color.green(color), Color.blue(color)), R_FULL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(ctx, SP_4) }
        }
    }

    /** Internal: create a gradient button drawable (pressed state = darker) */
    private fun makeButtonDrawable(ctx: Context, topColor: Int, bottomColor: Int, radius: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor)
        ).apply {
            cornerRadius = dpf(ctx, radius.toInt())
        }
    }

    // ══════════════════════════════════════════════════════
    //  BADGE COLOR HELPERS
    // ══════════════════════════════════════════════════════

    fun projectionColor(projection: String): Int {
        return when {
            projection.contains("360", ignoreCase = true) -> BADGE_VR360
            projection.contains("180", ignoreCase = true) -> BADGE_VR180
            projection.contains("fisheye", ignoreCase = true) -> BADGE_FISHEYE
            else -> TEXT_DIM
        }
    }

    fun stereoColor(stereoMode: String): Int {
        return when {
            stereoMode.contains("sbs", ignoreCase = true) ||
            stereoMode.contains("lr", ignoreCase = true) -> BADGE_SBS
            stereoMode.contains("tb", ignoreCase = true) -> BADGE_TB
            else -> TEXT_DIM
        }
    }
}
