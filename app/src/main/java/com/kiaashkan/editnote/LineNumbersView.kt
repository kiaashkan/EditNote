package com.kiaashkan.editnote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.EditText

/**
 * Line-number gutter that draws ONLY the numbers currently on screen.
 *
 * The old gutter was one giant TextView holding every number of the
 * file ("1\n2\n…\n5000"): past roughly a thousand lines Android stopped
 * painting its lower part entirely (canvas size limit), and redrawing
 * that huge view on every scroll frame made scrolling feel stuck
 * (KiA, items 25-26). Drawing ~40 visible numbers per frame has
 * neither problem, at any file length.
 */
class LineNumbersView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }

    /** The editor whose layout this gutter mirrors. */
    var editor: EditText? = null

    private var digits = 1

    fun setTextColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setTextSizeSp(sp: Float) {
        paint.textSize = sp * resources.displayMetrics.scaledDensity
        requestLayout()
        invalidate()
    }

    fun setTypeface(typeface: Typeface?) {
        paint.typeface = typeface
        requestLayout()
        invalidate()
    }

    /** Recompute the gutter width (digit count) and redraw. Cheap to
     *  call on every text change. */
    fun refresh() {
        val count = editor?.lineCount ?: 1
        val d = count.toString().length.coerceAtLeast(1)
        if (d != digits) {
            digits = d
            requestLayout()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = paint.measureText("9".repeat(digits)).toInt()
        val w = paddingLeft + paddingRight + textWidth
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ed = editor ?: return
        val layout = ed.layout ?: return
        val lineCount = layout.lineCount
        if (lineCount == 0 || height == 0) return

        val scrollY = ed.scrollY
        // Visible window in layout coordinates (padding excluded there).
        val top = (scrollY - ed.paddingTop).coerceAtLeast(0)
        val bottom = scrollY - ed.paddingTop + height
        val first = layout.getLineForVertical(top)
        val last = layout.getLineForVertical(bottom).coerceAtMost(lineCount - 1)
        val x = (width - paddingRight).toFloat()
        for (i in first..last) {
            val baseline =
                ed.paddingTop + layout.getLineBaseline(i) - scrollY
            canvas.drawText((i + 1).toString(), x, baseline.toFloat(), paint)
        }
    }
}
