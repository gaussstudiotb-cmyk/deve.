package com.deve.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Trait de lumière néon qui parcourt le bord de l'écran de gauche à droite, puis s'efface. */
class NeonBorderView(context: Context, private val onDone: () -> Unit) : View(context) {

    private var progress = 0f
    private var ended = false
    private var bandW = 0f
    private var bandShader: LinearGradient? = null
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private val dens = resources.displayMetrics.density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val colors = intArrayOf(
        Color.TRANSPARENT,
        Color.parseColor("#38E8FF"),
        Color.parseColor("#3A6BFF"),
        Color.parseColor("#8B3BFF"),
        Color.parseColor("#FF3BC8"),
        Color.TRANSPARENT
    )
    private val positions = floatArrayOf(0f, 0.18f, 0.45f, 0.72f, 0.9f, 1f)

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                end()
            }
        })
    }

    private fun end() {
        if (ended) return
        ended = true
        post { onDone() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bandW = w * 0.5f
        val s = LinearGradient(0f, 0f, bandW, 0f, colors, positions, Shader.TileMode.CLAMP)
        bandShader = s
        strokePaint.shader = s
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.removeAllListeners()
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val s = bandShader ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        // La bande lumineuse avance de la gauche vers la droite de l'écran.
        shaderMatrix.setTranslate(-bandW + progress * (w + bandW), 0f)
        s.setLocalMatrix(shaderMatrix)

        val fade = min(1f, progress / 0.12f) * min(1f, (1f - progress) / 0.25f)
        val inset = 3f * dens
        rect.set(inset, inset, w - inset, h - inset)
        val rad = 44f * dens

        strokePaint.strokeWidth = 24f * dens
        strokePaint.alpha = (50 * fade).toInt()
        c.drawRoundRect(rect, rad, rad, strokePaint)
        strokePaint.strokeWidth = 12f * dens
        strokePaint.alpha = (110 * fade).toInt()
        c.drawRoundRect(rect, rad, rad, strokePaint)
        strokePaint.strokeWidth = 4f * dens
        strokePaint.alpha = (255 * fade).toInt()
        c.drawRoundRect(rect, rad, rad, strokePaint)
    }
}
