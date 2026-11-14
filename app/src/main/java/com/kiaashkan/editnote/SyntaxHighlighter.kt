package com.kiaashkan.editnote

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Small, conservative syntax highlighter for common code/text formats.
 * Regex-based, foreground colors only; safe on plain text (returns quickly
 * for non-code files and very large documents).
 */
object SyntaxHighlighter {

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "ts", "json", "c", "h", "cpp", "cs",
        "go", "rs", "php", "rb", "sh", "xml", "html", "css", "md", "sql", "swift"
    )

    private const val MAX_LENGTH = 300_000

    private val KEYWORDS = Regex(
        "\\b(package|import|fun|val|var|class|object|interface|if|else|when|for|while|do|return|break|continue|" +
            "public|private|protected|static|void|int|long|float|double|boolean|char|new|this|super|null|true|false|" +
            "def|print|lambda|from|as|try|except|finally|with|in|is|not|and|or|const|let|function|vararg|data|sealed|" +
            "SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|TABLE|function|echo|fn|mut|impl|struct|enum|match)\\b"
    )
    private val STRINGS = Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")
    private val COMMENTS = Regex("(?m)(//.*$|#.*$)")
    private val NUMBERS = Regex("\\b\\d+(\\.\\d+)?\\b")

    private val COLOR_KEYWORD = Color.rgb(156, 39, 176)
    private val COLOR_STRING = Color.rgb(46, 125, 50)
    private val COLOR_COMMENT = Color.rgb(140, 140, 140)
    private val COLOR_NUMBER = Color.rgb(21, 101, 192)

    fun isCodeFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in CODE_EXTENSIONS

    fun apply(text: Spannable, fileName: String) {
        // Clear previous highlighting.
        text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, StyleSpan::class.java)
            .forEach { text.removeSpan(it) }
        if (!isCodeFile(fileName) || text.length > MAX_LENGTH || text.isEmpty()) return

        val source = text.toString()
        COMMENTS.findAll(source).forEach {
            text.setSpan(
                ForegroundColorSpan(COLOR_COMMENT),
                it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        STRINGS.findAll(source).forEach {
            text.setSpan(
                ForegroundColorSpan(COLOR_STRING),
                it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        KEYWORDS.findAll(source).forEach {
            text.setSpan(
                ForegroundColorSpan(COLOR_KEYWORD),
                it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        NUMBERS.findAll(source).forEach {
            text.setSpan(
                ForegroundColorSpan(COLOR_NUMBER),
                it.range.first, it.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
