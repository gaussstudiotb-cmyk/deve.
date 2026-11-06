package com.deve.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/** Faisceau néon violet qui parcourt le bord gauche de l'écran. */
class EdgeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.TRANSPARENT, Color.rgb(91, 42, 158), Color.rgb(190, 135, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.alpha = 145
        canvas.drawRect(0f, 0f, w, h, paint)

        val y = progress * h
        val glow = 90f * resources.displayMetrics.density
        paint.shader = LinearGradient(
            0f, y - glow, 0f, y + glow,
            intArrayOf(Color.TRANSPARENT, Color.rgb(112, 65, 255), Color.WHITE, Color.rgb(112, 65, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.alpha = 255
        canvas.drawRect(0f, maxOf(0f, y - glow), w, minOf(h, y + glow), paint)
        paint.shader = null
    }
}
