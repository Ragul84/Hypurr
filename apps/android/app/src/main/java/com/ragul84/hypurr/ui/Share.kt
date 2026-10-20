package com.ragul84.hypurr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands text or a file to the system share sheet. Nothing is sent by Hypurr: the person
 * picks the app and the recipient there.
 */
object Share {
    fun text(ctx: Context, subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        runCatching { ctx.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Writes [body] to the shared cache folder and offers it as a file (CSV, Markdown). */
    fun file(ctx: Context, fileName: String, mime: String, body: String) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeText(body) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, fileName).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copy(ctx: Context, label: String, body: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, body))
    }
}
