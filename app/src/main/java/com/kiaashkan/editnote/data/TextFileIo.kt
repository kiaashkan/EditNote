package com.kiaashkan.editnote.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Reads/writes plain text through a Uri (SAF content:// or file://).
 * Every document carries its own encoding (chosen in the editor's
 * Encoding menu; a BOM in the file wins when it is opened). Text inside
 * the editor always uses LF; the document's line-break style (Settings)
 * is applied only when writing to disk.
 */
object TextFileIo {

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        // Audit fix: a failed read used to return an EMPTY array,
        // which the editor happily opened as a blank document — one
        // later save then overwrote the real file with nothing.
        // Fail loudly; callers abort the open instead.
        if (uri.scheme == "file") {
            val path = uri.path
                ?: throw IllegalStateException("File has no path: $uri")
            return File(path).readBytes()
        }
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not open file for reading")
    }

    /** Returns the decoded text and the encoding actually used. */
    fun read(context: Context, uri: Uri, preferred: String): Pair<String, String> {
        val (text, used) = TextCodec.decode(readBytes(context, uri), preferred)
        return text.replace("\r\n", "\n") to used
    }

    /** Decode the file with exactly [encoding] (Encoding menu choice). */
    fun readForced(context: Context, uri: Uri, encoding: String): String =
        TextCodec.decodeForced(readBytes(context, uri), encoding).replace("\r\n", "\n")

    fun write(context: Context, uri: Uri, text: String, encoding: String) {
        val out = if (AppSettings(context).crlf) {
            text.replace("\r\n", "\n").replace("\n", "\r\n")
        } else text
        val bytes = TextCodec.encode(out, encoding)
        if (uri.scheme == "file") {
            // Audit fix: a null path used to return silently, so the
            // app reported "Saved" while nothing was written.
            val path = uri.path
                ?: throw IllegalStateException("File has no path: $uri")
            File(path).writeBytes(bytes)
            return
        }
        // "wt" truncates first, so a shorter edit never leaves tail bytes.
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: throw IllegalStateException("Could not open file for writing")
    }
}
