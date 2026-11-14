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
import android.widget.ProgressBar
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
        var largeWrapToastShown: Boolean = false,
        /** Longest single line, measured during the background
         *  load (item 64): one enormous line is as expensive to
         *  wrap as a whole large file, so it triggers the same
         *  large-file mode. 0 = not measured (drafts). */
        var maxLine: Int = 0,
        /** True while the background loader (item 64) is reading
         *  this document; the editor shows a spinner and stays
         *  read-only until the content lands. */
        var pendingLoad: Boolean = false,
        /** True when the source app handed this file over with a
         *  read-only grant (item 65 — e.g. "Open with" from Google
         *  Drive): it can be read but never written back, so Save
         *  goes straight to the create-file picker and the copy
         *  becomes the document's home — no error is ever shown.
         *  Set at open from the intent flags, or later when a
         *  write is denied despite looking writable. Cleared when
         *  the doc is rebound to a user-picked location. */
        var readOnlySource: Boolean = false
    )

    private lateinit var settings: AppSettings
    private lateinit var palette: Palette
    private lateinit var editor: FastEditorView
    private lateinit var fastScroll: FastScrollHandle
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var tabStrip: LinearLayout
    private lateinit var accessoryScroll: View
    private lateinit var accessoryBar: LinearLayout
    private lateinit var findBar: View
    private lateinit var findField: EditText
    private lateinit var findCounter: TextView
    private lateinit var loadProgress: ProgressBar

    // Background file I/O (KiA, item 64): reads and decodes never
    // run on the UI thread any more — that was the open-time ANR.
    private val ioExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "editnote-io").apply { isDaemon = true }
        }

    // Highlight bookkeeping for the chunked path (item 64): every
    // edit bumps editCounter (in-flight chunk results computed from
    // older text are dropped on delivery) and remembers the
    // earliest edited offset, so a re-plan only recomputes from
    // the edited chunk onward.
    private var editCounter = 0
    private var lastEditStart = Int.MAX_VALUE

    private class HighlightPlan(
        val doc: Doc,
        val version: Int,
        /** editCounter at plan time: the snapshot in [source] is
         *  only valid while no edit has landed since. */
        val stamp: Int,
        val source: String,
        val lang: SyntaxHighlighter.Lang,
        val style: EditorStyle,
        val chunks: List<IntRange>,
        val done: MutableSet<Int> = mutableSetOf()
    )

    private var currentPlan: HighlightPlan? = null

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

    // Scrolling lives inside the FastEditorView engine now (item
    // 51): its own OverScroller fling replaced the old observe-and-
    // extend glide machinery that patched the stock EditText.

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
        val current = editor.textString()
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

    private val scrollHighlightRunnable = Runnable {
        val plan = currentPlan ?: return@Runnable
        if (plan.chunks.size <= HIGHLIGHT_FULL_PLAN_CHUNKS) return@Runnable
        val len = editor.textLength()
        val max = editor.maxScrollY()
        if (len == 0 || max <= 0) return@Runnable
        val approx = (editor.scrollYPos.toDouble() / max * len).toInt()
        ensureHighlightAround(plan, approx)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        settings = AppSettings(this)
        palette = Palette(this)
        appliedSignature = ThemeHelper.signature(this)

        editor = findViewById(R.id.editor)
        // Assigned early: loadDocIntoEditor (below) already reads it.
        loadProgress = findViewById(R.id.loadProgress)
        fastScroll = findViewById(R.id.fastScroll)
        fastScroll.editor = editor
        fastScroll.accentColor = palette.accent
        titleView = findViewById(R.id.fileTitle)
        statusView = findViewById(R.id.saveStatus)
        tabStrip = findViewById(R.id.tabStrip)
        accessoryScroll = findViewById(R.id.accessoryScroll)
        accessoryBar = findViewById(R.id.accessoryBar)
        titleView.setTextColor(palette.textPrimary)
        statusView.setTextColor(palette.textSecondary)
        editor.gutterColor = palette.textSecondary
        editor.gutterBackground = palette.alt
        editor.textColor = palette.textPrimary
        editor.accentColor = palette.accent

        docs.addAll(resolveInitialDocs())
        if (docs.isEmpty()) docs.add(newDraftDoc("Untitled"))
        active = 0
        applyEditorSettings()
        loadDocIntoEditor(docs[0])
        if (docs[0].pendingLoad) startLoad(docs[0])
        editor.post { refreshLineNumbers() }
        buildAccessoryBar()
        renderTabs()

        // The engine reports edits through one listener; it replaces
        // the old TextWatcher pair (auto-indent hook first, then the
        // after-change bookkeeping, same order as before).
        editor.onTextChanged = { start, _, inserted ->
            if (!suppress && !applyingIndent && settings.autoIndent &&
                inserted == "\n"
            ) {
                applyAutoIndent(start)
            }
            refreshLineNumbers()
            if (!suppress) {
                editCounter++
                if (start < lastEditStart) lastEditStart = start
                val doc = docs.getOrNull(active)
                if (doc != null) {
                    doc.dirty = true
                    editedSinceBaseline = true
                    lastFindIndex = -1
                    if (!doc.needsDestination) {
                        statusView.text = "Unsaved changes"
                    }
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
            }
        }
        editor.onScrollChanged = {
            fastScroll.invalidate()
            // Very large files highlight a window of chunks around
            // the viewport (item 64); scrolling asks for the chunks
            // near the new position, debounced.
            handler.removeCallbacks(scrollHighlightRunnable)
            handler.postDelayed(scrollHighlightRunnable, 250)
        }
        editor.onSelectionChanged = { refreshBracketPair() }
        fastScroll.onGrab = { editor.stopFling() }

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
                listOf(pendingDoc(uri, name))
            }
            intent.data != null -> {
                val uri = intent.data!!
                val name = RecentFiles.displayName(this, uri)
                RecentFiles(this).add(uri, name)
                // Item 65: "Open with" from another app (Drive,
                // Gmail, …) arrives with per-URI grant flags. Take
                // the persistable permission when the sender offers
                // it, and remember when NO write grant came along —
                // such a file can be read but never saved back, and
                // Save must offer a copy instead of failing raw.
                val flags = intent.flags
                val canWrite =
                    flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    var take = 0
                    if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        take = take or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    if (canWrite) {
                        take = take or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    }
                    if (take != 0) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, take)
                        } catch (e: SecurityException) { /* temporary grant only */ }
                    }
                }
                listOf(pendingDoc(uri, name).apply {
                    // Grant flags only exist for content:// URIs;
                    // a file:// path is judged by its first write
                    // instead (the saveDoc fallback marks it then).
                    readOnlySource = !canWrite && uri.scheme == "content"
                })
            }
            newName != null -> listOf(newDraftDoc(newName))
            else -> listOf(newDraftDoc("Untitled"))
        }
    }



    // (The old readExternal() helper — which answered every read
    // failure with an EMPTY string — was deleted in the audit: it
    // turned lost permissions into blank documents. All reads now
    // go through TextFileIo.read and handle failure explicitly.)

    /** A document whose content the background loader will fill
     *  in (KiA, item 64). Until then it renders as an empty,
     *  read-only tab with a spinner — never as a blank document
     *  bound to the URI (item 61's data-loss shape). */
    private fun pendingDoc(uri: Uri, name: String): Doc =
        Doc(uri = uri, name = name, text = "", encoding = settings.encoding,
            pendingLoad = true)

    /** Length of the longest single line in [text] (chars). */
    private fun longestLine(text: String): Int {
        var maxLine = 0
        var run = 0
        for (ch in text) {
            if (ch == '\n') {
                if (run > maxLine) maxLine = run
                run = 0
            } else {
                run++
            }
        }
        if (run > maxLine) maxLine = run
        return maxLine
    }

    /** One background read: decoded text, the encoding used, and
     *  the longest line (for large-file mode). Throws on failure;
     *  callers run this on ioExecutor only. */
    private fun readDocSync(uri: Uri, encoding: String): Triple<String, String, Int> {
        val (text, usedEncoding) = TextFileIo.read(this, uri, encoding)
        return Triple(text, usedEncoding, longestLine(text))
    }

    /**
     * Fill [doc] from its URI in the background (KiA, item 64 —
     * the open-time ANR: a 5,000-line file used to be read and
     * decoded on the UI thread inside onCreate). On failure the
     * item-61 behaviour holds: an actionable toast, and the
     * placeholder is replaced by a draft (single tab) or removed
     * (extra tab) — it never stays behind as an empty document
     * that autosave could write over the real file.
     */
    private fun startLoad(doc: Doc) {
        val uri = doc.uri ?: return
        ioExecutor.execute {
            val result = try {
                readDocSync(uri, doc.encoding)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null) {
                    Toast.makeText(
                        this,
                        "Access to “${doc.name}” was lost. Open it once " +
                            "via Open file — after that it stays in Recents.",
                        Toast.LENGTH_LONG
                    ).show()
                    doc.pendingLoad = false
                    val idx = docs.indexOf(doc)
                    if (idx >= 0) {
                        if (docs.size == 1) {
                            val draft = newDraftDoc("Untitled")
                            docs[0] = draft
                            active = 0
                            loadDocIntoEditor(draft)
                        } else {
                            docs.removeAt(idx)
                            if (idx == active) {
                                active = (idx - 1).coerceAtLeast(0)
                                loadDocIntoEditor(docs[active])
                            } else if (active > idx) {
                                active--
                            }
                        }
                        renderTabs()
                    }
                    return@runOnUiThread
                }
                doc.text = result.first
                doc.encoding = result.second
                doc.maxLine = result.third
                doc.pendingLoad = false
                doc.dirty = false
                if (doc === docs.getOrNull(active)) loadDocIntoEditor(doc)
                renderTabs()
            }
        }
    }

    private fun stashActive() {
        val doc = docs.getOrNull(active) ?: return
        if (!suppress) {
            doc.text = editor.textString()
            doc.selection = editor.selectionStart
        }
    }

    private fun loadDocIntoEditor(doc: Doc) {
        suppress = true
        editor.setTextContent(doc.text)
        editor.setSelection(doc.selection.coerceIn(0, editor.textLength()))
        suppress = false
        editor.stopFling()
        baseline = doc.text
        undoStack.clear()
        undoSnapshotChars = 0
        editedSinceBaseline = false
        redoStack.clear()
        updateUndoRedoButtons()
        titleView.text = doc.name
        when {
            doc.pendingLoad -> statusView.text = "Loading…"
            doc.needsDestination && doc.text.isNotBlank() ->
                statusView.text = "Draft · tap Save to choose where to keep it"
            doc.needsDestination -> statusView.text = "New file · not saved yet"
            doc.dirty -> statusView.text = "Unsaved changes"
            doc.readOnlySource -> statusView.text =
                "Read-only from source · Save saves a copy"
            else -> setStatusSaved()
        }
        // A document still loading in the background (item 64)
        // shows the spinner and cannot be typed into yet.
        loadProgress.visibility = if (doc.pendingLoad) View.VISIBLE else View.GONE
        editor.readOnly = readOnly || doc.pendingLoad
        // Large-file mode (KiA, item 46; how real editors stay
        // smooth): past ~200K characters, word wrap is the single
        // most expensive layout mode a stock EditText has — every
        // line must be broken and measured. Big files therefore open
        // unwrapped (horizontal scroll) even when the setting is on;
        // the Options menu can still force wrap back on for the
        // session. Small files are untouched.
        // One enormous line is as expensive to wrap as a whole
        // large file (item 64), so it triggers the same mode.
        val large = doc.text.length > LARGE_FILE_CHARS ||
            doc.maxLine > MAX_LINE_CHARS
        editor.wordWrap = settings.wordWrap && !large
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
        // Audit fix: find state is per-document. Without this, the
        // previous file's match ranges painted random positions of
        // the new file and the counter kept the old count.
        lastFindIndex = -1
        if (::findBar.isInitialized && findBar.visibility == View.VISIBLE) {
            refreshFindCounter()
        } else {
            clearFindHighlights()
        }
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
        // Item 65 (behaviour set by KiA): a read-only-source
        // document can never be written back, and the user must
        // never see a "Permission Denial" error for it. Autosave
        // stays silent (status line only). A manual Save goes
        // STRAIGHT to the create-file picker — no error, no
        // extra dialog — so the user just confirms the name and
        // place; the new file then becomes the document's home
        // (the REQ_CREATE handler rebinds it), and later saves
        // write there directly.
        if (doc.readOnlySource && doc.uri != null) {
            if (doc === docs.getOrNull(active)) {
                if (silent) {
                    statusView.text =
                        "Read-only from source · tap Save to save a copy"
                } else {
                    promptSaveAs()
                }
            }
            return
        }
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
            if (!silent && e is SecurityException && doc.uri != null &&
                doc === docs.getOrNull(active)
            ) {
                // Item 65: the grant looked writable but the
                // provider denied the write anyway (e.g. Drive).
                // Remember it (the next Save skips the attempt)
                // and — per KiA — show NO error: Save becomes
                // "create a copy" via the picker, and the copy
                // becomes the document's home.
                doc.readOnlySource = true
                promptSaveAs()
            } else {
                if (doc === docs.getOrNull(active)) statusView.text = "Save failed"
                if (!silent) {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
        editor.setTextContent(text)
        editor.setSelection(editor.textLength())
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
        val current = editor.textString()
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
        pushUndoSnapshot(editor.textString())
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
        // The engine draws the gutter itself; this just syncs the
        // visibility setting and asks for a redraw.
        editor.showLineNumbers = settings.lineNumbers
        editor.invalidate()
    }

    private fun applyEditorSettings() {
        settings = AppSettings(this)
        editor.setTextSizePx(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, settings.fontSizeSp,
                resources.displayMetrics
            )
        )
        val face = if (settings.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        editor.setTypeface(face)
        val spacingPx = settings.lineSpacingSp * resources.displayMetrics.scaledDensity
        editor.setLineSpacing(spacingPx, 1f)
        // Large-file mode (item 46) wins over the raw setting for
        // the document actually open — this runs on every resume
        // and must not undo what loadDocIntoEditor decided. The
        // condition must mirror loadDocIntoEditor's EXACTLY,
        // including item 64's longest-line criterion: judging by
        // total length alone re-enabled wrap on every resume for
        // a small file with one enormous line — the exact layout
        // cost large-file mode exists to avoid (audit, item 68).
        val activeMaxLine = docs.getOrNull(active)?.maxLine ?: 0
        editor.wordWrap = settings.wordWrap &&
            editor.textLength() <= LARGE_FILE_CHARS &&
            activeMaxLine <= MAX_LINE_CHARS
        editor.noSuggestions = !settings.showSuggestions
        editor.autoCapitalize = settings.autoCapitalize
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
            editor.textColor = palette.textPrimary
            editor.editorBackground = android.graphics.Color.TRANSPARENT
            editor.selectionColor = palette.accent and 0x66FFFFFF
            editor.gutterColor = palette.textSecondary
            editor.gutterBackground = palette.alt
            editorRow?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            editor.textColor = style.foreground
            editor.editorBackground = style.background
            editor.selectionColor = style.selection
            editor.gutterColor = style.gutterText
            editor.gutterBackground = style.gutterBackground
            editorRow?.setBackgroundColor(style.background)
        }
        editor.invalidate()
    }

    /** Item 67: one-line diagnosis of the most recent highlight
     *  pass, shown in Statistics. Every exit point of
     *  [applyHighlighting] and every async delivery records what
     *  actually happened, so a silent no-colour failure names its
     *  own cause instead of being guessed at. */
    private var highlightDiag: String = "not run yet"

    private fun applyHighlighting(direct: Boolean = false) {
        val doc = docs.getOrNull(active) ?: return
        try {
            if (!settings.syntaxHighlight) {
                highlightDiag = "off in Settings"
                editor.highlightRanges = emptyList()
                editor.codeBehaviors = false
                editor.pairRanges = emptyList()
                return
            }
            val source = editor.textString()
            // Name first; when the name says nothing (new files,
            // .txt), sniff the content — KiA, item 54.
            val lang = SyntaxHighlighter.resolve(doc.syntax, doc.name)
                ?: if (doc.syntax == SyntaxHighlighter.AUTO) {
                    SyntaxHighlighter.detect(source)
                } else {
                    null
                }
            if (lang == null) {
                highlightDiag =
                    "no language (syntax=${doc.syntax}, name=${doc.name})"
                editor.highlightRanges = emptyList()
                editor.codeBehaviors = false
                editor.pairRanges = emptyList()
                return
            }
            editor.codeBehaviors = true
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
            // Small files: compute and apply SYNCHRONOUSLY (KiA,
            // item 58). The scan is ~5 ms per 30K chars on a desktop
            // JVM and his code files are a few KB; the async path's
            // version/length guards could silently swallow results
            // on-device, and colours simply never appeared for him
            // even with a manual JavaScript pick.
            // Item 67: an EXPLICIT pick (Syntax / Visual Style menu)
            // is direct — the user is staring at the screen waiting
            // for colour, so up to the mid-tier cap the scan runs
            // synchronously on the spot: no executor, no guards, no
            // silent drop possible. A short hitch on a deliberate
            // menu tap beats a silent nothing.
            if (source.length <= SYNC_HIGHLIGHT_MAX ||
                (direct && source.length <= CHUNKED_HIGHLIGHT_MIN)
            ) {
                currentPlan = null
                editor.highlightRanges = try {
                    val ranges = SyntaxHighlighter.compute(source, lang, style)
                    highlightDiag =
                        "sync: ${ranges.size} ranges (${lang.id}, ${source.length} chars)"
                    ranges
                } catch (e: Exception) {
                    highlightDiag =
                        "sync: compute threw ${e.javaClass.simpleName} (${lang.id})"
                    emptyList()
                }
                return
            }
            // Mid-size files: one background scan of the whole
            // document (the path that has shipped since item 50's
            // freeze fix — the freeze was the old whole-document
            // span application on EditText, not this pure scan).
            if (source.length <= CHUNKED_HIGHLIGHT_MIN) {
                currentPlan = null
                val docRef = doc
                val version = ++highlightVersion
                highlightDiag =
                    "mid: queued (${lang.id}, ${source.length} chars)"
                highlightExecutor.execute {
                    val ranges = try {
                        SyntaxHighlighter.compute(source, lang, style)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    runOnUiThread {
                        if (version != highlightVersion) {
                            highlightDiag = "mid: dropped (newer pass)"
                            return@runOnUiThread
                        }
                        if (docs.getOrNull(active) !== docRef) {
                            highlightDiag = "mid: dropped (tab changed)"
                            return@runOnUiThread
                        }
                        if (editor.textLength() != source.length) {
                            highlightDiag = "mid: dropped (text changed)"
                            return@runOnUiThread
                        }
                        highlightDiag = "mid: delivered ${ranges.size} ranges"
                        editor.highlightRanges = ranges
                    }
                }
                return
            }
            // Huge files (KiA, item 64): past the old 200K cap the
            // highlighter used to give up entirely — a 5,000-line
            // file got NO colours. Now the document is scanned in
            // line-aligned chunks, viewport chunk first, and each
            // chunk's ranges are merged in as they land.
            planChunkedHighlight(doc, source, lang, style)
        } catch (e: Exception) {
            // Highlighting is cosmetic; never let it break editing —
            // but DO record the failure (item 67): a silent catch
            // is how this bug survived three reports.
            highlightDiag =
                "threw ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private val highlightExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "editnote-highlight").apply { isDaemon = true }
        }
    private var highlightVersion = 0

    /**
     * Progressive highlighting for huge documents (KiA, item 64).
     * The plan snapshots the text once; chunks are scanned on the
     * highlight executor (the chunk under the cursor first) and
     * merged into the engine chunk by chunk. Guards mirror the
     * mid-size path, plus an edit stamp: any edit since the plan
     * was made drops in-flight deliveries, and the debounced
     * re-plan then only recomputes from the edited chunk onward —
     * ranges before it stay valid because their text did not move.
     * For documents with more chunks than [HIGHLIGHT_FULL_PLAN_CHUNKS]
     * only a window around the cursor is queued up front; scrolling
     * queues more via [ensureHighlightAround].
     */
    private fun planChunkedHighlight(
        doc: Doc, source: String,
        lang: SyntaxHighlighter.Lang, style: EditorStyle
    ) {
        val version = ++highlightVersion
        val chunks = SyntaxHighlighter.splitChunks(source, HIGHLIGHT_CHUNK_SIZE)
        val plan = HighlightPlan(
            doc, version, editCounter, source, lang, style, chunks
        )
        currentPlan = plan
        highlightDiag =
            "chunked: plan of ${chunks.size} chunks (${lang.id}, ${source.length} chars)"
        val cursor = editor.selectionStart
        val firstIdx = chunks.indexOfFirst { cursor < it.last + 1 }
            .let { if (it < 0) 0 else it }
        // Ranges at/after the edit point (or everywhere, for a
        // fresh plan) are stale: drop them up front so old colours
        // never linger at shifted offsets while chunks recompute.
        val editStart = lastEditStart
        lastEditStart = Int.MAX_VALUE
        if (editStart != Int.MAX_VALUE) {
            val from = chunks.lastOrNull { it.first <= editStart }?.first ?: 0
            editor.applyHighlightChunk(from, source.length, emptyList())
            // The chunks before the edit kept their ranges; mark
            // them done so the scroll window never recomputes them.
            for (i in chunks.indices) {
                if (chunks[i].last + 1 <= from) plan.done.add(i)
            }
        } else {
            editor.highlightRanges = emptyList()
        }
        val order = ArrayList<Int>()
        if (chunks.size <= HIGHLIGHT_FULL_PLAN_CHUNKS) {
            order.add(firstIdx)
            for (i in chunks.indices) if (i != firstIdx) order.add(i)
        } else {
            val lo = (firstIdx - HIGHLIGHT_WINDOW_CHUNKS).coerceAtLeast(0)
            val hi = (firstIdx + HIGHLIGHT_WINDOW_CHUNKS)
                .coerceAtMost(chunks.lastIndex)
            order.add(firstIdx)
            for (i in lo..hi) if (i != firstIdx) order.add(i)
        }
        queueHighlightChunks(plan, order)
    }

    /** Queue more of [plan]'s chunks around [offset] (scrolling in
     *  a document too large to highlight in one pass). Only while
     *  the plan's snapshot is still the live text — after an edit,
     *  the debounced re-plan takes over. */
    private fun ensureHighlightAround(plan: HighlightPlan, offset: Int) {
        if (docs.getOrNull(active) !== plan.doc) return
        if (editCounter != plan.stamp) return
        val center = plan.chunks.indexOfFirst { offset < it.last + 1 }
        if (center < 0) return
        val lo = (center - HIGHLIGHT_WINDOW_CHUNKS).coerceAtLeast(0)
        val hi = (center + HIGHLIGHT_WINDOW_CHUNKS)
            .coerceAtMost(plan.chunks.lastIndex)
        val missing = (lo..hi).filter { it !in plan.done }
        if (missing.isEmpty()) return
        queueHighlightChunks(plan, missing)
    }

    private fun queueHighlightChunks(
        plan: HighlightPlan, indices: List<Int>
    ) {
        if (indices.isEmpty()) return
        highlightExecutor.execute {
            for (idx in indices) {
                if (plan.version != highlightVersion) return@execute
                val range = plan.chunks[idx]
                val chunkText = plan.source.substring(range.first, range.last + 1)
                val ranges = try {
                    SyntaxHighlighter.computeChunk(chunkText, plan.lang, plan.style)
                } catch (e: Exception) {
                    emptyList()
                }
                val offset = range.first
                val shifted = if (offset == 0) {
                    ranges
                } else {
                    ranges.map {
                        SyntaxHighlighter.Range(
                            it.from + offset, it.to + offset, it.color
                        )
                    }
                }
                runOnUiThread {
                    if (plan.version != highlightVersion) {
                        highlightDiag = "chunked: dropped (newer pass)"
                        return@runOnUiThread
                    }
                    if (docs.getOrNull(active) !== plan.doc) {
                        highlightDiag = "chunked: dropped (tab changed)"
                        return@runOnUiThread
                    }
                    if (editCounter != plan.stamp) {
                        highlightDiag = "chunked: dropped (text edited)"
                        return@runOnUiThread
                    }
                    plan.done.add(idx)
                    editor.applyHighlightChunk(range.first, range.last + 1, shifted)
                    highlightDiag =
                        "chunked: ${plan.done.size}/${plan.chunks.size} chunks, " +
                            "${editor.highlightRanges.size} ranges"
                }
            }
        }
    }

    /**
     * Enter handling (KiA, item 53 — real code-editor behaviour):
     * keep the previous line's indentation; after an opening brace
     * add one level; when Enter is pressed between a brace pair
     * ({|}), split into three lines with the closer back at the
     * base indent and the cursor on the middle line.
     */
    private fun applyAutoIndent(newlineIndex: Int) {
        val text = editor.textString()
        if (newlineIndex !in 0 until text.length) return
        var lineStart = newlineIndex - 1
        while (lineStart >= 0 && text[lineStart] != '\n') lineStart--
        lineStart++
        val prevLine = text.substring(lineStart, newlineIndex)
        val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val lastChar = prevLine.trimEnd().lastOrNull()
        val unit = settings.indentUnit()
        val extra = if (editor.codeBehaviors &&
            (lastChar == '{' || lastChar == '[' || lastChar == '(')
        ) unit else ""
        if (baseIndent.isEmpty() && extra.isEmpty()) return
        val restStart = newlineIndex + 1
        var restEnd = text.indexOf('\n', restStart)
        if (restEnd < 0) restEnd = text.length
        val rest = text.substring(restStart, restEnd)
        val firstRest = rest.firstOrNull { it != ' ' && it != '\t' }
        val matchingCloser = when (lastChar) {
            '{' -> '}'
            '[' -> ']'
            '(' -> ')'
            else -> null
        }
        applyingIndent = true
        try {
            if (extra.isNotEmpty() && matchingCloser != null &&
                firstRest == matchingCloser
            ) {
                val insert = baseIndent + extra + "\n" + baseIndent
                editor.replaceRange(restStart, restStart, insert)
                editor.setSelection(restStart + baseIndent.length + extra.length)
            } else {
                val insert = baseIndent + extra
                if (insert.isEmpty()) return
                editor.replaceRange(restStart, restStart, insert)
                editor.setSelection(restStart + insert.length)
            }
        } finally {
            applyingIndent = false
        }
    }

    /** Bracket-pair highlight (KiA, item 53), via the engine scan. */
    private fun refreshBracketPair() {
        if (!editor.codeBehaviors || editor.selectionStart != editor.selectionEnd) {
            if (editor.pairRanges.isNotEmpty()) editor.pairRanges = emptyList()
            return
        }
        val found = editor.bracketMatchAt(editor.selectionStart)
        if (found == null) {
            if (editor.pairRanges.isNotEmpty()) editor.pairRanges = emptyList()
            return
        }
        val (at, match) = found
        val color = (palette.accent and 0x00FFFFFF) or 0x55000000
        val ranges = listOf((at..at) to color, (match..match) to color)
        if (editor.pairRanges != ranges) editor.pairRanges = ranges
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
        editor.replaceRange(start, end, value)
        editor.setSelection(start + value.length)
        editor.requestFocus()
    }

    private fun moveCursor(delta: Int) {
        val pos = (editor.selectionStart + delta).coerceIn(0, editor.textLength())
        editor.setSelection(pos)
        editor.requestFocus()
    }

    private fun moveCursorLine(delta: Int) {
        val text = editor.textString()
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
                MenuRow("Select all") { editor.selectAllText() },
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
                    editor.wordWrap = settings.wordWrap
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
            applyHighlighting(direct = true)
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
            // Background, like every other read (item 64).
            loadProgress.visibility = View.VISIBLE
            ioExecutor.execute {
                var error: String? = null
                val text = try {
                    com.kiaashkan.editnote.data.TextFileIo
                        .readForced(this, uri, chosen)
                } catch (e: Exception) {
                    error = e.message
                    null
                }
                runOnUiThread {
                    loadProgress.visibility =
                        if (docs.getOrNull(active)?.pendingLoad == true) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    if (text == null) {
                        Toast.makeText(
                            this, "Could not re-read file: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        doc.text = text
                        doc.encoding = chosen
                        doc.maxLine = longestLine(text)
                        doc.dirty = false
                        doc.selection = 0
                        if (doc === docs.getOrNull(active)) {
                            loadDocIntoEditor(doc)
                        }
                        Toast.makeText(
                            this, "Encoding: $chosen", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
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
            // Item 67: an explicit pick is applied directly
            // (synchronously up to the mid-tier cap) — the user is
            // watching for the colours and must never be left with
            // a silently dropped background delivery.
            applyHighlighting(direct = true)
        }
    }

    private fun setReadOnly(on: Boolean) {
        readOnly = on
        editor.readOnly = on
        editor.isFocusableInTouchMode = !on
        editor.isFocusable = !on
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
            // Audit fix: readExternal() answered a failed read with
            // an EMPTY string — reloading a file whose access was
            // lost wiped the tab's text and marked it clean. Read
            // with a throwing call and abort on failure instead.
            // Item 64: the read itself now runs in the background.
            loadProgress.visibility = View.VISIBLE
            ioExecutor.execute {
                var error: String? = null
                val result = try {
                    readDocSync(uri, doc.encoding)
                } catch (e: Exception) {
                    error = e.message
                    null
                }
                runOnUiThread {
                    loadProgress.visibility =
                        if (docs.getOrNull(active)?.pendingLoad == true) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    if (result == null) {
                        Toast.makeText(
                            this, "Could not reload file: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        doc.text = result.first
                        doc.encoding = result.second
                        doc.maxLine = result.third
                        doc.dirty = false
                        doc.selection = 0
                        if (doc === docs.getOrNull(active)) {
                            loadDocIntoEditor(doc)
                        }
                    }
                }
            }
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
        val text = editor.textString()
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

        // Transform only the touched lines' span and push it
        // through the engine's edit funnel (audit fix — this used
        // to swap the WHOLE document via setTextContent under
        // suppress: the scroll jumped to the top, undo bookkeeping
        // never saw the change and the redo stack kept stale text).
        val spanStart = firstLineStart
        val lastStart = lineStarts.last()
        val spanEnd = text.indexOf('\n', lastStart).let {
            if (it < 0) text.length else it
        }
        val spanLines = text.substring(spanStart, spanEnd).split("\n")
        var deltaTotal = 0
        val newSpan = spanLines.joinToString("\n") { line ->
            val changed = when {
                increase -> unit + line
                line.startsWith("\t") -> line.substring(1)
                line.startsWith(unit) -> line.removePrefix(unit)
                line.startsWith(" ") -> line.replaceFirst(Regex("^ {1,4}"), "")
                else -> line
            }
            deltaTotal += changed.length - line.length
            changed
        }
        if (deltaTotal == 0 && newSpan == text.substring(spanStart, spanEnd)) return
        val savedScroll = editor.scrollYPos
        editor.replaceRange(spanStart, spanEnd, newSpan)
        val updatedLength = text.length + deltaTotal
        val newStart = (selStart + if (increase) unit.length else 0)
            .coerceIn(0, updatedLength)
        val newEnd = (selEnd + deltaTotal).coerceIn(newStart, updatedLength)
        editor.setSelection(newStart, newEnd)
        editor.scrollToY(savedScroll)
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
        val text = editor.textString()
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
                val doc = pendingDoc(uri, name)
                addDoc(doc)
                startLoad(doc)
            }
            REQ_SAVE_DEST, REQ_CREATE -> {
                val doc = pendingDestinationDoc ?: docs.getOrNull(active) ?: return
                pendingDestinationDoc = null
                stashActiveIf(doc)
                try {
                    TextFileIo.write(this, uri, doc.text, doc.encoding)
                    // KiA, item 61: without this, the create grant
                    // dies with the process and the file can never be
                    // reopened from Recents ("Permission Denial").
                    // REQ_OPEN took its permission; the save flows
                    // never did.
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: SecurityException) { /* keep the write */ }
                    doc.uri = uri
                    doc.name = RecentFiles.displayName(this, uri)
                    doc.needsDestination = false
                    // The doc now lives at a user-picked, writable
                    // location (item 65): the read-only-source flag
                    // of its previous home no longer applies.
                    doc.readOnlySource = false
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
    private fun clearFindHighlights() {
        editor.findRanges = emptyList()
        editor.findFgRanges = emptyList()
    }

    private fun applyFindHighlights() {
        clearFindHighlights()
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE) return
        if (lastQuery.isEmpty()) return
        val soft = android.graphics.Color.argb(
            90,
            android.graphics.Color.red(palette.accent),
            android.graphics.Color.green(palette.accent),
            android.graphics.Color.blue(palette.accent)
        )
        val bg = mutableListOf<Pair<IntRange, Int>>()
        val fg = mutableListOf<Pair<IntRange, Int>>()
        for (idx in matchOffsets().take(500)) {
            val range = idx until idx + lastQuery.length
            if (idx == lastFindIndex) {
                bg.add(range to palette.accent)
                fg.add(range to palette.onAccent)
            } else {
                bg.add(range to soft)
            }
        }
        editor.findRanges = bg
        editor.findFgRanges = fg
    }

    /** All match start offsets for the current query (case-insensitive). */
    private fun matchOffsets(): List<Int> {
        if (lastQuery.isEmpty()) return emptyList()
        val text = editor.textString()
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
        editor.scrollToOffset(idx)
    }

    private fun findNext() {
        if (lastQuery.isEmpty()) return
        val text = editor.textString()
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
        val text = editor.textString()
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
        val text = editor.textString()
        val start = editor.selectionEnd.coerceAtLeast(0)
        var idx = text.indexOf(find, startIndex = start, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(find, startIndex = 0, ignoreCase = true)
        if (idx < 0) {
            Toast.makeText(this, "Not found: $find", Toast.LENGTH_SHORT).show()
            return
        }
        editor.replaceRange(idx, idx + find.length, replacement)
        editor.setSelection(idx, idx + replacement.length)
        lastFindIndex = idx
        editor.scrollToOffset(idx)
    }

    private fun replaceAll(find: String, replacement: String) {
        val current = editor.textString()
        val updated = current.replace(find, replacement, ignoreCase = true)
        if (updated == current) {
            Toast.makeText(this, "Not found: $find", Toast.LENGTH_SHORT).show()
            return
        }
        val count = Regex(Regex.escape(find), RegexOption.IGNORE_CASE).findAll(current).count()
        // Audit fix: this used setTextContent under suppress — the
        // change was invisible to undo bookkeeping (baseline never
        // updated, redo stack kept stale text) and the view jumped
        // to the top. One engine edit keeps dirty/undo/autosave
        // bookkeeping via the normal listener and preserves the
        // scroll position and selection.
        val savedScroll = editor.scrollYPos
        val savedSel = editor.selectionStart
        editor.replaceRange(0, current.length, updated)
        editor.setSelection(savedSel.coerceIn(0, updated.length))
        editor.scrollToY(savedScroll)
        Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
    }

    private fun showStats() {
        val doc = docs.getOrNull(active) ?: return
        stashActive()
        val text = editor.textString()
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
        val syntaxLabel = when (doc.syntax) {
            SyntaxHighlighter.AUTO ->
                SyntaxHighlighter.languageForExtension(doc.name)?.display?.let { "$it (automatic)" }
                    ?: SyntaxHighlighter.detect(text)?.display?.let { "$it (automatic)" }
                    ?: "None (automatic)"
            SyntaxHighlighter.NONE -> "None"
            else -> SyntaxHighlighter.languageById(doc.syntax)?.display ?: doc.syntax
        }
        val styleLabel = currentStyle()?.name ?: "Default"
        CardDialogs.info(
            this, palette, "Statistics",
            "Characters: ${text.length}\nWords: $words\nLines: $lines\n" +
                "Encoding: ${doc.encoding}\nSyntax: $syntaxLabel\nVisual style: $styleLabel" +
                "\nColour ranges: ${editor.highlightRanges.size}" +
                "\nHighlight: $highlightDiag"
        )
    }

    private fun shareText() {
        val doc = docs.getOrNull(active) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            putExtra(Intent.EXTRA_TEXT, editor.textString())
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    companion object {
        private const val HISTORY_DELAY_MS = 600L
        private const val LARGE_FILE_CHARS = 200_000
        private const val HIGHLIGHT_DELAY_MS = 500L
        private const val SYNC_HIGHLIGHT_MAX = 30_000

        // Item 64 (100K-line scale): past this size the whole-file
        // scan is replaced by line-aligned chunks of
        // HIGHLIGHT_CHUNK_SIZE; plans with at most
        // HIGHLIGHT_FULL_PLAN_CHUNKS chunks run to completion,
        // larger ones highlight a ±HIGHLIGHT_WINDOW_CHUNKS window
        // around the cursor/viewport. A single line longer than
        // MAX_LINE_CHARS triggers large-file mode on its own.
        private const val CHUNKED_HIGHLIGHT_MIN = 200_000
        private const val HIGHLIGHT_CHUNK_SIZE = 65_536
        private const val HIGHLIGHT_FULL_PLAN_CHUNKS = 40
        private const val HIGHLIGHT_WINDOW_CHUNKS = 10
        private const val MAX_LINE_CHARS = 4_000
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
