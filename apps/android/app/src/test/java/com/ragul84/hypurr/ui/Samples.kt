package com.ragul84.hypurr.ui

import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.PermissionOption

/** Fixed sample state for the screenshot tests. */
object Samples {
    const val NOW = 1_790_000_000_000L
    private const val MIN = 60_000L

    val computer = Computer(
        id = "NHUPmL1Z_PyUbaRaqr6TOw", name = "Kevin's Mac Studio", signKey = "iojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w",
        urls = listOf("http://192.168.1.20:19222", "http://100.88.12.4:19222"), cloud = "https://api.hypurr.dev",
    )

    val reviewer = Bot(id = "b1", name = "Reviewer", avatarColor = "violet", avatarShape = "blob", status = "needsInput",
        activity = "wants to run cargo test --all", lastAt = NOW - 2 * MIN, unread = 1, cwd = "/Users/kevin/hypurr")
    val bots = listOf(
        reviewer,
        Bot(id = "b2", name = "Frontend", avatarColor = "cyan", avatarShape = "squircle", status = "working",
            activity = "Editing web/src/App.tsx", lastAt = NOW - 1 * MIN, pinned = true),
        Bot(id = "b3", name = "Docs", avatarColor = "magenta", avatarShape = "pebble", lastMessage = "Updated the setup guide for Android.",
            lastAt = NOW - 42 * MIN, unread = 2),
        Bot(id = "b4", name = "Release", avatarColor = "orange", avatarShape = "hex", status = "error",
            lastMessage = "Notarization timed out", lastAt = NOW - 3 * 60 * MIN),
        Bot(id = "b5", name = "Host", avatarColor = "green", avatarShape = "tablet", lastMessage = "All 178 tests pass.",
            lastAt = NOW - 26 * 60 * MIN),
    )

    private var seq = 0L
    private fun e(kind: String, data: EntryData, at: Long) = Entry(id = "e${++seq}", seq = seq, botId = "b1", kind = kind, data = data,
        createdAt = at, updatedAt = at)

    val chat = listOf(
        e("user", EntryData(text = "Can you review the Android channel transport before I open the PR?"), NOW - 9 * MIN),
        e("agent", EntryData(text = "Looked through ChannelTransport.kt. The handshake and frame counters match the spec, and " +
            "every vector in remote-relay-vectors.json passes. One thing: the relay heartbeat should close a socket that has " +
            "been silent for 75 s, like the iOS client.", final = true), NOW - 7 * MIN),
        e("user", EntryData(text = "Good catch, fixed. Run the whole suite?"), NOW - 3 * MIN),
        e("permission", EntryData(title = "Run cargo test --all?", command = "cd host && cargo test --all", status = "pending",
            options = listOf(PermissionOption("allow", "Allow once", "allow_once"), PermissionOption("always", "Always allow", "allow_always"),
                PermissionOption("reject", "Deny", "reject_once"))), NOW - 2 * MIN),
    )
}
