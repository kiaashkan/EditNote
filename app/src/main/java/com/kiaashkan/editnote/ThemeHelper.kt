package com.kiaashkan.editnote

import android.app.Activity
import com.kiaashkan.editnote.data.AppSettings

/** Applies the user's theme (light/dark + accent) before setContentView(). */
object ThemeHelper {

    fun apply(activity: Activity) {
        val settings = AppSettings(activity)
        val dark = settings.isDark(activity)
        val style = when (settings.accent) {
            AppSettings.ACCENT_GREEN -> if (dark) R.style.Theme_EditNote_Dark_Green else R.style.Theme_EditNote_Light_Green
            AppSettings.ACCENT_BLUE -> if (dark) R.style.Theme_EditNote_Dark_Blue else R.style.Theme_EditNote_Light_Blue
            AppSettings.ACCENT_PURPLE -> if (dark) R.style.Theme_EditNote_Dark_Purple else R.style.Theme_EditNote_Light_Purple
            AppSettings.ACCENT_TEAL -> if (dark) R.style.Theme_EditNote_Dark_Teal else R.style.Theme_EditNote_Light_Teal
            AppSettings.ACCENT_MONO -> if (dark) R.style.Theme_EditNote_Dark_Mono else R.style.Theme_EditNote_Light_Mono
            else -> if (dark) R.style.Theme_EditNote_Dark_Orange else R.style.Theme_EditNote_Light_Orange
        }
        activity.setTheme(style)
    }

    /**
     * A fingerprint of everything that changes how a screen looks. Activities
     * capture it in onCreate and recreate() in onResume when it changes —
     * returning from Settings does not re-apply a theme by itself, which is
     * why v0.2.0 looked "stuck on dark" and ignored the accent color.
     */
    fun signature(activity: Activity): String {
        val s = AppSettings(activity)
        return listOf(
            s.themeMode, s.accent, s.isDark(activity).toString(),
            s.fontSizeSp.toString(), s.monospace.toString(),
            s.lineNumbers.toString(), s.wordWrap.toString(),
            s.lineSpacingSp.toString(),
            s.accessoryView.toString(), s.syntaxHighlight.toString()
        ).joinToString("|")
    }
}
