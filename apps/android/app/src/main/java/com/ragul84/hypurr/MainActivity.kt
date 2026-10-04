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
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.model.TaskTemplate
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SettingsScreen
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
        var screen by rememberSaveable { mutableStateOf("list") }
        requested?.let {
            openBot.value = null
            screen = "chat:$it"
        }
        BackHandler(screen != "list") { screen = "list" }

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
                target.startsWith("chat:") -> {
                    val id = target.removePrefix("chat:")
                    val bot = bots[id]
                    if (bot == null) {
                        screen = "list"
                        return@AnimatedContent
                    }
                    var draft by rememberSaveable(id) { mutableStateOf("") }
                    val integrations by store.integrations.collectAsState()
                    LaunchedEffect(bot.task != null) { if (bot.task != null) store.loadIntegrations() }
                    androidx.compose.runtime.LaunchedEffect(id, synced) { store.openChat(id) }
                    ChatScreen(
                        bot, entries[id].orEmpty(), draft, { draft = it },
                        onBack = { screen = "list" },
                        onSend = {
                            val text = draft
                            draft = ""
                            scope.launch { runCatching { store.send(id, text) } }
                        },
                        onStop = { scope.launch { runCatching { store.stop(id) } } },
                        onRespond = { entry, option -> scope.launch { runCatching { store.respond(entry.id, option) } } },
                        onUndo = { entry -> entry.data.checkpoint?.let { cp -> scope.launch { runCatching { store.rollback(id, cp) } } } },
                        onRollback = { cp -> scope.launch { runCatching { store.rollback(id, cp.id) } } },
                        onFinishTask = { o -> scope.launch { runCatching { store.finishTask(id, o.openPr, o.notify, o.learning) } } },
                        onSaveCheckpoint = { scope.launch { runCatching { store.saveCheckpoint(id) } } },
                        onOpenLink = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        integrations = integrations,
                    )
                }
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
                            integrations = integrations, costs = costs, testResults = tests),
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
                else -> BotListScreen(
                    current.name, linkState, rosterOrder(bots.values), synced,
                    onOpen = { screen = "chat:${it.id}" },
                    onSettings = { screen = "settings" },
                    onRetry = store::reconnect,
                    onNewTask = { screen = "newtask" },
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOT = "botId"
        const val MAX_FILE = 100 * 1024 * 1024
    }
}
