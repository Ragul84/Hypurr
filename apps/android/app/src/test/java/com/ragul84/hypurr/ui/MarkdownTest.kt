package com.ragul84.hypurr.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What agents write, parsed the way the chat renders it. */
class MarkdownTest {
    @Test
    fun blocks() {
        val md = """
            # Plan
            Here's what I'll do:

            - run `npm test`
              and read the output
            - [x] fix the button
            1. first
            2) second

            ```kotlin
            val x = 1
            ```
            > a quote
            > on two lines

            | File | Lines |
            |---|---:|
            | a.kt | 3 |
            ---
            done
        """.trimIndent()
        val b = Markdown.blocks(md)
        assertEquals(MdBlock.Heading(1, "Plan"), b[0])
        assertEquals(MdBlock.Paragraph("Here's what I'll do:"), b[1])
        assertEquals(MdBlock.Item("run `npm test` and read the output", null, 0), b[2])
        assertEquals(MdBlock.Item("fix the button", null, 0, checked = true), b[3])
        assertEquals(MdBlock.Item("first", 1, 0), b[4])
        assertEquals(MdBlock.Item("second", 2, 0), b[5])
        assertEquals(MdBlock.Code("val x = 1", "kotlin"), b[6])
        assertEquals(MdBlock.Quote("a quote\non two lines"), b[7])
        assertEquals(MdBlock.Table(listOf("File", "Lines"), listOf(listOf("a.kt", "3"))), b[8])
        assertEquals(MdBlock.Rule, b[9])
        assertEquals(MdBlock.Paragraph("done"), b[10])
        assertEquals(11, b.size)
    }

    @Test
    fun unclosedFenceKeepsTheRest() {
        val b = Markdown.blocks("text\n```\ncode still streaming")
        assertEquals(MdBlock.Code("code still streaming", ""), b[1])
    }

    @Test
    fun nestedListsAndPlainText() {
        val b = Markdown.blocks("- top\n  - nested\n    - deeper\njust text with a | pipe")
        assertEquals(listOf(0, 1, 2), b.filterIsInstance<MdBlock.Item>().map { it.depth })
        assertEquals(MdBlock.Paragraph("just text with a | pipe"), b.last())
    }

    @Test
    fun inlineStyles() {
        val s = Markdown.inline("**Bold** and *it* and `code` and ~~gone~~ and [docs](https://example.com/docs) and snake_case_name")
        assertEquals("Bold and it and code and gone and docs and snake_case_name", s.text)
        fun styleAt(word: String) = s.spanStyles.filter { it.start <= s.text.indexOf(word) && it.end > s.text.indexOf(word) }.map { it.item }
        assertTrue(styleAt("Bold").any { it.fontWeight == FontWeight.SemiBold })
        assertTrue(styleAt("it ").any { it.fontStyle == FontStyle.Italic })
        assertTrue(styleAt("code").any { it.fontFamily == FontFamily.Monospace })
        assertTrue(styleAt("gone").any { it.textDecoration == TextDecoration.LineThrough })
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals("https://example.com/docs", (link.item as LinkAnnotation.Url).url)
        assertEquals("docs", s.text.substring(link.start, link.end))
        assertTrue(styleAt("snake").none { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun bareLinksEscapesAndStrayMarks() {
        val s = Markdown.inline("see https://hypurr.app/docs. 2 * 3 = 6, \\*not italic\\*, a ** b")
        assertEquals("see https://hypurr.app/docs. 2 * 3 = 6, *not italic*, a ** b", s.text)
        val link = s.getLinkAnnotations(0, s.length).single()
        assertEquals("https://hypurr.app/docs", (link.item as LinkAnnotation.Url).url)
        assertTrue(s.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun hostReplyFromTheFixture() {
        val text = "**Alice** here. You said: _what should I run?_\n\n- run `npm test`\n- check [the docs](https://example.com/docs)\n\n```sh\nnpm test\n```"
        val b = Markdown.blocks(text)
        assertEquals(4, b.size)
        assertEquals(MdBlock.Code("npm test", "sh"), b[3])
        val first = Markdown.inline((b[0] as MdBlock.Paragraph).text)
        assertEquals("Alice here. You said: what should I run?", first.text)
    }

    @Test
    fun plainDropsTheSyntax() {
        assertEquals("Here's the plan for the checkout fix:\nGuard cart.total\nconst x = 1\nSee the guide.",
            Markdown.plain("Here's the plan for the **checkout fix**:\n\n1. Guard `cart.total`\n\n```ts\nconst x = 1\n```\nSee [the guide](https://x.y)."))
    }
}
