package com.zaynikhlaq.dodostt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A two-or-three-way choice drawn as a sliding pill, in place of a column of RadioButtons. Framework
 * only — the app deliberately has no AppCompat or Material dependency.
 */
class SegmentedControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val inset = 3f * density
    private val radius = 14f * density

    private var labels: List<String> = emptyList()

    var selected = 0
        private set

    /** Called only for user-driven changes, never for [setOptions] or a programmatic [select]. */
    var onSelect: ((Int) -> Unit)? = null

    /** Thumb position in segment-index units; animates between whole numbers. */
    private var thumbAt = 0f
    private var animator: ValueAnimator? = null

    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface) }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.getColor(R.color.hairline)
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.thumb) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val colorPrimary = context.getColor(R.color.text_primary)
    private val colorSecondary = context.getColor(R.color.text_secondary)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val bounds = RectF()
    private var downIndex = -1

    init {
        isClickable = true
        isFocusable = true
        label.textSize = 15f * resources.displayMetrics.scaledDensity
    }

    fun setOptions(options: List<String>, initial: Int) {
        labels = options
        selected = initial.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        animator?.cancel()
        thumbAt = selected.toFloat()
        contentDescription = labels.getOrNull(selected)
        invalidate()
    }

    /** Moves the selection without notifying [onSelect]. */
    fun select(index: Int) {
        if (labels.isEmpty() || index == selected) return
        selected = index.coerceIn(0, labels.size - 1)
        contentDescription = labels.getOrNull(selected)
        animateThumbTo(selected.toFloat())
    }

    private fun animateThumbTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(thumbAt, target).apply {
            duration = 190
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                thumbAt = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((260 * density).toInt(), widthMeasureSpec)
        val height = resolveSize((48 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    private fun segmentWidth() = if (labels.isEmpty()) 0f else (width - inset * 2) / labels.size

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.isEmpty()) return super.onTouchEvent(event)
        val index = ((event.x - inset) / segmentWidth()).toInt().coerceIn(0, labels.size - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downIndex = index
            MotionEvent.ACTION_UP -> {
                if (downIndex == index && index != selected) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    selected = index
                    contentDescription = labels[index]
                    animateThumbTo(index.toFloat())
                    onSelect?.invoke(index)
                }
                downIndex = -1
            }
            MotionEvent.ACTION_CANCEL -> downIndex = -1
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val h = height.toFloat()

        bounds.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(bounds, radius, radius, trackFill)
        bounds.inset(density / 2f, density / 2f)
        canvas.drawRoundRect(bounds, radius, radius, hairline)

        val segment = segmentWidth()
        val left = inset + thumbAt * segment
        bounds.set(left, inset, left + segment, h - inset)
        val thumbRadius = radius - inset
        canvas.drawRoundRect(bounds, thumbRadius, thumbRadius, thumbFill)
        bounds.inset(density / 2f, density / 2f)
        canvas.drawRoundRect(bounds, thumbRadius, thumbRadius, hairline)

        val baseline = h / 2f - (label.descent() + label.ascent()) / 2f
        labels.forEachIndexed { i, text ->
            // Fade the label's weight and colour with the thumb so nothing snaps mid-slide.
            val nearness = (1f - kotlin.math.abs(thumbAt - i)).coerceIn(0f, 1f)
            label.typeface = if (nearness > 0.5f) medium else regular
            label.color = if (nearness > 0.5f) colorPrimary else colorSecondary
            canvas.drawText(text, inset + segment * (i + 0.5f), baseline, label)
        }
    }
}
