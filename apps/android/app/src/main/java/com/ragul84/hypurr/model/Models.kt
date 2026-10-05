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
