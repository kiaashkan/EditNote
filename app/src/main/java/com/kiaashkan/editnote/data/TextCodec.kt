package com.kiaashkan.editnote.data

import java.nio.charset.Charset

/**
 * Pure byte<->text codec for every encoding EditNote offers, with BOM
 * detection. Kept free of Android types so it can be unit-tested on a JVM.
 *
 * UTF-32 is implemented by hand: Android's runtime does not guarantee a
 * "UTF-32" charset, and KiA explicitly wants UTF-32 in the list.
 */
object TextCodec {

    /** Encodings shown in the editor's Encoding menu and in Settings. */
    val ENCODINGS = listOf(
        "UTF-8", "UTF-16", "UTF-32",
        "windows-1256", "windows-1252", "windows-1251",
        "ISO-8859-1", "US-ASCII", "UTF-16BE", "UTF-16LE"
    )

    fun isSupported(name: String): Boolean =
        name.equals("UTF-32", true) ||
            name.equals("UTF-32LE", true) ||
            name.equals("UTF-32BE", true) ||
            runCatching { Charset.forName(name) }.isSuccess

    private fun isUtf32(name: String): Boolean = name.equals("UTF-32", true)

    /** Decode [bytes]; returns the text and the encoding actually used
     *  (a BOM, when present, wins over [preferred]). */
    fun decode(bytes: ByteArray, preferred: String): Pair<String, String> {
        detectBom(bytes)?.let { (enc, bomLen) ->
            val body = bytes.copyOfRange(bomLen, bytes.size)
            // Keep the precise variant (UTF-32LE, UTF-16BE, …) so a later
            // save writes the same byte order and BOM back.
            return decodeWith(body, enc) to enc
        }
        val enc = if (isSupported(preferred)) preferred else "UTF-8"
        return decodeWith(bytes, enc) to enc
    }

    /** Decode with exactly [name] (used when the user picks an encoding
     *  from the menu); a matching BOM is stripped when present. */
    fun decodeForced(bytes: ByteArray, name: String): String {
        if (isUtf32(name)) {
            detectBom(bytes)?.let { (enc, bomLen) ->
                if (enc.startsWith("UTF-32")) {
                    return decodeWith(bytes.copyOfRange(bomLen, bytes.size), enc)
                }
            }
            return decodeUtf32(bytes, littleEndian = false)
        }
        val text = decodeWith(bytes, name)
        return if (text.startsWith("\uFEFF")) text.substring(1) else text
    }

    private fun decodeWith(bytes: ByteArray, name: String): String = when {
        name.equals("UTF-32LE", true) -> decodeUtf32(bytes, littleEndian = true)
        name.equals("UTF-32BE", true) -> decodeUtf32(bytes, littleEndian = false)
        isUtf32(name) -> decodeUtf32(bytes, littleEndian = false)
        else -> bytes.toString(Charset.forName(name))
    }

    fun encode(text: String, name: String): ByteArray = when {
        name.equals("UTF-32LE", true) -> encodeUtf32(text, littleEndian = true)
        isUtf32(name) || name.equals("UTF-32BE", true) ->
            encodeUtf32(text, littleEndian = false)
        // The endian-specific UTF-16 charsets write no BOM themselves;
        // add it back so the saved file keeps the form it was opened in.
        name.equals("UTF-16LE", true) ->
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                text.toByteArray(Charset.forName("UTF-16LE"))
        name.equals("UTF-16BE", true) ->
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                text.toByteArray(Charset.forName("UTF-16BE"))
        else -> text.toByteArray(Charset.forName(name))
    }

    /** (encoding, bomLength) or null when the bytes carry no known BOM. */
    fun detectBom(bytes: ByteArray): Pair<String, Int>? {
        fun has(vararg b: Int): Boolean =
            bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            // UTF-32 must be checked before UTF-16: FF FE 00 00 starts FF FE.
            has(0xFF, 0xFE, 0x00, 0x00) -> "UTF-32LE" to 4
            has(0x00, 0x00, 0xFE, 0xFF) -> "UTF-32BE" to 4
            has(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
            has(0xFE, 0xFF) -> "UTF-16BE" to 2
            has(0xFF, 0xFE) -> "UTF-16LE" to 2
            else -> null
        }
    }

    // --- UTF-32 (big-endian output with BOM, like Java's UTF-16) ----------

    private fun decodeUtf32(bytes: ByteArray, littleEndian: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 4 <= bytes.size) {
            val cp = if (littleEndian) {
                (bytes[i].toInt() and 0xFF) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 3].toInt() and 0xFF) shl 24)
            } else {
                ((bytes[i].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i + 3].toInt() and 0xFF)
            }
            if (cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) {
                sb.appendCodePoint(cp)
            } else {
                sb.append('\uFFFD')
            }
            i += 4
        }
        return sb.toString()
    }

    private fun encodeUtf32(text: String, littleEndian: Boolean): ByteArray {
        val codePoints = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            codePoints.add(cp)
            i += Character.charCount(cp)
        }
        val out = ByteArray(4 + codePoints.size * 4)
        if (littleEndian) {
            out[0] = 0xFF.toByte()
            out[1] = 0xFE.toByte()
        } else {
            out[2] = 0xFE.toByte()
            out[3] = 0xFF.toByte()
        }
        codePoints.forEachIndexed { idx, cp ->
            val o = 4 + idx * 4
            if (littleEndian) {
                out[o] = cp.toByte()
                out[o + 1] = (cp ushr 8).toByte()
                out[o + 2] = (cp ushr 16).toByte()
                out[o + 3] = (cp ushr 24).toByte()
            } else {
                out[o] = (cp ushr 24).toByte()
                out[o + 1] = (cp ushr 16).toByte()
                out[o + 2] = (cp ushr 8).toByte()
                out[o + 3] = cp.toByte()
            }
        }
        return out
    }
}
