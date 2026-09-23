package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The whole interface: one button on the edge of the screen.
 *
 * Idle it is a quiet disc with a mic on it. Recording, it fills with red and a halo breathes with
 * your voice, so a glance tells you it is still listening. Transcribing, an arc sweeps the rim.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    enum class Mode { IDLE, RECORDING, BUSY }

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value != Mode.RECORDING) shownLevel = 0f
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val discColor = context.getColor(R.color.orb_bg)
    private val inkColor = context.getColor(R.color.orb_ink)
    private val recColor = context.getColor(R.color.rec)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(6f), 0f, dp(2f), 0x40000000)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val rim = RectF()

    private val micIcon: Drawable = context.getDrawable(R.drawable.ic_mic)!!.mutate()

    private var targetLevel = 0f
    private var shownLevel = 0f

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (mode == Mode.RECORDING) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(64f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(6f)

        if (mode == Mode.RECORDING) {
            // Ease toward the live level so the halo breathes rather than flickers.
            shownLevel += (targetLevel - shownLevel) * 0.25f
            halo.color = recColor
            halo.alpha = 60
            canvas.drawCircle(cx, cy, radius + dp(2f) + dp(6f) * shownLevel, halo)
        }

        fill.color = if (mode == Mode.RECORDING) recColor else discColor
        canvas.drawCircle(cx, cy, radius, fill)

        if (mode == Mode.BUSY) {
            // A sweep round the rim: something is happening, and it isn't the microphone.
            arc.color = inkColor
            rim.set(cx - radius + dp(3f), cy - radius + dp(3f), cx + radius - dp(3f), cy + radius - dp(3f))
            val start = (SystemClock.uptimeMillis() / 3L % 360L).toFloat()
            canvas.drawArc(rim, start, 90f, false, arc)
        }

        val half = dp(13f).toInt()
        micIcon.setTint(if (mode == Mode.RECORDING) context.getColor(R.color.on_accent) else inkColor)
        micIcon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        micIcon.draw(canvas)

        if (mode != Mode.IDLE) postInvalidateOnAnimation()
    }
}
