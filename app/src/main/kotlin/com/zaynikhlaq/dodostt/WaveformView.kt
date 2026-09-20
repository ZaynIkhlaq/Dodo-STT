package com.zaynikhlaq.dodostt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * The level meter on the listening panel. Ten capsules on a shared midline that grow in both
 * directions, never to zero — the resting pose is itself a shape, so silence still looks alive.
 *
 * Bar width, gap and the resting heights are measured from the reference: 9 dp bars on an 18 dp
 * pitch, heights running 17-27-17-35-44-53-27-44-35-17 dp.
 */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barWidth = 9f * density
    private val pitch = 18f * density
    private val resting = floatArrayOf(17f, 27f, 17f, 35f, 44f, 53f, 27f, 44f, 35f, 17f)
    private val stub = 9f * density

    private var targetLevel = 0f
    private var shownLevel = 0f
    var live = false
        set(value) {
            field = value
            if (!value) shownLevel = 0f
            invalidate()
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kb_label) }
    private val rect = RectF()

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (pitch * (resting.size - 1) + barWidth).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize((56 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        shownLevel += (targetLevel - shownLevel) * 0.25f
        val midY = height / 2f
        val total = pitch * (resting.size - 1) + barWidth
        var x = (width - total) / 2f
        val t = SystemClock.uptimeMillis() / 260.0

        for (i in resting.indices) {
            // Each bar leads the next slightly, so a loud syllable travels across the group.
            val ripple = 0.72f + 0.28f * sin(t + i * 0.7).toFloat()
            val reach = if (live) (0.22f + 0.78f * shownLevel * ripple) else 0.34f
            val h = (resting[i] * density * reach).coerceAtLeast(stub)
            rect.set(x, midY - h / 2f, x + barWidth, midY + h / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, bar)
            x += pitch
        }
        if (live) postInvalidateOnAnimation()
    }
}
