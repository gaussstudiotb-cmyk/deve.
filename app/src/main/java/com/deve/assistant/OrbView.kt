package com.deve.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * L'orbe de DeVE : une bille de verre irisée. Cœur sombre bleu-violet aux remous fluides,
 * bord aux reflets arc-en-ciel, petit reflet de lumière. Tout est dessiné dans un repère
 * « rayon = 1 » : les dégradés sont créés une seule fois, rien n'est alloué pendant l'animation.
 */
class OrbView(context: Context) : View(context) {

    enum class State { LISTENING, THINKING, SPEAKING, RECORDING }

    @Volatile
    var state = State.LISTENING

    private var level = 0f
    private var targetLevel = 0f
    private var tClock = 0f
    private var lastNs = 0L

    private fun col(hex: String) = Color.parseColor(hex)
    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun radial(cx: Float, cy: Float, r: Float, colors: IntArray, pos: FloatArray?) =
        RadialGradient(cx, cy, r, colors, pos, Shader.TileMode.CLAMP)

    private fun fill(s: Shader) = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = s }

    // Halo doux autour de la bille
    private val halo = fill(
        radial(
            0f, 0f, 1.5f,
            intArrayOf(withAlpha(col("#5B3BFF"), 90), withAlpha(col("#2D6BFF"), 40), Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.78f, 1f)
        )
    )

    // Corps de la bille : presque noir, plus lumineux vers le bas à droite
    private val body = fill(
        radial(
            0.25f, 0.35f, 1.4f,
            intArrayOf(col("#4524C8"), col("#170A5E"), col("#05021F")),
            floatArrayOf(0f, 0.55f, 1f)
        )
    )
    private val bodyRec = fill(
        radial(
            0.25f, 0.35f, 1.4f,
            intArrayOf(col("#B0226A"), col("#3B0A52"), col("#0A0218")),
            floatArrayOf(0f, 0.55f, 1f)
        )
    )

    // Remous à l'intérieur
    private val blobs = listOf("#7A4BFF", "#2D7BFF", "#D23BC0").map {
        fill(radial(0f, 0f, 0.9f, intArrayOf(withAlpha(col(it), 190), Color.TRANSPARENT), null))
    }
    private val blobsRec = listOf("#FF2D8A", "#8A2BFF", "#C2185B").map {
        fill(radial(0f, 0f, 0.9f, intArrayOf(withAlpha(col(it), 190), Color.TRANSPARENT), null))
    }

    // Reflet de lumière en haut à gauche
    private val gloss = fill(
        radial(-0.45f, -0.5f, 0.55f, intArrayOf(withAlpha(Color.WHITE, 80), Color.TRANSPARENT), null)
    )

    // Bord irisé (arc-en-ciel)
    private val ringColors = intArrayOf(
        col("#38E8FF"), col("#3A6BFF"), col("#8B3BFF"), col("#FF3BC8"),
        col("#FF9A3B"), col("#FFE23B"), col("#3BFF9A"), col("#38E8FF")
    )
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = SweepGradient(0f, 0f, ringColors, null)
    }

    private val specular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val swoosh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = col("#FF6AD5")
    }
    private val arcBox = RectF(-0.9f, -0.9f, 0.9f, 0.9f)
    private val swooshBox = RectF(-0.82f, -0.82f, 0.82f, 0.82f)
    private val unitCircle = Path().apply { addCircle(0f, 0f, 1f, Path.Direction.CW) }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val now = System.nanoTime()
            val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.05f)
            lastNs = now
            level += (targetLevel - level) * 0.18f
            val speed = when (state) {
                State.LISTENING -> 0.8f + 1.2f * level
                State.THINKING -> 2.2f
                State.SPEAKING -> 1.4f
                State.RECORDING -> 0.6f
            }
            tClock += dt * speed
            invalidate()
        }
    }

    /** Niveau sonore entre 0 et 1 : la voix fait gonfler l'orbe et agite les remous. */
    fun setLevel(v: Float) {
        targetLevel = v.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastNs = 0L
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val half = min(width, height) / 2f
        val t = tClock
        val rec = state == State.RECORDING

        val pulse = when (state) {
            State.LISTENING -> 1f + 0.28f * level + 0.02f * sin(t * 3f)
            State.THINKING -> 1f + 0.03f * sin(t * 5f)
            State.SPEAKING -> 1.08f + 0.08f * sin(t * 7f)
            State.RECORDING -> 1f + 0.05f * sin(t * 2.5f)
        }
        val r = half * 0.46f * pulse

        c.save()
        c.translate(width / 2f, height / 2f)
        c.scale(r, r)

        c.drawCircle(0f, 0f, 1.5f, halo)
        c.drawCircle(0f, 0f, 1f, if (rec) bodyRec else body)

        // Remous fluides, découpés dans la bille
        c.save()
        c.clipPath(unitCircle)
        val set = if (rec) blobsRec else blobs
        val amp = 0.36f + 0.28f * level + (if (state == State.THINKING) 0.08f else 0f)
        for (i in 0 until 3) {
            val a = t * (0.9f + 0.55f * i) + i * 2.1f
            c.save()
            c.translate(amp * cos(a), amp * sin(a * 1.27f + i))
            c.drawCircle(0f, 0f, 0.9f, set[i])
            c.restore()
        }
        c.drawCircle(-0.45f, -0.5f, 0.55f, gloss)
        c.restore()

        // Bord irisé qui tourne lentement
        c.save()
        c.rotate(t * 38f)
        ring.strokeWidth = 0.18f
        ring.alpha = 35
        c.drawCircle(0f, 0f, 0.93f, ring)
        ring.strokeWidth = 0.09f
        ring.alpha = 90
        c.drawCircle(0f, 0f, 0.96f, ring)
        ring.strokeWidth = 0.035f
        ring.alpha = 255
        c.drawCircle(0f, 0f, 0.985f, ring)
        c.restore()

        // Reflets : trait blanc en haut à gauche, courbe rose en bas à droite
        c.save()
        c.rotate(sin(t * 0.8f) * 6f)
        specular.strokeWidth = 0.028f
        specular.alpha = 200
        c.drawArc(arcBox, 200f, 48f, false, specular)
        swoosh.strokeWidth = 0.07f
        swoosh.alpha = 110
        c.drawArc(swooshBox, 15f, 75f, false, swoosh)
        specular.strokeWidth = 0.014f
        specular.alpha = 150
        c.drawArc(swooshBox, 25f, 55f, false, specular)
        c.restore()

        c.restore()
    }
}
