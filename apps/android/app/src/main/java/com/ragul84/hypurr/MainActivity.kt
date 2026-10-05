package com.ragul84.hypurr

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import com.ragul84.hypurr.data.HypurrStore
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.ScreenState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.screen.ScreenPhase
import com.ragul84.hypurr.screen.ScreenSession
import com.ragul84.hypurr.ui.screens.AttachKind
import com.ragul84.hypurr.ui.screens.BotEditorScreen
import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.ComposerFiles
import com.ragul84.hypurr.ui.screens.ScreenScreen
import com.ragul84.hypurr.ui.screens.ScreenUiState
import com.ragul84.hypurr.ui.screens.usableBackends
import android.webkit.MimeTypeMap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.BuiltinInstallPrompt
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.model.TaskTemplate
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.AdminTab
import com.ragul84.hypurr.ui.screens.TeamAdminScreen
import com.ragul84.hypurr.ui.screens.TeamAdminUiState
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.screens.rosterOrder
import com.ragul84.hypurr.ui.theme.HypurrTheme
import com.ragul84.hypurr.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as HypurrApp
    private val pendingLink = MutableStateFlow<String?>(null)
    private val openBot = MutableStateFlow<String?>(null)
    private val scanned = MutableStateFlow<String?>(null)

    private val scan = registerForActivityResult(ScanContract()) { result -> result.contents?.let { scanned.value = it } }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** A screenshot or photo picked for the New task screen. */
    private val picked = MutableStateFlow<PickedFile?>(null)
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { readFile(it, "screenshot.jpg") }?.let { picked.value = it }
    }

    /** Photos and files picked for the open chat's composer. */
    private val chatPicked = MutableStateFlow<List<PickedFile>>(emptyList())
    private val pickChatMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        addChatFiles(uris.mapNotNull { readFile(it, "photo.jpg") })
    }
    private val pickChatFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addChatFiles(uris.mapNotNull { readFile(it, "file") })
    }

    private fun addChatFiles(files: List<PickedFile>) {
        if (files.isNotEmpty()) chatPicked.value = chatPicked.value + files
    }

    /** An image on the clipboard, if there is one. */
    private fun clipboardImage(): PickedFile? {
        val item = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        return item?.uri?.takeIf { contentResolver.getType(it)?.startsWith("image/") == true }?.let { readFile(it, "pasted.png") }
    }

    /** Hands a chat's file to another app: written to the cache, shared read-only through the FileProvider. */
    private fun openFile(a: Attachment, bytes: ByteArray) {
        val dir = java.io.File(cacheDir, "shared/${a.id.filter { it.isLetterOrDigit() || it == '-' }}").apply { mkdirs() }
        val file = java.io.File(dir, a.name.substringAfterLast('/').ifEmpty { "file" })
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(view, a.name))
    }

    /** Reads a picked or pasted file (the system photo picker or the clipboard; no storage permission). */
    private fun readFile(uri: Uri, fallback: String): PickedFile? = runCatching {
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: fallback
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_FILE) return null
        PickedFile(name, bytes, mime)
    }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val store = app.store
            val mode by store.themeMode.collectAsState()
            val dynamic by store.dynamicColor.collectAsState()
            HypurrTheme(mode, dynamic) { App(store) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        val store = app.store
        if (store.computer.value != null && store.link.value !is LinkState.Ready) store.reconnect()
    }

    private fun handle(intent: Intent?) {
        // A pairing link fills the pairing screen; the user still confirms it with Pair.
        intent?.data?.takeIf { it.scheme == "hypurr" }?.let { pendingLink.value = it.toString() }
        intent?.getStringExtra(EXTRA_BOT)?.let { openBot.value = it }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Composable
    private fun App(store: HypurrStore) {
        val computer by store.computer.collectAsState()
        val scope = rememberCoroutineScope()
        var pairing by remember { mutableStateOf(PairingUiState()) }
        val link by pendingLink.collectAsState()
        val scan by scanned.collectAsState()

        fun pair(text: String) {
            val parsed = runCatching { Pairing.parse(text) }
            pairing = pairing.copy(link = text, busy = parsed.isSuccess, error = parsed.exceptionOrNull()?.message,
                computerName = parsed.getOrNull()?.computer?.name)
            if (parsed.isFailure) return
            scope.launch {
                pairing = try {
                    store.pair(text)
                    askForNotifications()
                    PairingUiState()
                } catch (e: Exception) {
                    pairing.copy(busy = false, error = e.message ?: "Pairing failed.")
                }
            }
        }
        link?.let {
            pendingLink.value = null
            pairing = pairing.copy(link = it, error = null)
        }
        scan?.let {
            scanned.value = null
            pair(it)
        }

        val current = computer
        if (current == null) {
            PairingScreen(
                pairing,
                onLinkChange = { pairing = pairing.copy(link = it, error = null) },
                onScan = {
                    this.scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                        .setOrientationLocked(false).setPrompt(getString(R.string.scan_prompt)))
                },
                onPaste = {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    clip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { pairing = pairing.copy(link = it.trim(), error = null) }
                },
                onPair = { pair(pairing.link) },
            )
            return
        }

        val linkState by store.link.collectAsState()
        val bots by store.bots.collectAsState()
        val entries by store.entries.collectAsState()
        val synced by store.synced.collectAsState()
        val requested by openBot.collectAsState()
        val you by store.you.collectAsState()
        var screen by rememberSaveable { mutableStateOf("list") }
        var builtinBusy by remember { mutableStateOf(false) }
        var builtinError by remember { mutableStateOf<String?>(null) }
        requested?.let {
            openBot.value = null
            screen = "chat:$it"
        }
        BackHandler(screen != "list") { screen = parentOf(screen) }

        AnimatedContent(
            screen,
            transitionSpec = {
                val forward = targetState != "list"
                (slideInHorizontally(Motion.offset) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.effects()))
                    .togetherWith(slideOutHorizontally(Motion.offset) { if (forward) -it / 4 else it / 4 } + fadeOut(Motion.effects()))
            },
            label = "nav",
        ) { target ->
            when {
                target.startsWith("chat:") || target.startsWith("thread:") -> {
                    val parts = target.split(':')
                    val id = parts[1]
                    val rootId = parts.getOrNull(2)
                    val bot = bots[id]
                    val root = rootId?.let { r -> entries[id]?.firstOrNull { it.id == r } }
                    if (bot == null || (rootId != null && root == null)) {
                        screen = if (bot == null) "list" else "chat:$id"
                        return@AnimatedContent
                    }
                    var draft by rememberSaveable(target) { mutableStateOf("") }
                    var files by remember(target) { mutableStateOf(listOf<PickedFile>()) }
                    val integrations by store.integrations.collectAsState()
                    val threads by store.threads.collectAsState()
                    val images by store.files.collectAsState()
                    LaunchedEffect(target) {
                        chatPicked.value = emptyList()
                        chatPicked.collect { picked ->
                            if (picked.isNotEmpty()) {
                                files = files + picked
                                chatPicked.value = emptyList()
                            }
                        }
                    }
                    LaunchedEffect(bot.task != null) { if (bot.task != null) store.loadIntegrations() }
                    LaunchedEffect(target, synced) { if (rootId != null) store.openThread(id, rootId) else store.openChat(id) }
                    ChatScreen(
                        bot, if (rootId != null) threads[rootId].orEmpty() else entries[id].orEmpty(), draft, { draft = it },
                        onBack = { screen = parentOf(target) },
                        onSend = {
                            val text = draft
                            val sending = files
                            draft = ""
                            files = emptyList()
                            scope.launch {
                                // A failed message with files gives them back to the composer for another try.
                                runCatching { store.send(id, text, rootId, sending) }.onFailure { if (sending.isNotEmpty()) files = sending + files }
                            }
                        },
                        onStop = { scope.launch { runCatching { store.stop(id) } } },
                        onRespond = { entry, option -> scope.launch { runCatching { store.respond(entry.id, option) } } },
                        onUndo = { entry -> entry.data.checkpoint?.let { cp -> scope.launch { runCatching { store.rollback(id, cp) } } } },
                        onRollback = { cp -> scope.launch { runCatching { store.rollback(id, cp.id) } } },
                        onFinishTask = { o -> scope.launch { runCatching { store.finishTask(id, o.openPr, o.notify, o.learning) } } },
                        onSaveCheckpoint = { scope.launch { runCatching { store.saveCheckpoint(id) } } },
                        onOpenLink = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        integrations = integrations,
                        you = you,
                        bots = bots,
                        threadRoot = root,
                        composer = ComposerFiles(files, canAttach = !bot.isGroup && you?.canAct != false, online = linkState is LinkState.Ready),
                        onAttach = { kind ->
                            when (kind) {
                                AttachKind.Photos -> pickChatMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                AttachKind.Files -> pickChatFiles.launch(arrayOf("*/*"))
                                AttachKind.Paste -> clipboardImage()?.let { files = files + it }
                            }
                        },
                        onRemoveFile = { i -> files = files.filterIndexed { j, _ -> j != i } },
                        images = images,
                        onLoadAttachment = { a -> scope.launch { store.loadAttachment(id, a) } },
                        onOpenFile = { a -> scope.launch { runCatching { openFile(a, store.fileBytes(id, a)) } } },
                        onOpenThread = { e -> screen = "thread:$id:${e.id}" },
                        onReact = { e, emoji -> scope.launch { runCatching { store.react(e.id, emoji) } } },
                        onEdit = if (you?.canAct != false) ({ screen = "edit:$id" }) else null,
                    )
                }
                target == "bot:new" || target == "group:new" || target.startsWith("edit:") -> {
                    val hello by store.hello.collectAsState()
                    val policies by store.policies.collectAsState()
                    val editing = if (target.startsWith("edit:")) bots[target.removePrefix("edit:")] else null
                    var form by remember(target) {
                        mutableStateOf(when {
                            editing != null -> BotEditorState.of(editing)
                            target == "group:new" -> BotEditorState.newGroup()
                            else -> BotEditorState()
                        })
                    }
                    LaunchedEffect(target) {
                        store.refreshHello()
                        store.loadPolicies()
                    }
                    val usable = usableBackends(hello?.backends.orEmpty(), policies?.policies?.allowedAgents.orEmpty())
                    LaunchedEffect(usable) {
                        if (form.isNew && !form.group && form.backend == null) usable.singleOrNull()?.let { form = form.copy(backend = it.id) }
                    }
                    BotEditorScreen(
                        form, usable,
                        agentBots = bots.values.filter { !it.isGroup && !it.hidden }.sortedBy { it.name.lowercase() },
                        onChange = { form = it },
                        onSave = {
                            val body = form.toJson(editing)
                            form = form.copy(busy = true, error = null)
                            scope.launch {
                                try {
                                    val saved = if (editing == null) store.createBot(body) else store.updateBot(body)
                                    screen = "chat:${saved.id}"
                                } catch (e: Exception) {
                                    form = form.copy(busy = false, error = e.message ?: "Couldn't save.")
                                }
                            }
                        },
                        onDelete = {
                            val id = editing?.id ?: return@BotEditorScreen
                            scope.launch {
                                try {
                                    store.deleteBot(id)
                                    screen = "list"
                                } catch (e: Exception) {
                                    form = form.copy(confirmDelete = false, error = e.message ?: "Couldn't delete.")
                                }
                            }
                        },
                        onBack = { screen = parentOf(target) },
                        onBrowse = { path ->
                            scope.launch {
                                runCatching { store.listDirs(path) }
                                    .onSuccess { form = form.copy(browsing = it, error = null) }
                                    .onFailure { form = form.copy(error = it.message ?: "Couldn't list folders.") }
                            }
                        },
                    )
                }
                target == "screen" -> ScreenRoute(store, current.name) { screen = "list" }
                target == "newtask" -> {
                    val setup by store.setup.collectAsState()
                    var task by remember { mutableStateOf(NewTaskUiState()) }
                    LaunchedEffect(Unit) { store.loadSetup() }
                    // Ask the host for its pick as the user types (debounced).
                    LaunchedEffect(task.goal, task.template, task.issue, setup) {
                        if (task.routeText.isBlank() && task.template == null) return@LaunchedEffect
                        delay(500)
                        runCatching { store.route(task.routeText, task.template) }.getOrNull()?.let { task = task.copy(route = it) }
                    }
                    val file by picked.collectAsState()
                    file?.let {
                        picked.value = null
                        task = task.copy(files = task.files + it, error = null)
                    }
                    NewTaskScreen(
                        task.copy(setup = setup),
                        onChange = { task = it },
                        onStart = {
                            task = task.copy(busy = true, error = null)
                            scope.launch {
                                try {
                                    val botId = store.startTask(task.goal, task.template, task.input, task.projectPath, task.agentId,
                                        task.files, task.issue)
                                    screen = "chat:$botId"
                                } catch (e: Exception) {
                                    task = task.copy(busy = false, error = e.message ?: "Couldn't start the task.")
                                }
                            }
                        },
                        onBack = { screen = "list" },
                        onAddImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onPaste = {
                            // An image on the clipboard becomes an attachment; text goes where the error belongs.
                            val item = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                            val uri = item?.uri
                            val pasted = uri?.takeIf { contentResolver.getType(it)?.startsWith("image/") == true }?.let { readFile(it, "pasted.png") }
                            if (pasted != null) {
                                task = task.copy(files = task.files + pasted)
                            } else {
                                val text = item?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                                if (text.isNotEmpty()) task = if (task.selectedTemplate?.inputLabel != null || task.goal.isNotBlank())
                                    task.copy(input = listOf(task.input, text).filter { it.isNotBlank() }.joinToString("\n"))
                                else task.copy(goal = text)
                            }
                        },
                        onPickIssue = {
                            task = task.copy(pickingIssue = true, issues = null)
                            scope.launch {
                                val list = runCatching { store.issues() }.getOrElse {
                                    com.ragul84.hypurr.model.IssueList(errors = listOf(com.ragul84.hypurr.model.IssueError(message = it.message ?: "Couldn't load issues.")))
                                }
                                task = task.copy(issues = list)
                            }
                        },
                    )
                }
                target == "settings" -> {
                    val theme by store.themeMode.collectAsState()
                    val dynamic by store.dynamicColor.collectAsState()
                    val notify by store.notifications.collectAsState()
                    val setup by store.setup.collectAsState()
                    val integrations by store.integrations.collectAsState()
                    val costs by store.costs.collectAsState()
                    var tests by remember { mutableStateOf(mapOf<String, String>()) }
                    LaunchedEffect(Unit) {
                        store.loadSetup()
                        store.loadIntegrations()
                        store.loadCosts()
                    }
                    SettingsScreen(
                        SettingsUiState(current, linkState, theme, dynamic, notify, app.pushAvailable, BuildConfig.VERSION_NAME,
                            safety = setup?.safety, customTemplates = setup?.templates.orEmpty().filter { !it.builtin },
                            integrations = integrations, costs = costs, testResults = tests, you = you),
                        onTeamAdmin = { screen = "team" },
                        onBack = { screen = "list" },
                        onTheme = store::setTheme,
                        onDynamic = store::setDynamicColor,
                        onNotifications = store::setNotifications,
                        onForget = {
                            scope.launch {
                                store.forget()
                                screen = "list"
                            }
                        },
                        onSafety = { scope.launch { runCatching { store.setSafety(it) } } },
                        onAddTemplate = { title, prompt -> scope.launch { runCatching { store.saveTemplate(TaskTemplate(title = title, prompt = prompt)) } } },
                        onDeleteTemplate = { scope.launch { runCatching { store.deleteTemplate(it) } } },
                        onIntegrations = { patch ->
                            scope.launch {
                                runCatching { store.setIntegrations(patch) }.onFailure { e ->
                                    patch.keys.firstOrNull()?.let { tests = tests + (it to (e.message ?: "Couldn't save.")) }
                                }
                            }
                        },
                        onTestIntegration = { kind ->
                            tests = tests + (kind to "Testing…")
                            scope.launch {
                                val result = runCatching { store.testIntegration(kind) }.getOrElse { it.message ?: "Test failed." }
                                tests = tests + (kind to result)
                            }
                        },
                    )
                }
                target == "team" -> {
                    val team by store.team.collectAsState()
                    val policies by store.policies.collectAsState()
                    val activity by store.activity.collectAsState()
                    val audit by store.audit.collectAsState()
                    var tab by rememberSaveable { mutableStateOf(AdminTab.Activity) }
                    var error by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) { store.loadAdmin() }
                    fun save(block: suspend () -> Unit) {
                        error = null
                        scope.launch { runCatching { block() }.onFailure { error = it.message ?: "Couldn't save." } }
                    }
                    BackHandler { screen = "settings" }
                    TeamAdminScreen(
                        TeamAdminUiState(you, team, policies, activity, audit, tab, error),
                        onBack = { screen = "settings" },
                        onTab = {
                            tab = it
                            scope.launch { store.loadAdmin() }
                        },
                        onPolicies = { patch -> save { store.setPolicies(patch) } },
                        onRole = { key, role -> save { store.setRole(key, role) } },
                        onDefaultRole = { role -> save { store.setDefaultRole(role) } },
                    )
                }
                else -> {
                    val hello by store.hello.collectAsState()
                    val builtin = hello?.builtinAgent
                    val prompt = if (you?.canAct != false && builtin != null && (builtin.needsInstall || (!builtin.installed && builtin.available))) {
                        BuiltinInstallPrompt(busy = builtinBusy, error = builtinError, consent = builtin.consent ?: BuiltinInstallPrompt().consent)
                    } else null
                    BotListScreen(
                        current.name, linkState, rosterOrder(bots.values), synced,
                        onOpen = { screen = "chat:${it.id}" },
                        onSettings = { screen = "settings" },
                        onRetry = store::reconnect,
                        onNewTask = { screen = "newtask" },
                        byId = bots,
                        onNewBot = { screen = "bot:new" },
                        onNewGroup = { screen = "group:new" },
                        canCreate = you?.canAct != false,
                        onScreen = if (hello?.screen?.enabled == true && you?.canAct != false) ({ screen = "screen" }) else null,
                        builtinInstall = prompt,
                        onInstallBuiltin = {
                            builtinBusy = true; builtinError = null
                            lifecycleScope.launch {
                                runCatching { store.installBuiltinAgent() }
                                    .onFailure { builtinError = it.message ?: "Couldn't install Hypurr Agent" }
                                builtinBusy = false
                            }
                        },
                    )
                }
            }
        }
    }

    /** Where Back goes: a thread to its chat, a bot's settings to its chat, everything else to the list. */
    private fun parentOf(screen: String): String = when {
        screen.startsWith("thread:") -> "chat:" + screen.split(':')[1]
        screen.startsWith("edit:") -> "chat:" + screen.removePrefix("edit:")
        screen == "team" -> "settings"
        else -> "list"
    }

    /** The computer's screen over WebRTC; the session lives as long as this screen is open. */
    @Composable
    private fun ScreenRoute(store: HypurrStore, name: String, onBack: () -> Unit) {
        val client = store.client
        var status by remember { mutableStateOf<ScreenState?>(null) }
        var frame by remember { mutableStateOf(IntSize.Zero) }
        var session by remember { mutableStateOf<ScreenSession?>(null) }
        val idle = remember { MutableStateFlow<ScreenPhase>(ScreenPhase.Connecting) }
        fun begin() {
            frame = IntSize.Zero
            lifecycleScope.launch {
                val st = runCatching { client?.screenStatus() }.getOrNull()
                status = st ?: ScreenState()
                session?.release()
                session = null
                if (client != null && st?.ready == true) {
                    val display = st.displays.firstOrNull { it.main } ?: st.displays.firstOrNull()
                    session = ScreenSession(this@MainActivity, client, store.route == Route.Relay, lifecycleScope, display).also { it.start() }
                }
            }
        }
        LaunchedEffect(Unit) { begin() }
        DisposableEffect(Unit) { onDispose { session?.release() } }
        BackHandler(onBack = onBack)
        val s = session
        val phase by (s?.phase ?: idle).collectAsState()
        val clipboard by (s?.remoteClipboard ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
        val error by (s?.lastError ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
        ScreenScreen(
            ScreenUiState(name, status, phase, frame, clipboard, error),
            onBack = onBack,
            onRetry = ::begin,
            onInput = { s?.send(it) },
            onTakeClipboard = {
                clipboard?.let { getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Computer", it)) }
                s?.remoteClipboard?.value = null
            },
        ) { modifier ->
            if (s != null) key(s) {
                val track by s.track.collectAsState()
                var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
                AndroidView(factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(s.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() = Unit
                            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                                post { frame = if (rotation % 180 == 0) IntSize(w, h) else IntSize(h, w) }
                            }
                        })
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setEnableHardwareScaler(true)
                        renderer = this
                    }
                }, modifier = modifier, onRelease = { it.release() })
                DisposableEffect(track, renderer) {
                    val r = renderer
                    val t = track
                    if (r != null) t?.addSink(r)
                    onDispose { if (r != null) t?.removeSink(r) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_BOT = "botId"
        const val MAX_FILE = 100 * 1024 * 1024
    }
}
