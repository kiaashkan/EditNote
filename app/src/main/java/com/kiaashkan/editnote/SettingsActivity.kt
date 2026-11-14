package com.kiaashkan.editnote

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.kiaashkan.editnote.data.AppSettings

/**
 * Settings (v0.3): Appearance, Editor, Files & Saving, About.
 * All framework controls follow the theme's accent color, because
 * ThemeHelper picks a theme style per accent — nothing stays green.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var palette: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        palette = Palette(this)

        findViewById<TextView>(R.id.headerAppearance).setTextColor(palette.accent)
        findViewById<TextView>(R.id.headerEditor).setTextColor(palette.accent)
        findViewById<TextView>(R.id.headerFiles).setTextColor(palette.accent)

        // Grouped cards for every settings section.
        val cardBg = palette.cardDrawable(16f, resources.displayMetrics.density)
        findViewById<LinearLayout>(R.id.cardAppearance).background = cardBg
        findViewById<LinearLayout>(R.id.cardEditor).background =
            palette.cardDrawable(16f, resources.displayMetrics.density)
        findViewById<LinearLayout>(R.id.cardFiles).background =
            palette.cardDrawable(16f, resources.displayMetrics.density)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // About card: rounded card background + tappable GitHub rows.
        findViewById<LinearLayout>(R.id.aboutCard).background =
            palette.cardDrawable(16f, resources.displayMetrics.density)
        findViewById<LinearLayout>(R.id.aboutGithub).setOnClickListener {
            openUrl("https://github.com/kiaashkan")
        }
        // Update row (KiA, item 21): EditNote keeps ZERO permissions, so
        // updating opens the latest GitHub release in the browser instead
        // of downloading in-app.
        findViewById<LinearLayout>(R.id.aboutUpdate).setOnClickListener {
            openUrl("https://github.com/kiaashkan/EditNote/releases/latest")
        }
        // Bug report row (KiA, item 29): opens the mail app addressed to
        // KiA with the app/Android version already in the message.
        findViewById<LinearLayout>(R.id.aboutBug).setOnClickListener {
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                "?"
            }
            val subject = android.net.Uri.encode("EditNote bug report")
            val body = android.net.Uri.encode(
                "EditNote $version · Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                    "What happened:\n"
            )
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_SENDTO,
                        android.net.Uri.parse(
                            "mailto:kiaashkan6@gmail.com?subject=$subject&body=$body"
                        )
                    )
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this, "No email app available", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Theme
        val themeGroup = findViewById<RadioGroup>(R.id.themeGroup)
        themeGroup.check(
            when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> R.id.themeLight
                AppSettings.THEME_DARK -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.themeMode = when (checkedId) {
                R.id.themeLight -> AppSettings.THEME_LIGHT
                R.id.themeDark -> AppSettings.THEME_DARK
                else -> AppSettings.THEME_SYSTEM
            }
            recreate()
        }

        // Accent color swatches
        val accentLabel = findViewById<TextView>(R.id.accentLabel)
        val accentRow = findViewById<LinearLayout>(R.id.accentRow)
        val density = resources.displayMetrics.density
        val names = mapOf(
            AppSettings.ACCENT_ORANGE to "Orange",
            AppSettings.ACCENT_MONO to "Black / White",
            AppSettings.ACCENT_GREEN to "Green",
            AppSettings.ACCENT_BLUE to "Pink",
            AppSettings.ACCENT_PURPLE to "Purple",
            AppSettings.ACCENT_TEAL to "Cream"
        )
        fun refreshAccentLabel() {
            accentLabel.text = names[settings.accent] ?: "Orange"
        }
        accentLabel.setTextColor(palette.accent)
        refreshAccentLabel()
        AppSettings.ACCENTS.forEach { accentName ->
            val swatchColor = AppSettings.colorFor(accentName, settings.isDark(this))
            val swatch = Button(this).apply {
                text = if (settings.accent == accentName) "✓" else ""
                setTextColor(
                    if (swatchColor == android.graphics.Color.WHITE) {
                        android.graphics.Color.rgb(17, 17, 17)
                    } else android.graphics.Color.WHITE
                )
                background = Palette(this@SettingsActivity).ovalDrawable(swatchColor)
                setOnClickListener {
                    settings.accent = accentName
                    refreshAccentLabel()
                    recreate()
                }
            }
            val size = (44 * density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * density).toInt()
            }
            accentRow.addView(swatch, params)
        }

        // Font size: SeekBar 0..10 maps to 13..23 sp
        val fontLabel = findViewById<TextView>(R.id.fontSizeLabel)
        val fontSeek = findViewById<SeekBar>(R.id.fontSizeSeek)
        fun refreshFontLabel() {
            fontLabel.text = "${settings.fontSizeSp.toInt()}sp"
        }
        fontLabel.setTextColor(palette.accent)
        fontSeek.progress = (settings.fontSizeSp.toInt() - 13).coerceIn(0, 10)
        refreshFontLabel()
        fontSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settings.fontSizeSp = (progress + 13).toFloat()
                    refreshFontLabel()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        // Line spacing: 0..8 sp
        val spacingLabel = findViewById<TextView>(R.id.lineSpacingLabel)
        val spacingSeek = findViewById<SeekBar>(R.id.lineSpacingSeek)
        fun refreshSpacingLabel() {
            spacingLabel.text = "${settings.lineSpacingSp.toInt()}sp"
        }
        spacingLabel.setTextColor(palette.accent)
        spacingSeek.progress = settings.lineSpacingSp.toInt().coerceIn(0, 8)
        refreshSpacingLabel()
        spacingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settings.lineSpacingSp = progress.toFloat()
                    refreshSpacingLabel()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        bindCheck(R.id.checkMono, settings.monospace) { settings.monospace = it }
        bindCheck(R.id.checkLineNumbers, settings.lineNumbers) { settings.lineNumbers = it }
        bindCheck(R.id.checkWordWrap, settings.wordWrap) { settings.wordWrap = it }
        bindCheck(R.id.checkSyntax, settings.syntaxHighlight) { settings.syntaxHighlight = it }
        bindCheck(R.id.checkAutoIndent, settings.autoIndent) { settings.autoIndent = it }
        bindCheck(R.id.checkAccessory, settings.accessoryView) { settings.accessoryView = it }
        bindCheck(R.id.checkSuggestions, settings.showSuggestions) { settings.showSuggestions = it }
        bindCheck(R.id.checkAutoCap, settings.autoCapitalize) { settings.autoCapitalize = it }
        bindCheck(R.id.checkUndoByBack, settings.undoByBack) { settings.undoByBack = it }

        val indentValue = findViewById<TextView>(R.id.indentValue)
        fun refreshIndent() {
            indentValue.text = if (settings.indentWithTab) "Tab" else "4 spaces"
        }
        indentValue.setTextColor(palette.accent)
        refreshIndent()
        findViewById<android.view.View>(R.id.rowIndent).setOnClickListener {
            showPicker(
                "Indentation",
                listOf("4 spaces", "Tab"),
                if (settings.indentWithTab) 1 else 0
            ) { picked ->
                settings.indentWithTab = picked == 1
                refreshIndent()
            }
        }

        // Files & saving
        bindCheck(R.id.checkAutosave, settings.autosave) { settings.autosave = it }
        bindCheck(R.id.checkAutosaveConfirm, settings.autosaveConfirm) { settings.autosaveConfirm = it }

        val intervalValue = findViewById<TextView>(R.id.intervalValue)
        val intervals = listOf(30, 60, 120, 300)
        fun intervalLabel(sec: Int): String = when (sec) {
            30 -> "30 seconds"
            60 -> "1 minute"
            120 -> "2 minutes"
            else -> "5 minutes"
        }
        fun refreshInterval() {
            intervalValue.text = intervalLabel(settings.autosaveIntervalSec)
        }
        intervalValue.setTextColor(palette.accent)
        refreshInterval()
        findViewById<android.view.View>(R.id.rowInterval).setOnClickListener {
            val current = intervals.indexOf(settings.autosaveIntervalSec)
                .let { if (it < 0) 1 else it }
            showPicker(
                "Autosave interval",
                intervals.map { intervalLabel(it) },
                current
            ) { picked ->
                settings.autosaveIntervalSec = intervals[picked]
                refreshInterval()
            }
        }

        val encodingValue = findViewById<TextView>(R.id.encodingValue)
        fun refreshEncoding() {
            encodingValue.text = settings.encoding
        }
        encodingValue.setTextColor(palette.accent)
        refreshEncoding()
        findViewById<android.view.View>(R.id.rowEncoding).setOnClickListener {
            val list = AppSettings.ENCODINGS
            val current = list.indexOf(settings.encoding).let { if (it < 0) 0 else it }
            showPicker("Default encoding", list, current) { picked ->
                settings.encoding = list[picked]
                refreshEncoding()
            }
        }

        val lineBreakValue = findViewById<TextView>(R.id.lineBreakValue)
        fun refreshLineBreak() {
            lineBreakValue.text = if (settings.crlf) "CRLF (Windows)" else "LF"
        }
        lineBreakValue.setTextColor(palette.accent)
        refreshLineBreak()
        findViewById<android.view.View>(R.id.rowLineBreak).setOnClickListener {
            showPicker(
                "Line break",
                listOf("LF", "CRLF (Windows)"),
                if (settings.crlf) 1 else 0
            ) { picked ->
                settings.crlf = picked == 1
                refreshLineBreak()
            }
        }

        bindCheck(R.id.checkTypeFilter, settings.fileTypeFilter) { settings.fileTypeFilter = it }
    }

    /** Single-choice list for the value rows (KiA, items 27+32: a list,
     *  not cycling — and in a modern card window like the rest of the
     *  app, not the stock dialog). Current choice carries an accent ✓;
     *  tapping outside or Back dismisses. */
    private fun showPicker(
        title: String,
        options: List<String>,
        current: Int,
        onPick: (Int) -> Unit
    ) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val dialog = android.app.Dialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEachIndexed { index, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true
                )
                setBackgroundResource(ripple.resourceId)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 15.5f
                    setTextColor(palette.textPrimary)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = if (index == current) "✓" else ""
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(palette.accent)
                })
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            }
            list.addView(row)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(list)
            // Long lists (the 10 encodings) scroll inside the card.
            val cap = (resources.displayMetrics.heightPixels * 0.62).toInt()
            post {
                if (height > cap) {
                    layoutParams = layoutParams.apply { height = cap }
                }
            }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.cardDrawable(20f, density)
            setPadding(0, dp(16), 0, dp(8))
            addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.textPrimary)
                setPadding(dp(18), 0, dp(18), dp(8))
            })
            addView(scroll)
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


    private fun bindCheck(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val box = findViewById<android.widget.CompoundButton>(id)
        box.isChecked = initial
        box.setOnCheckedChangeListener { _, checked -> save(checked) }
        // Tapping anywhere on the row toggles the switch.
        (box.parent as? android.view.View)?.setOnClickListener { box.toggle() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this, "No browser available", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
