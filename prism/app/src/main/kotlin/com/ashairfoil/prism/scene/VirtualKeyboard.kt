package com.ashairfoil.prism.scene

import android.graphics.*

/**
 * On-screen QWERTY keyboard for the VR save-name editor.
 * Pre-computes key layout at construction time (zero per-frame allocation).
 * Rendered to the Canvas bitmap panel by UiRenderer.
 */
class VirtualKeyboard {

    enum class KeyAction { INSERT, BACKSPACE, SHIFT, SPACE }

    data class VKey(
        val label: String,
        val char: Char,
        val x: Float, val y: Float,
        val w: Float, val h: Float,
        val action: KeyAction = KeyAction.INSERT
    )

    var isShifted = false

    val keys: List<VKey> = buildLayout()

    // Pre-allocated paints (zero-alloc render loop)
    private val keyBgPaint = Paint().apply { isAntiAlias = true }
    private val keyBorderPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val keyTextPaint = Paint().apply {
        isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val glowPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.OUTER)
    }

    fun hitTest(px: Float, py: Float): Int {
        for ((i, k) in keys.withIndex()) {
            if (px in k.x..(k.x + k.w) && py in k.y..(k.y + k.h)) return i
        }
        return -1
    }

    fun getKeyChar(index: Int): Pair<KeyAction, Char> {
        val k = keys.getOrNull(index) ?: return Pair(KeyAction.INSERT, ' ')
        if (k.action != KeyAction.INSERT) return Pair(k.action, k.char)
        val ch = if (isShifted) k.char.uppercaseChar() else k.char.lowercaseChar()
        return Pair(KeyAction.INSERT, ch)
    }

    fun render(canvas: Canvas, hoveredKeyIndex: Int) {
        for ((i, k) in keys.withIndex()) {
            val isHovered = i == hoveredKeyIndex
            val isSpecial = k.action != KeyAction.INSERT
            val isShiftKey = k.action == KeyAction.SHIFT

            // Background
            keyBgPaint.color = when {
                isShiftKey && isShifted -> 0xFF8B5CF6.toInt()
                isHovered -> 0xFF2A2A38.toInt()
                isSpecial -> 0xFF202030.toInt()
                else -> 0xFF1A1A24.toInt()
            }
            canvas.drawRoundRect(k.x, k.y, k.x + k.w, k.y + k.h, 8f, 8f, keyBgPaint)

            // Border
            keyBorderPaint.strokeWidth = if (isHovered) 2f else 0.8f
            keyBorderPaint.color = when {
                isHovered -> 0xFF8B5CF6.toInt()
                isSpecial -> 0xFF3A3A4A.toInt()
                else -> 0xFF2A2A34.toInt()
            }
            canvas.drawRoundRect(k.x, k.y, k.x + k.w, k.y + k.h, 8f, 8f, keyBorderPaint)

            // Glow on hover
            if (isHovered) {
                glowPaint.color = 0xFF8B5CF6.toInt()
                glowPaint.strokeWidth = 3f
                canvas.drawRoundRect(k.x, k.y, k.x + k.w, k.y + k.h, 8f, 8f, glowPaint)
            }

            // Text
            keyTextPaint.textSize = if (isSpecial) 22f else 28f
            keyTextPaint.color = when {
                isHovered -> 0xFFFFFFFF.toInt()
                isShiftKey && isShifted -> 0xFFFFFFFF.toInt()
                isSpecial -> 0xFF9090A0.toInt()
                else -> 0xFFE8E8EC.toInt()
            }
            keyTextPaint.isFakeBoldText = isHovered || (isShiftKey && isShifted)

            val displayLabel = if (k.action == KeyAction.INSERT && !isSpecial) {
                if (isShifted) k.label.uppercase() else k.label.lowercase()
            } else k.label
            canvas.drawText(displayLabel, k.x + k.w / 2f, k.y + k.h * 0.68f, keyTextPaint)
        }
    }

    companion object {
        private const val KB_LEFT = 20f
        private const val KB_TOP = 260f
        private const val KEY_H = 52f
        private const val KEY_GAP = 4f
        private const val ROW_GAP = 6f
        private const val PANEL_W = 1024f

        private fun buildLayout(): List<VKey> {
            val keys = mutableListOf<VKey>()
            val usable = PANEL_W - 2 * KB_LEFT

            // Row 0: 1-9, 0, BKSP (11 keys)
            val r0Chars = "1234567890"
            val r0Count = 11
            val r0KeyW = (usable - (r0Count - 1) * KEY_GAP) / r0Count
            val r0Y = KB_TOP
            for (i in r0Chars.indices) {
                keys.add(VKey(
                    r0Chars[i].toString(), r0Chars[i],
                    KB_LEFT + i * (r0KeyW + KEY_GAP), r0Y, r0KeyW, KEY_H
                ))
            }
            keys.add(VKey(
                "\u2190", ' ',
                KB_LEFT + 10 * (r0KeyW + KEY_GAP), r0Y, r0KeyW, KEY_H,
                KeyAction.BACKSPACE
            ))

            // Row 1: QWERTYUIOP (10 keys, offset 15px)
            val r1Chars = "QWERTYUIOP"
            val r1Offset = 15f
            val r1Count = 10
            val r1KeyW = (usable - r1Offset - (r1Count - 1) * KEY_GAP) / r1Count
            val r1Y = r0Y + KEY_H + ROW_GAP
            for (i in r1Chars.indices) {
                keys.add(VKey(
                    r1Chars[i].toString(), r1Chars[i],
                    KB_LEFT + r1Offset + i * (r1KeyW + KEY_GAP), r1Y, r1KeyW, KEY_H
                ))
            }

            // Row 2: ASDFGHJKL (9 keys, offset 30px)
            val r2Chars = "ASDFGHJKL"
            val r2Offset = 30f
            val r2Count = 9
            val r2KeyW = (usable - r2Offset - (r2Count - 1) * KEY_GAP) / r2Count
            val r2Y = r1Y + KEY_H + ROW_GAP
            for (i in r2Chars.indices) {
                keys.add(VKey(
                    r2Chars[i].toString(), r2Chars[i],
                    KB_LEFT + r2Offset + i * (r2KeyW + KEY_GAP), r2Y, r2KeyW, KEY_H
                ))
            }

            // Row 3: SHIFT + ZXCVBNM + BKSP (9 elements)
            val r3Chars = "ZXCVBNM"
            val r3Y = r2Y + KEY_H + ROW_GAP
            val r3WideW = (usable * 0.14f)  // SHIFT and BKSP are wider
            val r3NormalCount = r3Chars.length
            val r3NormalW = (usable - 2 * r3WideW - (r3NormalCount + 1) * KEY_GAP) / r3NormalCount

            keys.add(VKey(
                "SHIFT", ' ',
                KB_LEFT, r3Y, r3WideW, KEY_H,
                KeyAction.SHIFT
            ))
            for (i in r3Chars.indices) {
                keys.add(VKey(
                    r3Chars[i].toString(), r3Chars[i],
                    KB_LEFT + r3WideW + KEY_GAP + i * (r3NormalW + KEY_GAP), r3Y,
                    r3NormalW, KEY_H
                ))
            }
            keys.add(VKey(
                "BKSP", ' ',
                KB_LEFT + usable - r3WideW, r3Y, r3WideW, KEY_H,
                KeyAction.BACKSPACE
            ))

            // Row 4: Aa + SPACE + . - _
            val r4Y = r3Y + KEY_H + ROW_GAP
            val r4SmallW = usable * 0.1f
            val r4SpaceW = usable - 3 * r4SmallW - r4SmallW - 4 * KEY_GAP

            keys.add(VKey(
                "Aa", ' ',
                KB_LEFT, r4Y, r4SmallW, KEY_H,
                KeyAction.SHIFT
            ))
            keys.add(VKey(
                "SPACE", ' ',
                KB_LEFT + r4SmallW + KEY_GAP, r4Y, r4SpaceW, KEY_H,
                KeyAction.SPACE
            ))
            keys.add(VKey(
                ".", '.',
                KB_LEFT + r4SmallW + KEY_GAP + r4SpaceW + KEY_GAP, r4Y,
                r4SmallW, KEY_H
            ))
            keys.add(VKey(
                "-", '-',
                KB_LEFT + r4SmallW + 2 * KEY_GAP + r4SpaceW + r4SmallW, r4Y,
                r4SmallW, KEY_H
            ))
            keys.add(VKey(
                "_", '_',
                KB_LEFT + r4SmallW + 3 * KEY_GAP + r4SpaceW + 2 * r4SmallW, r4Y,
                r4SmallW, KEY_H
            ))

            return keys
        }
    }
}
