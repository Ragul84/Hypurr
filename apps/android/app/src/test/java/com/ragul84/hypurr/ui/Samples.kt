package com.ragul84.hypurr.ui

import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.ActivityPerson
import com.ragul84.hypurr.model.ActivityTask
import com.ragul84.hypurr.model.ActivityTotals
import com.ragul84.hypurr.model.Actor
import com.ragul84.hypurr.model.AuditEntry
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.Policies
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.StartedBy
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.model.TeamPerson
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
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.CostRow
import com.ragul84.hypurr.model.CostTotal
import com.ragul84.hypurr.model.FileChange
import com.ragul84.hypurr.model.GitHubStatus
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.JiraStatus
import com.ragul84.hypurr.model.Learning
import com.ragul84.hypurr.model.PullRequest
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.TaskUsage
import com.ragul84.hypurr.model.WebhookStatus

/** Fixed sample state for the screenshot tests. */
object Samples {
    const val NOW = 1_790_000_000_000L
    private const val MIN = 60_000L

    val computer = Computer(
        id = "NHUPmL1Z_PyUbaRaqr6TOw", name = "Kevin's Mac Studio", signKey = "iojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w",
        urls = listOf("http://192.168.1.20:19222", "http://100.88.12.4:19222"), cloud = "https://api.hypurr.dev",
    )

    val reviewer = Bot(id = "b1", name = "Reviewer", avatarColor = "sunflower", avatarShape = "squircle", status = "needsInput",
        activity = "Run the host tests?", lastAt = NOW - 2 * MIN, unread = 1, cwd = "/Users/kevin/hypurr")
    val bots = listOf(
        reviewer,
        Bot(id = "b2", name = "Frontend", avatarColor = "ink", avatarShape = "squircle", status = "working",
            activity = "Editing App.tsx", lastAt = NOW - 1 * MIN, pinned = true),
        Bot(id = "b3", name = "Docs", avatarColor = "asphalt", avatarShape = "pebble", lastMessage = "Updated the setup guide for Android.",
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
        e("permission", EntryData(
            title = "Run cargo test --all?",
            explain = "Run the host tests?",
            command = "cd host && cargo test --all",
            detail = "Staging DB · ~4 min",
            status = "pending",
            options = listOf(
                PermissionOption("allow", "Allow once", "allow_once"),
                PermissionOption("always", "Always", "allow_always"),
                PermissionOption("reject", "Deny", "reject_once"),
            ),
        ), NOW - 2 * MIN),
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

    // MARK: workplace (stage B)

    val integrations = Integrations(
        github = GitHubStatus(configured = true, usesCli = true),
        jira = JiraStatus(configured = true, baseUrl = "https://acme.atlassian.net", email = "priya@acme.in", tokenHint = "…x9Qe"),
        slack = WebhookStatus(configured = true, urlHint = "…Hk2s"),
        teams = WebhookStatus(),
    )
    val setupWork = setup.copy(integrations = integrations)

    val ghIssue = Issue("github", "#142", "Checkout button does nothing on Safari", "https://github.com/acme/shop/issues/142",
        "Clicking Checkout on Safari 17 does nothing.", "/Users/priya/shop")
    val issues = IssueList(listOf(
        ghIssue,
        Issue("github", "#139", "Coupon total rounds the wrong way", "https://github.com/acme/shop/issues/139", project = "/Users/priya/shop"),
        Issue("jira", "SHOP-311", "Add VAT to invoice PDFs", "https://acme.atlassian.net/browse/SHOP-311"),
        Issue("jira", "SHOP-298", "Login times out after 5 minutes on VPN", "https://acme.atlassian.net/browse/SHOP-298"),
    ))

    /** A phone screenshot of a browser error, drawn so the attachment chip has a real picture. */
    fun screenshot(): PickedFile {
        val bmp = android.graphics.Bitmap.createBitmap(270, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0xFFFFFFFF.toInt())
        val p = android.graphics.Paint().apply { isAntiAlias = true }
        p.color = 0xFFE8EAED.toInt(); canvas.drawRect(0f, 0f, 270f, 40f, p)
        p.color = 0xFFD93025.toInt(); canvas.drawRect(16f, 90f, 254f, 200f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 22f; canvas.drawText("TypeError", 30f, 130f, p)
        p.textSize = 15f; canvas.drawText("Cannot read 'total'", 30f, 160f, p)
        p.color = 0xFF9AA0A6.toInt()
        for (i in 0 until 6) canvas.drawRect(16f, 230f + i * 34f, 254f - (i % 3) * 40f, 246f + i * 34f, p)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return PickedFile("checkout-error.png", out.toByteArray(), "image/png")
    }

    private val usage = TaskUsage(inputTokens = 48_210, outputTokens = 6_930, totalTokens = 55_140, cost = 0.31, estimated = false, turns = 4)
    val workTask = task.copy(id = "t2", botId = "b7", title = "#142 Checkout button does nothing on Safari", template = "fix-error",
        branch = "hypurr/142-checkout-button-does-nothing-9f8e7d6c", issue = ghIssue, usage = usage)
    val workBot = taskBot.copy(id = "b7", name = workTask.title, status = "idle", activity = "", task = workTask, avatarColor = "orange")
    val finishedBot = workBot.copy(task = workTask.copy(status = "finished", pr = PullRequest(57, "https://github.com/acme/shop/pull/57"),
        summary = "Fixed the click handler."))

    private fun w(kind: String, data: EntryData, at: Long) = Entry(id = "w${++seq}", seq = seq, botId = "b7", kind = kind, data = data,
        createdAt = at, updatedAt = at)

    val workChat = listOf(
        w("user", EntryData(text = "#142 Checkout button does nothing on Safari",
            attachments = listOf(Attachment("u1", "checkout-error.png", 48_213))), NOW - 20 * MIN),
        w("agent", EntryData(text = "Found it: Safari doesn't support `Array.prototype.at` in the checkout bundle's target, so the " +
            "click handler threw before submitting. I replaced it with an index lookup and added a test.", final = true), NOW - 12 * MIN),
        w("user", EntryData(text = "Explain what you changed and why (learning mode)"), NOW - 4 * MIN),
        w("agent", EntryData(text = "- I changed src/Checkout.tsx and added tests/checkout.test.tsx.\n- Why: older Safari can't run `.at()`, " +
            "so the button's code crashed.\n- Check: open Checkout on Safari and tap the button.", final = true), NOW - 3 * MIN),
        w("notice", EntryData(text = "What changed", style = "info", learning = Learning(
            taskId = "t2", title = workTask.title,
            summary = "• I changed src/Checkout.tsx and added a test.\n• Why: older Safari can't run .at(), so the button's code crashed " +
                "before it could submit the order.\n• Check: open Checkout on Safari and tap the button.",
            files = listOf(FileChange("src/Checkout.tsx", 3, 2), FileChange("tests/checkout.test.tsx", 41, 0)), added = 44, removed = 2,
            branch = workTask.branch, base = "main", pr = PullRequest(57, "https://github.com/acme/shop/pull/57"), cost = usage,
            posted = listOf("slack"))), NOW - 2 * MIN),
    )

    val costs = TaskCosts(
        tasks = listOf(
            CostRow("t2", "b7", workTask.title, NOW - 20 * MIN, usage),
            CostRow("t1", "b6", task.title, NOW - 14 * MIN, TaskUsage(totalTokens = 31_400, cost = 0.18, estimated = true, turns = 3)),
            CostRow("t0", "b8", "Update dependencies", NOW - 26 * 60 * MIN, TaskUsage(totalTokens = 92_700, cost = 0.64, estimated = false, turns = 6)),
        ),
        total = CostTotal(1.13, 179_240, 3, estimated = true), week = 1.13, today = 0.49,
    )

    // Stage C: team admin.

    val admin = Actor("k-arjun", "Arjun's Pixel", "admin")
    val member = Actor("k-priya", "Priya's phone", "member")

    val team = TeamInfo(admin, "member", listOf(
        TeamPerson("local", "This computer", "macos", "admin", fixed = true),
        TeamPerson("k-arjun", "Arjun's Pixel", "android", "admin", ownRole = true, lastSeenAt = NOW - 10_000, you = true),
        TeamPerson("k-priya", "Priya's phone", "android", "member", lastSeenAt = NOW - 25 * MIN),
        TeamPerson("k-sam", "Sam's iPhone", "ios", "viewer", ownRole = true, lastSeenAt = NOW - 3 * 60 * MIN),
    ))

    val policies = PolicyInfo(
        Policies(allowedAgents = listOf("claude", "codex"), dailyLimit = 5.0, taskLimit = 1.0, askFrom = "medium", adminApprovesFrom = "high"),
        SafetySettings(protectedBranches = listOf("main", "release/*", "production")),
        spentToday = 3.42,
        agents = listOf(Agent("claude", "Claude Code"), Agent("codex", "Codex"), Agent("gemini", "Gemini CLI"), Agent("cursor", "Cursor")),
    )

    private fun audit(seq: Long, ago: Long, who: Actor?, action: String, target: String, detail: String = "{}") = AuditEntry(
        seq, NOW - ago, who?.key ?: "hypurr", who?.name ?: "Hypurr safety net", who?.role ?: "system", action, target,
        com.ragul84.hypurr.model.HypurrJson.parseToJsonElement(detail), "h$seq")

    val auditLog = AuditLog(listOf(
        audit(14, 2 * MIN, admin, "approval.answer", "Delete the build folder", """{"risk":"high","answer":"allow_once"}"""),
        audit(13, 3 * MIN, member, "blocked", "respondPermission", """{"reason":"Your team's rules say an admin approves high risk requests."}"""),
        audit(12, 9 * MIN, null, "approval.blocked", "git push origin main", """{"risk":"high","reason":"Pushing to main is blocked by the safety net."}"""),
        audit(11, 14 * MIN, member, "task.start", "#142 Checkout button does nothing on Safari"),
        audit(10, 40 * MIN, null, "policy.limit", "Update dependencies", """{"reason":"This task reached its spending limit ($1.00)."}"""),
        audit(9, 62 * MIN, admin, "policy.change", "Team rules"),
        audit(8, 65 * MIN, admin, "role.change", "Sam's iPhone", """{"role":"viewer"}"""),
        audit(7, 2 * 60 * MIN, Actor("local", "This computer", "admin"), "role.default", "New devices"),
    ), count = 14, intact = true)

    val activity = Activity(
        days = 7,
        people = listOf(
            ActivityPerson("local", "This computer", "admin", tasks = 1, spend = 0.64),
            ActivityPerson("k-arjun", "Arjun's Pixel", "admin", NOW - 10_000, tasks = 2, spend = 0.49, approvals = 4),
            ActivityPerson("k-priya", "Priya's phone", "member", NOW - 25 * MIN, tasks = 5, spend = 2.29, approvals = 2, blocked = 1),
            ActivityPerson("k-sam", "Sam's iPhone", "viewer", NOW - 3 * 60 * MIN),
        ),
        tasks = listOf(
            ActivityTask("t9", "b9", "#142 Checkout button does nothing on Safari", "finished", "shop", "claude", StartedBy("k-priya", "Priya's phone"),
                usage, PullRequest(57, "https://github.com/acme/shop/pull/57"), NOW - 14 * MIN),
            ActivityTask("t8", "b8", "Write tests: the checkout button", "active", "shop", "codex", StartedBy("k-arjun", "Arjun's Pixel"),
                TaskUsage(totalTokens = 31_400, cost = 0.18, estimated = true, turns = 3), null, NOW - 50 * MIN),
            ActivityTask("t7", "b7", "Update dependencies", "finished", "api", "claude", StartedBy("local", "This computer"),
                TaskUsage(totalTokens = 92_700, cost = 0.64, turns = 6), null, NOW - 26 * 60 * MIN),
        ),
        events = auditLog.entries,
        totals = ActivityTotals(spend = 3.42, todaySpend = 3.42, todayTasks = 6, approvals = 6, blocked = 3),
    )

    /** A high-risk card a member can see but only an admin answers. */
    val adminCardChat = listOf(
        t("user", EntryData(text = "Write tests: the checkout button"), NOW - 6 * MIN),
        t("permission", EntryData(title = "Clean build", command = "rm -rf build", status = "pending", options = options, risk = "high",
            explain = "The agent wants to delete build and everything inside.",
            riskReasons = listOf("It deletes files. A checkpoint can bring tracked files back, but not ignored or new ones."),
            checkpoint = "d4e5f6a", needsAdmin = true), NOW - 1 * MIN),
    )

    // Stage D: Markdown, files, groups and threads, bots from the phone, remote screen.

    private fun g(id: String, kind: String, data: EntryData, at: Long, bot: String = "b1", thread: String? = null) =
        Entry(id = id, seq = ++seq, botId = bot, threadId = thread, kind = kind, data = data, createdAt = at, updatedAt = at)

    val markdownReply = "Here's the plan for the **checkout fix**:\n\n" +
        "1. Guard `cart.total` before rounding\n2. Add a test for coupons\n   - one with `0.1 + 0.2`\n\n" +
        "```ts\nconst total = round(cart?.total ?? 0)\n```\n" +
        "> The VAT rule lives in `pricing.ts`.\n\nSee [the PR guide](https://example.com/guide)."

    val richChat: List<Entry> get() {
        val shot = screenshot()
        return listOf(
            g("m1", "user", EntryData(text = "The total is wrong with a coupon, here's what I see", attachments = listOf(
                Attachment("u1", "checkout.png", shot.size), Attachment("u2", "console.log", 18_400))), NOW - 12 * MIN),
            g("m2", "agent", EntryData(text = markdownReply, final = true, reactions = listOf("👍", "🎉"),
                thread = com.ragul84.hypurr.model.ThreadSummary(3, NOW - 4 * MIN, listOf("user", "b1"), unread = 1)), NOW - 10 * MIN),
        )
    }

    val richImages: Map<String, ByteArray> get() = mapOf("u1" to screenshot().bytes)

    val thread: List<Entry> = listOf(
        g("t1", "user", EntryData(text = "Should the test cover free shipping too?"), NOW - 8 * MIN, thread = "m2"),
        g("t2", "agent", EntryData(text = "Yes: free shipping changes the **VAT base**. I'll add `freeShipping()` as a second case.", final = true),
            NOW - 6 * MIN, thread = "m2"),
        g("t3", "user", EntryData(text = "Great, go ahead"), NOW - 4 * MIN, thread = "m2"),
    )

    val alice = Bot(id = "a1", name = "Alice", avatarColor = "green", backend = "claude", cwd = "/Users/kevin/shop", description = "Backend and pricing")
    val bob = Bot(id = "a2", name = "Bob", avatarColor = "orange", avatarShape = "squircle", backend = "codex", cwd = "/Users/kevin/shop",
        description = "Frontend")
    val group = Bot(id = "g1", kind = "group", name = "Shop team", members = listOf("a1", "a2"), description = "Ship the coupon fix",
        lastMessage = "Bob: The button is fixed on small phones.", lastAt = NOW - 3 * MIN, unread = 2)
    val groupBots: Map<String, Bot> = (bots + listOf(alice, bob, group)).associateBy { it.id }

    val groupChat = listOf(
        g("gm1", "user", EntryData(text = "@Alice can you check the VAT rounding? Bob, the button on small phones"), NOW - 9 * MIN, bot = "g1"),
        g("gm2", "agent", EntryData(text = "VAT rounding is fixed in `pricing.ts`: totals now round **once**, at the end.", final = true,
            author = "a1"), NOW - 7 * MIN, bot = "g1"),
        g("gm3", "agent", EntryData(text = "The button is fixed on small phones. I also:\n- moved the coupon field above the total\n- added a loading state",
            final = true, author = "a2", thread = com.ragul84.hypurr.model.ThreadSummary(2, NOW - 2 * MIN, listOf("user", "a2"))), NOW - 3 * MIN, bot = "g1"),
    )

    val freeModels = listOf(
        com.ragul84.hypurr.model.AgentModel("hypurr/hypurr-free", "Hypurr Free", "Daily free allowance", free = true),
        com.ragul84.hypurr.model.AgentModel("hypurr/hypurr-fast", "Hypurr Fast", free = true),
    )
    val builtinHypurrAgent = com.ragul84.hypurr.model.Backend(
        "hypurr-agent", "Hypurr Agent", available = true, installed = true, builtin = true, free = true,
        subtitle = "Hypurr Agent · free models", defaultModel = "hypurr/hypurr-free", freeModels = freeModels,
        description = "Hypurr Agent with free models via the Hypurr gateway.",
    )
    val backends = listOf(builtinHypurrAgent, com.ragul84.hypurr.model.Backend("claude", "Claude Code"), com.ragul84.hypurr.model.Backend("codex", "Codex"),
        com.ragul84.hypurr.model.Backend("gemini", "Gemini CLI"))

    val dirs = com.ragul84.hypurr.model.DirListing("/Users/kevin/code", "/Users/kevin", false, listOf(
        com.ragul84.hypurr.model.DirEntry("hypurr", "/Users/kevin/code/hypurr", true),
        com.ragul84.hypurr.model.DirEntry("shop", "/Users/kevin/code/shop", true),
        com.ragul84.hypurr.model.DirEntry("notes", "/Users/kevin/code/notes", false)))

    val screenOn = com.ragul84.hypurr.model.ScreenState(enabled = true, connected = true, platform = "macos", capture = true, input = true,
        displays = listOf(com.ragul84.hypurr.model.ScreenDisplay(1, "Studio Display", 2560.0, 1440.0, true)))
}
