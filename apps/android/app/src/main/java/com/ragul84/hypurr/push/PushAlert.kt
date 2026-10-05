package com.ragul84.hypurr.push

import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.model.HypurrJson
import kotlinx.serialization.Serializable

/** One alert as shown: the sealed title/body when they open, else the relay's generic line. */
data class PushAlert(
    val title: String,
    val subtitle: String?,
    val body: String,
    val botId: String,
    val threadId: String,
    /** done | needsInput | failed */
    val category: String?,
) {
    @Serializable
    private data class Secret(val title: String = "", val subtitle: String? = null, val body: String = "", val from: String? = null)

    companion object {
        /** `data` is the FCM data map the relay builds (relay/src/fcm.ts). */
        fun from(data: Map<String, String>, pushPrivate: ByteArray): PushAlert {
            val botId = data["botId"].orEmpty()
            val computerId = data["computerId"].orEmpty()
            val secret = data["sealed"]?.let { sealed ->
                runCatching {
                    HypurrJson.decodeFromString<Secret>(RelayCrypto.openPush(sealed, computerId, pushPrivate).decodeToString())
                }.getOrNull()
            }
            return PushAlert(
                title = secret?.title?.takeIf { it.isNotEmpty() } ?: data["title"] ?: "Hypurr",
                subtitle = secret?.subtitle,
                body = secret?.body ?: data["body"].orEmpty(),
                botId = botId,
                threadId = data["threadId"] ?: "$computerId:$botId",
                category = data["category"],
            )
        }
    }
}
