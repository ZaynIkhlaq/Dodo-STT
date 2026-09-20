package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The mic orb. Idle it is a quiet accent disc inside a faint ring; recording it turns red and
 * breathes with the voice; transcribing it dims and a single arc travels around it.
 *
 * This is the one place in the app where the accent colour is allowed to be large.
 */
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
    private val recording = context.getColor(R.color.rec)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        val base = min(width, height) / 2f * 0.54f

        when (mode) {
            Mode.IDLE -> {
                fill.color = accent
                // A hairline of accent at low alpha, so the orb sits in something rather than floating.
                ring.color = accent
                ring.alpha = 38
                ring.strokeWidth = base * 0.045f
                canvas.drawCircle(cx, cy, base * 1.3f, ring)
            }
            Mode.RECORDING -> {
                // Ease towards the live level so the halo breathes instead of flickering.
                shownLevel += (targetLevel - shownLevel) * 0.22f
                halo.color = recording
                halo.alpha = 26
                canvas.drawCircle(cx, cy, base * (1.22f + shownLevel * 0.62f), halo)
                halo.alpha = 52
                canvas.drawCircle(cx, cy, base * (1.10f + shownLevel * 0.30f), halo)
                fill.color = recording
                postInvalidateOnAnimation()
            }
            Mode.BUSY -> {
                fill.color = accent
                fill.alpha = 130
                arc.strokeWidth = base * 0.075f
                val r = base * 1.3f
                arcBounds.set(cx - r, cy - r, cx + r, cy + r)
                val start = (SystemClock.uptimeMillis() / 3L % 360L).toFloat()
                canvas.drawArc(arcBounds, start, 86f, false, arc)
                postInvalidateOnAnimation()
            }
        }

        canvas.drawCircle(cx, cy, if (isPressed) base * 0.95f else base, fill)
        fill.alpha = 255

        val icon = if (mode == Mode.RECORDING) stopIcon else micIcon
        val half = (base * 0.52f).toInt()
        icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon.draw(canvas)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
