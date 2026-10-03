package com.ragul84.hypurr.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ragul84.hypurr.crypto.B64
import com.ragul84.hypurr.crypto.b64url
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets wrapped with a non-exportable AES key in the Android Keystore. The Keystore can't hold
 * Ed25519/X25519 keys on every supported API level, so the raw 32-byte keys are sealed with it instead
 * and the ciphertext lives in private app storage (excluded from backups).
 */
class KeystoreSecrets(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("hypurr.secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun get(name: String): ByteArray? {
        val stored = prefs.getString(name, null)?.let(B64::decode) ?: return null
        if (stored.size < 12 + 16) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored, 0, 12))
            c.updateAAD(name.toByteArray())
            c.doFinal(stored, 12, stored.size - 12)
        }.getOrNull()
    }

    override fun put(name: String, value: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val ct = c.doFinal(value)
        prefs.edit().putString(name, (c.iv + ct).b64url()).apply()
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private companion object {
        const val ALIAS = "com.ragul84.hypurr.secrets"
    }
}
