package com.ashairfoil.prism.playback

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.min

/**
 * BufferingIndicator — Circular loading spinner for VR video viewport.
 *
 * Shows a Material-style rotating arc when the video is buffering.
 * Transparent background so it overlays the video viewport.
 * Self-contained custom View — no XML required.
 *
 * Usage:
 *   val indicator = BufferingIndicator(context)
 *   viewportLayout.addView(indicator)
 *   // When buffering starts:
 *   indicator.show()
 *   // When buffering ends:
 *   indicator.hide()
 */
class BufferingIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 0, 0, 0)
    }

    private var sweepAngle = 90f
    private var startAngle = 0f
    private val rect = RectF()
    private var animator: ObjectAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    private val spinnerSize = (48 * context.resources.displayMetrics.density).toInt()

    init {
        visibility = GONE
    }

    fun show() {
        visibility = VISIBLE
        startAnimation()
    }

    fun hide() {
        stopAnimation()
        visibility = GONE
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    private fun startAnimation() {
        // Rotation animation
        animator?.cancel()
        animator = ObjectAnimator.ofFloat(this, "rotation", 0f, 360f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = null // Linear
            start()
        }

        // Sweep length animation (grows and shrinks)
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(30f, 270f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { sweepAngle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        sweepAnimator?.cancel()
        animator = null
        sweepAnimator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(spinnerSize, spinnerSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val padding = paint.strokeWidth * 2
        rect.set(padding, padding, size - padding, size - padding)

        // Subtle background circle
        canvas.drawCircle(size / 2, size / 2, size / 2 - paint.strokeWidth, bgPaint)

        // Spinning arc
        canvas.drawArc(rect, startAngle, sweepAngle, false, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    companion object {
        /**
         * Create and attach a buffering indicator centered in the given parent.
         */
        fun createIn(parent: ViewGroup): BufferingIndicator {
            val indicator = BufferingIndicator(parent.context)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            parent.addView(indicator, lp)
            return indicator
        }
    }
}
