package com.ragul84.hypurr.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lenient like the iOS decoder: small host additions never break the app. */
val HypurrJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** A paired computer (kit Computer.swift). */
@Serializable
data class Computer(
    val id: String,
    val name: String,
    /** Pinned host signing key: the QR's `sk`. */
    val signKey: String,
    /** Pinned mailbox key from the QR's `bk` or the E2E `hello`. */
    val boxKey: String? = null,
    /** Direct candidates (`http://ip:port`), tried before the relay. */
    val urls: List<String> = emptyList(),
    /** The cloud relay base URL; null = the host has the cloud off. */
    val cloud: String? = null,
    val device: String? = null,
)

@Serializable
data class Bot(
    val id: String,
    val kind: String = "bot",
    val members: List<String> = emptyList(),
    val name: String = "",
    val description: String = "",
    val avatarColor: String = "blue",
    val avatarShape: String = "blob",
    val backend: String = "",
    val cwd: String = "",
    val managedWorkspace: Boolean = false,
    val permission: String = "ask",
    val model: String? = null,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    val notify: Boolean? = null,
    val createdAt: Long = 0,
    val rev: Long = 0,
    /** idle | working | needsInput | error */
    val status: String = "idle",
    val activity: String = "",
    val startedAt: Long? = null,
    val unread: Int = 0,
    val lastMessage: String? = null,
    val lastAt: Long = 0,
    val deleted: Boolean? = null,
    /** Set when this bot runs a beginner task (host `tasks`). */
    val task: TaskInfo? = null,
    /** Where the running turn talks: a chat (bot or group id) and maybe a thread root. */
    val workingChat: String? = null,
    val workingThread: String? = null,
) {
    val isGroup: Boolean get() = kind == "group"
    val isWorking: Boolean get() = status == "working" || status == "needsInput"
    val needsInput: Boolean get() = status == "needsInput"
    val failed: Boolean get() = status == "error"
    val folderName: String get() = if (managedWorkspace) "Personal workspace" else cwd.substringAfterLast('/')
}

@Serializable
data class PermissionOption(val optionId: String, val name: String, val kind: String = "")

@Serializable
data class EntryData(
    val text: String? = null,
    val final: Boolean? = null,
    /** user: queued | sent | cancelled | failed · permission: pending | answered | cancelled | expired */
    val status: String? = null,
    val clientNonce: String? = null,
    val title: String? = null,
    val toolKind: String? = null,
    val options: List<PermissionOption>? = null,
    val selected: String? = null,
    val command: String? = null,
    val detail: String? = null,
    /** notice: info | error | divider */
    val style: String? = null,
    val author: String? = null,
    val reactions: List<String>? = null,
    /** permission: low | medium | high, from the host's risk rules. */
    val risk: String? = null,
    /** permission: one plain sentence ("The agent wants to delete build and everything inside."). */
    val explain: String? = null,
    val riskReasons: List<String>? = null,
    /** permission: the safety net refused it without asking; why, in plain words. */
    val blocked: String? = null,
    /** permission: the task checkpoint saved just before it (Undo goes back here). */
    val checkpoint: String? = null,
    /** user: files sent with the message. */
    val attachments: List<Attachment>? = null,
    /** notice: a finished task's "What changed" card (learning mode, PR, cost). */
    val learning: Learning? = null,
    /** permission: the team's rules say an admin approves this one. */
    val needsAdmin: Boolean? = null,
    /** A main-chat message with replies in its thread (host-maintained summary). */
    val thread: ThreadSummary? = null,
)

/** `authors`: bot ids and `"user"`, first reply first; `unread`: replies newer than the thread was last read. */
@Serializable
data class ThreadSummary(val count: Int = 0, val lastAt: Long = 0, val authors: List<String> = emptyList(), val unread: Int = 0)

/** A folder on the computer (`listDirs`), for a new bot's working folder. */
@Serializable
data class DirEntry(val name: String, val path: String, val isGit: Boolean = false)

@Serializable
data class DirListing(val path: String = "", val parent: String? = null, val isGit: Boolean = false, val dirs: List<DirEntry> = emptyList())

/** Quick reactions under a message (the iPhone's long-press row). */
val QuickReactions = listOf("👍", "❤️", "😂", "🎉", "👀", "✅")

/** Avatar palette ids, in the order the iPhone's picker shows them. */
val AvatarColors = listOf("blue", "cyan", "green", "yellow", "orange", "red", "magenta", "violet", "brown", "gray", "black")

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

val Attachment.isImage: Boolean get() = name.substringAfterLast('.', "").lowercase() in imageExtensions

/** 1.2 MB · 340 KB · 12 B */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Serializable
data class Attachment(val id: String, val name: String = "", val size: Long = 0)

@Serializable
data class FileChange(val path: String, val added: Long = 0, val removed: Long = 0)

@Serializable
data class PullRequest(val number: Long = 0, val url: String = "", val repo: String? = null)

/** Per-task tokens and cost (host `tasks::cost`). `estimated`: from list prices, not the agent. */
@Serializable
data class TaskUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
    val currency: String = "USD",
    val estimated: Boolean = true,
    val turns: Long = 0,
) {
    val label: String get() = costLabel(cost, estimated)
}

fun costLabel(cost: Double, estimated: Boolean): String {
    val s = if (cost > 0 && cost < 0.01) "less than $0.01" else "$" + String.format(java.util.Locale.US, "%.2f", cost)
    return if (estimated) "≈ $s" else s
}

@Serializable
data class Learning(
    val taskId: String = "",
    val title: String = "",
    val summary: String = "",
    val files: List<FileChange> = emptyList(),
    val added: Long = 0,
    val removed: Long = 0,
    val branch: String? = null,
    val base: String? = null,
    val pr: PullRequest? = null,
    val cost: TaskUsage? = null,
    /** slack | teams | jira */
    val posted: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** A GitHub issue or Jira ticket a task can start from. */
@Serializable
data class Issue(
    /** github | jira */
    val source: String,
    val key: String,
    val title: String = "",
    val url: String = "",
    val body: String = "",
    val project: String? = null,
)

@Serializable
data class IssueList(val issues: List<Issue> = emptyList(), val errors: List<IssueError> = emptyList())

@Serializable
data class IssueError(val source: String = "", val message: String = "")

@Serializable
data class GitHubStatus(val configured: Boolean = false, val tokenHint: String = "", val usesCli: Boolean = false, val apiUrl: String = "")

@Serializable
data class JiraStatus(
    val configured: Boolean = false,
    val baseUrl: String = "",
    val email: String = "",
    val tokenHint: String = "",
    val jql: String = "",
)

@Serializable
data class WebhookStatus(val configured: Boolean = false, val urlHint: String = "")

/** Work tools as the host shows them: never a token, only whether it's set and its last characters. */
@Serializable
data class Integrations(
    val github: GitHubStatus = GitHubStatus(),
    val jira: JiraStatus = JiraStatus(),
    val slack: WebhookStatus = WebhookStatus(),
    val teams: WebhookStatus = WebhookStatus(),
    val learning: Boolean = true,
    val autoPr: Boolean = true,
    val notify: Boolean = true,
) {
    val anyIssues: Boolean get() = github.configured || jira.configured
    val anyChat: Boolean get() = slack.configured || teams.configured
}

@Serializable
data class CostRow(val taskId: String, val botId: String = "", val title: String = "", val createdAt: Long = 0, val usage: TaskUsage = TaskUsage())

@Serializable
data class CostTotal(val cost: Double = 0.0, val tokens: Long = 0, val tasks: Int = 0, val estimated: Boolean = false)

@Serializable
data class TaskCosts(
    val tasks: List<CostRow> = emptyList(),
    val total: CostTotal = CostTotal(),
    val week: Double = 0.0,
    val today: Double = 0.0,
    val currency: String = "USD",
    /** Hypurr gateway free tokens remaining today (formatted). */
    val gatewayFreeRemaining: String? = null,
    /** Credits balance label, e.g. "$5.00". */
    val gatewayCreditsLabel: String? = null,
    /** Stripe Checkout URL (placeholder until live). */
    val buyCreditsUrl: String? = null,
)

@Serializable
data class Entry(
    val id: String,
    val seq: Long = 0,
    val botId: String,
    val threadId: String? = null,
    val rev: Long = 0,
    /** user | agent | thought | tool | plan | permission | notice */
    val kind: String,
    val turn: Long = 0,
    val data: EntryData = EntryData(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Chat-visible: user messages, final replies, approval cards, notices (CLAUDE.md "Chat shows only"). */
    val isChat: Boolean
        get() = when (kind) {
            "user", "permission", "notice" -> true
            "agent" -> data.final == true
            else -> false
        }
}

@Serializable
data class Backend(
    val id: String,
    val name: String = "",
    val available: Boolean = true,
    val installed: Boolean = true,
    val description: String = "",
    val builtin: Boolean = false,
    val free: Boolean = false,
    val subtitle: String = "",
    val defaultModel: String? = null,
    val freeModels: List<AgentModel> = emptyList(),
    val needsInstall: Boolean = false,
    val version: String? = null,
    val consent: String? = null,
)

/** A model an agent can run (Hypurr free gateway models, or whatever `agentModels` returns). */
@Serializable
data class AgentModel(val id: String, val name: String = "", val description: String = "", val free: Boolean = false)

@Serializable
data class Hello(
    val hostId: String = "",
    val name: String = "",
    val version: String = "",
    val os: String = "",
    val device: String? = null,
    val rev: Long = 0,
    val urls: List<String>? = null,
    val computerId: String? = null,
    val signKey: String? = null,
    val boxKey: String? = null,
    val cloud: String? = null,
    /** Who this phone is on the computer (team admin). */
    val you: Actor? = null,
    /** Agents the computer knows; `available` ones can run a new bot. */
    val backends: List<Backend> = emptyList(),
    val builtinAgent: Backend? = null,
    val home: String? = null,
    val screen: ScreenState? = null,
)

/** The computer's remote screen (`screenStatus`, `hello.screen`). */
@Serializable
data class ScreenState(
    val enabled: Boolean = false,
    /** The screen helper is running. */
    val connected: Boolean = false,
    val platform: String = "",
    /** Screen recording is permitted. */
    val capture: Boolean = false,
    /** Input injection is permitted. */
    val input: Boolean = false,
    val displays: List<ScreenDisplay> = emptyList(),
    val userControl: Boolean = false,
    val viewers: Int = 0,
) {
    val ready: Boolean get() = enabled && connected && capture
}

/** A screen session the host reserved, with its short-lived ICE servers (never stored). */
@Serializable
data class ScreenConnection(val session: String, val iceServers: List<IceServer> = emptyList(), val expiresAt: Long = 0)

@Serializable
data class IceServer(val urls: List<String> = emptyList(), val username: String? = null, val credential: String? = null)

@Serializable
data class ScreenDisplay(val id: Long = 0, val name: String = "", val width: Double = 0.0, val height: Double = 0.0, val main: Boolean = false)

@Serializable
data class SyncResponse(
    val hostId: String = "",
    val rev: Long = 0,
    val bots: List<Bot> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

/** A saved point on a task's branch. */
@Serializable
data class Checkpoint(val id: String, val label: String = "", val at: Long = 0, val files: List<String> = emptyList())

/** A beginner task (host `tasks::Task`), carried on its bot. */
@Serializable
data class TaskInfo(
    val id: String,
    val botId: String = "",
    val goal: String = "",
    val title: String = "",
    val template: String? = null,
    val project: String = "",
    val projectName: String = "",
    val backend: String = "",
    /** worktree | none */
    val safety: String = "worktree",
    val branch: String? = null,
    val base: String? = null,
    val worktree: String? = null,
    /** active | finishing | finished */
    val status: String = "active",
    val checkpoints: List<Checkpoint> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val issue: Issue? = null,
    val usage: TaskUsage? = null,
    /** The learning-mode summary, once finished. */
    val summary: String? = null,
    val pr: PullRequest? = null,
) {
    val hasSafetyNet: Boolean get() = safety == "worktree" && branch != null && worktree != null
    val isActive: Boolean get() = status == "active"
    val isFinishing: Boolean get() = status == "finishing"
}

@Serializable
data class TaskTemplate(
    val id: String = "",
    val title: String,
    val summary: String = "",
    /** test | bug | review | deps | explain | custom */
    val icon: String = "custom",
    val prompt: String = "",
    val inputLabel: String? = null,
    val goalHint: String = "",
    val readOnly: Boolean = false,
    val builtin: Boolean = false,
)

@Serializable
data class Project(val name: String, val path: String, val keywords: List<String> = emptyList(), val saved: Boolean = false)

@Serializable
data class Agent(val id: String, val name: String)

@Serializable
data class SafetySettings(
    val protectedBranches: List<String> = listOf("main", "master", "production", "prod", "release/*", "develop"),
    val blockProtected: Boolean = true,
    val alwaysAskHigh: Boolean = true,
    val requireGit: Boolean = false,
)

@Serializable
data class TaskSetup(
    val templates: List<TaskTemplate> = emptyList(),
    val projects: List<Project> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val safety: SafetySettings = SafetySettings(),
    val integrations: Integrations = Integrations(),
)

@Serializable
data class TaskRoute(val project: Project? = null, val agent: Agent? = null, val reason: String = "")

// MARK: team admin (host `admin`)

/** A person (device) and their role: admin | member | viewer. */
@Serializable
data class Actor(val key: String = "", val name: String = "", val role: String = "admin") {
    val isAdmin get() = role == "admin"
    val canAct get() = role != "viewer"
}

@Serializable
data class TeamPerson(
    val key: String = "",
    val name: String = "",
    val platform: String = "",
    val role: String = "member",
    /** This computer itself: always an admin. */
    val fixed: Boolean = false,
    /** Has its own role (otherwise the default). */
    val ownRole: Boolean = false,
    val lastSeenAt: Long? = null,
    val you: Boolean = false,
)

@Serializable
data class TeamInfo(val you: Actor = Actor(), val defaultRole: String = "admin", val people: List<TeamPerson> = emptyList())

@Serializable
data class Policies(
    val allowedAgents: List<String> = emptyList(),
    val dailyLimit: Double = 0.0,
    val taskLimit: Double = 0.0,
    /** low | medium | high | never: at or above, even auto-approve bots ask. */
    val askFrom: String = "high",
    /** low | medium | high | off: at or above, only an admin approves. */
    val adminApprovesFrom: String = "off",
)

@Serializable
data class PolicyInfo(
    val policies: Policies = Policies(),
    val safety: SafetySettings = SafetySettings(),
    val spentToday: Double = 0.0,
    val agents: List<Agent> = emptyList(),
)

@Serializable
data class AuditEntry(
    val seq: Long = 0,
    val at: Long = 0,
    val actor: String = "",
    val actorName: String = "",
    val role: String = "",
    val action: String = "",
    val target: String = "",
    val detail: kotlinx.serialization.json.JsonElement? = null,
    val hash: String = "",
) {
    /** One plain line: "started a task", "was blocked: setPolicies". */
    val summary: String get() = when (action) {
        "task.start" -> "started a task"
        "task.finish" -> "finished a task"
        "task.rollback" -> "went back to a checkpoint"
        "task.checkpoint" -> "saved a checkpoint"
        "approval.answer" -> "answered an approval"
        "approval.auto" -> "approved automatically"
        "approval.blocked" -> "blocked a request"
        "policy.limit" -> "stopped a task at its spending limit"
        "policy.change" -> "changed the team rules"
        "role.change" -> "changed a role"
        "role.default" -> "changed the role for new devices"
        "integrations.change" -> "changed work tools"
        "bot.create" -> "created a bot"
        "bot.update" -> "changed a bot"
        "bot.delete" -> "deleted a bot"
        "bot.stop" -> "stopped an agent"
        "blocked" -> "was blocked"
        else -> action.replace('.', ' ')
    }

    /** What it was about, in plain words (a refused method becomes what the person tried to do). */
    val targetLabel: String get() = if (action != "blocked") target else when (target) {
        "respondPermission" -> "Answering an approval"
        "setPolicies", "setSafetySettings" -> "Changing the team rules"
        "setRole", "setTeam" -> "Changing roles"
        "send" -> "Sending a message"
        "startTask" -> "Starting a task"
        "createBot" -> "Creating a bot"
        "updateBot" -> "Changing a bot"
        "auditLog" -> "Opening the audit log"
        "activity" -> "Opening the activity view"
        "setIntegrations" -> "Changing work tools"
        else -> target
    }

    val reason: String? get() = (detail as? kotlinx.serialization.json.JsonObject)?.get("reason")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    val risk: String? get() = (detail as? kotlinx.serialization.json.JsonObject)?.get("risk")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}

@Serializable
data class AuditLog(
    val entries: List<AuditEntry> = emptyList(),
    val count: Long = 0,
    /** The hash chain checks out: nothing was edited or removed. */
    val intact: Boolean = true,
    val brokenAt: Long? = null,
)

@Serializable
data class ActivityPerson(
    val key: String = "",
    val name: String = "",
    val role: String = "member",
    val lastSeenAt: Long? = null,
    val tasks: Int = 0,
    val spend: Double = 0.0,
    val approvals: Int = 0,
    val blocked: Int = 0,
    val removed: Boolean = false,
)

@Serializable
data class StartedBy(val key: String = "", val name: String = "")

@Serializable
data class ActivityTask(
    val taskId: String = "",
    val botId: String = "",
    val title: String = "",
    val status: String = "active",
    val projectName: String = "",
    val backend: String = "",
    val startedBy: StartedBy = StartedBy(),
    val usage: TaskUsage? = null,
    val pr: PullRequest? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ActivityTotals(
    val spend: Double = 0.0,
    val todaySpend: Double = 0.0,
    val todayTasks: Int = 0,
    val approvals: Int = 0,
    val blocked: Int = 0,
)

@Serializable
data class Activity(
    val days: Int = 7,
    val people: List<ActivityPerson> = emptyList(),
    val tasks: List<ActivityTask> = emptyList(),
    val events: List<AuditEntry> = emptyList(),
    val totals: ActivityTotals = ActivityTotals(),
)

/** "$4.20" for a limit; "No limit" for 0. */
fun limitLabel(x: Double): String = if (x <= 0.0) "No limit" else "$" + String.format(java.util.Locale.US, "%.2f", x)
