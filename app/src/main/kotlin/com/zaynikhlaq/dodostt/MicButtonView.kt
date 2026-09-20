package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** The big round mic: pulses with the voice while recording, spins while transcribing. */
class MicButtonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { IDLE, RECORDING, BUSY }

    var mode = Mode.IDLE
        set(value) {
            field = value
            if (value != Mode.RECORDING) shownLevel = 0f
            invalidate()
        }

    private var targetLevel = 0f
    private var shownLevel = 0f

    private val accent = context.getColor(R.color.accent)
    private val recording = context.getColor(R.color.accent_recording)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val arcBounds = RectF()
    private val micIcon = context.getDrawable(R.drawable.ic_mic)!!.mutate().apply { setTint(context.getColor(R.color.on_accent)) }
    private val stopIcon = context.getDrawable(R.drawable.ic_stop)!!.mutate().apply { setTint(context.getColor(R.color.on_accent)) }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.56f

        when (mode) {
            Mode.RECORDING -> {
                // Ease towards the live level so the halo breathes instead of flickering.
                shownLevel += (targetLevel - shownLevel) * 0.25f
                halo.color = recording
                halo.alpha = 36
                canvas.drawCircle(cx, cy, base * (1.18f + shownLevel * 0.55f), halo)
                halo.alpha = 64
                canvas.drawCircle(cx, cy, base * (1.08f + shownLevel * 0.28f), halo)
                fill.color = recording
                postInvalidateOnAnimation()
            }
            Mode.BUSY -> {
                fill.color = accent
                fill.alpha = 150
                arc.strokeWidth = base * 0.09f
                val r = base * 1.22f
                arcBounds.set(cx - r, cy - r, cx + r, cy + r)
                val start = (SystemClock.uptimeMillis() / 3L % 360L).toFloat()
                canvas.drawArc(arcBounds, start, 100f, false, arc)
                postInvalidateOnAnimation()
            }
            Mode.IDLE -> fill.color = accent
        }

        canvas.drawCircle(cx, cy, if (isPressed) base * 0.96f else base, fill)
        fill.alpha = 255

        val icon = if (mode == Mode.RECORDING) stopIcon else micIcon
        val half = (base * 0.5f).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
