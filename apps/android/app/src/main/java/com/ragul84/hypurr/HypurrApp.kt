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
        FirebaseApp.getApps(this).firstOrNull()?.options?.projectId?.let { it != PLACEHOLDER_PROJECT } ?: false
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
