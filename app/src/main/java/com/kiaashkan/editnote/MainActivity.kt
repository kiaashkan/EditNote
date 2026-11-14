package com.kiaashkan.editnote

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.kiaashkan.editnote.data.AppSettings
import com.kiaashkan.editnote.data.RecentFiles
import java.text.DateFormat
import java.util.Date

/**
 * Home screen (v0.3): EditNote stores no files of its own. The home screen
 * shows the device files you recently opened, most recent first —
 * bookmarks are starred but never change the order (KiA, item 70) —
 * plus Open / New actions. Files live — and die — on the device itself.
 */
class MainActivity : Activity() {

    private lateinit var recents: RecentFiles
    private lateinit var palette: Palette
    private lateinit var adapter: RecentAdapter
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private var appliedSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recents = RecentFiles(this)
        palette = Palette(this)
        appliedSignature = ThemeHelper.signature(this)

        searchBox = findViewById(R.id.searchBox)
        searchBox.background = palette.pillDrawable(resources.displayMetrics.density)
        searchBox.setTextColor(palette.textPrimary)
        searchBox.setHintTextColor(palette.textSecondary)

        val listView = findViewById<ListView>(R.id.noteList)
        emptyText = findViewById(R.id.emptyText)
        emptyText.setTextColor(palette.textSecondary)
        adapter = RecentAdapter()
        listView.adapter = adapter

        // Row-level listeners (set in the adapter), NOT ListView item
        // listeners: an item click can be swallowed when a row hosts
        // interactive children, and KiA's recents stopped opening
        // twice because of it (items 43/49). A listener on the row
        // view itself cannot be intercepted that way.
        adapter.onOpen = { entry ->
            startActivity(EditorActivity.externalIntent(this, Uri.parse(entry.uri), entry.name))
        }
        adapter.onActions = { entry -> showEntryActions(entry) }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val fab = findViewById<ImageButton>(R.id.btnFab)
        fab.background = palette.ovalDrawable(palette.accent)
        fab.setColorFilter(palette.onAccent)
        fab.setOnClickListener { toggleSpeedDial() }
        setupSpeedDial()

        // KiA (item 24): three direct icon buttons instead of one ⋮ menu —
        // sort (tinted with the accent while A–Z sorting is on), reset
        // list, and settings.
        // Bookmarks view (KiA, item 41): a header star button next to
        // Sort/Reset/Settings filters the list to bookmarked files
        // only; accent-filled while on, like the Sort tint.
        val bookmarksBtn = findViewById<ImageButton>(R.id.btnHomeBookmarks)
        fun refreshBookmarksBtn() {
            bookmarksBtn.setImageResource(
                if (bookmarksOnly) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            bookmarksBtn.setColorFilter(
                if (bookmarksOnly) palette.accent else palette.textPrimary
            )
        }
        refreshBookmarksBtn()
        bookmarksBtn.setOnClickListener {
            bookmarksOnly = !bookmarksOnly
            refreshBookmarksBtn()
            refresh()
        }
        val sortBtn = findViewById<ImageButton>(R.id.btnHomeSort)
        fun refreshSortTint() {
            sortBtn.setColorFilter(
                if (AppSettings(this).sortByName) palette.accent else palette.textPrimary
            )
        }
        refreshSortTint()
        sortBtn.setOnClickListener {
            val s = AppSettings(this)
            s.sortByName = !s.sortByName
            refreshSortTint()
            refresh()
        }
        findViewById<ImageButton>(R.id.btnHomeReset).setOnClickListener {
            confirmResetList()
        }
        findViewById<ImageButton>(R.id.btnHomeSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (ThemeHelper.signature(this) != appliedSignature) {
            recreate()
            return
        }
        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.sectionLabel).text =
            if (bookmarksOnly) "BOOKMARKS" else "RECENT FILES"
        val query = searchBox.text?.toString()?.trim()?.lowercase() ?: ""
        val entries = recents.list().filter {
            (query.isEmpty() || it.name.lowercase().contains(query)) &&
                (!bookmarksOnly || it.bookmarked)
        }
        adapter.submit(entries)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (entries.isEmpty()) {
            emptyText.text = when {
                query.isNotEmpty() -> "No files match \u201C$query\u201D."
                bookmarksOnly ->
                    "No bookmarks yet.\nTap the star on a file to bookmark it."
                else ->
                    "No files yet.\nTap + to create a new file, or open one from your device."
            }
        }
    }

    /** Reset is destructive, so it asks first (KiA, item 36) — in a
     *  card window in the app's own design, not a stock dialog. Only
     *  the recent list is cleared; files and bookmarks stay. */
    private fun confirmResetList() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(20f, density)
            setPadding(dp(22), dp(20), dp(22), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "Reset the recent list?"
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = "This clears the list of recently opened files. " +
                    "Your files and bookmarks are not touched."
                textSize = 14f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(8), 0, 0)
            })
            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(18), 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Cancel"
                    textSize = 15f
                    setTextColor(palette.textPrimary)
                    val ripple = android.util.TypedValue()
                    theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, ripple, true
                    )
                    setBackgroundResource(ripple.resourceId)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener { dialog.dismiss() }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Reset"
                    textSize = 15f
                    isAllCaps = false
                    // Accent-filled pill (pillDrawable is the soft alt
                    // fill — onAccent text would vanish on it).
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 24f * density
                        setColor(palette.accent)
                    }
                    setTextColor(palette.onAccent)
                    setPadding(dp(22), 0, dp(22), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)
                    ).apply { marginStart = dp(10) }
                    setOnClickListener {
                        dialog.dismiss()
                        recents.clear()
                        refresh()
                        Toast.makeText(
                            this@MainActivity,
                            "Recent list cleared (bookmarks kept)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
            addView(buttons)
        }
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private var bookmarksOnly = false

    private var speedDialOpen = false

    /** Two small actions that pop up right above the "+" button. */
    private fun setupSpeedDial() {
        val density = resources.displayMetrics.density
        val newLabel = findViewById<TextView>(R.id.speedNewLabel)
        val openLabel = findViewById<TextView>(R.id.speedOpenLabel)
        for (label in listOf(newLabel, openLabel)) {
            label.background = palette.cardDrawable(18f, density)
            label.setTextColor(palette.textPrimary)
        }
        val newBtn = findViewById<ImageButton>(R.id.speedNew)
        newBtn.background = palette.ovalDrawable(palette.accent)
        newBtn.setColorFilter(palette.onAccent)
        val openBtn = findViewById<ImageButton>(R.id.speedOpen)
        openBtn.background = palette.ovalDrawable(palette.card)
        openBtn.setColorFilter(palette.accent)
        val openAction = {
            closeSpeedDial()
            pickExternalFile()
        }
        val newAction = {
            closeSpeedDial()
            promptNewFile()
        }
        newBtn.setOnClickListener { newAction() }
        newLabel.setOnClickListener { newAction() }
        openBtn.setOnClickListener { openAction() }
        openLabel.setOnClickListener { openAction() }
        findViewById<View>(R.id.speedScrim).setOnClickListener { closeSpeedDial() }
    }

    private fun toggleSpeedDial() {
        if (speedDialOpen) closeSpeedDial() else openSpeedDial()
    }

    private fun openSpeedDial() {
        speedDialOpen = true
        val dial = findViewById<View>(R.id.speedDial)
        findViewById<View>(R.id.speedScrim).visibility = View.VISIBLE
        dial.visibility = View.VISIBLE
        dial.alpha = 0f
        dial.translationY = 14f * resources.displayMetrics.density
        dial.animate().alpha(1f).translationY(0f).setDuration(160).start()
        findViewById<ImageButton>(R.id.btnFab).animate().rotation(45f).setDuration(160).start()
    }

    private fun closeSpeedDial() {
        if (!speedDialOpen) return
        speedDialOpen = false
        val dial = findViewById<View>(R.id.speedDial)
        dial.animate().alpha(0f)
            .setDuration(120)
            .withEndAction {
                dial.visibility = View.GONE
                findViewById<View>(R.id.speedScrim).visibility = View.GONE
            }
            .start()
        findViewById<ImageButton>(R.id.btnFab).animate().rotation(0f).setDuration(160).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Item 75 (KiA): leaving from the home screen closes the
        // app completely — the task leaves Recents too, instead of
        // the app idling underneath until a Force Stop.
        if (speedDialOpen) closeSpeedDial() else finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Item 75: on a genuine exit (Back from the root screen),
        // end the process — nothing of EditNote stays resident.
        // isFinishing is false for recreate() (theme/accent change)
        // and for system-driven destroys, so those never kill.
        if (isFinishing) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Modern card dialog for the new-file name (KiA, item 22: the stock
     *  AlertDialog looked cheap, with the input clipped at the edge). */
    private fun promptNewFile() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val input = EditText(this).apply {
            hint = "File name (e.g. ideas.txt)"
            isSingleLine = true
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textSecondary)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(palette.card)
                setStroke(dp(1), palette.accent)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(20f, density)
            setPadding(dp(22), dp(20), dp(22), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = "New file"
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = "You will choose where to save it."
                textSize = 14f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(4), 0, dp(14))
            })
            addView(input)
            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(16), 0, 0)
            }
            addView(buttons)
            tag = buttons
        }
        val dialog = android.app.Dialog(this)
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        val buttons = card.tag as LinearLayout
        buttons.addView(TextView(this).apply {
            text = "Cancel"
            textSize = 15f
            setTextColor(palette.textPrimary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { dialog.dismiss() }
        })
        buttons.addView(TextView(this).apply {
            text = "Create"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(palette.onAccent)
            background = palette.let {
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 20f * density
                    setColor(palette.accent)
                }
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                val name = input.text.toString().trim().ifEmpty { "Untitled" }
                dialog.dismiss()
                startActivity(EditorActivity.newDocIntent(this@MainActivity, name))
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val name = input.text.toString().trim().ifEmpty { "Untitled" }
                dialog.dismiss()
                startActivity(EditorActivity.newDocIntent(this@MainActivity, name))
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun showEntryActions(entry: RecentFiles.Entry) {
        val items = listOf(
            "Open",
            if (entry.bookmarked) "Remove bookmark" else "Bookmark",
            "Remove from list"
        )
        CardDialogs.picker(this, palette, entry.name, items, current = -1) { which ->
            when (which) {
                0 -> startActivity(
                    EditorActivity.externalIntent(this, Uri.parse(entry.uri), entry.name)
                )
                1 -> {
                    recents.setBookmarked(entry.uri, !entry.bookmarked)
                    refresh()
                }
                2 -> {
                    recents.remove(entry.uri)
                    refresh()
                }
            }
        }
    }

    private fun pickExternalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // KiA (item 73): NO type filter — every file may be
            // opened as text. The old "text/*" filter silently
            // blocked real text files (.js, .json, .xml, extension-
            // less…) whenever the storage provider reported them
            // as application/* or octet-stream instead of text/*.
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_OPEN)
    }

    @Deprecated("Framework-only: no AndroidX activity-result dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers only grant temporary access; the file still opens now.
            }
            val name = RecentFiles.displayName(this, uri)
            recents.add(uri, name)
            startActivity(EditorActivity.externalIntent(this, uri, name))
        }
    }

    private inner class RecentAdapter : BaseAdapter() {

        var onOpen: ((RecentFiles.Entry) -> Unit)? = null
        var onActions: ((RecentFiles.Entry) -> Unit)? = null

        private var items: List<RecentFiles.Entry> = emptyList()

        fun submit(entries: List<RecentFiles.Entry>) {
            // KiA (item 70): A–Z sorting is by name only — the old
            // bookmarked-first key pinned bookmarks to the top even
            // here. Default order (list order) is pure recency.
            // KiA (item 71): the name sort is NATURAL — digit runs
            // compare by value, so numbered files order 1, 2, 5, 10
            // instead of the plain lexicographic 1, 10, 2, 5.
            items = if (AppSettings(this@MainActivity).sortByName) {
                entries.sortedWith { a, b -> naturalNameCompare(a.name, b.name) }
            } else entries
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RecentFiles.Entry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_note, parent, false)
            val entry = items[position]
            view.findViewById<View>(R.id.noteCard).background =
                palette.cardDrawable(density = resources.displayMetrics.density)
            val title = view.findViewById<TextView>(R.id.noteTitle)
            val preview = view.findViewById<TextView>(R.id.notePreview)
            val meta = view.findViewById<TextView>(R.id.noteMeta)
            title.text = entry.name
            title.setTextColor(palette.textPrimary)
            // Visible bookmark star (KiA, item 38: bookmarks existed in
            // the data layer and a long-press menu, but with nothing on
            // the row the feature was undiscoverable). Bookmarked files
            // survive a list reset; they do NOT pin to the top (item 70).
            val star = view.findViewById<ImageButton>(R.id.btnBookmark)
            star.setImageResource(
                if (entry.bookmarked) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            star.setColorFilter(
                if (entry.bookmarked) palette.accent else palette.textSecondary
            )
            star.setOnClickListener {
                recents.setBookmarked(entry.uri, !entry.bookmarked)
                refresh()
            }
            view.setOnClickListener { onOpen?.invoke(entry) }
            view.setOnLongClickListener {
                onActions?.invoke(entry)
                true
            }
            preview.text = "Device file"
            preview.setTextColor(palette.textSecondary)
            meta.text = if (entry.lastOpened > 0) {
                "Opened " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(entry.lastOpened))
            } else ""
            meta.setTextColor(palette.textSecondary)
            return view
        }
    }

    companion object {
        private const val REQ_OPEN = 1001

        /**
         * Natural name ordering (KiA, item 71): digit runs compare
         * by numeric value ("2" < "10"), everything else compares
         * case-insensitively, character by character. Pure string
         * work — JVM-tested (1, 2, 5, 10 order; case; prefixes).
         */
        fun naturalNameCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var iEnd = i
                    while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
                    var jEnd = j
                    while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
                    // Numeric compare without overflow: strip the
                    // leading zeros, the longer run is the bigger
                    // number, equal lengths compare lexicographically.
                    val na = a.substring(i, iEnd).trimStart('0')
                    val nb = b.substring(j, jEnd).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val byDigits = na.compareTo(nb)
                    if (byDigits != 0) return byDigits
                    i = iEnd
                    j = jEnd
                } else {
                    val byChar = ca.lowercaseChar()
                        .compareTo(cb.lowercaseChar())
                    if (byChar != 0) return byChar
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
