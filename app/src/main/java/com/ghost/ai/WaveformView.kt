package com.ghost.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.ghost.ai.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cyan to purple bar waveform. Feed it microphone levels with [setLevel] (0..1);
 * it keeps a gentle idle motion so it always looks alive.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 29
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cyan = context.getColor(R.color.ghost_cyan)
    private val purple = context.getColor(R.color.ghost_purple)

    private var level = 0f
    private var targetLevel = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** @param value microphone level in the range 0..1 */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, cyan, purple, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slot = w / barCount
        val barWidth = slot * 0.55f
        val centerY = h / 2f

        for (i in 0 until barCount) {
            val x = slot * i + slot / 2f
            val envelope = sin(PI * (i + 0.5) / barCount).toFloat()
            val wobble = 0.5f + 0.5f * sin(phase * 1.7f + i * 0.9f)
            val amplitude = 0.10f + 0.90f * level * envelope * (0.45f + 0.55f * wobble) + 0.05f * wobble
            val half = (h / 2f) * amplitude.coerceIn(0.06f, 1f)
            rect.set(x - barWidth / 2f, centerY - half, x + barWidth / 2f, centerY + half)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase += 0.18f
                level += (targetLevel - level) * 0.25f
                targetLevel *= 0.92f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }
}
