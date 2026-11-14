package com.kiaashkan.editnote

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.os.SystemClock
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.kiaashkan.editnote.data.AppSettings
import com.kiaashkan.editnote.data.RecentFiles
import com.kiaashkan.editnote.data.TextFileIo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Editor (v0.3, professional pass):
 * - Files live on the device only; EditNote keeps no copies of its own.
 *   Brand-new files are drafts until the first save, which asks where the
 *   file should be created (system picker). Opened files save in place.
 * - Tabs, line numbers, undo/redo, find/replace with prev/next, go to line,
 *   indent/outdent, insert date/time, paste, select all, reload, print,
 *   basic syntax highlighting, accessory symbol bar, session restore.
 * - Behavior comes from Settings: theme/accent, font, spacing, wrap,
 *   autosave + interval, auto-indent, suggestions, capitalization, encoding,
 *   line breaks, undo-by-back.
 */
class EditorActivity : Activity() {

    private class Doc(
        var uri: Uri? = null,
        var name: String,
        var text: String = "",
        var dirty: Boolean = false,
        var selection: Int = 0,
        var needsDestination: Boolean = false,
        var draftFile: File? = null,
        /** This file's encoding: used for every read and write of it. */
        var encoding: String = "UTF-8",
        /** Syntax choice: SyntaxHighlighter.AUTO, NONE, or a language id. */
        var syntax: String = SyntaxHighlighter.AUTO,
        /** Set once the "large file: wrap off" note has been shown. */
        var largeWrapToastShown: Boolean = false
    )

    private lateinit var settings: AppSettings
    private lateinit var palette: Palette
    private lateinit var editor: EditText
    private lateinit var lineNumbers: LineNumbersView
    private lateinit var gutter: View
    private lateinit var fastScroll: FastScrollHandle
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var tabStrip: LinearLayout
    private lateinit var accessoryScroll: View
    private lateinit var accessoryBar: LinearLayout
    private lateinit var findBar: View
    private lateinit var findField: EditText
    private lateinit var findCounter: TextView

    private val docs = mutableListOf<Doc>()
    private var active = 0
    private var readOnly = false
    private var suppress = false
    private var applyingIndent = false
    private var appliedSignature: String = ""

    private var lastQuery = ""
    private var lastFindIndex = -1

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var baseline: String = ""
    // Undo history holds FULL-TEXT snapshots. In a long file those are
    // megabytes each; an unbounded stack (it was 100 snapshots) plus a
    // full-text copy on every keystroke made the whole app — scrolling
    // included — stutter from GC pressure (KiA, item 33). Snapshots now
    // live under a total-size budget, and "is there anything to undo"
    // is an O(1) flag instead of a whole-text comparison.
    private var undoSnapshotChars = 0
    private var editedSinceBaseline = false

    // Momentum glide (KiA, item 34): a stock EditText fling does not
    // scale to a huge file — a flick dies within a screen or two, so
    // the user has to drag the whole way. We observe the flick
    // (never consuming touches), and once the native fling settles,
    // an extension glide carries the scroll on with a decelerating
    // animator. Any new touch or handle-grab cancels it.
    private var glideAnimator: ValueAnimator? = null
    private var editorTouching = false
    private val dragSamples = ArrayDeque<Pair<Long, Int>>()
    private var releaseVelocity = 0f
    private var lastScrollChangeAt = 0L
    private var glideSettleTries = 0
    private val glideSettleRunnable = object : Runnable {
        override fun run() {
            if (editorTouching) return
            val idle = SystemClock.uptimeMillis() - lastScrollChangeAt
            if (idle < 150) {
                if (glideSettleTries < 12) {
                    glideSettleTries++
                    handler.postDelayed(this, 100)
                }
                // Still moving after ~1.4 s: the native fling is long
                // enough on its own — do not stack a second glide.
                return
            }
            startExtensionGlide()
        }
    }

    private fun cancelGlide() {
        glideAnimator?.cancel()
        glideAnimator = null
        handler.removeCallbacks(glideSettleRunnable)
    }

    private fun editorMaxScroll(): Int {
        val layout = editor.layout ?: return 0
        val content = editor.paddingTop + layout.height + editor.paddingBottom
        return (content - editor.height).coerceAtLeast(0)
    }

    private fun startExtensionGlide() {
        val v = releaseVelocity
        releaseVelocity = 0f
        if (kotlin.math.abs(v) < 1500f) return
        val max = editorMaxScroll()
        if (max <= 0) return
        val startY = editor.scrollY
        val target = (startY + (v * 1.6f).toInt()).coerceIn(0, max)
        if (kotlin.math.abs(target - startY) < editor.height / 2) return
        glideAnimator = ValueAnimator.ofInt(startY, target).apply {
            duration = 750
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                editor.scrollTo(0, anim.animatedValue as Int)
            }
            start()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autosaveRunnable = Runnable {
        docs.getOrNull(active)?.let { doc ->
            val wasDirty = doc.dirty
            saveDoc(doc, silent = true)
            if (wasDirty && !doc.dirty && settings.autosaveConfirm) {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val historyRunnable = Runnable {
        val current = editor.text.toString()
        if (current != baseline) {
            pushUndoSnapshot(baseline)
            redoStack.clear()
            baseline = current
            editedSinceBaseline = false
            updateUndoRedoButtons()
        }
    }

    private fun pushUndoSnapshot(snapshot: String) {
        undoStack.addLast(snapshot)
        undoSnapshotChars += snapshot.length
        val budget = 4_000_000
        while (undoStack.size > 1 &&
            (undoStack.size > 100 || undoSnapshotChars > budget)
        ) {
            undoSnapshotChars -= undoStack.removeFirst().length
        }
    }
    private val highlightRunnable = Runnable { applyHighlighting() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        settings = AppSettings(this)
        palette = Palette(this)
        appliedSignature = ThemeHelper.signature(this)

        editor = findViewById(R.id.editor)
        lineNumbers = findViewById(R.id.lineNumbers)
        lineNumbers.editor = editor
        fastScroll = findViewById(R.id.fastScroll)
        fastScroll.editor = editor
        fastScroll.accentColor = palette.accent
        gutter = findViewById(R.id.gutterFrame)
        titleView = findViewById(R.id.fileTitle)
        statusView = findViewById(R.id.saveStatus)
        tabStrip = findViewById(R.id.tabStrip)
        accessoryScroll = findViewById(R.id.accessoryScroll)
        accessoryBar = findViewById(R.id.accessoryBar)
        titleView.setTextColor(palette.textPrimary)
        statusView.setTextColor(palette.textSecondary)
        lineNumbers.setTextColor(palette.textSecondary)
        gutter.setBackgroundColor(palette.alt)
        editor.setTextColor(palette.textPrimary)

        docs.addAll(resolveInitialDocs())
        if (docs.isEmpty()) docs.add(newDraftDoc("Untitled"))
        active = 0
        applyEditorSettings()
        loadDocIntoEditor(docs[0])
        editor.post { refreshLineNumbers() }
        buildAccessoryBar()
        renderTabs()

        editor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refreshLineNumbers()
                if (suppress) return
                val doc = docs.getOrNull(active) ?: return
                doc.dirty = true
                editedSinceBaseline = true
                lastFindIndex = -1
                if (!doc.needsDestination) statusView.text = "Unsaved changes"
                handler.removeCallbacks(historyRunnable)
                handler.postDelayed(historyRunnable, HISTORY_DELAY_MS)
                if (settings.autosave) {
                    handler.removeCallbacks(autosaveRunnable)
                    handler.postDelayed(
                        autosaveRunnable,
                        settings.autosaveIntervalSec * 1000L
                    )
                }
                handler.removeCallbacks(highlightRunnable)
                handler.postDelayed(highlightRunnable, HIGHLIGHT_DELAY_MS)
                updateUndoRedoButtons()
                renderTabs()
                if (::findBar.isInitialized && findBar.visibility == View.VISIBLE) {
                    refreshFindCounter()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppress || applyingIndent || !settings.autoIndent) return
                if (count == 1 && s != null && start in s.indices && s[start] == '\n') {
                    applyAutoIndent(start)
                }
            }
        })
        editor.setOnScrollChangeListener { _, scrollY, _, _, _ ->
            lineNumbers.invalidate()
            fastScroll.invalidate()
            lastScrollChangeAt = SystemClock.uptimeMillis()
            if (editorTouching) {
                dragSamples.addLast(lastScrollChangeAt to scrollY)
                while (dragSamples.size > 2 &&
                    lastScrollChangeAt - dragSamples.first().first > 120
                ) {
                    dragSamples.removeFirst()
                }
            }
        }
        // Observation only — returning false keeps the editor's own
        // touch handling (selection, native fling) fully intact.
        editor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelGlide()
                    editorTouching = true
                    dragSamples.clear()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    editorTouching = false
                    if (dragSamples.size >= 2) {
                        val (t0, y0) = dragSamples.first()
                        val (t1, y1) = dragSamples.last()
                        val dt = t1 - t0
                        releaseVelocity = if (dt > 0) {
                            (y1 - y0) * 1000f / dt
                        } else {
                            0f
                        }
                    } else {
                        releaseVelocity = 0f
                    }
                    glideSettleTries = 0
                    handler.removeCallbacks(glideSettleRunnable)
                    handler.postDelayed(glideSettleRunnable, 180)
                }
            }
            false
        }
        fastScroll.onGrab = { cancelGlide() }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finishWithSave() }
        findViewById<ImageButton>(R.id.btnFileMenu).setOnClickListener { showFileMenu(it) }
        findViewById<ImageButton>(R.id.btnEditMenu).setOnClickListener { showEditMenu(it) }
        findViewById<ImageButton>(R.id.btnEditorMenu).setOnClickListener { showOptionsMenu(it) }
        findViewById<ImageButton>(R.id.btnOpenFile).setOnClickListener { pickExternalFile() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { onSavePressed() }
        findBar = findViewById(R.id.findBar)
        findField = findViewById(R.id.findField)
        findCounter = findViewById(R.id.findCounter)
        findCounter.setTextColor(palette.textSecondary)
        findViewById<ImageButton>(R.id.btnFind).setOnClickListener { toggleFindBar() }
        findViewById<ImageButton>(R.id.btnFindBarNext).setOnClickListener { findNext() }
        findViewById<ImageButton>(R.id.btnFindBarPrev).setOnClickListener { findPrev() }
        findViewById<ImageButton>(R.id.btnFindBarClose).setOnClickListener { hideFindBar() }
        findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                lastQuery = s?.toString() ?: ""
                lastFindIndex = -1
                if (lastQuery.isNotEmpty()) findNext() else refreshFindCounter()
            }
        })
        findField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext()
                true
            } else false
        }
        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<ImageButton>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<ImageButton>(R.id.btnCloseTab).setOnClickListener { closeTab(active) }
        updateUndoRedoButtons()
    }

    override fun onResume() {
        super.onResume()
        if (ThemeHelper.signature(this) != appliedSignature) {
            recreate()
            return
        }
        settings = AppSettings(this)
        applyEditorSettings()
        buildAccessoryBar()
    }

    override fun onPause() {
        super.onPause()
        stashActive()
        docs.filter { it.dirty }.forEach { saveDoc(it, silent = true) }
    }

    @Deprecated("Back may undo (setting) or leave after saving.")
    override fun onBackPressed() {
        if (AppSettings(this).undoByBack &&
            (undoStack.isNotEmpty() || editedSinceBaseline)
        ) {
            undo()
        } else {
            finishWithSave()
        }
    }

    // --- Documents ---------------------------------------------------------------

    private fun newDraftDoc(displayName: String): Doc {
        val draftsDir = File(filesDir, "drafts").apply { mkdirs() }
        val draft = File(draftsDir, "draft-${System.currentTimeMillis()}.txt")
        return Doc(
            name = sanitizeFileName(displayName),
            text = "",
            needsDestination = true,
            draftFile = draft,
            encoding = settings.encoding
        )
    }

    private fun resolveInitialDocs(): List<Doc> {
        val external = intent.getStringExtra(EXTRA_EXTERNAL_URI)
        val newName = intent.getStringExtra(EXTRA_NEW_NAME)
        return when {
            external != null -> {
                val uri = Uri.parse(external)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "file.txt"
                RecentFiles(this).add(uri, name)
                listOf(docFromUri(uri, name))
            }
            intent.data != null -> {
                val uri = intent.data!!
                val name = RecentFiles.displayName(this, uri)
                RecentFiles(this).add(uri, name)
                listOf(docFromUri(uri, name))
            }
            newName != null -> listOf(newDraftDoc(newName))
            else -> listOf(newDraftDoc("Untitled"))
        }
    }



    /** Reads a file; returns its text and the encoding actually used
     *  (a BOM in the file wins over the Settings default). */
    private fun readExternal(uri: Uri, preferred: String = settings.encoding):
        Pair<String, String> = try {
        TextFileIo.read(this, uri, preferred)
    } catch (e: Exception) {
        Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
        "" to preferred
    }

    private fun docFromUri(uri: Uri, name: String): Doc {
        val (text, usedEncoding) = readExternal(uri)
        return Doc(uri = uri, name = name, text = text, encoding = usedEncoding)
    }

    private fun stashActive() {
        val doc = docs.getOrNull(active) ?: return
        if (!suppress) {
            doc.text = editor.text.toString()
            doc.selection = editor.selectionStart
        }
    }

    private fun loadDocIntoEditor(doc: Doc) {
        suppress = true
        editor.setText(doc.text)
        editor.setSelection(doc.selection.coerceIn(0, editor.text.length))
        suppress = false
        cancelGlide()
        baseline = doc.text
        undoStack.clear()
        undoSnapshotChars = 0
        editedSinceBaseline = false
        redoStack.clear()
        updateUndoRedoButtons()
        titleView.text = doc.name
        when {
            doc.needsDestination && doc.text.isNotBlank() ->
                statusView.text = "Draft · tap Save to choose where to keep it"
            doc.needsDestination -> statusView.text = "New file · not saved yet"
            doc.dirty -> statusView.text = "Unsaved changes"
            else -> setStatusSaved()
        }
        // Large-file mode (KiA, item 46; how real editors stay
        // smooth): past ~200K characters, word wrap is the single
        // most expensive layout mode a stock EditText has — every
        // line must be broken and measured. Big files therefore open
        // unwrapped (horizontal scroll) even when the setting is on;
        // the Options menu can still force wrap back on for the
        // session. Small files are untouched.
        val large = doc.text.length > LARGE_FILE_CHARS
        editor.setHorizontallyScrolling(!settings.wordWrap || large)
        if (large && settings.wordWrap && !doc.largeWrapToastShown) {
            doc.largeWrapToastShown = true
            Toast.makeText(
                this,
                "Large file: word wrap off for smooth scrolling",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshLineNumbers()
        applyHighlighting()
    }

    private fun switchTo(index: Int) {
        if (index == active || index !in docs.indices) return
        stashActive()
        active = index
        loadDocIntoEditor(docs[active])
        renderTabs()
    }

    private fun addDoc(doc: Doc) {
        if (doc.uri != null) {
            val existing = docs.indexOfFirst { it.uri?.toString() == doc.uri?.toString() }
            if (existing >= 0) {
                switchTo(existing)
                return
            }
        }
        stashActive()
        docs.add(doc)
        active = docs.lastIndex
        loadDocIntoEditor(doc)
        renderTabs()
    }

    private fun closeTab(index: Int) {
        if (index !in docs.indices) return
        if (index == active) stashActive()
        val doc = docs[index]
        if (doc.needsDestination && doc.text.isNotBlank()) {
            CardDialogs.confirm(
                this, palette,
                "Save “${doc.name}”?",
                "This new file has not been saved anywhere yet.",
                "Save…",
                onPositive = {
                    pendingCloseIndex = index
                    promptSaveDestination(doc)
                },
                negativeLabel = "Discard",
                onNegative = {
                    doc.needsDestination = false
                    doc.text = ""
                    doc.draftFile?.delete()
                    doc.draftFile = null
                    doc.dirty = false
                    closeTab(index)
                }
            )
            return
        }
        if (doc.dirty) saveDoc(doc, silent = true)
        doc.draftFile?.delete()
        val activeDocBefore = docs.getOrNull(active)
        docs.removeAt(index)
        if (docs.isEmpty()) {
                finish()
            return
        }
        if (active > index) active -= 1
        if (active >= docs.size) active = docs.lastIndex
        // Only reload the editor when the visible document actually changed,
        // so closing a background tab never loses cursor position or history.
        if (docs[active] !== activeDocBefore) loadDocIntoEditor(docs[active])
        renderTabs()
    }

    private var lastTabSignature: String = ""

    private fun renderTabs() {
        val signature = docs.joinToString("|") { "${it.name}:${it.dirty}" } + "@$active"
        if (signature == lastTabSignature) return
        lastTabSignature = signature
        // The tab strip only earns its space with more than one file open;
        // a single giant chip under the title was the "orange brick" KiA
        // flagged as ugly. With one document the header already names it.
        (tabStrip.parent as? View)?.visibility =
            if (docs.size > 1) View.VISIBLE else View.GONE
        tabStrip.removeAllViews()
        val density = resources.displayMetrics.density
        docs.forEachIndexed { index, doc ->
            val isActive = index == active
            val fg = if (isActive) palette.onAccent else palette.textPrimary
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = palette.chipDrawable(isActive, density)
                setPadding(
                    (12 * density).toInt(), (7 * density).toInt(),
                    if (isActive) (6 * density).toInt() else (12 * density).toInt(),
                    (7 * density).toInt()
                )
                val nameView = TextView(this@EditorActivity).apply {
                    text = (if (doc.dirty) "• " else "") +
                        doc.name.removeSuffix(".txt")
                    textSize = 13.5f
                    if (isActive) {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    setTextColor(
                        if (isActive) fg else palette.textSecondary
                    )
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = (170 * density).toInt()
                }
                addView(nameView)
                if (isActive) {
                    val closeView = TextView(this@EditorActivity).apply {
                        text = "✕"
                        textSize = 11f
                        setTextColor(fg)
                        setPadding(
                            (8 * density).toInt(), 0,
                            (5 * density).toInt(), 0
                        )
                        setOnClickListener { closeTab(index) }
                    }
                    addView(closeView)
                }
                setOnClickListener { switchTo(index) }
                setOnLongClickListener {
                    closeTab(index)
                    true
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            tabStrip.addView(chip, params)
        }
    }

    // --- Saving --------------------------------------------------------------------

    private fun saveDoc(doc: Doc, silent: Boolean) {
        if (doc === docs.getOrNull(active)) stashActive()
        try {
            when {
                doc.uri != null -> TextFileIo.write(this, doc.uri!!, doc.text, doc.encoding)
                doc.draftFile != null -> {
                    doc.draftFile!!.parentFile?.mkdirs()
                    doc.draftFile!!.writeText(doc.text)
                    if (doc === docs.getOrNull(active) && !silent) {
                        statusView.text = "Draft saved · tap Save to choose where to keep it"
                    }
                    return
                }
                else -> return
            }
            doc.dirty = false
            if (doc === docs.getOrNull(active)) setStatusSaved()
            if (!silent) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            renderTabs()
        } catch (e: Exception) {
            if (doc === docs.getOrNull(active)) statusView.text = "Save failed"
            if (!silent) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onSavePressed() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        if (doc.needsDestination) promptSaveDestination(doc) else saveDoc(doc, silent = false)
    }

    /** Ask for a file name, then open the system "create file" picker so the
     *  user chooses the folder/location. Nothing is stored inside EditNote. */
    private fun promptSaveDestination(doc: Doc) {
        stashActive()
        val input = CardDialogs.inputField(this, palette, "File name", doc.name)
        CardDialogs.show(
            this, palette,
            "Save new file",
            "Name the file, then choose where to save it.",
            content = { it.addView(input) },
            focusView = input,
            buttons = listOf(
                CardDialogs.ButtonSpec("Cancel") { d ->
                    d.dismiss()
                    pendingDestinationDoc = null
                    pendingCloseIndex = -1
                    pendingFinish = false
                },
                CardDialogs.ButtonSpec("Choose location…", primary = true) { d ->
                    d.dismiss()
                    val typed = input.text.toString().trim()
                    if (typed.isNotEmpty()) doc.name = sanitizeFileName(typed)
                    pendingDestinationDoc = doc
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, doc.name)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_SAVE_DEST)
                }
            )
        )
    }

    private var pendingDestinationDoc: Doc? = null
    private var pendingCloseIndex: Int = -1
    private var pendingFinish: Boolean = false

    private fun finishWithSave() {
        stashActive()
        val pending = docs.firstOrNull { it.needsDestination && it.text.isNotBlank() }
        if (pending != null) {
            CardDialogs.confirm(
                this, palette,
                "Save “${pending.name}”?",
                "This new file has not been saved anywhere yet.",
                "Save…",
                onPositive = {
                    pendingFinish = true
                    promptSaveDestination(pending)
                },
                negativeLabel = "Discard",
                onNegative = {
                    pending.needsDestination = false
                    pending.text = ""
                    pending.draftFile?.delete()
                    pending.draftFile = null
                    pending.dirty = false
                    finishWithSave()
                }
            )
            return
        }
        docs.filter { it.dirty }.forEach { saveDoc(it, silent = true) }
        finish()
    }

    private fun setStatusSaved() {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        statusView.text = if (settings.autosave) "Saved $time · Autosave on" else "Saved $time"
    }

    // --- Undo / redo -----------------------------------------------------------------

    private fun applyText(text: String) {
        suppress = true
        editor.setText(text)
        editor.setSelection(editor.text.length)
        suppress = false
        baseline = text
        editedSinceBaseline = false
        docs.getOrNull(active)?.let {
            it.text = text
            it.dirty = true
        }
        refreshLineNumbers()
        applyHighlighting()
        updateUndoRedoButtons()
        if (settings.autosave) {
            handler.removeCallbacks(autosaveRunnable)
            handler.postDelayed(autosaveRunnable, settings.autosaveIntervalSec * 1000L)
        }
        renderTabs()
    }

    private fun undo() {
        handler.removeCallbacks(historyRunnable)
        val current = editor.text.toString()
        if (current != baseline) {
            redoStack.addLast(current)
            applyText(baseline)
            return
        }
        if (undoStack.isEmpty()) return
        redoStack.addLast(current)
        applyText(undoStack.removeLast())
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        pushUndoSnapshot(editor.text.toString())
        applyText(redoStack.removeLast())
    }

    private fun updateUndoRedoButtons() {
        val undoBtn = findViewById<ImageButton>(R.id.btnUndo) ?: return
        val canUndo = undoStack.isNotEmpty() || editedSinceBaseline
        undoBtn.isEnabled = canUndo
        undoBtn.alpha = if (canUndo) 1f else 0.35f
        val redoBtn = findViewById<ImageButton>(R.id.btnRedo) ?: return
        redoBtn.isEnabled = redoStack.isNotEmpty()
        redoBtn.alpha = if (redoStack.isNotEmpty()) 1f else 0.35f
    }

    // --- Editor prefs, line numbers, highlighting, auto-indent -------------------------

    private fun refreshLineNumbers() {
        if (!settings.lineNumbers) {
            gutter.visibility = View.GONE
            return
        }
        gutter.visibility = View.VISIBLE
        lineNumbers.refresh()
    }

    private fun applyEditorSettings() {
        settings = AppSettings(this)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp)
        val face = if (settings.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        editor.typeface = face
        lineNumbers.setTextSizeSp(settings.fontSizeSp)
        lineNumbers.setTypeface(face)
        val spacingPx = settings.lineSpacingSp * resources.displayMetrics.scaledDensity
        editor.setLineSpacing(spacingPx, 1f)
        editor.setHorizontallyScrolling(!settings.wordWrap)
        var inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (!settings.showSuggestions) {
            inputType = inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (settings.autoCapitalize) {
            inputType = inputType or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (editor.inputType != inputType) editor.inputType = inputType
        accessoryScroll.visibility = if (settings.accessoryView) View.VISIBLE else View.GONE
        applyEditorStyle()
        refreshLineNumbers()
    }

    /** The active editor colour scheme, or null for "Default" (app theme). */
    private fun currentStyle(): EditorStyle? =
        EditorStyles.byId(AppSettings(this).visualStyle)

    /** Paint the editing area (background, text, gutter) from the active
     *  Visual Style; "Default" follows the app theme as before. */
    private fun applyEditorStyle() {
        val style = currentStyle()
        val editorRow = editor.parent as? View
        if (style == null) {
            editor.setTextColor(palette.textPrimary)
            editor.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            editor.highlightColor = palette.accent and 0x66FFFFFF
            lineNumbers.setTextColor(palette.textSecondary)
            gutter.setBackgroundColor(palette.alt)
            editorRow?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            editor.setTextColor(style.foreground)
            editor.setBackgroundColor(style.background)
            editor.highlightColor = style.selection
            lineNumbers.setTextColor(style.gutterText)
            gutter.setBackgroundColor(style.gutterBackground)
            editorRow?.setBackgroundColor(style.background)
        }
    }

    private fun applyHighlighting() {
        val doc = docs.getOrNull(active) ?: return
        try {
            if (!settings.syntaxHighlight) {
                SyntaxHighlighter.clear(editor.text)
                return
            }
            val lang = SyntaxHighlighter.resolve(doc.syntax, doc.name)
            if (lang == null) {
                SyntaxHighlighter.clear(editor.text)
                return
            }
            val style = currentStyle() ?: EditorStyle(
                id = EditorStyles.DEFAULT_ID, name = "Default",
                background = palette.surface, foreground = palette.textPrimary,
                keyword = android.graphics.Color.rgb(156, 39, 176),
                string = android.graphics.Color.rgb(46, 125, 50),
                comment = android.graphics.Color.rgb(140, 140, 140),
                number = android.graphics.Color.rgb(21, 101, 192),
                tag = android.graphics.Color.rgb(0, 105, 92),
                property = android.graphics.Color.rgb(230, 81, 0)
            )
            SyntaxHighlighter.apply(editor.text, lang, style)
        } catch (e: Exception) {
            // Highlighting is cosmetic; never let it break editing.
        }
    }

    /** After Enter, copy the indentation of the line that was just ended. */
    private fun applyAutoIndent(newlineIndex: Int) {
        val text = editor.text ?: return
        var lineStart = newlineIndex - 1
        while (lineStart >= 0 && text[lineStart] != '\n') lineStart--
        lineStart++
        val indent = StringBuilder()
        var i = lineStart
        while (i < newlineIndex && (text[i] == ' ' || text[i] == '\t')) {
            indent.append(text[i])
            i++
        }
        if (indent.isEmpty()) return
        applyingIndent = true
        try {
            text.insert(newlineIndex + 1, indent)
        } finally {
            applyingIndent = false
        }
    }

    // --- Accessory (symbol) bar ----------------------------------------------------------

    private fun buildAccessoryBar() {
        accessoryBar.removeAllViews()
        val density = resources.displayMetrics.density
        fun addKey(label: String, action: () -> Unit) {
            val key = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                setOnClickListener { action() }
            }
            accessoryBar.addView(key)
        }
        addKey("Tab") { insertAtCursor(settings.indentUnit()) }
        addKey("←") { moveCursor(-1) }
        addKey("→") { moveCursor(1) }
        addKey("↑") { moveCursorLine(-1) }
        addKey("↓") { moveCursorLine(1) }
        listOf("/", "\\", "|", "<", ">", "{", "}", "(", ")", "[", "]", ";", "=", "\"", "'", "_", "-").forEach { sym ->
            addKey(sym) { insertAtCursor(sym) }
        }
    }

    private fun insertAtCursor(value: String) {
        if (readOnly) return
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, value)
        editor.setSelection(start + value.length)
        editor.requestFocus()
    }

    private fun moveCursor(delta: Int) {
        val pos = (editor.selectionStart + delta).coerceIn(0, editor.text.length)
        editor.setSelection(pos)
        editor.requestFocus()
    }

    private fun moveCursorLine(delta: Int) {
        val text = editor.text.toString()
        val pos = editor.selectionStart.coerceIn(0, text.length)
        val currentLineStart = text.lastIndexOf('\n', pos - 1) + 1
        val column = pos - currentLineStart
        val targetStart = if (delta < 0) {
            if (currentLineStart == 0) return
            val prevEnd = currentLineStart - 1
            text.lastIndexOf('\n', prevEnd - 1) + 1
        } else {
            val nextStart = text.indexOf('\n', pos) + 1
            if (nextStart <= 0) return
            nextStart
        }
        val lineEnd = text.indexOf('\n', targetStart).let { if (it < 0) text.length else it }
        editor.setSelection(minOf(targetStart + column, lineEnd))
        editor.requestFocus()
    }

    // --- Overflow menu & actions ------------------------------------------------------------

    // The editor's actions live behind THREE header buttons (KiA: one
    // giant overflow list was too long and messy, but nothing may be
    // deleted): a File menu (document icon), an Edit menu (pencil icon)
    // and an Options menu (⋮). Every action from the old single menu
    // exists in exactly one of the three.

    /** One row of the card popup menu; [checked] null = plain row. */
    private class MenuRow(
        val label: String,
        val checked: Boolean? = null,
        val action: () -> Unit
    )

    /** Modern card popup menu (KiA, item 23: the stock PopupMenu looked
     *  dated). Same items, drawn on a rounded card that follows the app
     *  palette, with accent check marks and row ripples. */
    private fun showCardMenu(anchor: View, rows: List<MenuRow>) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(14f, density)
            setPadding(0, dp(6), 0, dp(6))
        }
        val popup = android.widget.PopupWindow(
            container, dp(248),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.elevation = 8f * density
        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(
            android.R.attr.selectableItemBackground, ripple, true
        )
        rows.forEach { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(ripple.resourceId)
                setPadding(dp(16), dp(11), dp(16), dp(11))
                addView(TextView(this@EditorActivity).apply {
                    text = row.label
                    textSize = 15f
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                if (row.checked != null) {
                    addView(TextView(this@EditorActivity).apply {
                        text = if (row.checked) "✓" else ""
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(palette.accent)
                    })
                }
                setOnClickListener {
                    popup.dismiss()
                    row.action()
                }
            }
            container.addView(rowView)
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    /** File menu: everything about the document as a file. */
    private fun showFileMenu(anchor: View) {
        showCardMenu(
            anchor,
            listOf(
                MenuRow("New") { addDoc(newDraftDoc("Untitled")) },
                MenuRow("Open…") { pickExternalFile() },
                MenuRow("Save") { onSavePressed() },
                MenuRow("Save as…") { promptSaveAs() },
                MenuRow("Bookmarked", isActiveBookmarked()) { toggleActiveBookmark() },
                MenuRow("Reload") { reloadActive() },
                MenuRow("Close tab") { closeTab(active) },
                MenuRow("Share") { shareText() },
                MenuRow("Print") { printActive() },
                MenuRow("Settings") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            )
        )
    }

    /** Bookmark state of the open file (device files only — drafts
     *  have no URI yet; they join recents on their first save). */
    private fun isActiveBookmarked(): Boolean {
        val doc = docs.getOrNull(active) ?: return false
        val uri = doc.uri ?: return false
        val entry = RecentFiles(this).list()
            .firstOrNull { it.uri == uri.toString() } ?: return false
        return entry.bookmarked
    }

    private fun toggleActiveBookmark() {
        val doc = docs.getOrNull(active) ?: return
        val uri = doc.uri ?: run {
            Toast.makeText(
                this, "Save the file first to bookmark it", Toast.LENGTH_SHORT
            ).show()
            return
        }
        val store = RecentFiles(this)
        val entry = store.list().firstOrNull { it.uri == uri.toString() }
        if (entry == null) store.add(uri, doc.name)
        val now = !(entry?.bookmarked ?: false)
        store.setBookmarked(uri.toString(), now)
        Toast.makeText(
            this,
            if (now) "Bookmarked — pinned on the home screen" else "Bookmark removed",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Edit menu: everything that acts on the text. */
    private fun showEditMenu(anchor: View) {
        showCardMenu(
            anchor,
            listOf(
                MenuRow("Select all") { editor.selectAll() },
                MenuRow("Paste") { pasteFromClipboard() },
                MenuRow("Insert date & time") {
                    insertAtCursor(
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", Locale.getDefault()
                        ).format(Date())
                    )
                },
                MenuRow("Increase indent") { changeIndent(increase = true) },
                MenuRow("Decrease indent") { changeIndent(increase = false) },
                MenuRow("Go to line…") { promptGoToLine() },
                MenuRow("Find & Replace…") { promptReplace() },
                MenuRow("Statistics") { showStats() }
            )
        )
    }

    /** Options menu (⋮): how the editor looks and treats this file. */
    private fun showOptionsMenu(anchor: View) {
        showCardMenu(
            anchor,
            listOf(
                MenuRow("Visual style…") { promptVisualStyle() },
                MenuRow("Encoding…") { promptEncoding() },
                MenuRow("Syntax…") { promptSyntax() },
                MenuRow("Word wrap", settings.wordWrap) {
                    settings.wordWrap = !settings.wordWrap
                    editor.setHorizontallyScrolling(!settings.wordWrap)
                },
                MenuRow("Read only", readOnly) { setReadOnly(!readOnly) }
            )
        )
    }

    /** Visual Styles picker: recolours the editing area immediately. */
    private fun promptVisualStyle() {
        val names = EditorStyles.menuNames().toTypedArray()
        val ids = listOf(EditorStyles.DEFAULT_ID) + EditorStyles.ALL.map { it.id }
        val current = ids.indexOf(AppSettings(this).visualStyle).coerceAtLeast(0)
        CardDialogs.picker(this, palette, "Visual Styles", names.toList(), current) { which ->
            AppSettings(this).visualStyle = ids[which]
            settings = AppSettings(this)
            applyEditorStyle()
            applyHighlighting()
        }
    }

    /** Encoding picker for the active file. With a saved file open, the
     *  file is re-read from disk using the chosen encoding (that is what
     *  the encoding really means); for a new file it sets how the file
     *  will be written. */
    private fun promptEncoding() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        val names = com.kiaashkan.editnote.data.TextCodec.ENCODINGS.toTypedArray()
        // A file opened as UTF-32LE/BE shows the plain "UTF-32" entry.
        val docEncDisplay =
            if (doc.encoding.startsWith("UTF-32")) "UTF-32" else doc.encoding
        val current = names.indexOfFirst {
            it.equals(docEncDisplay, ignoreCase = true)
        }.coerceAtLeast(0)
        CardDialogs.picker(this, palette, "Encoding", names.toList(), current) { which ->
            applyEncodingChoice(doc, names[which])
        }
    }

    private fun applyEncodingChoice(doc: Doc, chosen: String) {
        if (chosen == doc.encoding) return
        val uri = doc.uri
        if (uri == null) {
            // New file: nothing to re-read; the choice applies on save.
            doc.encoding = chosen
            Toast.makeText(this, "Encoding: $chosen (applies when saved)", Toast.LENGTH_SHORT).show()
            return
        }
        val reread = {
            try {
                doc.text = com.kiaashkan.editnote.data.TextFileIo.readForced(this, uri, chosen)
                doc.encoding = chosen
                doc.dirty = false
                doc.selection = 0
                if (doc === docs.getOrNull(active)) loadDocIntoEditor(doc)
                Toast.makeText(this, "Encoding: $chosen", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not re-read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (doc.dirty) {
            CardDialogs.confirm(
                this, palette,
                "Re-read with $chosen?",
                "Changing the encoding re-reads the file from disk. Unsaved changes in this tab will be lost.",
                "Re-read",
                onPositive = { reread() }
            )
        } else reread()
    }

    /** Syntax picker for the active file: Automatic (by extension),
     *  None, or a specific language. Applies immediately. */
    private fun promptSyntax() {
        val doc = docs.getOrNull(active) ?: return
        val labels = mutableListOf("Automatic", "None")
        val ids = mutableListOf(SyntaxHighlighter.AUTO, SyntaxHighlighter.NONE)
        SyntaxHighlighter.LANGUAGES.forEach {
            labels.add(it.display)
            ids.add(it.id)
        }
        val current = ids.indexOf(doc.syntax).coerceAtLeast(0)
        CardDialogs.picker(this, palette, "Syntax", labels, current) { which ->
            doc.syntax = ids[which]
            applyHighlighting()
        }
    }

    private fun setReadOnly(on: Boolean) {
        readOnly = on
        editor.isFocusableInTouchMode = !on
        editor.isFocusable = !on
        editor.isCursorVisible = !on
        if (on) editor.clearFocus()
        if (on) statusView.text = "Read only" else setStatusSaved()
    }

    private fun reloadActive() {
        val doc = docs.getOrNull(active) ?: return
        val uri = doc.uri ?: run {
            Toast.makeText(this, "Nothing to reload for a new file", Toast.LENGTH_SHORT).show()
            return
        }
        val doReload = {
            val (reloadedText, reloadedEnc) = readExternal(uri, doc.encoding)
            doc.text = reloadedText
            doc.encoding = reloadedEnc
            doc.dirty = false
            doc.selection = 0
            if (doc === docs.getOrNull(active)) loadDocIntoEditor(doc)
        }
        if (doc.dirty) {
            CardDialogs.confirm(
                this, palette,
                "Reload file?",
                "Unsaved changes in this tab will be lost.",
                "Reload",
                onPositive = { doReload() }
            )
        } else doReload()
    }



    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val pasted = item?.coerceToText(this)?.toString()
        if (pasted.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        insertAtCursor(pasted)
    }

    /** Indent/outdent every line touched by the current selection. */
    private fun changeIndent(increase: Boolean) {
        if (readOnly) return
        val text = editor.text.toString()
        val selStart = editor.selectionStart.coerceIn(0, text.length)
        val selEnd = editor.selectionEnd.coerceIn(selStart, text.length)
        val unit = settings.indentUnit()

        // Absolute start offset of every line touched by the selection.
        val firstLineStart = text.lastIndexOf('\n', selStart - 1) + 1
        val lineStarts = mutableListOf<Int>()
        var pos = firstLineStart
        while (pos <= selEnd) {
            lineStarts.add(pos)
            val next = text.indexOf('\n', pos)
            if (next < 0) break
            pos = next + 1
            if (pos > selEnd && selEnd > firstLineStart && text.getOrNull(selEnd - 1) == '\n') break
        }
        if (lineStarts.isEmpty()) lineStarts.add(firstLineStart)

        val builder = StringBuilder(text)
        var deltaTotal = 0
        // Work from the last line backwards so earlier offsets stay valid.
        for (lineStart in lineStarts.asReversed()) {
            val lineEnd = builder.indexOf("\n", lineStart).let {
                if (it < 0) builder.length else it
            }
            val line = builder.substring(lineStart, lineEnd)
            val changed = when {
                increase -> unit + line
                line.startsWith("\t") -> line.substring(1)
                line.startsWith(unit) -> line.removePrefix(unit)
                line.startsWith(" ") -> line.replaceFirst(Regex("^ {1,4}"), "")
                else -> line
            }
            if (changed != line) {
                builder.replace(lineStart, lineEnd, changed)
                deltaTotal += changed.length - line.length
            }
        }
        val updated = builder.toString()
        if (updated == text) return
        suppress = true
        editor.setText(updated)
        suppress = false
        docs.getOrNull(active)?.let {
            it.text = updated
            it.dirty = true
        }
        val newStart = (selStart + if (increase) unit.length else 0)
            .coerceIn(0, updated.length)
        val newEnd = (selEnd + deltaTotal).coerceIn(newStart, updated.length)
        editor.setSelection(newStart, newEnd)
        refreshLineNumbers()
        applyHighlighting()
        if (settings.autosave) {
            handler.removeCallbacks(autosaveRunnable)
            handler.postDelayed(autosaveRunnable, settings.autosaveIntervalSec * 1000L)
        }
        renderTabs()
    }

    private fun printActive() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(
            doc.name,
            TextPrintAdapter(doc.name, doc.text),
            null
        )
    }


    private fun pickExternalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (settings.fileTypeFilter) "text/*" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun promptGoToLine() {
        val input = CardDialogs.inputField(
            this, palette, "Line number",
            inputType = InputType.TYPE_CLASS_NUMBER
        )
        CardDialogs.show(
            this, palette, "Go to line",
            content = { it.addView(input) },
            focusView = input,
            buttons = listOf(
                CardDialogs.ButtonSpec("Cancel") { d -> d.dismiss() },
                CardDialogs.ButtonSpec("Go", primary = true) { d ->
                    d.dismiss()
                    val line = input.text.toString().toIntOrNull()
                        ?: return@ButtonSpec
                    goToLine(line)
                }
            )
        )
    }

    private fun goToLine(line: Int) {
        val text = editor.text.toString()
        val lines = text.split("\n")
        val target = line.coerceIn(1, lines.size)
        var offset = 0
        for (i in 0 until target - 1) offset += lines[i].length + 1
        editor.requestFocus()
        editor.setSelection(offset.coerceAtMost(text.length))
    }

    // --- Save as -----------------------------------------------------------------------------

    private fun promptSaveAs() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        pendingDestinationDoc = doc
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, doc.name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CREATE)
    }

    @Deprecated("Framework result handling for the SAF pickers.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_SAVE_DEST) {
                pendingDestinationDoc = null
                pendingFinish = false
                pendingCloseIndex = -1
            }
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) { /* temporary access is fine */ }
                val name = RecentFiles.displayName(this, uri)
                RecentFiles(this).add(uri, name)
                addDoc(docFromUri(uri, name))
            }
            REQ_SAVE_DEST, REQ_CREATE -> {
                val doc = pendingDestinationDoc ?: docs.getOrNull(active) ?: return
                pendingDestinationDoc = null
                stashActiveIf(doc)
                try {
                    TextFileIo.write(this, uri, doc.text, doc.encoding)
                    doc.uri = uri
                    doc.name = RecentFiles.displayName(this, uri)
                    doc.needsDestination = false
                    doc.dirty = false
                    doc.draftFile?.delete()
                    doc.draftFile = null
                    RecentFiles(this).add(uri, doc.name)
                    if (doc === docs.getOrNull(active)) {
                        titleView.text = doc.name
                        setStatusSaved()
                    }
                    renderTabs()
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                if (requestCode == REQ_SAVE_DEST) {
                    val closeIndex = pendingCloseIndex
                    pendingCloseIndex = -1
                    if (closeIndex >= 0) closeTab(closeIndex)
                    if (pendingFinish) {
                        pendingFinish = false
                        finishWithSave()
                    }
                }
            }
        }
    }

    private fun stashActiveIf(doc: Doc) {
        if (doc === docs.getOrNull(active)) stashActive()
    }

    // --- Find / replace / stats / share ----------------------------------------------------------

    // --- Inline find bar ---------------------------------------------------
    // The old toolbar had separate up/down buttons next to the search
    // icon; KiA flagged them as doing "the same job as the magnifier".
    // They were find-previous/next, so they now live in the find bar
    // itself — the main toolbar keeps only the search icon.

    private fun toggleFindBar() {
        if (findBar.visibility == View.VISIBLE) hideFindBar() else showFindBar()
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        if (findField.text.toString() != lastQuery) {
            findField.setText(lastQuery)
        }
        findField.requestFocus()
        findField.selectAll()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(findField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        refreshFindCounter()
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        clearFindHighlights()
        editor.requestFocus()
    }

    // Find highlights (KiA, item 31): every match gets a soft accent
    // background and the current match a solid one, so the found word
    // is visible at a glance instead of making the user hunt for a
    // bare selection. BackgroundColorSpans only — the syntax
    // highlighter never touches those.
    private val findSpans = mutableListOf<Any>()

    private fun clearFindHighlights() {
        val text = editor.text ?: return
        findSpans.forEach { text.removeSpan(it) }
        findSpans.clear()
    }

    private fun applyFindHighlights() {
        clearFindHighlights()
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE) return
        if (lastQuery.isEmpty()) return
        val text = editor.text ?: return
        val soft = android.graphics.Color.argb(
            90,
            android.graphics.Color.red(palette.accent),
            android.graphics.Color.green(palette.accent),
            android.graphics.Color.blue(palette.accent)
        )
        for (idx in matchOffsets().take(500)) {
            val current = idx == lastFindIndex
            val bg = android.text.style.BackgroundColorSpan(
                if (current) palette.accent else soft
            )
            text.setSpan(
                bg, idx, idx + lastQuery.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            findSpans.add(bg)
            if (current) {
                val fg = android.text.style.ForegroundColorSpan(palette.onAccent)
                text.setSpan(
                    fg, idx, idx + lastQuery.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                findSpans.add(fg)
            }
        }
    }

    /** All match start offsets for the current query (case-insensitive). */
    private fun matchOffsets(): List<Int> {
        if (lastQuery.isEmpty()) return emptyList()
        val text = editor.text.toString()
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = text.indexOf(lastQuery, startIndex = from, ignoreCase = true)
            if (idx < 0) break
            out.add(idx)
            from = idx + 1
            if (out.size > 9999) break
        }
        return out
    }

    private fun refreshFindCounter() {
        if (!::findCounter.isInitialized) return
        if (lastQuery.isEmpty()) {
            findCounter.text = ""
            clearFindHighlights()
            return
        }
        val matches = matchOffsets()
        if (matches.isEmpty()) {
            findCounter.text = "0/0"
            clearFindHighlights()
            return
        }
        val current = matches.indexOf(lastFindIndex)
        findCounter.text = if (current >= 0) {
            "${current + 1}/${matches.size}"
        } else {
            "${matches.size}"
        }
        applyFindHighlights()
    }

    /** Select the match at [idx] AND scroll the document so it is
     *  actually on screen. setSelection alone does not scroll an
     *  unfocused EditText — focus sits in the find field — which is why
     *  search looked dead (KiA, item 28): the counter moved but the
     *  document never jumped to the match. */
    private fun revealMatch(idx: Int) {
        editor.setSelection(idx, idx + lastQuery.length)
        editor.post {
            val layout = editor.layout ?: return@post
            val line = layout.getLineForOffset(idx)
            val y = layout.getLineTop(line)
            val target = (y - editor.height / 3).coerceAtLeast(0)
            editor.scrollTo(0, target)
        }
    }

    private fun findNext() {
        if (lastQuery.isEmpty()) return
        val text = editor.text.toString()
        var idx = text.indexOf(lastQuery, startIndex = lastFindIndex + 1, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(lastQuery, startIndex = 0, ignoreCase = true)
        if (idx < 0) {
            if (findBar.visibility != View.VISIBLE) {
                Toast.makeText(this, "Not found: $lastQuery", Toast.LENGTH_SHORT).show()
            }
            refreshFindCounter()
            return
        }
        lastFindIndex = idx
        revealMatch(idx)
        refreshFindCounter()
    }

    private fun findPrev() {
        if (lastQuery.isEmpty()) return
        val text = editor.text.toString()
        var idx = if (lastFindIndex > 0) {
            text.lastIndexOf(lastQuery, startIndex = lastFindIndex - 1, ignoreCase = true)
        } else -1
        if (idx < 0) idx = text.lastIndexOf(lastQuery, startIndex = text.length, ignoreCase = true)
        if (idx < 0) {
            if (findBar.visibility != View.VISIBLE) {
                Toast.makeText(this, "Not found: $lastQuery", Toast.LENGTH_SHORT).show()
            }
            refreshFindCounter()
            return
        }
        lastFindIndex = idx
        revealMatch(idx)
        refreshFindCounter()
    }

    private fun promptReplace() {
        val findInput = CardDialogs.inputField(this, palette, "Find", lastQuery)
        val replaceInput = CardDialogs.inputField(this, palette, "Replace with")
        CardDialogs.show(
            this, palette, "Find & Replace",
            content = { body ->
                body.addView(findInput)
                body.addView(replaceInput)
            },
            focusView = findInput,
            buttons = listOf(
                CardDialogs.ButtonSpec("Cancel") { d -> d.dismiss() },
                CardDialogs.ButtonSpec("Replace all") { d ->
                    val find = findInput.text.toString()
                    if (find.isEmpty()) return@ButtonSpec
                    d.dismiss()
                    lastQuery = find
                    replaceAll(find, replaceInput.text.toString())
                },
                CardDialogs.ButtonSpec("Replace", primary = true) { d ->
                    val find = findInput.text.toString()
                    if (find.isEmpty()) return@ButtonSpec
                    d.dismiss()
                    lastQuery = find
                    replaceNext(find, replaceInput.text.toString())
                }
            )
        )
    }

    private fun replaceNext(find: String, replacement: String) {
        val text = editor.text.toString()
        val start = editor.selectionEnd.coerceAtLeast(0)
        var idx = text.indexOf(find, startIndex = start, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(find, startIndex = 0, ignoreCase = true)
        if (idx < 0) {
            Toast.makeText(this, "Not found: $find", Toast.LENGTH_SHORT).show()
            return
        }
        editor.text.replace(idx, idx + find.length, replacement)
        editor.setSelection(idx, idx + replacement.length)
        lastFindIndex = idx
        editor.post {
            val layout = editor.layout ?: return@post
            val y = layout.getLineTop(layout.getLineForOffset(idx))
            editor.scrollTo(0, (y - editor.height / 3).coerceAtLeast(0))
        }
    }

    private fun replaceAll(find: String, replacement: String) {
        val current = editor.text.toString()
        val updated = current.replace(find, replacement, ignoreCase = true)
        if (updated == current) {
            Toast.makeText(this, "Not found: $find", Toast.LENGTH_SHORT).show()
            return
        }
        val count = Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(current).count()
        suppress = true
        editor.setText(updated)
        suppress = false
        docs.getOrNull(active)?.let {
            it.text = updated
            it.dirty = true
        }
        refreshLineNumbers()
        applyHighlighting()
        if (settings.autosave) {
            handler.removeCallbacks(autosaveRunnable)
            handler.postDelayed(autosaveRunnable, settings.autosaveIntervalSec * 1000L)
        }
        renderTabs()
        Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
    }

    private fun showStats() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        val text = editor.text.toString()
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        val syntaxLabel = when (doc.syntax) {
            SyntaxHighlighter.AUTO ->
                SyntaxHighlighter.languageForExtension(doc.name)?.display?.let { "$it (automatic)" }
                    ?: "None (automatic)"
            SyntaxHighlighter.NONE -> "None"
            else -> SyntaxHighlighter.languageById(doc.syntax)?.display ?: doc.syntax
        }
        val styleLabel = currentStyle()?.name ?: "Default"
        CardDialogs.info(
            this, palette, "Statistics",
            "Characters: ${text.length}\nWords: $words\nLines: $lines\n" +
                "Encoding: ${doc.encoding}\nSyntax: $syntaxLabel\nVisual style: $styleLabel"
        )
    }

    private fun shareText() {
        val doc = docs.getOrNull(active) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            putExtra(Intent.EXTRA_TEXT, editor.text.toString())
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    companion object {
        private const val HISTORY_DELAY_MS = 600L
        private const val LARGE_FILE_CHARS = 200_000
        private const val HIGHLIGHT_DELAY_MS = 500L
        private const val REQ_OPEN = 2002
        private const val REQ_CREATE = 2001
        private const val REQ_SAVE_DEST = 2003
        private const val M_NEW_TAB = 20
        private const val M_OPEN_FILE = 2
        private const val M_SAVE = 22
        private const val M_SAVE_AS = 6
        private const val M_CLOSE_TAB = 24
        private const val M_SELECT_ALL = 25
        private const val M_PASTE = 26
        private const val M_INDENT = 28
        private const val M_OUTDENT = 29
        private const val M_WRAP = 7
        private const val M_RELOAD = 23
        private const val M_INSERT_TIME = 27
        private const val M_GOTO = 3
        private const val M_REPLACE = 10
        private const val M_STATS = 4
        private const val M_SHARE = 5
        private const val M_PRINT = 30
        private const val M_VISUAL_STYLE = 31
        private const val M_ENCODING = 32
        private const val M_SYNTAX = 33
        private const val M_READ_ONLY = 8
        private const val M_SETTINGS = 9
        private const val EXTRA_EXTERNAL_URI = "external_uri"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_NEW_NAME = "new_name"

        fun externalIntent(context: Context, uri: Uri, name: String): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_EXTERNAL_URI, uri.toString())
                .putExtra(EXTRA_NAME, name)

        /** A brand-new document: nothing exists on disk until the first save. */
        fun newDocIntent(context: Context, name: String): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_NEW_NAME, name)

        /** Local sanitize for draft/suggested names (device files are named by the system picker). */
        fun sanitizeFileName(rawName: String): String {
            var name = rawName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (name.isBlank()) name = "Untitled"
            if (!name.endsWith(".txt", ignoreCase = true)) name += ".txt"
            return name
        }
    }
}
