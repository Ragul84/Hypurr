package com.ragul84.hypurr.data

import kotlinx.serialization.Serializable

/**
 * Optional first-run profile, kept on this phone (app-private storage).
 * Never collects employee ID, company, salary, or other sensitive identifiers.
 * Only the fixed choices are sent to the computer, which renders its own agent guidance
 * (host `chat::profile`): plain words + test-first + stricter approvals for cautious
 * profiles, terse replies for comfortable developers.
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
