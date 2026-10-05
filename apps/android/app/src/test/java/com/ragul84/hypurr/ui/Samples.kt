package com.ragul84.hypurr.ui

import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.Agent
import com.ragul84.hypurr.model.Checkpoint
import com.ragul84.hypurr.model.PermissionOption
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate

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

    // MARK: tasks (stage A)

    private val options = listOf(PermissionOption("allow", "Allow once", "allow_once"), PermissionOption("reject", "Deny", "reject_once"))

    val task = TaskInfo(
        id = "t1", botId = "b6", goal = "the checkout button", title = "Write tests: the checkout button", template = "write-tests",
        project = "/Users/priya/shop", projectName = "shop", backend = "claude", branch = "hypurr/write-tests-the-checkout-button-1a2b3c4d",
        base = "main", worktree = "/Users/priya/.hypurr/worktrees/t1",
        checkpoints = listOf(
            Checkpoint("a1b2c3d", "Start (from main)", NOW - 14 * MIN),
            Checkpoint("b2c3d4e", "After step 1", NOW - 9 * MIN, listOf("tests/checkout.test.js")),
            Checkpoint("c3d4e5f", "Before: Install testing library", NOW - 6 * MIN, listOf("package.json")),
            Checkpoint("d4e5f6a", "After step 2", NOW - 2 * MIN, listOf("tests/checkout.test.js", "package-lock.json")),
        ),
    )
    val taskBot = Bot(id = "b6", name = "Write tests: the checkout button", avatarColor = "green", avatarShape = "squircle",
        status = "needsInput", activity = "Needs approval: Clean build", lastAt = NOW - 1 * MIN, task = task,
        description = "the checkout button", cwd = "/Users/priya/.hypurr/worktrees/t1")
    val botsWithTask = listOf(taskBot) + bots.drop(1)

    private fun t(kind: String, data: EntryData, at: Long) = Entry(id = "t${++seq}", seq = seq, botId = "b6", kind = kind, data = data,
        createdAt = at, updatedAt = at)

    val taskChat = listOf(
        t("user", EntryData(text = "Write tests: the checkout button"), NOW - 14 * MIN),
        t("permission", EntryData(title = "Install testing library", command = "npm install --save-dev @testing-library/react",
            status = "answered", selected = "allow", options = options, risk = "medium",
            explain = "The agent wants to download and install packages (npm install --save-dev @testing-library/react).",
            riskReasons = listOf("New packages come from the internet; check they're ones you expect."), checkpoint = "c3d4e5f"), NOW - 6 * MIN),
        t("permission", EntryData(title = "Push", command = "git push origin main", status = "answered", selected = "reject", options = options,
            risk = "high", explain = "The agent wants to upload commits straight to main on origin.",
            riskReasons = listOf("main is a protected branch; changes should go through a pull request."),
            blocked = "Pushing to main is blocked by the safety net. Open a pull request instead."), NOW - 3 * MIN),
        t("permission", EntryData(title = "Clean build", command = "rm -rf build", status = "pending", options = options, risk = "high",
            explain = "The agent wants to delete build and everything inside.",
            riskReasons = listOf("It deletes files. A checkpoint can bring tracked files back, but not ignored or new ones."),
            checkpoint = "d4e5f6a"), NOW - 1 * MIN),
    )

    val safety = SafetySettings()
    val templates = listOf(
        TaskTemplate("write-tests", "Write tests", "Add tests for a feature or file", "test", goalHint = "What should be tested?", builtin = true),
        TaskTemplate("fix-error", "Fix this error", "Paste an error or log and get a fix", "bug", inputLabel = "Error message or log", builtin = true),
        TaskTemplate("review-pr", "Review my PR", "Get a plain-language review of changes", "review", readOnly = true, builtin = true),
        TaskTemplate("update-deps", "Update dependencies", "Safely update packages and check the build", "deps", builtin = true),
        TaskTemplate("explain-code", "Explain this code", "Understand a file or feature in plain words", "explain", readOnly = true, builtin = true),
        TaskTemplate("custom-1", "Add API docs", "", "custom", prompt = "Write API documentation for {goal}"),
    )
    val setup = TaskSetup(
        templates = templates,
        projects = listOf(Project("shop", "/Users/priya/shop"), Project("payments", "/Users/priya/payments-svc", listOf("billing"))),
        agents = listOf(Agent("claude", "Claude Code"), Agent("codex", "Codex"), Agent("gemini", "Gemini CLI")),
        safety = safety,
    )
    val route = TaskRoute(Project("shop", "/Users/priya/shop"), Agent("claude", "Claude Code"),
        "shop matches “checkout” in your request; Claude Code is installed and good at making careful code changes.")
}
