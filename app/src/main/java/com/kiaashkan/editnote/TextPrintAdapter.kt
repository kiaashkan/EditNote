package com.kiaashkan.editnote

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream
import java.io.OutputStream

/** Prints plain text as simple paginated monospace pages. */
class TextPrintAdapter(
    private val jobName: String,
    private val text: String
) : PrintDocumentAdapter() {

    private var pages: List<List<String>> = emptyList()
    private val paint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        val media = newAttributes.mediaSize
        val width = media?.widthMils?.div(1000f)?.times(72f) ?: 612f
        val height = media?.heightMils?.div(1000f)?.times(72f) ?: 792f
        val margin = 36f
        val usableWidth = width - margin * 2
        val charsPerLine = (usableWidth / paint.measureText("M")).toInt().coerceAtLeast(20)
        val lineHeight = paint.fontSpacing
        val linesPerPage = ((height - margin * 2) / lineHeight).toInt().coerceAtLeast(10)

        val wrapped = mutableListOf<String>()
        text.split("\n").forEach { line ->
            if (line.isEmpty()) wrapped.add("")
            else {
                var i = 0
                while (i < line.length) {
                    wrapped.add(line.substring(i, minOf(i + charsPerLine, line.length)))
                    i += charsPerLine
                }
            }
        }
        pages = wrapped.chunked(linesPerPage)
        if (pages.isEmpty()) pages = listOf(listOf(""))

        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pageRanges: Array<PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal?,
        callback: WriteResultCallback
    ) {
        val document = PdfDocument()
        try {
            val media = PrintAttributes.MediaSize.ISO_A4
            val pageInfo = PdfDocument.PageInfo
                .Builder(media.widthMils / 1000 * 72, media.heightMils / 1000 * 72, 1)
                .create()
            pages.forEachIndexed { index, lines ->
                if (!shouldWrite(index, pageRanges)) return@forEachIndexed
                val page = document.startPage(
                    PdfDocument.PageInfo
                        .Builder(pageInfo.pageWidth, pageInfo.pageHeight, index + 1)
                        .create()
                )
                val canvas = page.canvas
                var y = 36f + paint.fontSpacing
                lines.forEach { line ->
                    canvas.drawText(line, 36f, y, paint)
                    y += paint.fontSpacing
                }
                document.finishPage(page)
            }
            FileOutputStream(destination.fileDescriptor).use { out: OutputStream ->
                document.writeTo(out)
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        } finally {
            document.close()
        }
    }

    private fun shouldWrite(page: Int, ranges: Array<PageRange>): Boolean =
        ranges.any { it == PageRange.ALL_PAGES || (page in it.start..it.end) }
}
