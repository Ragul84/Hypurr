package com.ragul84.hypurr.ui

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

// ---- JSON helpers ----

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toLong()
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Gateway credits are US cents. */
fun dollars(cents: Long?): String = cents?.let { "$" + String.format(java.util.Locale.US, "%.2f", it / 100.0) } ?: "–"

// ---- OAuth (authorization code + PKCE, public client; no secret on the phone) ----

/** Where the company's identity provider sends the browser back to the app. */
const val WORK_REDIRECT = "hypurr://oauth/work"

data class SsoConfig(val provider: String, val clientId: String, val tenant: String?, val authorizeUrl: String, val tokenUrl: String) {
    companion object {
        fun from(o: JsonObject) = SsoConfig(
            o["provider"].str().orEmpty(), o["clientId"].str().orEmpty(), o["tenant"].str(),
            o["authorizeUrl"].str().orEmpty(), o["tokenUrl"].str().orEmpty(),
        )

        /** Endpoints for first-time setup, before the gateway knows the team. */
        fun forSetup(provider: String, clientId: String, tenant: String?) = if (provider == "microsoft") SsoConfig(
            provider, clientId, tenant,
            "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token",
        ) else SsoConfig(provider, clientId, null, "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token")
    }
}

/** One sign-in attempt: kept until the browser comes back with the code. */
data class WorkOAuthAttempt(val config: SsoConfig, val email: String, val state: String, val verifier: String, val setup: JsonObject? = null)

object WorkOAuth {
    private val rng = SecureRandom()
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun random() = b64(ByteArray(32).also(rng::nextBytes))
    fun challenge(verifier: String) = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun start(config: SsoConfig, email: String, setup: JsonObject? = null) = WorkOAuthAttempt(config, email.trim().lowercase(), random(), random(), setup)

    fun authorizeUrl(a: WorkOAuthAttempt): String {
        val domain = a.email.substringAfter('@')
        val params = buildList {
            add("client_id" to a.config.clientId)
            add("response_type" to "code")
            add("redirect_uri" to WORK_REDIRECT)
            add("scope" to "openid email profile")
            add("state" to a.state)
            add("code_challenge" to challenge(a.verifier))
            add("code_challenge_method" to "S256")
            add("login_hint" to a.email)
            add("prompt" to "select_account")
            if (a.config.provider == "google") add("hd" to domain)
        }
        return a.config.authorizeUrl + "?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** The code from `hypurr://oauth/work?code=…&state=…`, or the provider's error in plain words. */
    fun codeFrom(uri: android.net.Uri, a: WorkOAuthAttempt): String {
        uri.getQueryParameter("error")?.let { throw IllegalStateException(uri.getQueryParameter("error_description") ?: "Sign-in was cancelled.") }
        if (uri.getQueryParameter("state") != a.state) throw IllegalStateException("That sign-in didn't come from this app. Try again.")
        return uri.getQueryParameter("code") ?: throw IllegalStateException("Sign-in was cancelled.")
    }

    /** Trades the code for the ID token at the provider (PKCE proves it's the same app). */
    fun exchange(a: WorkOAuthAttempt, code: String, http: OkHttpClient = OkHttpClient()): String {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", WORK_REDIRECT)
            .add("client_id", a.config.clientId).add("code_verifier", a.verifier).build()
        http.newCall(Request.Builder().url(a.config.tokenUrl).post(body).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { com.ragul84.hypurr.model.HypurrJson.parseToJsonElement(text).obj() }.getOrNull()
            json?.get("id_token").str()?.let { return it }
            throw IllegalStateException(json?.get("error_description").str() ?: "Your company's sign-in didn't finish (${res.code}).")
        }
    }
}

// ---- UI ----

@Composable
private fun WField(value: String, hint: String, modifier: Modifier, onChange: (String) -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { com.ragul84.hypurr.ui.screens.SmallField(value, hint, onChange = onChange) }
}

/** On Spend: who pays (you or your company's team pool), and the way in or out. */
@Composable
fun WorkTeamCard(balance: JsonObject?, onSignIn: () -> Unit, onManage: () -> Unit, onSignOut: () -> Unit) {
    val c = Hypurr.colors
    val email = balance?.get("workEmail").str()
    val team = balance?.get("team").obj()
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (email == null) {
                Text("Work account", color = c.text, style = MaterialTheme.typography.titleSmall)
                Text("Sign in with your company's Google or Microsoft account and Hypurr is paid from your team's credits.",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
                SoftButton("Sign in with work account", Modifier.fillMaxWidth(), onClick = onSignIn)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paid by your team", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill("Work", c.text)
                }
                Text(email, color = c.secondary, style = MaterialTheme.typography.bodySmall)
                val limit = team?.get("monthlyLimitCents").long()
                val spent = team?.get("spentThisMonthCents").long() ?: 0
                Text(if (limit != null) "${dollars(spent)} of your ${dollars(limit)} this month" else "${dollars(spent)} this month · no limit",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Team", Modifier.weight(1f), onClick = onManage)
                    SoftButton("Sign out", Modifier.weight(1f), onClick = onSignOut)
                }
            }
        }
    }
}

/** Work email first: the gateway says which company sign-in to open. */
@Composable
fun WorkSignInForm(busy: Boolean, error: String?, onContinue: (String) -> Unit, onSetup: () -> Unit) {
    val c = Hypurr.colors
    var email by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sign in with work account", color = c.text, style = MaterialTheme.typography.titleMedium)
        Text("Use your company email. Your company's own sign-in page opens next.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        WField(email, "you@company.com", Modifier.fillMaxWidth()) { email = it }
        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton(if (busy) "Opening…" else "Continue", Modifier.fillMaxWidth(), enabled = !busy && email.contains('@')) { onContinue(email) }
        SoftButton("Set up Hypurr for my company", Modifier.fillMaxWidth(), onClick = onSetup)
    }
}

/** First-time setup by IT: the team and its sign-in settings. */
@Composable
fun TeamSetupForm(busy: Boolean, error: String?, onContinue: (name: String, email: String, provider: String, clientId: String, tenant: String?) -> Unit) {
    val c = Hypurr.colors
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(0) }
    var clientId by remember { mutableStateOf("") }
    var tenant by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set up your company", color = c.text, style = MaterialTheme.typography.titleMedium)
        Text("For IT: the OAuth client id comes from your company's Google or Microsoft admin console. You become the team's admin.",
            color = c.secondary, style = MaterialTheme.typography.bodySmall)
        WField(name, "Team name", Modifier.fillMaxWidth()) { name = it }
        WField(email, "Your work email", Modifier.fillMaxWidth()) { email = it }
        Segmented(listOf("Google", "Microsoft"), provider) { provider = it }
        WField(clientId, "OAuth client id", Modifier.fillMaxWidth()) { clientId = it }
        if (provider == 1) WField(tenant, "Tenant id", Modifier.fillMaxWidth()) { tenant = it }
        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
        val ready = name.isNotBlank() && email.contains('@') && clientId.isNotBlank() && (provider == 0 || tenant.isNotBlank())
        PrimaryButton(if (busy) "Opening…" else "Sign in and create team", Modifier.fillMaxWidth(), enabled = ready && !busy) {
            onContinue(name.trim(), email.trim(), if (provider == 0) "google" else "microsoft", clientId.trim(), tenant.trim().ifEmpty { null })
        }
    }
}

/** The company team: the pool and everyone's spend; admins set limits, pause people and fill the pool. */
@Composable
fun WorkTeamScreen(
    team: JsonObject?,
    error: String?,
    onBack: () -> Unit,
    onLimit: (userId: String, cents: Long?) -> Unit,
    onPause: (userId: String, paused: Boolean) -> Unit,
    onAdmin: (userId: String) -> Unit,
    onFillPool: (cents: Long) -> Unit,
) {
    val c = Hypurr.colors
    val info = team?.get("team").obj()
    val admin = team?.get("role").str() == "admin"
    var editing by remember { mutableStateOf<String?>(null) }
    var limitText by remember { mutableStateOf("") }
    var poolText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(8.dp))
            Text(info?.get("name").str() ?: "Team", style = MaterialTheme.typography.headlineSmall, color = c.text)
        }
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { e -> item { Text(e, color = c.danger, style = MaterialTheme.typography.bodySmall) } }
            if (team == null) item { Text("Loading…", color = c.secondary) }
            if (team != null && !admin) item {
                val limit = team["monthlyLimitCents"].long()
                Text("You're a member. ${dollars(team["spentThisMonthCents"].long() ?: 0)} used this month" +
                    (limit?.let { " of ${dollars(it)}" } ?: "") + ". Ask your admin for a higher limit.", color = c.secondary)
            }
            if (admin) {
                item {
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Eyebrow("Team credits")
                            Text(dollars(team?.get("poolCents").long()), color = c.text, style = MaterialTheme.typography.headlineMedium)
                            Text("Move credits you bought into the team pool. Everyone's use comes out of it.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                WField(poolText, "Amount in $", Modifier.weight(1f)) { poolText = it.filter { ch -> ch.isDigit() || ch == '.' } }
                                SoftButton("Add") {
                                    poolText.toDoubleOrNull()?.takeIf { it > 0 }?.let { onFillPool(Math.round(it * 100)); poolText = "" }
                                }
                            }
                        }
                    }
                }
                item { Eyebrow("People") }
                items(team?.get("members").arr(), key = { it["userId"].str().orEmpty() }) { m ->
                    val id = m["userId"].str().orEmpty()
                    val paused = m["status"].str() == "suspended"
                    val limit = m["monthlyLimitCents"].long()
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m["email"].str() ?: id, color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Pill(if (paused) "Paused" else if (m["role"].str() == "admin") "Admin" else "Member", if (paused) c.danger else c.text)
                            }
                            Text("${dollars(m["spentThisMonthCents"].long() ?: 0)} this month · " + (limit?.let { "limit ${dollars(it)}" } ?: "no limit"),
                                color = c.secondary, style = MaterialTheme.typography.bodySmall)
                            if (editing == id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    WField(limitText, "Monthly limit in $ (empty = none)", Modifier.weight(1f)) { limitText = it.filter { ch -> ch.isDigit() || ch == '.' } }
                                    SoftButton("Save") {
                                        onLimit(id, limitText.toDoubleOrNull()?.let { Math.round(it * 100) }); editing = null
                                    }
                                }
                            } else if (m["role"].str() != "admin") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SoftButton("Limit", Modifier.weight(1f)) { editing = id; limitText = limit?.let { (it / 100.0).toString() }.orEmpty() }
                                    SoftButton(if (paused) "Resume" else "Pause", Modifier.weight(1f)) { onPause(id, !paused) }
                                    SoftButton("Admin", Modifier.weight(1f)) { onAdmin(id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
