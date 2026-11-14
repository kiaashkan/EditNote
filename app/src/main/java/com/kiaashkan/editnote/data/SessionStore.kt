package com.kiaashkan.editnote.data

import android.content.Context

/**
 * Remembers which documents were open so the session can be restored on
 * the next launch (the "Resume Session" setting). Stores only references:
 * device-file URIs, and for unsaved new files the name of their internal
 * draft scratch file (drafts are deleted as soon as the file gets a home).
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val uri: String?, val name: String, val draftFileName: String?)

    fun save(entries: List<Entry>) {
        val raw = entries.joinToString("\n") { e ->
            listOf(e.uri ?: "", e.name, e.draftFileName ?: "").joinToString(SEP)
        }
        prefs.edit().putString(KEY, raw).apply()
    }

    fun load(): List<Entry> {
        val raw = prefs.getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size < 2) null
            else Entry(
                uri = parts[0].ifBlank { null },
                name = parts[1],
                draftFileName = parts.getOrNull(2)?.ifBlank { null }
            )
        }
    }

    fun hasSession(): Boolean = load().isNotEmpty()

    companion object {
        private const val PREFS = "editnote_session"
        private const val KEY = "docs"
        private const val SEP = "\u0001"
    }
}
