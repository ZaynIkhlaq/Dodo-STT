package com.zaynikhlaq.dodostt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * The whole interface: one translucent tile docked against the edge of the screen.
 *
 * It is a single object that changes shape rather than a set of views that come and go — idle it is
 * a rounded square with a mic on it, and recording it grows sideways out of the docked edge into a
 * pill holding a level rail and a clock. Wispr Flow's Android bubble works this way and the reason
 * is worth keeping: a control that grows is obviously the same control, where a popup is a new thing
 * to find and understand.
 *
 * Everything it ever says goes in [label], beside the glyph, in the same tile.
 */
class DodoTab @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    enum class Mode { IDLE, RECORDING, BUSY }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    /** The square itself. Everything else is measured off this. */
    private val tile = dp(48f)
    private val radius = dp(16f)
    /** Room around the tile for the shadow to fall into. */
    private val pad = dp(7f)
    private val gap = dp(10f)

    private val bars = 5
    private val barWidth = dp(3f)
    private val barPitch = dp(7f)
    private val barMax = dp(18f)
    private val railWidth = barPitch * (bars - 1) + barWidth

    private val tileColor = context.getColor(R.color.tab_bg)
    private val inkColor = context.getColor(R.color.tab_ink)
    private val recColor = context.getColor(R.color.rec)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(5f), 0f, dp(1.5f), 0x3D000000)
    }
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * scaled
    }
    private val body = RectF()
    private val bar = RectF()

    private val micIcon: Drawable = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val stopIcon: Drawable = context.getDrawable(R.drawable.ic_stop_square)!!.mutate()
    private val cancelIcon: Drawable = context.getDrawable(R.drawable.ic_cross)!!.mutate()

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value != Mode.RECORDING) shownLevel = 0f
            retarget()
        }

    /** The finger has slid away from a hold: letting go now throws the recording away. */
    var armed = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Which edge it is parked against, so the glyph stays outermost and the label grows inward. */
    var dockedLeft = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The clock while recording, a word of help otherwise, or nothing at all. */
    var label: String? = null
        set(value) {
            if (field == value) return
            field = value
            retarget()
        }

    private var targetLevel = 0f
    private var shownLevel = 0f

    private var shownWidth = tile
    private var animator: ValueAnimator? = null

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (mode == Mode.RECORDING) invalidate()
    }

    /** Width the tile wants to be right now: the square, plus whatever it has to show. */
    private fun wantedWidth(): Float {
        val caption = label
        var extra = 0f
        if (mode == Mode.RECORDING) extra += gap + railWidth
        if (!caption.isNullOrEmpty()) extra += gap + text.measureText(caption)
        return tile + extra + if (extra > 0f) dp(6f) else 0f
    }

    /** Grows and shrinks rather than jumping, because it is meant to read as one object moving. */
    private fun retarget() {
        val to = wantedWidth()
        animator?.cancel()
        if (abs(to - shownWidth) < 0.5f) {
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shownWidth, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownWidth = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((shownWidth + pad * 2).toInt(), (tile + pad * 2).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        body.set(pad, pad, width - pad, height - pad)
        fill.color = if (mode == Mode.RECORDING && !armed) recColor else tileColor
        // Idle it sits back at 85%, so it never quite competes with what is underneath.
        fill.alpha = if (mode == Mode.IDLE) 217 else 255
        canvas.drawRoundRect(body, radius, radius, fill)

        val ink = if (mode == Mode.RECORDING && !armed) context.getColor(R.color.on_accent) else inkColor
        val glyphCentre = if (dockedLeft) body.left + tile / 2f else body.right - tile / 2f
        val icon = when {
            armed -> cancelIcon
            mode == Mode.RECORDING -> stopIcon
            else -> micIcon
        }
        val half = (if (mode == Mode.RECORDING && !armed) dp(9f) else dp(12f)).toInt()
        icon.setTint(ink)
        icon.setBounds(
            (glyphCentre - half).toInt(), (body.centerY() - half).toInt(),
            (glyphCentre + half).toInt(), (body.centerY() + half).toInt(),
        )
        icon.draw(canvas)

        // Everything else stacks inward from the glyph, mirrored when docked left.
        var cursor = if (dockedLeft) body.left + tile + gap else body.right - tile - gap
        if (mode == Mode.RECORDING) {
            drawRail(canvas, cursor, ink)
            cursor += if (dockedLeft) railWidth + gap else -(railWidth + gap)
        }
        val caption = label
        if (!caption.isNullOrEmpty()) {
            text.color = ink
            val w = text.measureText(caption)
            val x = if (dockedLeft) cursor else cursor - w
            canvas.drawText(caption, x, body.centerY() - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    private fun drawRail(canvas: Canvas, cursor: Float, ink: Int) {
        shownLevel += (targetLevel - shownLevel) * 0.25f
        rail.color = ink
        val left = if (dockedLeft) cursor else cursor - railWidth
        val phase = System.currentTimeMillis() / 180.0
        for (i in 0 until bars) {
            // Each bar lags the next a little, so a loud syllable travels along the rail.
            val ripple = 0.55f + 0.45f * abs(sin(phase + i * 0.7)).toFloat()
            val h = (barMax * (0.18f + 0.82f * shownLevel * ripple)).coerceAtLeast(dp(3f))
            val x = left + i * barPitch
            bar.set(x, body.centerY() - h / 2f, x + barWidth, body.centerY() + h / 2f)
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, rail)
        }
        postInvalidateOnAnimation()
    }
}
