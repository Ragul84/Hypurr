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
import com.ragul84.hypurr.data.HypurrStore
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.ChatScreen
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
                    )
                }
                target == "settings" -> {
                    val theme by store.themeMode.collectAsState()
                    val dynamic by store.dynamicColor.collectAsState()
                    val notify by store.notifications.collectAsState()
                    SettingsScreen(
                        SettingsUiState(current, linkState, theme, dynamic, notify, app.pushAvailable, BuildConfig.VERSION_NAME),
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
                    )
                }
                else -> BotListScreen(
                    current.name, linkState, rosterOrder(bots.values), synced,
                    onOpen = { screen = "chat:${it.id}" },
                    onSettings = { screen = "settings" },
                    onRetry = store::reconnect,
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOT = "botId"
    }
}
