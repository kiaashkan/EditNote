package com.kiaashkan.editnote

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
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
class MainActivity : BaseActivity() {

    private lateinit var recents: RecentFiles
    private lateinit var palette: Palette
    private lateinit var adapter: RecentAdapter
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private var appliedSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        // The app never mirrors (item 95): with supportsRtl=false
        // plus this, an RTL-locale phone (Persian/Arabic system
        // language) gets the exact same LTR layout as everyone else.
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
        // Swipe-delete (item 80): the entry leaves Recents; the
        // file itself stays on the device.
        adapter.onRemove = { entry ->
            recents.remove(entry.uri)
            refresh()
        }
        setupScrollThumb(listView)

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
        // Item 99: ImageView's FIT_START ignores the layout
        // direction, so in RTL the logo lockup hugged the wrong
        // side of its wide slot — align it to the end instead.
        val homeLogo = findViewById<android.widget.ImageView>(R.id.homeLogo)
        homeLogo.scaleType = if (
            resources.configuration.layoutDirection ==
            View.LAYOUT_DIRECTION_RTL
        ) {
            android.widget.ImageView.ScaleType.FIT_END
        } else {
            android.widget.ImageView.ScaleType.FIT_START
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

    /**
     * Short scroll indicator (KiA, item 81): the native scrollbar ran
     * the full height of the home list — a long line down the whole
     * screen. It is disabled in the layout; instead a slim fixed-size
     * pill (52dp, like the editor's scroll handle) tracks the scroll
     * position while the list moves and fades out when it settles.
     * Rows are uniform height, so the position math is exact.
     */
    private fun setupScrollThumb(listView: ListView) {
        val thumb = findViewById<View>(R.id.scrollThumb)
        val density = resources.displayMetrics.density
        thumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 2f * density
            setColor(palette.textSecondary)
            alpha = 150
        }
        val hideThumb = Runnable {
            thumb.animate().alpha(0f).setDuration(250).start()
        }
        fun positionThumb() {
            val child = listView.getChildAt(0) ?: return
            val rowH = child.height
            if (rowH == 0) return
            val scrollY = listView.firstVisiblePosition * rowH - child.top
            val range = rowH * listView.count - listView.height
            if (range <= 0) {
                thumb.alpha = 0f
                return
            }
            val frac = (scrollY.toFloat() / range).coerceIn(0f, 1f)
            thumb.translationY =
                listView.top + frac * (listView.height - thumb.height)
        }
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {
                if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    adapter.closeOpenSwipe()
                    thumb.removeCallbacks(hideThumb)
                    thumb.animate().alpha(1f).setDuration(120).start()
                } else {
                    thumb.removeCallbacks(hideThumb)
                    thumb.postDelayed(hideThumb, 700)
                }
            }

            override fun onScroll(
                v: AbsListView,
                first: Int,
                visible: Int,
                total: Int
            ) = positionThumb()
        })
    }

    private fun refresh() {
        findViewById<TextView>(R.id.sectionLabel).text =
            if (bookmarksOnly) getString(R.string.bookmarks_header) else getString(R.string.recent_files)
        val query = searchBox.text?.toString()?.trim()?.lowercase() ?: ""
        val entries = recents.list().filter {
            (query.isEmpty() || it.name.lowercase().contains(query)) &&
                (!bookmarksOnly || it.bookmarked)
        }
        adapter.submit(entries)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (entries.isEmpty()) {
            emptyText.text = when {
                query.isNotEmpty() -> getString(R.string.no_match, query)
                bookmarksOnly ->
                    getString(R.string.no_bookmarks)
                else ->
                    getString(R.string.empty_recents)
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
                text = getString(R.string.reset_recents_title)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.reset_recents_msg)
                textSize = 14f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(8), 0, 0)
            })
            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                setPadding(0, dp(18), 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.cancel)
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
                    text = getString(R.string.reset_btn)
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
                            getString(R.string.recents_cleared_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
            addView(buttons)
        }
        dialog.setContentView(card)
        // Dialogs never mirror either (item 95).
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
        // Item 79 (KiA): the Open mini-button was a card-coloured oval
        // with an accent glyph — on dark backgrounds the oval vanished
        // and the folder floated bare, unlike the accent-filled New
        // button. Both speed-dial buttons now share the exact same
        // mini-FAB treatment as the main "+" button.
        openBtn.background = palette.ovalDrawable(palette.accent)
        openBtn.setColorFilter(palette.onAccent)
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
            hint = getString(R.string.file_name_hint)
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
                text = getString(R.string.new_file)
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.new_file_msg)
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
        // Dialogs never mirror either (item 95).
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        val buttons = card.tag as LinearLayout
        buttons.addView(TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f
            setTextColor(palette.textPrimary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { dialog.dismiss() }
        })
        buttons.addView(TextView(this).apply {
            text = getString(R.string.create_btn)
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
                val name = input.text.toString().trim().ifEmpty { getString(R.string.untitled) }
                dialog.dismiss()
                startActivity(EditorActivity.newDocIntent(this@MainActivity, name))
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val name = input.text.toString().trim().ifEmpty { getString(R.string.untitled) }
                dialog.dismiss()
                startActivity(EditorActivity.newDocIntent(this@MainActivity, name))
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
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
        var onRemove: ((RecentFiles.Entry) -> Unit)? = null

        // Swipe-to-delete state (item 80): at most one row is open.
        // SWIPE_ACTION_DP mirrors the swipeDelete width in item_note.xml.
        private val swipeActionDp = 84f
        private var openUri: String? = null
        private var openCard: View? = null
        // Item 85: the harsh pure red clashed with the app and its
        // 84dp-only rect peeked out from under the card's rounded
        // corners. The under-layer is now the app's own accent with
        // the card's hairline border, drawn on a full-size rect
        // congruent with the card (see item_note.xml).
        private val deleteDrawable by lazy {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius =
                    16f * resources.displayMetrics.density
                setColor(palette.accent)
                setStroke(
                    (1 * resources.displayMetrics.density)
                        .toInt().coerceAtLeast(1),
                    palette.border
                )
            }
        }

        /** Snap the open row shut (list scrolls, data changed, …). */
        fun closeOpenSwipe() {
            openCard?.animate()?.translationX(0f)?.setDuration(180)?.start()
            openCard = null
            openUri = null
        }

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
            // Fresh data: no row stays swipe-open across a rebind.
            openUri = null
            openCard = null
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RecentFiles.Entry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_note, parent, false)
            val entry = items[position]
            val card = view.findViewById<View>(R.id.noteCard)
            card.background =
                palette.cardDrawable(density = resources.displayMetrics.density)
            bindSwipe(view, card, entry)
            val title = view.findViewById<TextView>(R.id.noteTitle)
            // File-name anchoring follows the app language
            // (item 98): in LTR languages Persian names anchor left
            // (item 94 — forced in code at bind time; XML
            // textDirection alone did not take effect); in RTL
            // languages (fa/ar) names render naturally.
            if (resources.configuration.layoutDirection ==
                View.LAYOUT_DIRECTION_RTL
            ) {
                title.textDirection = View.TEXT_DIRECTION_INHERIT
                title.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            } else {
                title.textDirection = View.TEXT_DIRECTION_LTR
                title.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            val preview = view.findViewById<TextView>(R.id.notePreview)
            val meta = view.findViewById<TextView>(R.id.noteMeta)
            // A leading LEFT-TO-RIGHT MARK pins the name's paragraph
            // direction at the string level (item 94, final form):
            // KiA's phone renders a Persian-first name as an RTL
            // paragraph despite the view's LTR direction settings —
            // the mark makes the first strong character LTR, so the
            // name anchors left under any direction heuristic.
            title.text = displayFileName(this@MainActivity, entry.name)
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
            view.setOnClickListener {
                // A tap on the swipe-open row closes it instead of
                // opening the file (item 80).
                if (openUri == entry.uri) closeOpenSwipe()
                else onOpen?.invoke(entry)
            }
            // No long-press menu (KiA, item 84: removed — Open is a
            // tap, bookmark is the star, removal is the swipe).
            preview.text = getString(R.string.device_file)
            preview.setTextColor(palette.textSecondary)
            meta.text = if (entry.lastOpened > 0) {
                // Item 98: follows the app language (locale date).
                getString(
                    R.string.opened_fmt,
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM, DateFormat.SHORT
                    ).format(Date(entry.lastOpened))
                )
            } else ""
            meta.setTextColor(palette.textSecondary)
            return view
        }

        /**
         * Swipe-left-to-delete (KiA, item 80): dragging a row's card
         * left reveals the Delete action behind it; releasing past
         * halfway snaps it open, otherwise it snaps shut. Vertical
         * drags are left to the list's own scrolling, and taps keep
         * working — the listener only consumes events once a clearly
         * horizontal drag is under way.
         */
        private fun bindSwipe(
            row: View,
            card: View,
            entry: RecentFiles.Entry
        ) {
            val density = resources.displayMetrics.density
            val actionW = swipeActionDp * density
            val slop = ViewConfiguration.get(this@MainActivity)
                .scaledTouchSlop
            val delete = row.findViewById<View>(R.id.swipeDelete)
            delete.background = deleteDrawable
            row.findViewById<android.widget.ImageView>(
                R.id.swipeDeleteIcon
            ).setColorFilter(palette.onAccent)
            row.findViewById<TextView>(R.id.swipeDeleteLabel)
                .setTextColor(palette.onAccent)
            // Item 86: the under-layer must NEVER consume touches.
            // Its click listener made it clickable, and once the
            // layer became full-size (item 85) it swallowed every
            // tap and swipe on the row — nothing opened and nothing
            // swiped (KiA's v1.4.4 test). The Delete-strip tap is
            // detected in the row's touch listener below instead.
            delete.isClickable = false
            delete.isFocusable = false
            // Recycling: a reused row shows its entry's swipe state.
            card.animate().cancel()
            card.translationX = if (entry.uri == openUri) {
                openCard = card
                -actionW
            } else 0f
            var downX = 0f
            var downY = 0f
            var startTx = 0f
            var mode = 0 // 0 undecided, 1 horizontal swipe, 2 vertical
            row.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        startTx = card.translationX
                        mode = 0
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (mode == 0) {
                            if (kotlin.math.abs(dx) > slop &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy)
                            ) {
                                mode = 1
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            } else if (kotlin.math.abs(dy) > slop) {
                                mode = 2
                            }
                        }
                        if (mode == 1) {
                            card.translationX =
                                (startTx + dx).coerceIn(-actionW, 0f)
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (mode == 1) {
                            val open = card.translationX < -actionW / 2
                            if (open) {
                                if (openUri != entry.uri) closeOpenSwipe()
                                openUri = entry.uri
                                openCard = card
                                card.animate().translationX(-actionW)
                                    .setDuration(180).start()
                            } else {
                                if (openUri == entry.uri) {
                                    openUri = null
                                    openCard = null
                                }
                                card.animate().translationX(0f)
                                    .setDuration(180).start()
                            }
                            mode = 0
                            true
                        } else if (
                            ev.actionMasked == MotionEvent.ACTION_UP &&
                            openUri == entry.uri &&
                            ev.x >= v.width - actionW
                        ) {
                            // Item 86: a tap on the exposed Delete
                            // strip of the swipe-open row (the layer
                            // itself no longer takes clicks).
                            onRemove?.invoke(entry)
                            true
                        } else false
                    }
                    else -> false
                }
            }
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

    /** File-name rendering follows the app language (item 98): in
     *  LTR languages a leading LEFT-TO-RIGHT MARK keeps Persian
     *  names anchored left (item 94); in RTL languages (fa/ar) the
     *  name renders naturally, with no mark. */
    fun displayFileName(context: android.content.Context, name: String): String =
        if (context.resources.configuration.layoutDirection ==
            View.LAYOUT_DIRECTION_RTL
        ) name else "\u200E" + name
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
                    // Item 97: the runs are first normalised by digit
                    // VALUE, so Persian/Arabic digits (۲، ٣…) compare
                    // as the numbers they are, not by codepoint.
                    val na = a.substring(i, iEnd)
                        .map { '0' + Character.digit(it, 10) }
                        .joinToString("").trimStart('0')
                    val nb = b.substring(j, jEnd)
                        .map { '0' + Character.digit(it, 10) }
                        .joinToString("").trimStart('0')
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
