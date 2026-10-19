package com.ragul84.hypurr.data

import kotlinx.serialization.Serializable

/**
 * Local-only first-run profile (Persistence / SharedPreferences).
 * Never collects employee ID, company, salary, or other sensitive identifiers.
 * Synced to the host as non-sensitive agent context (tone + defaults).
 */
@Serializable
data class UserProfile(
    val completed: Boolean = false,
    val skipped: Boolean = false,
    val role: String? = null,
    val experience: String? = null,
    val stacks: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
) {
    val hasAnswers: Boolean
        get() = role != null || experience != null || stacks.isNotEmpty() || goals.isNotEmpty()

    /** Plain prompt block for the host / agent. */
    fun agentContextBlock(): String {
        if (!hasAnswers) return ""
        val lines = mutableListOf("User preferences (local profile — adapt tone and defaults):")
        role?.let { lines += "- Role: $it" }
        experience?.let { lines += "- Coding experience: $it" }
        if (stacks.isNotEmpty()) lines += "- Main stack: ${stacks.joinToString(", ")}"
        if (goals.isNotEmpty()) lines += "- Mostly wants to: ${goals.joinToString(", ")}"
        when {
            isNewAndCautious -> {
                lines += "- Prefer plain explanations, short steps, and test-first suggestions."
                lines += "- Ask before risky edits, deletes, pushes, or work outside the project folder."
            }
            isComfortableDev -> {
                lines += "- Prefer terse replies; fewer confirmations for routine low-risk edits inside the project."
            }
            else -> lines += "- Keep replies phone-friendly and clear."
        }
        return lines.joinToString("\n")
    }

    val isNewAndCautious: Boolean
        get() = experience.equals("New", true) ||
            role in listOf("Tester/QA", "Student", "Support", "Manager")

    val isComfortableDev: Boolean
        get() = experience.equals("Comfortable", true) &&
            role in listOf("Developer", "DevOps")

    /** Default bot permission: cautious roles ask; comfortable devs may auto low-risk. */
    val suggestedPermission: String
        get() = if (isComfortableDev) "auto" else "ask"

    /** Template id preference order for Tasks. */
    fun preferTemplateIds(ids: List<String>): List<String> {
        val boost = when {
            goals.any { it.contains("test", true) } || role == "Tester/QA" ->
                listOf("write-tests", "fix-error", "review-pr", "explain-code", "update-deps")
            goals.any { it.contains("bug", true) } || goals.any { it.contains("Fix", true) } ->
                listOf("fix-error", "write-tests", "review-pr", "update-deps", "explain-code")
            goals.any { it.contains("Review", true) } ->
                listOf("review-pr", "explain-code", "write-tests", "fix-error", "update-deps")
            goals.any { it.contains("Learn", true) } || role == "Student" ->
                listOf("explain-code", "write-tests", "fix-error", "review-pr", "update-deps")
            else -> emptyList()
        }
        if (boost.isEmpty()) return ids
        val rest = ids.filter { it !in boost }
        return boost.filter { it in ids } + rest
    }

    companion object {
        val Roles = listOf("Tester/QA", "Developer", "Support", "DevOps", "Manager", "Student")
        val Experiences = listOf("New", "Some", "Comfortable")
        val Stacks = listOf("Java", "Python", "JS/React", ".NET", "Mobile", "Other")
        val Goals = listOf("Write tests", "Fix bugs", "Review PRs", "Learn")
    }
}
