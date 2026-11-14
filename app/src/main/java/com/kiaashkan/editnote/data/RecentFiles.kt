package com.kiaashkan.editnote.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Recently opened device files (Storage Access Framework URIs), most recent
 * first, plus user bookmarks. Only URI strings, display names, timestamps
 * and bookmark flags are kept in private preferences — the files themselves
 * always stay where the user put them on the device.
 */
class RecentFiles(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(
        val uri: String,
        val name: String,
        val lastOpened: Long = 0L,
        val bookmarked: Boolean = false
    )

    fun list(): List<Entry> {
        val raw = prefs.getString(KEY, "") ?: ""
        return raw.split(SEP).filter { it.isNotBlank() }.mapNotNull { item ->
            val parts = item.split('|')
            if (parts.size < 2) null
            else Entry(
                uri = parts[0],
                name = unescape(parts[1]),
                lastOpened = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                bookmarked = parts.getOrNull(3) == "1"
            )
        }
    }

    private fun save(entries: List<Entry>) {
        val bookmarks = entries.filter { it.bookmarked }
        val normal = entries.filter { !it.bookmarked }
        val ordered = normal.sortedByDescending { it.lastOpened }
        val all = (bookmarks + ordered).take(MAX + bookmarks.size)
        prefs.edit().putString(
            KEY,
            all.joinToString(SEP) { e ->
                "${e.uri}|${escape(e.name)}|${e.lastOpened}|${if (e.bookmarked) "1" else "0"}"
            }
        ).apply()
    }

    fun add(uri: Uri, name: String) {
        val existing = list().firstOrNull { it.uri == uri.toString() }
        val entries = list().filter { it.uri != uri.toString() }.toMutableList()
        entries.add(
            0,
            Entry(uri.toString(), name, System.currentTimeMillis(), existing?.bookmarked ?: false)
        )
        save(entries)
    }

    // Display names come from the file provider and may contain
    // the field separator '|' or even newlines ("Meeting | Notes",
    // audit finding) — escape them inside the store.
    private fun escape(s: String): String = s
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

    private fun unescape(s: String): String = s
        .replace("%0D", "\r")
        .replace("%0A", "\n")
        .replace("%7C", "|")
        .replace("%25", "%")

    fun remove(uri: String) {
        save(list().filter { it.uri != uri })
    }

    fun setBookmarked(uri: String, bookmarked: Boolean) {
        save(list().map { if (it.uri == uri) it.copy(bookmarked = bookmarked) else it })
    }

    fun clear() {
        // Keep bookmarks; clear only the recent history.
        save(list().filter { it.bookmarked })
    }

    companion object {
        private const val PREFS = "editnote_recents"
        private const val KEY = "recent_uris"
        private const val SEP = "\n"
        private const val MAX = 30

        /** Best-effort display name for a content:// (or file://) URI. */
        fun displayName(context: Context, uri: Uri): String {
            if (uri.scheme == "file") return uri.lastPathSegment ?: "file.txt"
            var cursor: Cursor? = null
            return try {
                cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) ?: "file.txt" else "file.txt"
                } else uri.lastPathSegment ?: "file.txt"
            } catch (e: Exception) {
                uri.lastPathSegment ?: "file.txt"
            } finally {
                cursor?.close()
            }
        }
    }
}
