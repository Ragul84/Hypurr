package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.theme.Hypurr

/** One block of a Markdown reply (what agents write: CommonMark's everyday subset plus GFM tables). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    /** `number` is null for a bullet; `depth` 0 = top level. */
    data class Item(val text: String, val number: Int?, val depth: Int, val checked: Boolean? = null) : MdBlock
    data class Code(val code: String, val language: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val fence = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** The text without Markdown syntax, one line per block: for previews and notifications. */
    fun plain(text: String): String = blocks(text).joinToString("\n") { b ->
        when (b) {
            is MdBlock.Heading -> inline(b.text).text
            is MdBlock.Paragraph -> inline(b.text).text
            is MdBlock.Item -> inline(b.text).text
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> inline(b.text).text
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("  ") { inline(it).text } }
            MdBlock.Rule -> ""
        }
    }.trim()

    fun blocks(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += MdBlock.Paragraph(para.joinToString("\n"))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val marker = f.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                out += MdBlock.Code(code.joinToString("\n"), f.groupValues[2])
                i++
                continue
            }
            when {
                line.isBlank() -> flush()
                heading.matchEntire(line) != null -> {
                    flush()
                    val m = heading.matchEntire(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> {
                    flush()
                    out += MdBlock.Rule
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quoted += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += MdBlock.Quote(quoted.joinToString("\n"))
                    continue
                }
                line.contains('|') && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++])
                    out += MdBlock.Table(header, rows)
                    continue
                }
                bullet.matchEntire(line) != null || ordered.matchEntire(line) != null -> {
                    flush()
                    val b = bullet.matchEntire(line)
                    val (indent, number, body) = if (b != null) Triple(b.groupValues[1], null, b.groupValues[2])
                    else ordered.matchEntire(line)!!.let { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }
                    val t = task.matchEntire(body)
                    out += MdBlock.Item(t?.groupValues?.get(2) ?: body, number, (indent.replace("\t", "    ").length / 2).coerceAtMost(3),
                        t?.groupValues?.get(1)?.let { it != " " })
                }
                // A lazy continuation of a list item.
                para.isEmpty() && out.lastOrNull() is MdBlock.Item && line.startsWith("  ") -> {
                    val last = out.removeAt(out.lastIndex) as MdBlock.Item
                    out += last.copy(text = last.text + " " + line.trim())
                }
                else -> para += line.trim()
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline Markdown: **bold**, *italic*, `code`, ~~strike~~, [links](url) and bare https links. */
    fun inline(text: String, code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace), link: Color = Color.Unspecified): AnnotatedString =
        buildAnnotatedString { appendInline(text, code, link) }

    private val bareUrl = Regex("https?://[^\\s<>()]+[^\\s<>().,;:!?'\"]")

    private fun AnnotatedString.Builder.appendInline(s: String, code: SpanStyle, link: Color) {
        var i = 0
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isEmpty()) return
            var last = 0
            val p = plain.toString()
            for (m in bareUrl.findAll(p)) {
                append(p.substring(last, m.range.first))
                withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(m.value) }
                last = m.range.last + 1
            }
            append(p.substring(last))
            plain.clear()
        }
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]()#+-.!~|>" -> { plain.append(s[i + 1]); i += 2 }
                ch == '`' -> {
                    val ticks = s.substring(i).takeWhile { it == '`' }.length
                    val end = s.indexOf("`".repeat(ticks), i + ticks)
                    if (end < 0) { plain.append(s, i, i + ticks); i += ticks } else {
                        flushPlain()
                        withStyle(code) { append(s.substring(i + ticks, end).trim()) }
                        i = end + ticks
                    }
                }
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2)
                    val end = s.indexOf(mark, i + 2)
                    if (end <= i + 2 || (mark == "__" && !wordEdge(s, i, end + 2))) { plain.append(mark); i += 2 } else {
                        flushPlain()
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(s.substring(i + 2, end), code, link) }
                        i = end + 2
                    }
                }
                s.startsWith("~~", i) -> {
                    val end = s.indexOf("~~", i + 2)
                    if (end <= i + 2) { plain.append("~~"); i += 2 } else {
                        flushPlain()
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(s.substring(i + 2, end), code, link) }
                        i = end + 2
                    }
                }
                (ch == '*' || ch == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                    val end = s.indexOf(ch, i + 1)
                    val ok = end > i + 1 && !s[end - 1].isWhitespace() && (ch == '*' || wordEdge(s, i, end + 1))
                    if (!ok) { plain.append(ch); i++ } else {
                        flushPlain()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), code, link) }
                        i = end + 1
                    }
                }
                ch == '[' -> {
                    val close = s.indexOf("](", i + 1)
                    val end = if (close > 0) s.indexOf(')', close + 2) else -1
                    if (close < 0 || end < 0 || s.substring(i + 1, close).contains('\n')) { plain.append(ch); i++ } else {
                        flushPlain()
                        val url = s.substring(close + 2, end).trim().substringBefore(' ')
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                            appendInline(s.substring(i + 1, close), code, link)
                        }
                        i = end + 1
                    }
                }
                else -> { plain.append(ch); i++ }
            }
        }
        flushPlain()
    }

    /** `_x_` / `__x__` only at word edges, so snake_case_names stay as written. */
    private fun wordEdge(s: String, start: Int, after: Int): Boolean =
        (start == 0 || !s[start - 1].isLetterOrDigit()) && (after >= s.length || !s[after].isLetterOrDigit())
}

/** Renders an agent's Markdown reply. Links open in the browser (Compose's default URI handler). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = Hypurr.colors.text) {
    val c = Hypurr.colors
    val blocks = remember(text) { Markdown.blocks(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = c.text.copy(alpha = 0.08f))
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    fun md(s: String) = Markdown.inline(s, code, c.accent)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(md(b.text), style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                    .copy(color = color, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 2.dp))
                is MdBlock.Paragraph -> Text(md(b.text), style = body)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    val mark = when {
                        b.checked == true -> "☑"
                        b.checked == false -> "☐"
                        b.number != null -> "${b.number}."
                        b.depth == 0 -> "•"
                        else -> "◦"
                    }
                    Text(mark, style = body.copy(color = c.secondary), modifier = Modifier.widthIn(min = 18.dp))
                    Box(Modifier.width(4.dp))
                    Text(md(b.text), style = body)
                }
                is MdBlock.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg.copy(alpha = 0.7f)).padding(10.dp)) {
                    if (b.language.isNotEmpty()) Text(b.language, color = c.tertiary, style = MaterialTheme.typography.labelSmall)
                    Text(b.code, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = 0.5f)))
                    Box(Modifier.width(10.dp))
                    Text(md(b.text), style = body.copy(color = c.secondary))
                }
                is MdBlock.Table -> Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.bg.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (listOf(b.header) + b.rows).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { cell ->
                                Text(md(cell), style = MaterialTheme.typography.bodyMedium.copy(color = color,
                                    fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.widthIn(min = 48.dp, max = 220.dp))
                            }
                        }
                    }
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(c.border))
            }
        }
    }
}
