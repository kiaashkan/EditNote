package com.kiaashkan.editnote.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** All user preferences, in one place. Stored in private SharedPreferences. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Appearance
    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    var accent: String
        get() = prefs.getString(KEY_ACCENT, ACCENT_ORANGE) ?: ACCENT_ORANGE
        set(v) = prefs.edit().putString(KEY_ACCENT, v).apply()

    // Editor behavior
    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 16f)
        set(v) = prefs.edit().putFloat(KEY_FONT_SIZE, v).apply()

    var lineSpacingSp: Float
        get() = prefs.getFloat(KEY_LINE_SPACING, 2f)
        set(v) = prefs.edit().putFloat(KEY_LINE_SPACING, v).apply()

    var monospace: Boolean
        get() = prefs.getBoolean(KEY_MONO, false)
        set(v) = prefs.edit().putBoolean(KEY_MONO, v).apply()

    var lineNumbers: Boolean
        get() = prefs.getBoolean(KEY_LINE_NUMBERS, true)
        set(v) = prefs.edit().putBoolean(KEY_LINE_NUMBERS, v).apply()

    var wordWrap: Boolean
        get() = prefs.getBoolean(KEY_WORD_WRAP, false)
        set(v) = prefs.edit().putBoolean(KEY_WORD_WRAP, v).apply()

    var syntaxHighlight: Boolean
        get() = prefs.getBoolean(KEY_SYNTAX, true)
        set(v) = prefs.edit().putBoolean(KEY_SYNTAX, v).apply()

    var autosave: Boolean
        get() = prefs.getBoolean(KEY_AUTOSAVE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSAVE, v).apply()

    /** Show a small "Saved" toast after each successful autosave. */
    var autosaveConfirm: Boolean
        get() = prefs.getBoolean(KEY_AUTOSAVE_CONFIRM, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSAVE_CONFIRM, v).apply()

    /** Autosave delay after typing stops, in seconds (30/60/120/300). */
    var autosaveIntervalSec: Int
        get() = prefs.getInt(KEY_AUTOSAVE_INTERVAL, 60)
        set(v) = prefs.edit().putInt(KEY_AUTOSAVE_INTERVAL, v).apply()

    var autoIndent: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INDENT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_INDENT, v).apply()

    /** Indentation unit: true = TAB character, false = 4 spaces. */
    var indentWithTab: Boolean
        get() = prefs.getBoolean(KEY_INDENT_TAB, false)
        set(v) = prefs.edit().putBoolean(KEY_INDENT_TAB, v).apply()

    fun indentUnit(): String = if (indentWithTab) "\t" else "    "

    var showSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SUGGESTIONS, false)
        set(v) = prefs.edit().putBoolean(KEY_SUGGESTIONS, v).apply()

    var autoCapitalize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAP, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CAP, v).apply()

    var accessoryView: Boolean
        get() = prefs.getBoolean(KEY_ACCESSORY, false)
        set(v) = prefs.edit().putBoolean(KEY_ACCESSORY, v).apply()

    var undoByBack: Boolean
        get() = prefs.getBoolean(KEY_UNDO_BY_BACK, false)
        set(v) = prefs.edit().putBoolean(KEY_UNDO_BY_BACK, v).apply()

    // Files
    var encoding: String
        get() = prefs.getString(KEY_ENCODING, ENC_UTF8) ?: ENC_UTF8
        set(v) = prefs.edit().putString(KEY_ENCODING, v).apply()

    /** Editor colour scheme id ("default" follows the app theme). */
    var visualStyle: String
        get() = prefs.getString(KEY_VISUAL_STYLE, "default") ?: "default"
        set(v) = prefs.edit().putString(KEY_VISUAL_STYLE, v).apply()

    /** true = CRLF, false = LF. */
    var crlf: Boolean
        get() = prefs.getBoolean(KEY_CRLF, false)
        set(v) = prefs.edit().putBoolean(KEY_CRLF, v).apply()

    // Item 73: fileTypeFilter was removed — the open picker no
    // longer filters by type at all (every file opens as text).
    // The stored "file_type_filter" pref, if present, is ignored.

    var sortByName: Boolean
        get() = prefs.getBoolean(KEY_SORT_BY_NAME, false)
        set(v) = prefs.edit().putBoolean(KEY_SORT_BY_NAME, v).apply()

    /** True when the effective theme (after resolving "system") is dark. */
    fun isDark(context: Context): Boolean = when (themeMode) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Accent resolved against the effective theme (the mono accent flips:
     *  black in light mode, white in dark mode). */
    fun accentColor(context: Context): Int = colorFor(accent, isDark(context))

    companion object {
        private const val PREFS = "editnote_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_MONO = "monospace"
        private const val KEY_LINE_NUMBERS = "line_numbers"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_SYNTAX = "syntax_highlight"
        private const val KEY_AUTOSAVE = "autosave"
        private const val KEY_AUTOSAVE_CONFIRM = "autosave_confirm"
        private const val KEY_AUTOSAVE_INTERVAL = "autosave_interval"
        private const val KEY_AUTO_INDENT = "auto_indent"
        private const val KEY_INDENT_TAB = "indent_tab"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val KEY_AUTO_CAP = "auto_capitalize"
        private const val KEY_ACCESSORY = "accessory_view"
        private const val KEY_UNDO_BY_BACK = "undo_by_back"
        private const val KEY_ENCODING = "encoding"
        private const val KEY_CRLF = "crlf"
        // "file_type_filter" key retired in item 73.
        private const val KEY_SORT_BY_NAME = "sort_by_name"
        private const val KEY_VISUAL_STYLE = "visual_style"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val ACCENT_GREEN = "green"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_TEAL = "teal"
        /** Black & white in one: black on light themes, white on dark themes. */
        const val ACCENT_MONO = "mono"

        // Orange (the default) first, by KiA's request.
        val ACCENTS = listOf(
            ACCENT_ORANGE, ACCENT_MONO, ACCENT_GREEN,
            ACCENT_BLUE, ACCENT_PURPLE, ACCENT_TEAL
        )

        const val ENC_UTF8 = "UTF-8"
        val ENCODINGS = TextCodec.ENCODINGS

        fun colorFor(accent: String, dark: Boolean): Int = when (accent) {
            // KiA's own palette (2026-11-14, item 40): the default and
            // mono stay; the other four slots take his hex values.
            // (Slot ids are historical: "blue" now shows Pink, "teal"
            // shows Cream — the ids only key the stored preference.)
            ACCENT_BLUE -> Color.rgb(255, 204, 255) // #FFCCFF — Pink
            ACCENT_PURPLE -> Color.rgb(76, 0, 153) // #4C0099
            ACCENT_TEAL -> Color.rgb(255, 255, 204) // #FFFFCC — Cream
            ACCENT_GREEN -> Color.rgb(0, 102, 51) // #006633
            ACCENT_MONO -> if (dark) Color.WHITE else Color.rgb(17, 17, 17)
            else -> Color.rgb(244, 102, 88) // orange (default) — the logo color #F46658
        }
    }
}
