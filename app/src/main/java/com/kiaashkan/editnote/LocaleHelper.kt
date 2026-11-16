package com.kiaashkan.editnote

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Item 99: the app's language follows the phone by default
 * (item 98), but Settings → Language can pin one of the built-in
 * languages. Reads the same preference AppSettings writes
 * ("app_language" in "editnote_settings"); "system" (or anything
 * unset) means no override.
 */
object LocaleHelper {

    private const val PREFS = "editnote_settings"
    private const val KEY_LANGUAGE = "app_language"
    const val SYSTEM = "system"

    fun selectedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    /** Returns [context] wrapped with the pinned language's
     *  configuration (locale + layout direction), or [context]
     *  itself when the language follows the system. */
    fun wrap(context: Context): Context {
        val tag = selectedTag(context)
        if (tag == SYSTEM) return context
        val locale = Locale(tag)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
