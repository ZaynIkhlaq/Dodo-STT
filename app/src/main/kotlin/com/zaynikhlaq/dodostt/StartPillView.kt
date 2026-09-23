package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * The one control that matters: a filled pill reading "Start", with a five-bar level meter beside
 * the word. Sized and coloured from the reference screenshot — 105 × 33 dp, fully rounded, the
 * highest-contrast thing on the keyboard.
 *
 * While recording, the bars follow the live input level. While transcribing they run a slow
 * standing wave of their own, so the control never looks frozen.
 */
class StartPillView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    enum class Mode { IDLE, RECORDING, BUSY }

    var mode = Mode.IDLE
        set(value) {
            field = value
            if (value == Mode.IDLE) shownLevel = 0f
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private val barWidth = 2.6f * density
    private val barPitch = 4.2f * density
    private val barMax = 14f * density
    private val gap = 15f * density

    /** Resting heights of the five bars, as a fraction of [barMax]. */
    private val resting = floatArrayOf(1f, 0.55f, 0.8f, 0.55f, 1f)

    private var targetLevel = 0f
    private var shownLevel = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f * scaled
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    private val pill = RectF()
    private val barRect = RectF()

    private val pillColor = context.getColor(R.color.bar_pill)
    private val onPill = context.getColor(R.color.bar_on_pill)
    private val recording = context.getColor(R.color.rec)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    private fun caption() = when (mode) {
        Mode.IDLE -> context.getString(R.string.pill_start)
        Mode.RECORDING -> context.getString(R.string.pill_stop)
        Mode.BUSY -> context.getString(R.string.pill_busy)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((106 * density).toInt(), widthMeasureSpec),
            resolveSize((44 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = 33f * density
        val top = (height - h) / 2f
        pill.set(0f, top, width.toFloat(), top + h)
        fill.color = pillColor
        fill.alpha = if (isPressed) 200 else 255
        canvas.drawRoundRect(pill, h / 2f, h / 2f, fill)

        val text = caption()
        val textWidth = ink.measureText(text)
        val bars = barPitch * (resting.size - 1) + barWidth
        val group = textWidth + gap + bars
        val startX = (width - group) / 2f
        val midY = pill.centerY()

        ink.color = if (mode == Mode.RECORDING) recording else onPill
        canvas.drawText(text, startX, midY - (ink.descent() + ink.ascent()) / 2f, ink)

        bar.color = if (mode == Mode.RECORDING) recording else onPill
        // Ease toward the live level so the meter breathes rather than flickers.
        shownLevel += (targetLevel - shownLevel) * 0.25f
        val phase = SystemClock.uptimeMillis() / 220.0
        var x = startX + textWidth + gap
        for (i in resting.indices) {
            val barH = when (mode) {
                Mode.IDLE -> resting[i]
                // Each bar is offset a little so the group ripples instead of pumping as one.
                Mode.RECORDING -> (0.28f + shownLevel * 1.15f * resting[i]).coerceIn(0.18f, 1f)
                Mode.BUSY -> 0.3f + 0.55f * abs(sin(phase + i * 0.6).toFloat())
            } * barMax
            barRect.set(x, midY - barH / 2f, x + barWidth, midY + barH / 2f)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, bar)
            x += barPitch
        }

        if (mode != Mode.IDLE) postInvalidateOnAnimation()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
