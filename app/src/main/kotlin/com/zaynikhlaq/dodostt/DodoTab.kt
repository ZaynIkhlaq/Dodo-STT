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
 * The whole interface: one tile docked against the edge of the screen, in three states and no more.
 *
 * It is a single object that changes shape rather than a set of views that come and go — idle it is
 * a rounded square with a mic on it, listening it grows sideways out of the docked edge into a pill
 * holding a level rail and a clock, and working it runs a quiet sweep along the same rail. A control
 * that grows is obviously the same control; a popup is a new thing to understand.
 *
 * The surface never changes colour. Red is rationed to a 6 dp dot and a hairline, because a tile
 * that floods red is a warning, and this is just a microphone that is on.
 */
class DodoTab @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    enum class Mode { IDLE, LISTENING, WORKING }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    /** The square itself. Everything else is measured off this. */
    private val tile = dp(48f)
    private val radius = dp(16f)
    /** Room around the tile for the shadow to fall into. */
    private val pad = dp(7f)
    private val gap = dp(9f)

    private val bars = 5
    private val barWidth = dp(3f)
    private val barPitch = dp(7f)
    private val barMax = dp(20f)
    private val railWidth = barPitch * (bars - 1) + barWidth

    private val surface = context.getColor(R.color.tab_bg)
    private val ink = context.getColor(R.color.tab_ink)
    private val muted = context.getColor(R.color.tab_muted)
    private val live = context.getColor(R.color.rec)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(dp(5f), 0f, dp(1.5f), 0x33000000)
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * scaled
    }
    private val body = RectF()
    private val bar = RectF()

    private val micIcon: Drawable = context.getDrawable(R.drawable.ic_mic)!!.mutate()
    private val cancelIcon: Drawable = context.getDrawable(R.drawable.ic_cross)!!.mutate()

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value != Mode.LISTENING) shownLevel = 0f
            retarget()
        }

    /** The finger has slid away from a hold: letting go now throws the recording away. */
    var armed = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Which edge it is parked against, so the glyph stays outermost and the rest grows inward. */
    var dockedLeft = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The clock while listening, a word of help otherwise, or nothing at all. */
    var label: String? = null
        set(value) {
            if (field == value) return
            field = value
            retarget()
        }

    /** A finger is on it: the tile sinks a little, the way a physical key would. */
    var pressed = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) 0.94f else 1f).scaleY(if (value) 0.94f else 1f)
                .setDuration(110).start()
        }

    private var targetLevel = 0f
    private var shownLevel = 0f

    private var shownWidth = tile
    private var animator: ValueAnimator? = null

    fun setLevel(level: Float) {
        targetLevel = level.coerceIn(0f, 1f)
        if (mode == Mode.LISTENING) invalidate()
    }

    /** Width the tile wants to be right now: the square, plus whatever it has to show. */
    private fun wantedWidth(): Float {
        val caption = label
        var extra = 0f
        if (mode != Mode.IDLE) extra += gap + railWidth
        if (!caption.isNullOrEmpty()) extra += gap + text.measureText(caption)
        return tile + extra + if (extra > 0f) dp(7f) else 0f
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
            duration = 200
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
        fill.color = surface
        // Idle it sits back, so it never quite competes with what is underneath.
        fill.alpha = if (mode == Mode.IDLE) 219 else 255
        canvas.drawRoundRect(body, radius, radius, fill)
        if (mode == Mode.LISTENING && !armed) {
            // One hairline is all the "you are being recorded" this needs.
            hairline.color = live
            canvas.drawRoundRect(body, radius, radius, hairline)
        }

        val glyphCentre = if (dockedLeft) body.left + tile / 2f else body.right - tile / 2f
        when {
            armed -> drawGlyph(canvas, cancelIcon, glyphCentre, muted, dp(11f))
            mode == Mode.LISTENING -> {
                rail.color = live
                canvas.drawCircle(glyphCentre, body.centerY(), dp(6f), rail)
            }
            mode == Mode.WORKING -> drawWorking(canvas, glyphCentre)
            else -> drawGlyph(canvas, micIcon, glyphCentre, ink, dp(11f))
        }

        // Everything else stacks inward from the glyph, mirrored when docked left.
        var cursor = if (dockedLeft) body.left + tile + gap else body.right - tile - gap
        if (mode != Mode.IDLE) {
            drawRail(canvas, cursor)
            cursor += if (dockedLeft) railWidth + gap else -(railWidth + gap)
        }
        val caption = label
        if (!caption.isNullOrEmpty()) {
            text.color = muted
            val w = text.measureText(caption)
            val x = if (dockedLeft) cursor else cursor - w
            canvas.drawText(caption, x, body.centerY() - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    private fun drawGlyph(canvas: Canvas, icon: Drawable, cx: Float, tint: Int, half: Float) {
        icon.setTint(tint)
        icon.setBounds(
            (cx - half).toInt(), (body.centerY() - half).toInt(),
            (cx + half).toInt(), (body.centerY() + half).toInt(),
        )
        icon.draw(canvas)
    }

    /** Working: the dot breathes instead of the rail dancing, so it reads as waiting, not listening. */
    private fun drawWorking(canvas: Canvas, cx: Float) {
        val pulse = 0.65f + 0.35f * abs(sin(System.currentTimeMillis() / 420.0)).toFloat()
        rail.color = muted
        canvas.drawCircle(cx, body.centerY(), dp(6f) * pulse, rail)
        postInvalidateOnAnimation()
    }

    private fun drawRail(canvas: Canvas, cursor: Float) {
        val left = if (dockedLeft) cursor else cursor - railWidth
        val now = System.currentTimeMillis()
        if (mode == Mode.LISTENING) {
            shownLevel += (targetLevel - shownLevel) * 0.25f
            rail.color = ink
            val phase = now / 180.0
            for (i in 0 until bars) {
                // Each bar lags the next a little, so a loud syllable travels along the rail.
                val ripple = 0.55f + 0.45f * abs(sin(phase + i * 0.7)).toFloat()
                val h = (barMax * (0.16f + 0.84f * shownLevel * ripple)).coerceAtLeast(dp(3f))
                capsule(canvas, left + i * barPitch, h)
            }
        } else {
            // A slow wave travelling along the same bars: the shape stays, the meaning changes.
            rail.color = muted
            val phase = now / 260.0
            for (i in 0 until bars) {
                val wave = 0.5f + 0.5f * sin(phase - i * 0.8).toFloat()
                capsule(canvas, left + i * barPitch, dp(3f) + dp(9f) * wave)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun capsule(canvas: Canvas, x: Float, height: Float) {
        bar.set(x, body.centerY() - height / 2f, x + barWidth, body.centerY() + height / 2f)
        canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, rail)
    }
}
