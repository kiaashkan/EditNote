package com.kiaashkan.editnote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText

/**
 * A slim drag handle on the editor's right edge for scrubbing through
 * very long files (KiA, item 30). A stock EditText fling travels a
 * fixed pixel distance no matter how tall the file is, so reaching
 * the end of a 10,000-line file took dozens of flings. Dragging this
 * handle maps the whole file onto the screen height: top = start of
 * file, bottom = end. It draws nothing when the file fits on screen.
 */
class FastScrollHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var editor: EditText? = null
    var accentColor: Int = 0xFFF46658.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    private fun maxScroll(): Int {
        val ed = editor ?: return 0
        val layout = ed.layout ?: return 0
        val content = ed.paddingTop + layout.height + ed.paddingBottom
        return (content - ed.height).coerceAtLeast(0)
    }

    private fun thumbHeight(max: Int): Float {
        val ed = editor ?: return 0f
        val content = max + ed.height
        val frac = if (content > 0) ed.height.toFloat() / content else 1f
        val min = 48 * resources.displayMetrics.density
        return (height * frac).coerceAtLeast(min).coerceAtMost(height.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ed = editor ?: return
        val max = maxScroll()
        if (max <= 0 || height == 0) return
        val thumbH = thumbHeight(max)
        val travel = height - thumbH
        val y = if (travel > 0) travel * (ed.scrollY.toFloat() / max) else 0f
        val w = 6 * resources.displayMetrics.density
        val inset = 3 * resources.displayMetrics.density
        paint.color = accentColor
        paint.alpha = if (dragging) 235 else 150
        val left = width - inset - w
        canvas.drawRoundRect(left, y, width - inset, y + thumbH, w / 2, w / 2, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ed = editor ?: return false
        val max = maxScroll()
        if (max <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a grab ON the pill itself starts a scrub — taps
                // elsewhere on this edge strip must reach the editor
                // (cursor placement at line ends lives there).
                val thumbH = thumbHeight(max)
                val travel = height - thumbH
                val top = if (travel > 0) {
                    travel * (ed.scrollY.toFloat() / max)
                } else {
                    0f
                }
                val slack = 10 * resources.displayMetrics.density
                if (event.y < top - slack || event.y > top + thumbH + slack) {
                    return false
                }
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                scrub(event.y, max)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                scrub(event.y, max)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrub(y: Float, max: Int) {
        val ed = editor ?: return
        val frac = (y / height).coerceIn(0f, 1f)
        ed.scrollTo(0, (frac * max).toInt())
        invalidate()
    }
}
