package com.ragul84.hypurr

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.data.HypurrStore
import com.ragul84.hypurr.data.KeystoreSecrets
import com.ragul84.hypurr.data.Persistence
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.push.HypurrMessagingService
import com.ragul84.hypurr.push.LocalAlerts
import com.ragul84.hypurr.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class HypurrApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var identity: DeviceIdentity
        private set
    lateinit var store: HypurrStore
        private set
    private lateinit var registrar: PushRegistrar

    /** Firebase is set up with a real project (not the placeholder google-services.json). */
    val pushAvailable: Boolean by lazy {
        BuildConfig.PUSH_CONFIGURED &&
            (FirebaseApp.getApps(this).firstOrNull()?.options?.projectId?.let { it != PLACEHOLDER_PROJECT } ?: false)
    }

    val deviceName: String by lazy {
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    override fun onCreate() {
        super.onCreate()
        Pairing.allowLocalCloud = BuildConfig.DEBUG
        identity = DeviceIdentity.load(KeystoreSecrets(this))
        store = HypurrStore(scope, PrefsPersistence(this), identity, deviceName)
        registrar = PushRegistrar(BuildConfig.RELAY_URL, tokenSource = ::fcmToken)
        store.onConnected = { client -> registrar.register(client, deviceName, identity.pushKey) }
        registerActivityLifecycleCallbacks(foreground)
        store.onLocalAlert = { kind, bot ->
            if (!foreground.visible) {
                HypurrMessagingService.show(this, LocalAlerts.alert(kind, bot, store.computer.value?.name.orEmpty()))
            }
        }
        store.connect()
    }

    suspend fun registerPush() {
        val client = store.client ?: return
        if (store.notifications.value) runCatching { registrar.register(client, deviceName, identity.pushKey) }
    }

    private suspend fun fcmToken(): String? {
        if (!pushAvailable) return null
        return suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    private val foreground = Foreground()

    /** Whether any Hypurr screen is on top (local alerts only show when none is). */
    private class Foreground : ActivityLifecycleCallbacks {
        private var started = 0
        val visible: Boolean get() = started > 0
        override fun onActivityStarted(activity: android.app.Activity) { started++ }
        override fun onActivityStopped(activity: android.app.Activity) { started = maxOf(0, started - 1) }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    private class PrefsPersistence(context: Context) : Persistence {
        private val prefs = context.getSharedPreferences("hypurr", Context.MODE_PRIVATE)
        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    private companion object {
        const val PLACEHOLDER_PROJECT = "hypurr-placeholder"
    }
}
