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
data class Backend(val id: String, val name: String = "", val available: Boolean = true)

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
)

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
    /** active | finished */
    val status: String = "active",
    val checkpoints: List<Checkpoint> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val hasSafetyNet: Boolean get() = safety == "worktree" && branch != null && worktree != null
    val isActive: Boolean get() = status == "active"
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
)

@Serializable
data class TaskRoute(val project: Project? = null, val agent: Agent? = null, val reason: String = "")
