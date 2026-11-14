package com.kiaashkan.editnote

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
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

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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
            AppSettings.ACCENT_BLUE to "Blue",
            AppSettings.ACCENT_PURPLE to "Purple",
            AppSettings.ACCENT_TEAL to "Teal"
        )
        fun refreshAccentLabel() {
            accentLabel.text = "Accent color: ${names[settings.accent] ?: "Orange"}"
        }
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
            fontLabel.text = "Font size: ${settings.fontSizeSp.toInt()}"
        }
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
            spacingLabel.text = "Line spacing: ${settings.lineSpacingSp.toInt()}sp"
        }
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

        val indentBtn = findViewById<Button>(R.id.btnIndent)
        fun refreshIndent() {
            indentBtn.text = "Indentation: " + if (settings.indentWithTab) "Tab" else "4 spaces"
        }
        refreshIndent()
        indentBtn.setOnClickListener {
            settings.indentWithTab = !settings.indentWithTab
            refreshIndent()
        }

        // Files & saving
        bindCheck(R.id.checkAutosave, settings.autosave) { settings.autosave = it }
        bindCheck(R.id.checkAutosaveConfirm, settings.autosaveConfirm) { settings.autosaveConfirm = it }

        val intervalBtn = findViewById<Button>(R.id.btnAutosaveInterval)
        val intervals = listOf(30, 60, 120, 300)
        fun intervalLabel(sec: Int): String = when (sec) {
            30 -> "30 seconds"
            60 -> "1 minute"
            120 -> "2 minutes"
            else -> "5 minutes"
        }
        fun refreshInterval() {
            intervalBtn.text = "Autosave interval: ${intervalLabel(settings.autosaveIntervalSec)}"
        }
        refreshInterval()
        intervalBtn.setOnClickListener {
            val idx = intervals.indexOf(settings.autosaveIntervalSec).let { if (it < 0) 1 else it }
            settings.autosaveIntervalSec = intervals[(idx + 1) % intervals.size]
            refreshInterval()
        }

        val encodingBtn = findViewById<Button>(R.id.btnEncoding)
        fun refreshEncoding() {
            encodingBtn.text = "Default encoding: ${settings.encoding}"
        }
        refreshEncoding()
        encodingBtn.setOnClickListener {
            val list = AppSettings.ENCODINGS
            val idx = list.indexOf(settings.encoding).let { if (it < 0) 0 else it }
            settings.encoding = list[(idx + 1) % list.size]
            refreshEncoding()
        }

        val lineBreakBtn = findViewById<Button>(R.id.btnLineBreak)
        fun refreshLineBreak() {
            lineBreakBtn.text = "Line break: " + if (settings.crlf) "CRLF (Windows)" else "LF"
        }
        refreshLineBreak()
        lineBreakBtn.setOnClickListener {
            settings.crlf = !settings.crlf
            refreshLineBreak()
        }

        bindCheck(R.id.checkTypeFilter, settings.fileTypeFilter) { settings.fileTypeFilter = it }
        bindCheck(R.id.checkResume, settings.resumeSession) { settings.resumeSession = it }
    }

    private fun bindCheck(id: Int, initial: Boolean, save: (Boolean) -> Unit) {
        val box = findViewById<CheckBox>(id)
        box.isChecked = initial
        box.setOnCheckedChangeListener { _, checked -> save(checked) }
    }
}
