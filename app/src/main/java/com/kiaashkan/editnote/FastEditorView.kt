package com.kiaashkan.editnote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * EditNote's own editor engine (KiA, item 51 — the QuickEdit lesson):
 * a stock EditText lays out the WHOLE document as one giant layout,
 * which is why long files scrolled badly no matter the tuning. This
 * view instead:
 *
 *  - keeps the text as a buffer + a line-start index,
 *  - builds a StaticLayout ONLY for the lines on screen (cached),
 *  - draws text, selection, cursor and the line-number gutter in a
 *    single onDraw pass (numbers can never lag behind the text),
 *  - scrolls with its own OverScroller, so fling physics are the
 *    platform's and travel scales with the flick,
 *  - highlights syntax per visible line from precomputed ranges.
 *
 * Line heights are measured per laid-out line and cached; unknown
 * lines use the running average, so total height is an estimate that
 * converges to exact as the user scrolls (the standard virtualized
 * list trick). Editing goes through replaceRange(), which is the
 * single funnel the activity's undo/autosave listeners hang off.
 */
class FastEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), FastEditorView.ScrollSurface {

    companion object {
        // Build width for no-wrap line layouts (item 87 — see
        // layoutForLine): wide enough for any realistic line,
        // small enough to keep the native shaper correct.
        const val NO_WRAP_LAYOUT_WIDTH = 1_000_000
    }

    /** Surface the fast-scroll handle drives (replaces its EditText). */
    interface ScrollSurface {
        val scrollPosY: Int
        val viewportH: Int
        val maxScrollPos: Int
        fun scrollToPos(y: Int)
    }

    // ------------------------------------------------------------------
    // Text model
    // ------------------------------------------------------------------
    private val buffer = StringBuilder()
    private var lineStarts = IntArray(64)
    private var lineCount = 1

    // Per-line measured height in px; -1 = not measured yet.
    private var lineHeights = IntArray(64)
    private var measuredSum = 0L
    private var measuredCount = 0

    private fun rebuildLineIndex() {
        var capacity = 64
        var starts = IntArray(capacity)
        var count = 0
        starts[count++] = 0
        var i = 0
        val n = buffer.length
        while (i < n) {
            if (buffer[i] == '\n') {
                if (count == capacity) {
                    capacity *= 2
                    starts = starts.copyOf(capacity)
                }
                starts[count++] = i + 1
            }
            i++
        }
        lineStarts = starts
        lineCount = count
        lineHeights = IntArray(max(64, count)) { -1 }
        measuredSum = 0
        measuredCount = 0
        layoutCache.clear()
    }

    private fun lineStart(line: Int): Int = lineStarts[line]
    private fun lineEnd(line: Int): Int =
        if (line + 1 < lineCount) lineStarts[line + 1] - 1 else buffer.length

    private fun lineOfOffset(offset: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun textString(): String = buffer.toString()
    fun textLength(): Int = buffer.length

    /** Programmatic load: no listener, selection reset by caller. */
    fun setTextContent(s: String) {
        stopFling()
        buffer.setLength(0)
        buffer.append(normalizeArabicForms(s))
        rebuildLineIndex()
        selStart = 0
        selEnd = 0
        composingStart = -1
        composingEnd = -1
        scrollYPos = 0
        scrollXPos = 0
        anchorLine = 0
        anchorY = 0
        requestLayout()
        invalidate()
    }

    /**
     * The single edit funnel. Fires [onTextChanged] AFTER applying,
     * unless suppressed by [silentEdit] (the activity sets it while
     * restoring snapshots, mirroring the old `suppress` flag usage —
     * actually the activity's listener checks its own flag; this view
     * always reports).
     */
    /**
     * Item 83: some keyboards deliver Arabic-script text to a
     * custom editor as PRE-SHAPED Presentation Forms (U+FB1D–FDFF,
     * U+FE70–FEFF) — a legacy fallback for editors that don't look
     * like full text editors. Those codepoints can never join in
     * ANY renderer, so Persian/Arabic shows letter-by-letter even
     * though the platform shaper is fine (it worked in the old
     * EditText app, whose real InputConnection got standard text).
     * NFKC maps each form back to its standard letter — lossless —
     * so normalizing at every text-entry point restores real text.
     * Characters outside those blocks pass through untouched.
     */
    private fun normalizeArabicForms(s: String): String {
        var needs = false
        for (ch in s) {
            if (ch.code in 0xFB1D..0xFDFF || ch.code in 0xFE70..0xFEFF) {
                needs = true
                break
            }
        }
        if (!needs) return s
        val out = StringBuilder(s.length)
        for (ch in s) {
            if (ch.code in 0xFB1D..0xFDFF || ch.code in 0xFE70..0xFEFF) {
                out.append(
                    java.text.Normalizer.normalize(
                        ch.toString(), java.text.Normalizer.Form.NFKC
                    )
                )
            } else out.append(ch)
        }
        return out.toString()
    }

    fun replaceRange(start: Int, end: Int, rawIns: String) {
        val ins = normalizeArabicForms(rawIns)
        val a = start.coerceIn(0, buffer.length)
        val b = end.coerceIn(a, buffer.length)
        if (a == b && ins.isEmpty()) return
        val firstLine = lineOfOffset(a)
        val lastLine = lineOfOffset(b)
        val removedLines = lastLine - firstLine
        val charDelta = ins.length - (b - a)
        val deltaLines = ins.count { it == '\n' } - removedLines
        // The edited lines' measured heights are stale.
        for (l in firstLine..lastLine) {
            if (l < lineHeights.size) lineHeights[l] = -1
        }
        buffer.replace(a, b, ins)
        spliceLineIndex(firstLine, removedLines, ins, a, charDelta)
        // Cache invalidation, surgical: with no line-count change the
        // indices do not shift, so only the edited lines lose their
        // layouts; otherwise everything from the edit onward shifts.
        if (deltaLines == 0) {
            for (l in firstLine..lastLine) layoutCache.remove(l)
        } else {
            val it = layoutCache.keys.iterator()
            while (it.hasNext()) {
                if (it.next() >= firstLine) it.remove()
            }
        }
        // Selection follows the edit point simply: collapse to the
        // end of the inserted text (callers set selection explicitly
        // when they need something else).
        selStart = a + ins.length
        selEnd = selStart
        composingStart = -1
        composingEnd = -1
        // Heights below the edit may have shifted; re-anchor at the
        // edited line with an estimated top (the draw refines it).
        anchorLine = firstLine
        anchorY = lineTopEstimate(firstLine)
        // Audit fix: an edit that shrinks the document (delete a
        // big chunk at the end) must not leave the scroll position
        // pointing into the void.
        val maxY = maxScrollY()
        if (scrollYPos > maxY) scrollYPos = maxY
        onSelectionChanged?.invoke()
        onTextChanged?.invoke(a, b - a, ins)
        ensureCursorVisible()
        invalidate()
    }

    /** Adjust the line index for an edit inside [firstLine] that
     *  removed [removedLines] lines and inserted [ins] at [editPos],
     *  shifting every later start by [charDelta]. */
    private fun spliceLineIndex(
        firstLine: Int,
        removedLines: Int,
        ins: String,
        editPos: Int,
        charDelta: Int
    ) {
        val deltaLines = ins.count { it == '\n' } - removedLines
        // Starts of the lines created by newlines inside the insert.
        val newStarts = ArrayList<Int>()
        var idx = ins.indexOf('\n')
        while (idx >= 0) {
            newStarts.add(editPos + idx + 1)
            idx = ins.indexOf('\n', idx + 1)
        }
        val tailIndex = firstLine + 1 + removedLines // first untouched old line
        val needed = lineCount + deltaLines
        val starts = IntArray(max(64, needed + 8))
        System.arraycopy(lineStarts, 0, starts, 0, firstLine + 1)
        var w = firstLine + 1
        for (ns in newStarts) starts[w++] = ns
        var r = tailIndex
        while (r < lineCount) {
            starts[w++] = lineStarts[r] + charDelta
            r++
        }
        lineStarts = starts
        val heights = IntArray(max(64, needed + 8)) { -1 }
        System.arraycopy(lineHeights, 0, heights, 0, firstLine + 1)
        var hw = firstLine + 1 + newStarts.size
        var hr = tailIndex
        while (hr < lineCount && hw < heights.size) {
            heights[hw++] = lineHeights[hr]
            hr++
        }
        lineHeights = heights
        lineCount = needed
        // measuredSum/Count are approximations after edits; recompute
        // lazily is overkill — drop them to the known prefix only.
        measuredSum = 0
        measuredCount = 0
        for (i in 0 until lineCount) {
            val h = lineHeights[i]
            if (h >= 0) {
                measuredSum += h
                measuredCount++
            }
        }
    }

    // ------------------------------------------------------------------
    // Selection / cursor / composing
    // ------------------------------------------------------------------
    var selStart = 0
        private set
    var selEnd = 0
        private set
    val selectionStart: Int get() = min(selStart, selEnd)
    val selectionEnd: Int get() = max(selStart, selEnd)

    private var composingStart = -1
    private var composingEnd = -1

    var onTextChanged: ((start: Int, removed: Int, inserted: String) -> Unit)? = null
    var onScrollChanged: (() -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    fun setSelection(a: Int, b: Int = a) {
        val len = buffer.length
        val wasEmpty = selStart == selEnd
        selStart = a.coerceIn(0, len)
        selEnd = b.coerceIn(0, len)
        restartCursorBlink()
        onSelectionChanged?.invoke()
        // KiA, item 56: the floating menu is built for the selection
        // state at open time; when the state flips (e.g. Select all
        // turns an empty selection into a full one) the menu must be
        // rebuilt or Copy/Cut never appear.
        if (actionMode != null && wasEmpty != (selStart == selEnd)) {
            actionMode?.invalidate()
        }
        invalidate()
    }

    // ------------------------------------------------------------------
    // Appearance / settings
    // ------------------------------------------------------------------
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Paint for lines containing complex scripts (item 82): cursive
     * (Arabic/Persian/Hebrew) and combining scripts (Indic, Thai…)
     * only shape correctly through the platform's default font
     * fallback. A monospace typeface can resolve those characters to
     * a fallback font without OpenType joining on some devices,
     * leaving every letter in its isolated form. Lines with such
     * characters are therefore laid out with the DEFAULT typeface
     * whenever a different (monospace) face is selected.
     */
    private val complexPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val fillPaint = Paint()

    var textColor: Int = 0xFF111111.toInt()
    var editorBackground: Int = 0xFFFFFFFF.toInt()
    var gutterColor: Int = 0xFF888888.toInt()
    var gutterBackground: Int = 0xFFF0F0F0.toInt()
    var accentColor: Int = 0xFFF46658.toInt()
    var selectionColor: Int = 0x66F46658
    var showLineNumbers: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var wordWrap: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) scrollXPos = 0
                resetLayoutState()
                invalidate()
            }
        }
    var readOnly: Boolean = false
    var noSuggestions: Boolean = false
    var autoCapitalize: Boolean = false

    private var lineSpacingAddPx: Float = 0f
    private var lineSpacingMult: Float = 1f


    /**
     * Every cached layout AND every recorded line height is stale
     * (KiA, item 57): wrap, text size, typeface and line spacing all
     * change line heights, and a stale height left in the array
     * poisons the anchor math - lines draw overlapped, bunched or
     * off-screen. All appearance changes funnel through here.
     */
    private fun resetLayoutState() {
        layoutCache.clear()
        for (i in lineHeights.indices) lineHeights[i] = -1
        measuredSum = 0
        measuredCount = 0
        anchorLine = 0
        anchorY = 0
    }

    fun setTextSizePx(px: Float) {
        if (textPaint.textSize == px) return
        textPaint.textSize = px
        gutterPaint.textSize = px
        lineHeightEstimate = computeLineHeight()
        resetLayoutState()
        invalidate()
    }

    fun setTypeface(tf: Typeface?) {
        if (textPaint.typeface == tf) return
        textPaint.typeface = tf
        gutterPaint.typeface = tf
        lineHeightEstimate = computeLineHeight()
        resetLayoutState()
        invalidate()
    }

    fun setLineSpacing(addPx: Float, mult: Float) {
        if (lineSpacingAddPx == addPx && lineSpacingMult == mult) return
        lineSpacingAddPx = addPx
        lineSpacingMult = mult
        lineHeightEstimate = computeLineHeight()
        resetLayoutState()
        invalidate()
    }

    private fun computeLineHeight(): Int {
        val fm = textPaint.fontMetricsInt
        val base = fm.descent - fm.ascent
        return (base * lineSpacingMult + lineSpacingAddPx).toInt().coerceAtLeast(1)
    }

    private var lineHeightEstimate: Int = 1

    private val density: Float get() = resources.displayMetrics.density

    fun gutterWidthPx(): Int {
        if (!showLineNumbers) return 0
        val digits = lineCount.toString().length.coerceAtLeast(2)
        val w = gutterPaint.measureText("9".repeat(digits)).toInt()
        return w + (22 * density).toInt()
    }

    // ------------------------------------------------------------------
    // Layout cache (per visible line)
    // ------------------------------------------------------------------
    private val layoutCache = object : LinkedHashMap<Int, StaticLayout>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, StaticLayout>
        ): Boolean = size > 220
    }

    /** Highlight ranges for the whole text (from the background
     *  highlighter); [highlightVersion] invalidates line layouts. */
    private var rangesBacking: List<SyntaxHighlighter.Range> = emptyList()

    /** Whole-list replacement: clears every cached layout, because
     *  any range may have moved. For progressive (chunked) delivery
     *  use [applyHighlightChunk], which invalidates surgically. */
    var highlightRanges: List<SyntaxHighlighter.Range>
        get() = rangesBacking
        set(value) {
            rangesBacking = value
            highlightVersion++
            layoutCache.clear()
            invalidate()
        }
    private var highlightVersion = 0

    private fun firstRangeStartingAtOrAfter(
        ranges: List<SyntaxHighlighter.Range>, offset: Int
    ): Int {
        var lo = 0
        var hi = ranges.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ranges[mid].from < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Replace the highlight ranges inside [from, to) with [ranges]
     * (sorted, absolute offsets, contained in the interval), keep
     * everything outside untouched, and drop ONLY the cached
     * layouts of lines the interval touches (KiA, item 64 — the
     * chunked highlighter delivers one chunk at a time; clearing
     * the whole cache per chunk made delivery itself stutter).
     * Chunk boundaries are line-aligned, so each touched line
     * belongs fully to this interval.
     */
    fun applyHighlightChunk(
        from: Int, to: Int,
        ranges: List<SyntaxHighlighter.Range>
    ) {
        val old = rangesBacking
        val lo = firstRangeEndingAfter(old, from)
        val hi = firstRangeStartingAtOrAfter(old, to)
        if (lo == hi && ranges.isEmpty()) return
        val merged =
            ArrayList<SyntaxHighlighter.Range>(old.size - (hi - lo) + ranges.size)
        for (i in 0 until lo) merged.add(old[i])
        merged.addAll(ranges)
        for (i in hi until old.size) merged.add(old[i])
        rangesBacking = merged
        highlightVersion++
        if (lineCount > 0 && textLength() > 0 && to > from) {
            val a = lineOfOffset(from.coerceIn(0, textLength()))
            val b = lineOfOffset((to - 1).coerceIn(0, textLength()))
            for (line in a..b) layoutCache.remove(line)
        }
        invalidate()
    }

    /** Extra one-off spans (find highlights), same mechanism. */
    var findRanges: List<Pair<IntRange, Int>> = emptyList()
        set(value) {
            field = value
            layoutCache.clear()
            invalidate()
        }

    /** Bracket-pair highlight ranges (background), same mechanism. */
    var pairRanges: List<Pair<IntRange, Int>> = emptyList()
        set(value) {
            if (field != value) {
                // Cursor moves refresh the pair constantly — only the
                // lines holding the old/new pair lose their layouts,
                // never the whole cache.
                val affected = HashSet<Int>()
                for ((r, _) in field) {
                    affected.add(lineOfOffset(r.first))
                    affected.add(lineOfOffset(r.last))
                }
                field = value
                for ((r, _) in value) {
                    affected.add(lineOfOffset(r.first))
                    affected.add(lineOfOffset(r.last))
                }
                for (l in affected) layoutCache.remove(l)
                invalidate()
            }
        }

    /** Foreground counterpart of [findRanges] (current match). */
    var findFgRanges: List<Pair<IntRange, Int>> = emptyList()
        set(value) {
            field = value
            layoutCache.clear()
            invalidate()
        }

    private var lastLayoutAvail = -1


    /** First index in the (sorted, non-overlapping) ranges whose end
     *  passes [offset] — binary search, so building a line near the
     *  end of a highlighted file no longer scans every range. */
    private fun firstRangeEndingAfter(
        ranges: List<SyntaxHighlighter.Range>, offset: Int
    ): Int {
        var lo = 0
        var hi = ranges.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ranges[mid].to <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun firstPairStartingAfter(
        ranges: List<Pair<IntRange, Int>>, offset: Int
    ): Int {
        var lo = 0
        var hi = ranges.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ranges[mid].first.last < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * True when the text contains characters from a script that
     * needs complex shaping: cursive joining (Arabic, Persian,
     * Hebrew, Syriac), combining marks and conjuncts (Indic, Thai,
     * Lao) or the Arabic presentation-form blocks. Used to pick the
     * shaping-safe paint and to keep span boundaries out of such
     * lines (item 82).
     */
    private fun containsComplexScript(s: CharSequence): Boolean {
        for (i in s.indices) {
            val c = s[i].code
            if (c in 0x0590..0x08FF || c in 0x0900..0x0DFF ||
                c in 0x0E00..0x0EFF || c in 0xFB1D..0xFEFF
            ) return true
        }
        return false
    }

    private fun layoutForLine(line: Int): StaticLayout {
        // The wrap width depends on the gutter width, which grows
        // when the line count crosses a power of ten; cached layouts
        // built for another width are invalid.
        val availNow = if (wordWrap) {
            (width - gutterWidthPx() - paddingLeft - paddingRight)
                .coerceAtLeast(1)
        } else {
            // No-wrap lines build at a wide but SANE width
            // (item 87): the old Int.MAX_VALUE / 2 (~1.07e9 px)
            // silently breaks cursive shaping in Android's
            // native layout engine — Persian/Arabic letters come
            // out isolated. Proven in an emulator matrix test:
            // width 1,000,000 shapes correctly, 2^30 does not.
            // 1M px still fits ~100K monospace chars per line.
            NO_WRAP_LAYOUT_WIDTH
        }
        if (lastLayoutAvail != -1 && availNow != lastLayoutAvail) {
            resetLayoutState()
        }
        lastLayoutAvail = availNow
        layoutCache[line]?.let { return it }
        val start = lineStart(line)
        val end = lineEnd(line)
        val raw = buffer.substring(start, end)
        // Complex-script lines (item 82) get the shaping-safe paint
        // when a non-default face (monospace) is in use.
        val complex = containsComplexScript(raw)
        val paintForLine = if (complex &&
            textPaint.typeface != null &&
            textPaint.typeface != Typeface.DEFAULT
        ) {
            if (complexPaint.textSize != textPaint.textSize) {
                complexPaint.textSize = textPaint.textSize
            }
            complexPaint
        } else textPaint
        // Collect this line's spans (if any) via binary search.
        val syntaxIdx = firstRangeEndingAfter(highlightRanges, start)
        val hasSyntax = syntaxIdx < highlightRanges.size &&
            highlightRanges[syntaxIdx].from < end
        val findIdx = firstPairStartingAfter(findRanges, start)
        val hasFind = findIdx < findRanges.size &&
            findRanges[findIdx].first.first <= end
        val fgIdx = firstPairStartingAfter(findFgRanges, start)
        val hasFg = fgIdx < findFgRanges.size &&
            findFgRanges[fgIdx].first.first <= end
        val pairIdx = firstPairStartingAfter(pairRanges, start)
        val hasPair = pairIdx < pairRanges.size &&
            pairRanges[pairIdx].first.first <= end
        val hasComposing = composingStart >= 0 &&
            composingStart < end && composingEnd > start
        // The composing underline is NOT applied to complex-script
        // lines (item 82): Android's shaper treats a span boundary
        // inside a cursive word as a hard break and the letters on
        // either side lose their joining forms. The IME's own
        // composing feedback still applies; only our underline goes.
        val composingSpan = hasComposing && !complex
        // Fast path: no spans touch this line — lay out the raw
        // string directly, skipping the SpannableString entirely.
        val text: CharSequence = if (!hasSyntax && !hasFind && !hasFg && !hasPair && !composingSpan) {
            raw
        } else {
            val spanned = SpannableString(raw)
            if (hasSyntax) {
                var i = syntaxIdx
                while (i < highlightRanges.size) {
                    val r = highlightRanges[i]
                    if (r.from >= end) break
                    val a = max(r.from, start) - start
                    val b = min(r.to, end) - start
                    if (b > a) {
                        spanned.setSpan(
                            ForegroundColorSpan(r.color), a, b,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    i++
                }
            }
            if (hasFind) {
                var i = findIdx
                while (i < findRanges.size) {
                    val (range, color) = findRanges[i]
                    if (range.first > end) break
                    val a = max(range.first, start) - start
                    val b = min(range.last + 1, end) - start
                    if (b > a) {
                        spanned.setSpan(
                            BackgroundColorSpan(color), a, b,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    i++
                }
            }
            if (hasFg) {
                var i = fgIdx
                while (i < findFgRanges.size) {
                    val (range, color) = findFgRanges[i]
                    if (range.first > end) break
                    val a = max(range.first, start) - start
                    val b = min(range.last + 1, end) - start
                    if (b > a) {
                        spanned.setSpan(
                            ForegroundColorSpan(color), a, b,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    i++
                }
            }
            if (hasPair) {
                var i = pairIdx
                while (i < pairRanges.size) {
                    val (range, color) = pairRanges[i]
                    if (range.first > end) break
                    val a = max(range.first, start) - start
                    val b = min(range.last + 1, end) - start
                    if (b > a) {
                        spanned.setSpan(
                            BackgroundColorSpan(color), a, b,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    i++
                }
            }
            if (composingSpan) {
                val a = max(composingStart, start) - start
                val b = min(composingEnd, end) - start
                if (b > a && a >= 0) {
                    spanned.setSpan(
                        UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            spanned
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paintForLine, availNow)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // Paragraph base direction is ALWAYS LTR (item 93,
            // superseding item 82's FIRSTSTRONG_LTR): every line —
            // Persian included — anchors at the left margin, the
            // code-editor convention KiA asked for ("like
            // QuickEdit"). The Unicode bidi algorithm still orders
            // and joins RTL runs correctly inside the line; only
            // the paragraph's anchor direction is fixed.
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setLineSpacing(lineSpacingAddPx, lineSpacingMult)
            .setIncludePad(false)
            .build()
        layoutCache[line] = layout
        val prevH = lineHeights[line]
        if (prevH < 0) {
            measuredSum += layout.height
            measuredCount++
        } else if (prevH != layout.height) {
            measuredSum += (layout.height - prevH)
        }
        lineHeights[line] = layout.height
        return layout
    }

    private fun avgLineHeight(): Float =
        if (measuredCount > 0) measuredSum.toFloat() / measuredCount
        else lineHeightEstimate.toFloat()

    /** Estimated total content height in px. */
    fun contentHeightPx(): Int {
        val avg = avgLineHeight()
        var total = 0L
        // Exact for measured lines, estimate for the rest.
        total = measuredSum + ((lineCount - measuredCount) * avg).toLong()
        return total.toInt().coerceAtLeast(0)
    }

    // ScrollSurface (for the fast-scroll handle).
    override val scrollPosY: Int get() = scrollYPos
    override val viewportH: Int get() = height
    override val maxScrollPos: Int get() = maxScrollY()
    override fun scrollToPos(y: Int) {
        stopFling()
        scrollToY(y)
    }

    // ------------------------------------------------------------------
    // Scrolling
    // ------------------------------------------------------------------
    private val scroller = OverScroller(context)
    var scrollYPos: Int = 0
        private set
    var scrollXPos: Int = 0
        private set

    fun maxScrollY(): Int =
        (contentHeightPx() + paddingTop + paddingBottom - height).coerceAtLeast(0)

    fun stopFling() {
        if (!scroller.isFinished) scroller.forceFinished(true)
    }

    // True while the current touch gesture is a scroll-stop (items
    // 89+90): it began while a fling was running, or a fling is still
    // running now. Such a gesture may ONLY stop the scroll — tap
    // callbacks check this and do nothing else.
    private fun isFlingStopGesture(): Boolean =
        downStoppedFling || !scroller.isFinished

    fun scrollToY(y: Int) {
        val clamped = y.coerceIn(0, maxScrollY())
        if (clamped != scrollYPos) {
            scrollYPos = clamped
            onScrollChanged?.invoke()
            invalidate()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYPos = scroller.currY.coerceIn(0, maxScrollY())
            scrollXPos = scroller.currX.coerceIn(0, maxScrollX())
            onScrollChanged?.invoke()
            invalidate()
        }
    }

    private fun maxScrollX(): Int {
        if (wordWrap) return 0
        // Widest measured line so far (estimate grows as lines render).
        var widest = 0
        for (line in 0 until lineCount) {
            val l = layoutCache[line] ?: continue
            widest = max(widest, l.width)
        }
        val avail = width - gutterWidthPx() - paddingLeft - paddingRight
        return (widest - avail).coerceAtLeast(0)
    }

    /** Bring [offset] into view (find reveal, go-to-line, cursor). */
    fun scrollToOffset(offset: Int) {
        scrollToOffsetNow(offset)
        // Far targets are placed from height estimates; once the
        // next draw has measured the neighbourhood, land exactly.
        post {
            val before = scrollYPos
            scrollToOffsetNow(offset)
            if (scrollYPos == before) return@post
            post { scrollToOffsetNow(offset) }
        }
    }

    private fun scrollToOffsetNow(offset: Int) {
        val line = lineOfOffset(offset)
        val layout = layoutForLine(line)
        val local = offset - lineStart(line)
        val rowLine = layout.getLineForOffset(local)
        val yTop = lineTopPx(line) + layout.getLineTop(rowLine)
        val yBottom = yTop + (layout.getLineBottom(rowLine) - layout.getLineTop(rowLine))
        val viewTop = scrollYPos
        val viewBottom = scrollYPos + height
        when {
            yTop < viewTop -> scrollToY(yTop - height / 3)
            yBottom > viewBottom -> scrollToY(yBottom - height * 2 / 3)
        }
        if (!wordWrap) {
            val x = layout.getPrimaryHorizontal(local).toInt()
            val avail = width - gutterWidthPx() - paddingLeft - paddingRight
            if (x - scrollXPos > avail - 40) {
                scrollXPos = (x - avail + 80).coerceAtLeast(0)
            } else if (x < scrollXPos) {
                scrollXPos = (x - 40).coerceAtLeast(0)
            }
            invalidate()
        }
    }

    fun ensureCursorVisible() {
        if (isFocused) scrollToOffset(selEnd)
    }

    /**
     * Estimated pixel Y of the top of [line]: sums cached heights and
     * uses the running average for unmeasured lines. Pure arithmetic
     * over the heights array — never builds a layout, so it is safe
     * for far jumps (go-to-line, fast-scroll drags).
     */
    private fun lineTopEstimate(line: Int): Int {
        val avg = avgLineHeight().toInt().coerceAtLeast(1)
        var y = 0L
        var i = 0
        while (i < line && i < lineCount) {
            val h = lineHeights[i]
            y += if (h >= 0) h else avg
            i++
        }
        return y.toInt()
    }

    private fun lineTopPx(line: Int): Int = lineTopEstimate(line)

    // Anchor: the (line, exactTopY) of the first visible line as of
    // the last draw. Scroll resolution steps from here, measuring
    // only the lines it passes, so a frame never lays out more than
    // the scroll delta plus the visible window.
    private var anchorLine = 0
    private var anchorY = 0

    private fun heightOf(line: Int): Int =
        if (lineHeights[line] >= 0) lineHeights[line] else layoutForLine(line).height

    /** First (partially) visible logical line + the px of its top. */
    private fun firstVisibleLine(): Pair<Int, Int> {
        val target = scrollYPos
        var line = anchorLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        var y = anchorY
        val avg = avgLineHeight().toInt().coerceAtLeast(1)
        if (target >= y) {
            // Estimate-jump most of the way, then measure exactly.
            val remaining = target - y
            if (remaining > height * 3) {
                val jump = (remaining / avg).coerceAtMost(lineCount - 1 - line)
                if (jump > 0) {
                    line += jump
                    y += jump * avg
                }
            }
            while (line < lineCount) {
                val h = heightOf(line)
                if (y + h > target) break
                y += h
                line++
            }
        } else {
            val remaining = y - target
            if (remaining > height * 3) {
                val jump = (remaining / avg).coerceAtMost(line)
                if (jump > 0) {
                    line -= jump
                    y -= jump * avg
                }
            }
            while (line > 0 && y > target) {
                line--
                y -= heightOf(line)
            }
            while (line < lineCount) {
                val h = heightOf(line)
                if (y + h > target) break
                y += h
                line++
            }
        }
        if (line >= lineCount) {
            line = (lineCount - 1).coerceAtLeast(0)
            y = lineTopEstimate(line)
        }
        // Invariant (KiA, item 55): the top of line 0 IS y=0. The
        // walk above measures real heights, but it starts from an
        // anchor whose Y may be an estimate (set after edits and
        // far jumps); when the estimate overshoots, the walk reaches
        // line 0 with y still positive and the whole document draws
        // that many px too low — the "empty space at the top, lines
        // shifted down" he saw after pasting into wrapped text.
        if (line == 0) y = 0
        anchorLine = line
        anchorY = y
        return line to y
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Backgrounds.
        fillPaint.color = editorBackground
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val gutterW = gutterWidthPx()
        if (showLineNumbers) {
            fillPaint.color = gutterBackground
            canvas.drawRect(0f, 0f, gutterW.toFloat(), height.toFloat(), fillPaint)
        }
        val (firstLine, firstTop) = firstVisibleLine()
        val cursorLine = if (isFocused) lineOfOffset(selEnd) else -1
        var y = firstTop - scrollYPos + paddingTop
        var line = firstLine
        val textLeft = gutterW + paddingLeft - scrollXPos
        val bottomLimit = height + lineHeightEstimate

        while (line < lineCount && y < bottomLimit) {
            val layout = layoutForLine(line)
            val start = lineStart(line)
            val end = lineEnd(line)
            // Everything text-side is clipped to the right of the
            // gutter (KiA, item 60): with word wrap off, horizontally
            // scrolled text must slide under the solid gutter, not
            // shine through it and collide with the line numbers.
            canvas.save()
            canvas.clipRect(gutterW, 0, width, height)
            // Selection background for this line.
            val selA = selectionStart
            val selB = selectionEnd
            if (selB > selA && selA < end + 1 && selB > start) {
                drawSelection(canvas, layout, start, selA, selB, textLeft, y)
            }
            // Text.
            canvas.save()
            canvas.translate(textLeft.toFloat(), y.toFloat())
            textPaint.color = textColor
            complexPaint.color = textColor
            layout.draw(canvas)
            canvas.restore()
            canvas.restore()
            // Gutter number (only on the line's first visual row) —
            // drawn UNclipped, on the solid gutter.
            if (showLineNumbers) {
                gutterPaint.color = gutterColor
                val baseline = y + layout.getLineBaseline(0)
                canvas.drawText(
                    (line + 1).toString(),
                    (gutterW - 10 * density),
                    baseline.toFloat(),
                    gutterPaint
                )
            }
            // Cursor + selection handles are drawn inside the loop
            // so their coordinates come from the same measured pass
            // as the text (no separate position walk). Clipped to
            // the text area like everything else text-side.
            canvas.save()
            canvas.clipRect(gutterW, 0, width, height)
            if (isFocused && line == cursorLine) {
                val local = selEnd - start
                val row = layout.getLineForOffset(local)
                val x = textLeft + layout.getPrimaryHorizontal(local)
                if (!readOnly && selStart == selEnd && cursorVisible) {
                    fillPaint.color = accentColor
                    canvas.drawRect(
                        x - 1, (y + layout.getLineTop(row)).toFloat(),
                        x + 1 + density, (y + layout.getLineBottom(row)).toFloat(),
                        fillPaint
                    )
                }
                if (selStart != selEnd) {
                    if (selectionStart in start..end) {
                        drawHandleAt(canvas, layout, selectionStart - start, textLeft, y, above = true)
                    }
                    if (selectionEnd in start..end) {
                        drawHandleAt(canvas, layout, selectionEnd - start, textLeft, y, above = false)
                    }
                }
            }
            canvas.restore()
            y += layout.height
            line++
        }
        prefetchAhead(firstLine, line)
    }

    // Prefetch: while the drawn frame is on screen, lay out the next
    // screenful in the scroll direction so upcoming fling frames hit
    // the cache instead of paying layout costs mid-flight.
    private var lastDrawnFirstLine = 0
    private var prefetchPosted = false

    private fun prefetchAhead(firstLine: Int, lastLine: Int) {
        val direction = when {
            firstLine > lastDrawnFirstLine -> 1
            firstLine < lastDrawnFirstLine -> -1
            else -> 0
        }
        lastDrawnFirstLine = firstLine
        if (direction == 0 || prefetchPosted) return
        prefetchPosted = true
        val from = if (direction > 0) lastLine else firstLine - 1
        val token = scrollYPos
        post {
            prefetchPosted = false
            // Abandon if the user has scrolled far meanwhile.
            if (kotlin.math.abs(scrollYPos - token) > height * 3) return@post
            var line = from
            var budget = 60
            while (budget-- > 0 && line in 0 until lineCount) {
                layoutForLine(line)
                line += direction
            }
        }
    }

    private fun drawHandleAt(
        canvas: Canvas,
        layout: StaticLayout,
        local: Int,
        textLeft: Int,
        lineTop: Int,
        above: Boolean
    ) {
        val row = layout.getLineForOffset(local)
        val x = textLeft + layout.getPrimaryHorizontal(local)
        val cy = if (above) {
            lineTop + layout.getLineTop(row) - 6 * density
        } else {
            lineTop + layout.getLineBottom(row) + 6 * density
        }
        fillPaint.color = accentColor
        canvas.drawCircle(x, cy, 9 * density, fillPaint)
    }

    private fun drawSelection(
        canvas: Canvas,
        layout: StaticLayout,
        lineStart: Int,
        selA: Int,
        selB: Int,
        textLeft: Int,
        lineTop: Int
    ) {
        val textLen = layout.text?.length ?: 0
        val a = (selA - lineStart).coerceIn(0, textLen)
        val b = (selB - lineStart).coerceIn(0, textLen)
        if (b < a) return
        fillPaint.color = selectionColor
        for (r in 0 until layout.lineCount) {
            val rowStart = layout.getLineStart(r)
            val rowEnd = layout.getLineEnd(r)
            val f = max(a, rowStart)
            val t = min(b, rowEnd)
            if (t < f) continue
            val x1 = layout.getPrimaryHorizontal(f)
            var x2 = layout.getPrimaryHorizontal(t)
            if (t == rowEnd && t > rowStart) {
                // The segment runs to this visual row's end. At a
                // wrap boundary, offset t is the FIRST character of
                // the next row, so getPrimaryHorizontal(t) answers
                // with the next row's start (~0) and the rect
                // collapsed to a stub — with word wrap on, the
                // wrapped rows of a line painted as unselected even
                // under Select all (KiA, item 69). Measure the row's
                // last character instead and keep whichever answer
                // reaches further right.
                val lastChar = layout.text?.getOrNull(t - 1)
                if (lastChar != null) {
                    val endX = layout.getPrimaryHorizontal(t - 1) +
                        textPaint.measureText(lastChar.toString())
                    if (endX > x2) x2 = endX
                }
            }
            if (t == rowEnd && selB > lineStart + rowEnd) {
                x2 += 10 * density // selection continues past the wrap
            }
            canvas.drawRect(
                textLeft + x1,
                (lineTop + layout.getLineTop(r)).toFloat(),
                textLeft + max(x2, x1 + 2),
                (lineTop + layout.getLineBottom(r)).toFloat(),
                fillPaint
            )
        }
    }

    // ------------------------------------------------------------------
    // Cursor blink
    // ------------------------------------------------------------------
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var cursorVisible = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            blinkHandler.postDelayed(this, 530)
        }
    }

    private fun restartCursorBlink() {
        cursorVisible = true
        blinkHandler.removeCallbacks(blinkRunnable)
        if (isFocused) blinkHandler.postDelayed(blinkRunnable, 530)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            // Wrap widths changed (rotation): every cached layout and
            // measured height is stale.
            layoutCache.clear()
            for (i in lineHeights.indices) lineHeights[i] = -1
            measuredSum = 0
            measuredCount = 0
            anchorLine = 0
            anchorY = 0
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) restartCursorBlink() else {
            blinkHandler.removeCallbacks(blinkRunnable)
            cursorVisible = true
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Hit testing
    // ------------------------------------------------------------------
    /** Offset at a view-space point (x, y). */
    fun offsetAtPoint(x: Float, y: Float): Int {
        val gutterW = gutterWidthPx()
        val contentY = y - paddingTop + scrollYPos
        // Walk from the draw anchor (the visible neighbourhood),
        // never from line 0 — on a 50K-line file a tap must not
        // measure the whole document.
        var line = anchorLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        var top = anchorY
        if (contentY >= top) {
            while (line < lineCount) {
                val h = heightOf(line)
                if (contentY < top + h) break
                top += h
                line++
            }
        } else {
            while (line > 0 && contentY < top) {
                line--
                top -= heightOf(line)
            }
        }
        if (line >= lineCount) return buffer.length
        val layout = layoutForLine(line)
        val row = ((contentY - top).toInt()).coerceAtLeast(0)
        val visualRow = layout.getLineForVertical(row)
        val localX = x - gutterW - paddingLeft + scrollXPos
        val local = layout.getOffsetForHorizontal(visualRow, localX)
        return (lineStart(line) + local).coerceIn(0, buffer.length)
    }

    private fun wordBounds(offset: Int): Pair<Int, Int> {
        if (buffer.isEmpty()) return 0 to 0
        var a = offset.coerceIn(0, buffer.length - 1)
        var b = a
        val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' }
        if (!isWord(buffer[a])) return offset to offset
        while (a > 0 && isWord(buffer[a - 1])) a--
        while (b < buffer.length && isWord(buffer[b])) b++
        return a to b
    }

    // ------------------------------------------------------------------
    // Touch / gestures
    // ------------------------------------------------------------------
    private var draggingHandle: Int = 0 // 0 none, 1 start, 2 end
    private var actionMode: ActionMode? = null
    // True when the current touch gesture began by stopping a fling
    // (item 89): that tap only stops the scroll — it must not pop the
    // keyboard; the next tap opens it.
    private var downStoppedFling = false
    // Item 91: a fling-stop gesture is run by the view itself, not by
    // GestureDetector — once the detector's long-press state engages
    // (a grab-and-hold crosses the timeout) it suppresses scrolling
    // for the rest of the gesture, so the grab could never drag.
    // While grabActive, MOVE scrolls 1:1 with the finger and a
    // VelocityTracker provides the release fling.
    private var grabActive = false
    private var grabLastX = 0f
    private var grabLastY = 0f
    private var velocityTracker: VelocityTracker? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                stopFling()
                scrollYPos = (scrollYPos + distanceY.toInt())
                    .coerceIn(0, maxScrollY())
                if (!wordWrap) {
                    scrollXPos = (scrollXPos + distanceX.toInt())
                        .coerceIn(0, maxScrollX())
                }
                onScrollChanged?.invoke()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val maxY = maxScrollY()
                if (maxY > 0) {
                    // Platform physics, lightly damped (items 88+89
                    // test round): item 52's 1.3x boost made the
                    // glide travel ~1.7x farther than any native app
                    // (distance grows with v^2) — slippery and out of
                    // control. Raw velocity was close, but KiA still
                    // found the glide a touch fast, so the measured
                    // velocity is scaled by 0.85: peak speed -15%,
                    // travel ~-28%, same native friction curve.
                    scroller.fling(
                        scrollXPos, scrollYPos,
                        (-velocityX * 0.85f).toInt(), (-velocityY * 0.85f).toInt(),
                        0, maxScrollX(), 0, maxY
                    )
                    invalidate()
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isFlingStopGesture()) {
                    // This touch ONLY stops the scroll (items 89+90):
                    // no caret move, no keyboard, nothing else. The
                    // next touch behaves as a normal tap.
                    stopFling()
                    downStoppedFling = false
                    actionMode?.finish()
                    return true
                }
                requestFocus()
                val off = offsetAtPoint(e.x, e.y)
                setSelection(off)
                if (!readOnly) showKeyboard()
                actionMode?.finish()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isFlingStopGesture()) return true
                val off = offsetAtPoint(e.x, e.y)
                val (a, b) = wordBounds(off)
                if (b > a) {
                    setSelection(a, b)
                    startTextActionMode()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isFlingStopGesture()) return
                val off = offsetAtPoint(e.x, e.y)
                val (a, b) = wordBounds(off)
                if (b > a) setSelection(a, b) else setSelection(off)
                startTextActionMode()
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A touch during a fling stops the glide dead,
                // exactly where the content is on screen (item 88 —
                // native ListView/EditText parity; before this, only
                // onScroll stopped it, after the finger had already
                // moved past the touch slop, so the text kept running
                // away under a grab-and-hold touch).
                downStoppedFling = !scroller.isFinished
                stopFling()
                // Handle drags take precedence near selection ends.
                draggingHandle = handleAt(event.x, event.y)
                if (draggingHandle != 0) return true
                if (downStoppedFling) {
                    // Grab the moving content (item 91): this view
                    // runs the gesture itself from here — the finger
                    // can drag the stopped content, and no tap
                    // consequence (caret, keyboard, selection) can
                    // fire for this gesture.
                    grabActive = true
                    grabLastX = event.x
                    grabLastY = event.y
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (grabActive) {
                    velocityTracker?.addMovement(event)
                    val dx = grabLastX - event.x
                    val dy = grabLastY - event.y
                    grabLastX = event.x
                    grabLastY = event.y
                    scrollYPos = (scrollYPos + dy.toInt())
                        .coerceIn(0, maxScrollY())
                    if (!wordWrap) {
                        scrollXPos = (scrollXPos + dx.toInt())
                            .coerceIn(0, maxScrollX())
                    }
                    onScrollChanged?.invoke()
                    invalidate()
                    return true
                }
                if (draggingHandle != 0) {
                    val off = offsetAtPoint(event.x, event.y)
                    if (draggingHandle == 1) {
                        selStart = off
                        if (selStart > selEnd) {
                            val t = selStart; selStart = selEnd; selEnd = t
                            draggingHandle = 2
                        }
                    } else {
                        selEnd = off
                        if (selEnd < selStart) {
                            val t = selStart; selStart = selEnd; selEnd = t
                            draggingHandle = 1
                        }
                    }
                    scrollToOffset(off)
                    onSelectionChanged?.invoke()
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (grabActive) {
                    grabActive = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // A fast grab-release keeps gliding, with
                        // the same 0.85 calibration as onFling.
                        velocityTracker?.addMovement(event)
                        val vc = ViewConfiguration.get(context)
                        velocityTracker?.computeCurrentVelocity(
                            1000, vc.scaledMaximumFlingVelocity.toFloat()
                        )
                        val vx = velocityTracker?.xVelocity ?: 0f
                        val vy = velocityTracker?.yVelocity ?: 0f
                        if (abs(vx) > vc.scaledMinimumFlingVelocity ||
                            abs(vy) > vc.scaledMinimumFlingVelocity
                        ) {
                            scroller.fling(
                                scrollXPos, scrollYPos,
                                (-vx * 0.85f).toInt(),
                                (-vy * 0.85f).toInt(),
                                0, maxScrollX(), 0, maxScrollY()
                            )
                            invalidate()
                        }
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    downStoppedFling = false
                    return true
                }
                if (draggingHandle != 0) {
                    draggingHandle = 0
                    if (selStart != selEnd) startTextActionMode()
                    return true
                }
            }
        }
        // Any other event of a grab gesture (extra pointers and the
        // like) is consumed here too — GestureDetector never sees a
        // grab, so its long-press state cannot engage mid-grab.
        if (grabActive) return true
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleAt(x: Float, y: Float): Int {
        if (selStart == selEnd) return 0
        val r = 28 * density
        val gutterW = gutterWidthPx()
        for ((offset, which) in listOf(selectionStart to 1, selectionEnd to 2)) {
            val line = lineOfOffset(offset)
            val top = lineTopEstimate(line) - scrollYPos + paddingTop
            val layout = layoutForLine(line)
            val row = layout.getLineForOffset(offset - lineStart(line))
            val hx = gutterW + paddingLeft - scrollXPos +
                layout.getPrimaryHorizontal(offset - lineStart(line))
            val hy = top + layout.getLineTop(row) + 
                (layout.getLineBottom(row) - layout.getLineTop(row)) / 2
            if (abs(x - hx) <= r && abs(y - hy) <= r + 10 * density) return which
        }
        return 0
    }

    // ------------------------------------------------------------------
    // Coding behaviours (KiA, item 53): auto-close brackets/quotes,
    // type-over closers, pair-aware backspace, wrap selection. Only
    // active when the activity says the document is code
    // ([codeBehaviors]) — plain notes and Persian text never pair.
    // ------------------------------------------------------------------
    var codeBehaviors: Boolean = false

    private val closers = mapOf(
        '(' to ')', '[' to ']', '{' to '}',
        '"' to '"', '\'' to '\'', '`' to '`'
    )
    private val closerChars = setOf(')', ']', '}', '"', '\'', '`')

    private fun charBefore(pos: Int): Char? =
        if (pos > 0) buffer[pos - 1] else null

    private fun charAt(pos: Int): Char? =
        if (pos < buffer.length) buffer[pos] else null

    private fun shouldPair(opener: Char, pos: Int): Boolean {
        val next = charAt(pos)
        if (next != null && (next.isLetterOrDigit() || next == '_')) return false
        if (opener == '"' || opener == '\'' || opener == '`') {
            val prev = charBefore(pos)
            if (prev != null && (prev.isLetterOrDigit() || prev == '_')) return false
        }
        return true
    }

    /** Route for everything the user TYPES (IME commits, key events). */
    fun typeText(text: String) {
        if (readOnly) return
        if (!codeBehaviors || text.isEmpty()) {
            replaceRange(selectionStart, selectionEnd, text)
            return
        }
        val a = selectionStart
        val b = selectionEnd
        if (a != b) {
            // Typing an opener over a selection wraps it in the pair.
            if (text.length == 1 && closers.containsKey(text[0])) {
                val open = text[0]
                val close = closers.getValue(open)
                val inner = buffer.substring(a, b)
                replaceRange(a, b, "$open$inner$close")
                setSelection(a + 1, a + 1 + inner.length)
                return
            }
            replaceRange(a, b, text)
            return
        }
        if (text.length == 1) {
            val c = text[0]
            // Type-over: closing over an identical existing closer.
            if (c in closerChars && charAt(a) == c) {
                setSelection(a + 1)
                ensureCursorVisible()
                return
            }
            val close = closers[c]
            if (close != null && shouldPair(c, a)) {
                replaceRange(a, a, "$c$close")
                setSelection(a + 1)
                return
            }
        }
        replaceRange(a, b, text)
    }

    /**
     * If [cursor] touches a bracket, return (anchor, match) offsets
     * of the pair — a bounded depth scan over the buffer (no copy).
     * Brackets inside strings/comments are not skipped (v1).
     */
    fun bracketMatchAt(cursor: Int): Pair<Int, Int>? {
        val pairs = mapOf(
            '(' to ')', '[' to ']', '{' to '}',
            ')' to '(', ']' to '[', '}' to '{'
        )
        var at = -1
        var ch: Char? = null
        if (cursor > 0 && pairs.containsKey(buffer[cursor - 1])) {
            at = cursor - 1
            ch = buffer[cursor - 1]
        } else if (cursor < buffer.length && pairs.containsKey(buffer[cursor])) {
            at = cursor
            ch = buffer[cursor]
        }
        if (at < 0 || ch == null) return null
        val target = pairs.getValue(ch)
        val limit = 20_000
        var depth = 0
        var match = -1
        if (ch == '(' || ch == '[' || ch == '{') {
            var i = at
            val end = minOf(buffer.length, at + limit)
            while (i < end) {
                val c = buffer[i]
                if (c == ch) depth++
                else if (c == target) {
                    depth--
                    if (depth == 0) { match = i; break }
                }
                i++
            }
        } else {
            var i = at
            val startAt = maxOf(0, at - limit)
            while (i >= startAt) {
                val c = buffer[i]
                if (c == ch) depth++
                else if (c == target) {
                    depth--
                    if (depth == 0) { match = i; break }
                }
                i--
            }
        }
        return if (match >= 0) at to match else null
    }

    /** Backspace that removes both halves of an empty pair. */
    fun deleteBackward() {
        if (readOnly) return
        if (selStart != selEnd) {
            replaceRange(selectionStart, selectionEnd, "")
            return
        }
        val pos = selEnd
        if (pos == 0) return
        val prev = buffer[pos - 1]
        val next = charAt(pos)
        // Pair-aware deletion is a CODE behaviour (audit #3): the
        // IME path already gated on codeBehaviors, but the
        // hardware-key path calls this directly — in a plain-text
        // file, backspace between an adjacent pair (e.g. []) must
        // delete one character, not both.
        if (codeBehaviors && next != null && closers[prev] == next) {
            replaceRange(pos - 1, pos + 1, "")
        } else {
            replaceRange(pos - 1, pos, "")
        }
    }

    // ------------------------------------------------------------------
    // Clipboard / action mode
    // ------------------------------------------------------------------
    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copySelection() {
        if (selStart == selEnd) return
        val a = selectionStart
        val b = selectionEnd
        clipboard().setPrimaryClip(
            ClipData.newPlainText("EditNote", buffer.substring(a, b))
        )
    }

    fun cutSelection() {
        if (readOnly || selStart == selEnd) return
        copySelection()
        replaceRange(selectionStart, selectionEnd, "")
    }

    fun pasteClipboard() {
        if (readOnly) return
        val clip = clipboard().primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        replaceRange(selectionStart, selectionEnd, text)
    }

    fun selectAllText() {
        setSelection(0, buffer.length)
    }

    private fun startTextActionMode() {
        actionMode?.finish()
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                return true // items are built in onPrepareActionMode
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                if (selStart != selEnd) {
                    menu.add(0, 1, 0, context.getString(R.string.cut)).setEnabled(!readOnly)
                    menu.add(0, 2, 1, context.getString(R.string.copy))
                }
                menu.add(0, 3, 2, context.getString(R.string.menu_paste)).setEnabled(!readOnly)
                menu.add(0, 4, 3, context.getString(R.string.menu_select_all))
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> cutSelection()
                    2 -> copySelection()
                    3 -> pasteClipboard()
                    4 -> selectAllText()
                }
                if (item.itemId != 4) mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (actionMode === mode) actionMode = null
            }
        })
    }

    // ------------------------------------------------------------------
    // Keyboard / IME
    // ------------------------------------------------------------------
    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (noSuggestions) {
            outAttrs.inputType = outAttrs.inputType or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (autoCapitalize) {
            outAttrs.inputType = outAttrs.inputType or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (readOnly) return true
                val ins = text.toString()
                if (composingStart >= 0) {
                    replaceRange(composingStart, composingEnd, ins)
                } else {
                    typeText(ins)
                }
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (readOnly) return true
                val ins = normalizeArabicForms(text.toString())
                val a = if (composingStart >= 0) composingStart else selectionStart
                val b = if (composingStart >= 0) composingEnd else selectionEnd
                // Apply the edit, then re-mark the composing range.
                val start = a
                buffer.replace(a, b, ins)
                rebuildLineIndexAfterComposing(a, b, ins)
                composingStart = start
                composingEnd = start + ins.length
                selStart = composingEnd
                selEnd = composingEnd
                layoutCache.clear()
                onTextChanged?.invoke(a, b - a, ins)
                invalidate()
                return true
            }

            override fun finishComposingText(): Boolean {
                composingStart = -1
                composingEnd = -1
                layoutCache.clear()
                invalidate()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (readOnly) return true
                if (selStart != selEnd) {
                    replaceRange(selectionStart, selectionEnd, "")
                    return true
                }
                if (codeBehaviors && beforeLength == 1 && afterLength == 0) {
                    deleteBackward()
                    return true
                }
                val cursor = selEnd
                val a = (cursor - beforeLength).coerceAtLeast(0)
                val b = (cursor + afterLength).coerceAtMost(buffer.length)
                if (a < cursor || b > cursor) {
                    replaceRange(a, b, "")
                    setSelection(a)
                }
                return true
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                val cursor = selStart
                return buffer.substring((cursor - n).coerceAtLeast(0), cursor)
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
                val cursor = selEnd
                return buffer.substring(cursor, (cursor + n).coerceAtMost(buffer.length))
            }

            override fun getSelectedText(flags: Int): CharSequence {
                if (selStart == selEnd) return ""
                return buffer.substring(selectionStart, selectionEnd)
            }

            override fun setSelection(start: Int, end: Int): Boolean {
                this@FastEditorView.setSelection(start, end)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            deleteBackward()
                            return true
                        }
                        KeyEvent.KEYCODE_FORWARD_DEL -> {
                            deleteSurroundingText(0, 1)
                            return true
                        }
                        KeyEvent.KEYCODE_ENTER -> {
                            commitText("\n", 1)
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    /** Composing edits bypass replaceRange (the composing range must
     *  survive the edit), so the line index is rebuilt wholesale —
     *  a tight O(n) char scan, a millisecond-scale cost per update. */
    private fun rebuildLineIndexAfterComposing(a: Int, b: Int, ins: String) {
        rebuildLineIndex()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (readOnly) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                deleteBackward()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (selStart != selEnd) replaceRange(selectionStart, selectionEnd, "")
                else if (selEnd < buffer.length) replaceRange(selEnd, selEnd + 1, "")
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                replaceRange(selectionStart, selectionEnd, "\n")
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                replaceRange(selectionStart, selectionEnd, "\t")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                setSelection((selStart - 1).coerceAtLeast(0))
                ensureCursorVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                setSelection((selEnd + 1).coerceAtMost(buffer.length))
                ensureCursorVisible()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveCursorLine(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                return true
            }
        }
        if (event.unicodeChar != 0 && !event.isCtrlPressed) {
            typeText(event.unicodeChar.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveCursorLine(delta: Int) {
        val line = lineOfOffset(selEnd)
        val target = (line + delta).coerceIn(0, lineCount - 1)
        val col = selEnd - lineStart(line)
        val newPos = min(lineStart(target) + col, lineEnd(target))
        setSelection(newPos)
        ensureCursorVisible()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
    }
}
