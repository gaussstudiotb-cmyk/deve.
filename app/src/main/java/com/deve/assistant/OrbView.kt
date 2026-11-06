package com.deve.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** L'orbe de DeVE : violet sombre et rose sombre, qui pulse selon l'état. */
class OrbView(context: Context) : View(context) {

    enum class State { LISTENING, THINKING, SPEAKING, RECORDING }

    @Volatile
    var state = State.LISTENING

    private var level = 0f
    private var target = 0f
    private var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val violet = Color.parseColor("#4A1A8C")
    private val violetDeep = Color.parseColor("#1E0838")
    private val rose = Color.parseColor("#C2336E")
    private val roseBright = Color.parseColor("#E0457F")

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            level += (target - level) * 0.18f
            invalidate()
        }
    }

    /** Niveau sonore entre 0 et 1 (la voix fait gonfler l'orbe). */
    fun setLevel(v: Float) {
        target = v.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = min(width, height) / 2f
        val base = half * 0.5f
        val t = (phase * 2.0 * PI).toFloat()

        val r = when (state) {
            State.LISTENING -> base * (1f + 0.30f * level + 0.03f * sin(t))
            State.THINKING -> base * (1f + 0.04f * sin(2f * t))
            State.SPEAKING -> base * (1.10f + 0.10f * sin(3f * t))
            State.RECORDING -> base * (1f + 0.06f * sin(t))
        }

        // Halo
        val haloR = min(r * 1.8f, half)
        paint.shader = RadialGradient(
            cx, cy, haloR,
            intArrayOf(alpha(rose, 120), alpha(violet, 50), Color.TRANSPARENT),
            floatArrayOf(0.4f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloR, paint)

        // Corps de l'orbe
        val core = if (state == State.RECORDING) roseBright else rose
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
            intArrayOf(core, violet, violetDeep),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)

        // Anneau qui tourne pendant la réflexion
        if (state == State.THINKING) {
            canvas.save()
            canvas.rotate(phase * 360f, cx, cy)
            ring.shader = SweepGradient(
                cx, cy,
                intArrayOf(Color.TRANSPARENT, roseBright, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f)
            )
            ring.strokeWidth = r * 0.09f
            canvas.drawCircle(cx, cy, r * 1.22f, ring)
            canvas.restore()
        }
    }
}
