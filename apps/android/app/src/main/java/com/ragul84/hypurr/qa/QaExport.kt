package com.ragul84.hypurr.qa

import com.ragul84.hypurr.model.QaCase

/** Where the cases get pasted or imported. Nothing is ever posted by Hypurr. */
enum class QaFormat(val label: String, val detail: String, val csv: Boolean) {
    Csv("CSV", "Opens in Excel or Google Sheets", true),
    Jira("Jira", "Paste into a Jira description or comment", false),
    Xray("Xray", "Xray test case importer (CSV)", true),
    TestRail("TestRail", "TestRail CSV import", true),
    Zephyr("Zephyr", "Zephyr Scale CSV import", true),
}

object QaExport {
    private fun cell(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""

    private fun row(vararg cells: String): String = cells.joinToString(",") { cell(it) }

    private fun numbered(steps: List<String>): String = steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

    private fun kindWord(k: String) = when (k) { "negative" -> "Negative"; "edge" -> "Edge"; else -> "Positive" }

    private fun priorityWord(p: String) = when (p) { "high" -> "High"; "low" -> "Low"; else -> "Medium" }

    /** Case ids an automated test covers, for the Automated column. */
    fun export(cases: List<QaCase>, format: QaFormat, automated: Set<String> = emptySet()): String = when (format) {
        QaFormat.Csv -> (listOf(row("ID", "Title", "Type", "Priority", "Steps", "Expected result", "Why", "Automated")) +
            cases.map { c ->
                row(c.id, c.title, kindWord(c.kind), priorityWord(c.priority), numbered(c.steps), c.expected, c.why,
                    if (c.id in automated) "Yes" else "No")
            }).joinToString("\r\n", postfix = "\r\n")
        // One row per step; the summary only on the first row of each test.
        QaFormat.Xray -> (listOf(row("TCID", "Test Summary", "Priority", "Action", "Expected Result")) +
            cases.flatMap { c ->
                val steps = c.steps.ifEmpty { listOf(c.title) }
                steps.mapIndexed { i, s ->
                    row(c.id, if (i == 0) c.title else "", if (i == 0) priorityWord(c.priority) else "", s,
                        if (i == steps.lastIndex) c.expected else "")
                }
            }).joinToString("\r\n", postfix = "\r\n")
        QaFormat.TestRail -> (listOf(row("Title", "Type", "Priority", "Steps", "Expected Result")) +
            cases.map { c -> row("${c.id} ${c.title}", kindWord(c.kind), priorityWord(c.priority), numbered(c.steps), c.expected) })
            .joinToString("\r\n", postfix = "\r\n")
        QaFormat.Zephyr -> (listOf(row("Name", "Objective", "Priority", "Test Script (Step-by-Step) - Step", "Test Script (Step-by-Step) - Expected Result")) +
            cases.map { c -> row("${c.id} ${c.title}", c.why, priorityWord(c.priority), numbered(c.steps), c.expected) })
            .joinToString("\r\n", postfix = "\r\n")
        QaFormat.Jira -> cases.joinToString("\n\n") { c ->
            buildString {
                append("h3. ${c.id} ${c.title}\n")
                append("*Type:* ${kindWord(c.kind)} | *Priority:* ${priorityWord(c.priority)}")
                if (c.id in automated) append(" | *Automated*")
                append("\n*Steps:*\n")
                c.steps.forEach { append("# $it\n") }
                append("*Expected:* ${c.expected}")
            }
        }
    }

    fun fileName(format: QaFormat): String = "test-cases-${format.name.lowercase()}.${if (format.csv) "csv" else "txt"}"
}
