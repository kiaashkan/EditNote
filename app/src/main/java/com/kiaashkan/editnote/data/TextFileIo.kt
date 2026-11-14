package com.kiaashkan.editnote.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.Charset

/**
 * Reads/writes plain text through a Uri (SAF content:// or file://).
 * The encoding and line-break style come from Settings. Text inside the
 * editor always uses LF; CRLF is applied only when writing to disk.
 */
object TextFileIo {

    private fun charset(context: Context): Charset =
        runCatching { Charset.forName(AppSettings(context).encoding) }.getOrDefault(Charsets.UTF_8)

    fun read(context: Context, uri: Uri): String {
        val cs = charset(context)
        val raw = if (uri.scheme == "file") {
            File(uri.path ?: return "").readText(cs)
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(cs)
            } ?: ""
        }
        return raw.replace("\r\n", "\n")
    }

    fun write(context: Context, uri: Uri, text: String) {
        val cs = charset(context)
        val out = if (AppSettings(context).crlf) {
            text.replace("\r\n", "\n").replace("\n", "\r\n")
        } else text
        if (uri.scheme == "file") {
            File(uri.path ?: return).writeText(out, cs)
            return
        }
        // "wt" truncates first, so a shorter edit never leaves tail bytes.
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(out.toByteArray(cs))
        } ?: throw IllegalStateException("Could not open file for writing")
    }
}
