package com.nexustv.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Visualização compacta para transmissões de rádio que não possuem vídeo. */
class RadioWaveView(context: Context) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 235, 244, 255)
        textSize = 13f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2_400L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }
    private var phase = 0f
    private val bars =  thirtyBars()

    init {
        isFocusable = false
        isClickable = false
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, 1_000f,
            intArrayOf(Color.rgb(5, 12, 29), Color.rgb(9, 18, 42), Color.rgb(4, 8, 20)),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    private fun thirtyBars(): FloatArray = floatArrayOf(
        0.25f, 0.38f, 0.52f, 0.34f, 0.65f, 0.48f, 0.78f, 0.55f,
        0.92f, 0.68f, 0.82f, 0.46f, 0.72f, 0.98f, 0.62f, 0.86f,
        0.52f, 0.78f, 0.96f, 0.58f, 0.84f, 0.68f, 0.92f, 0.48f,
        0.72f, 0.42f, 0.62f, 0.34f, 0.50f, 0.28f,
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    fun stopAnimation() {
        animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val radius = 22f
        canvas.drawRoundRect(RectF(0f, 0f, width, height), radius, radius, backgroundPaint)

        val centerX = width * 0.5f
        val centerY = height * 0.52f
        val glowRadius = width * 0.45f
        glowPaint.shader = RadialGradient(
            centerX, centerY, glowRadius,
            intArrayOf(Color.argb(105, 0, 220, 255), Color.argb(36, 76, 70, 255), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)

        ringPaint.color = Color.argb(90, 76, 232, 240)
        canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.24f, ringPaint)
        ringPaint.color = Color.argb(58, 241, 182, 74)
        canvas.drawCircle(centerX, centerY, minOf(width, height) * 0.31f, ringPaint)

        val barAreaWidth = width * 0.78f
        val barWidth = (barAreaWidth / bars.size) * 0.46f
        val gap = barAreaWidth / bars.size
        val startX = (width - barAreaWidth) * 0.5f
        val maxBarHeight = height * 0.54f
        val baseline = height * 0.72f
        barPaint.shader = LinearGradient(
            0f, baseline - maxBarHeight, 0f, baseline,
            intArrayOf(Color.rgb(123, 97, 255), Color.rgb(114, 93, 255), Color.rgb(244, 123, 156), Color.rgb(241, 182, 74)),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        barPaint.setShadowLayer(10f, 0f, 0f, Color.argb(205, 34, 206, 255))
        bars.forEachIndexed { index, base ->
            val pulse = 0.62f + 0.38f * ((sin(phase * 1.25f + index * 0.62f) + 1f) * 0.5f)
            val heightValue = maxOf(height * 0.08f, maxBarHeight * base * pulse)
            val x = startX + index * gap + (gap - barWidth) * 0.5f
            canvas.drawRoundRect(RectF(x, baseline - heightValue, x + barWidth, baseline), barWidth, barWidth, barPaint)
        }
        barPaint.clearShadowLayer()

        val wave = Path()
        val waveWidth = width * 0.64f
        val waveStart = centerX - waveWidth * 0.5f
        val waveY = height * 0.34f
        val step = waveWidth / 42f
        for (i in 0..42) {
            val x = waveStart + i * step
            val envelope = 0.30f + 0.70f * sin(Math.PI * i / 42.0).toFloat()
            val y = waveY + sin(phase * 1.55f + i * 0.52f) * height * 0.105f * envelope
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        wavePaint.shader = LinearGradient(
            waveStart, 0f, waveStart + waveWidth, 0f,
            intArrayOf(Color.rgb(123, 97, 255), Color.rgb(134, 92, 255), Color.rgb(244, 123, 156), Color.rgb(241, 182, 74)),
            null,
            Shader.TileMode.CLAMP,
        )
        wavePaint.setShadowLayer(9f, 0f, 0f, Color.argb(185, 65, 225, 255))
        canvas.drawPath(wave, wavePaint)
        wavePaint.clearShadowLayer()

        canvas.drawText("RÁDIO AO VIVO", centerX, height * 0.91f, labelPaint)
    }
}
