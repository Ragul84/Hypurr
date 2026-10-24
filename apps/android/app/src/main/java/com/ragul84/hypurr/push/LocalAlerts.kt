package com.ragul84.hypurr.push

import com.ragul84.hypurr.model.Bot

/**
 * In-app fallback for push: while the phone holds a live channel the host doesn't push (it thinks
 * the app is open), so the app raises the same three alerts itself from bot status changes. Works
 * without Firebase. Shown only when no Hypurr screen is visible.
 */
object LocalAlerts {
    enum class Kind { NeedsYou, Done, Failed }

    /** The alert for a status change, or null. A bot seen for the first time (sync) never alerts. */
    fun detect(old: Bot?, new: Bot): Kind? {
        if (old == null || old.status == new.status || new.deleted == true) return null
        return when {
            new.status == "needsInput" -> Kind.NeedsYou
            new.status == "error" -> Kind.Failed
            new.status == "idle" && old.isWorking -> Kind.Done
            else -> null
        }
    }

    /** Same plain words as the pushed line: what happened and where, never the task or code. */
    fun alert(kind: Kind, bot: Bot, computer: String): PushAlert {
        val on = computer.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        val title = when (kind) {
            Kind.NeedsYou -> "Hypurr needs you$on"
            Kind.Done -> "Task finished$on"
            Kind.Failed -> "Task failed$on"
        }
        val category = when (kind) {
            Kind.NeedsYou -> "needsInput"
            Kind.Done -> "done"
            Kind.Failed -> "failed"
        }
        return PushAlert(title = title, subtitle = null, body = bot.name.ifBlank { "Open Hypurr" },
            botId = bot.id, threadId = "local:${bot.id}", category = category)
    }
}
