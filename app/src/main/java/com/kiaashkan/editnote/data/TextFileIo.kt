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
        if (uri.scheme == "file") {
            val path = uri.path ?: return ByteArray(0)
            return File(path).readBytes()
        }
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: ByteArray(0)
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
            File(uri.path ?: return).writeBytes(bytes)
            return
        }
        // "wt" truncates first, so a shorter edit never leaves tail bytes.
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
        } ?: throw IllegalStateException("Could not open file for writing")
    }
}
