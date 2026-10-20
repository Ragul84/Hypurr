package com.ragul84.hypurr.triage

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the text in a screenshot on the phone (ML Kit, Play services model). The image never
 * leaves the phone; only the text goes to the computer, which removes secrets before an agent sees it.
 */
object Ocr {
    suspend fun read(context: Context, uri: Uri): String? = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text.takeIf { t -> t.isNotBlank() }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }.getOrNull()
}
