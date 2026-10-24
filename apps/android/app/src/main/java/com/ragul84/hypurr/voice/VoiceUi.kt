package com.ragul84.hypurr.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat

/** The user's voice settings, provided once at the top of the app. */
data class VoiceSettings(val language: VoiceLanguage = VoiceLanguage.EnglishIndia, val explainInMyLanguage: Boolean = false)

val LocalVoiceSettings = compositionLocalOf { VoiceSettings() }

/** A mic that is listening or not, with the last friendly error. */
class VoiceInput internal constructor() {
    var listening by mutableStateOf(false)
        internal set
    var partial by mutableStateOf("")
        internal set
    var error by mutableStateOf<String?>(null)
    internal var startImpl: () -> Unit = {}
    internal var stopImpl: () -> Unit = {}
    fun start() = startImpl()
    fun stop() = stopImpl()
}

/**
 * Speech to text with Android's built-in recognizer in [language]. Asks for the mic the first
 * time. Phones without a recognizer or without the language pack get a plain message, never a crash.
 */
@Composable
fun rememberVoiceInput(language: VoiceLanguage, onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val input = remember { VoiceInput() }
    val result by rememberUpdatedState(onResult)
    val lang by rememberUpdatedState(language)
    if (LocalInspectionMode.current) return input
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun listen() {
        input.error = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            input.error = NoRecognizerMessage
            return
        }
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also { recognizer = it }
        if (r == null) { input.error = NoRecognizerMessage; return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { input.listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { input.listening = false }
            override fun onError(error: Int) {
                input.listening = false
                input.partial = ""
                input.error = voiceErrorMessage(error, lang)
            }
            override fun onResults(results: Bundle?) {
                input.listening = false
                input.partial = ""
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(result)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                input.partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        runCatching { r.startListening(intent); input.listening = true }
            .onFailure { input.listening = false; input.error = voiceErrorMessage(-1, lang) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else input.error = voiceErrorMessage(9, lang)
    }
    input.startImpl = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    input.stopImpl = { recognizer?.stopListening(); input.listening = false }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }
    return input
}

/** Read-aloud with Android TextToSpeech in the chosen language; falls back to English (India). */
class Speaker internal constructor(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, VoiceLanguage>? = null
    var speaking by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)

    fun speak(text: String, language: VoiceLanguage) {
        notice = null
        val engine = tts ?: TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) notice = "This phone can't read aloud right now."
            pending?.let { (t, l) -> pending = null; if (ready) say(t, l) }
        }.also { tts = it }
        if (ready) say(text, language) else pending = text to language
        if (engine !== tts) tts = engine
    }

    private fun say(text: String, language: VoiceLanguage) {
        val engine = tts ?: return
        val ok = engine.setLanguage(language.locale)
        if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) {
            notice = "${language.label} reading isn't on this phone, so it's in English. Add the voice in Android Settings › Text-to-speech."
            engine.setLanguage(VoiceLanguage.EnglishIndia.locale)
        }
        speaking = true
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { speaking = false }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { speaking = false }
        })
        engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, "hypurr-summary")
    }

    fun stop() { tts?.stop(); speaking = false }
    internal fun shutdown() { tts?.shutdown(); tts = null }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    return speaker
}
