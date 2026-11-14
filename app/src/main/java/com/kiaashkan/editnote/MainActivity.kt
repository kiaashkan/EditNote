package com.kiaashkan.editnote

import android.app.Activity
import android.app.AlertDialog
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
 * shows the device files you recently opened (bookmarks pinned first),
 * plus Open / New actions. Files live — and die — on the device itself.
 */
class MainActivity : Activity() {

    private lateinit var recents: RecentFiles
    private lateinit var palette: Palette
    private lateinit var adapter: RecentAdapter
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private lateinit var resumeButton: Button
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

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position)
            startActivity(EditorActivity.externalIntent(this, Uri.parse(entry.uri), entry.name))
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showEntryActions(adapter.getItem(position))
            true
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val fab = findViewById<Button>(R.id.btnFab)
        fab.background = palette.ovalDrawable(palette.accent)
        fab.setTextColor(palette.onAccent)
        fab.setOnClickListener { toggleSpeedDial() }
        setupSpeedDial()

        resumeButton = findViewById(R.id.btnResume)
        resumeButton.setOnClickListener {
            startActivity(EditorActivity.restoreIntent(this))
        }

        // KiA (item 24): three direct icon buttons instead of one ⋮ menu —
        // sort (tinted with the accent while A–Z sorting is on), reset
        // list, and settings.
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
            recents.clear()
            refresh()
            Toast.makeText(this, "Recent list cleared (bookmarks kept)", Toast.LENGTH_SHORT).show()
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
        val query = searchBox.text?.toString()?.trim()?.lowercase() ?: ""
        val entries = recents.list().filter {
            query.isEmpty() || it.name.lowercase().contains(query)
        }
        adapter.submit(entries)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val hasSession = getSharedPreferences("editnote_session", MODE_PRIVATE)
            .getString("docs", "")?.isNotBlank() == true
        resumeButton.visibility =
            if (hasSession && AppSettings(this).resumeSession) View.VISIBLE else View.GONE
    }

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
        findViewById<Button>(R.id.btnFab).animate().rotation(45f).setDuration(160).start()
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
        findViewById<Button>(R.id.btnFab).animate().rotation(0f).setDuration(160).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (speedDialOpen) closeSpeedDial() else super.onBackPressed()
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
        val items = arrayOf(
            "Open",
            if (entry.bookmarked) "Remove bookmark" else "Bookmark",
            "Remove from list"
        )
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
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
            .show()
    }

    private fun pickExternalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (AppSettings(this@MainActivity).fileTypeFilter) "text/*" else "*/*"
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
        private var items: List<RecentFiles.Entry> = emptyList()

        fun submit(entries: List<RecentFiles.Entry>) {
            items = if (AppSettings(this@MainActivity).sortByName) {
                entries.sortedWith(compareBy({ !it.bookmarked }, { it.name.lowercase() }))
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
            title.text = (if (entry.bookmarked) "★ " else "") + entry.name
            title.setTextColor(palette.textPrimary)
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
    }
}
