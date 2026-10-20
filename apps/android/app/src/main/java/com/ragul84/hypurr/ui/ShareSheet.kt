package com.ragul84.hypurr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands text or a file to the system share sheet; the person picks the app. Hypurr sends nothing. */
object ShareSheet {
    fun file(ctx: Context, fileName: String, mime: String, body: String) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        existing(ctx, File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeText(body) }, mime)
    }

    fun existing(ctx: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copy(ctx: Context, label: String, body: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, body))
    }
}
