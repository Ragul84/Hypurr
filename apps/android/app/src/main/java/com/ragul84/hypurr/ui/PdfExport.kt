package com.ragul84.hypurr.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Writes Markdown as a plain, readable A4 PDF (headings bold, code monospaced). */
object PdfExport {
    private const val W = 595
    private const val H = 842
    private const val M = 48

    fun write(ctx: Context, fileName: String, markdown: String): File {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = M.toFloat()
        val body = TextPaint().apply { isAntiAlias = true; textSize = 11f; color = android.graphics.Color.BLACK }
        val code = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9.5f }
        var inCode = false
        for (raw in markdown.lines()) {
            if (raw.trim().startsWith("```")) { inCode = !inCode; continue }
            val level = if (inCode) 0 else raw.takeWhile { it == '#' }.length
            val text = if (level > 0) raw.drop(level).trim() else if (inCode) raw else raw.replace("`", "").replace("**", "")
            val paint = when {
                inCode -> code
                level == 1 -> TextPaint(body).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
                level >= 2 -> TextPaint(body).apply { textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD }
                else -> body
            }
            val layout = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, W - 2 * M)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            val gap = if (level > 0) 8f else 2f
            if (y + gap + layout.height > H - M) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
                y = M.toFloat()
            }
            y += gap
            page.canvas.save()
            page.canvas.translate(M.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height
        }
        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
