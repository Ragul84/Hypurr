package com.ragul84.hypurr.voice

import java.util.Locale

/**
 * Indian-language voice: Android's own SpeechRecognizer and TextToSpeech, on the phone, free,
 * no Hypurr server. These helpers are pure so they can be unit tested.
 */
enum class VoiceLanguage(val tag: String, val label: String, val native: String) {
    EnglishIndia("en-IN", "English (India)", "English"),
    Hindi("hi-IN", "Hindi", "हिन्दी"),
    Tamil("ta-IN", "Tamil", "தமிழ்"),
    Telugu("te-IN", "Telugu", "తెలుగు"),
    Kannada("kn-IN", "Kannada", "ಕನ್ನಡ"),
    Malayalam("ml-IN", "Malayalam", "മലയാളം"),
    Marathi("mr-IN", "Marathi", "मराठी"),
    Bengali("bn-IN", "Bengali", "বাংলা");

    val locale: Locale get() = Locale.forLanguageTag(tag)
    val isEnglish: Boolean get() = this == EnglishIndia

    companion object {
        fun of(tag: String?): VoiceLanguage = entries.firstOrNull { it.tag.equals(tag, true) } ?: EnglishIndia
    }
}

/** What a spoken approval means. Risky actions still need a tap (see [voiceApprovalStep]). */
enum class VoiceAnswer { Allow, Deny, Unknown }

private val DenyWords = listOf(
    // English, Hindi/Marathi/Bengali, Tamil, Telugu, Kannada, Malayalam
    "no", "nope", "stop", "deny", "don't", "dont", "cancel", "reject",
    "nahi", "nahin", "nai", "mat", "ruko", "naka", "nako",
    "illa", "illai", "vendam", "venda", "venaam", "beda", "kadu", "vaddu", "vaddhu", "ledu",
    "नहीं", "नही", "मत", "रुको", "नको", "না",
    "இல்லை", "வேண்டாம்", "நிறுத்து", "వద్దు", "కాదు", "లేదు", "ಬೇಡ", "ಇಲ್ಲ", "വേണ്ട", "ഇല്ല", "നിർത്തൂ",
)
private val AllowWords = listOf(
    "yes", "yeah", "yep", "allow", "ok", "okay", "go ahead", "approve", "sure", "do it",
    "haan", "han", "haa", "ha", "ji", "theek", "thik", "chalo", "ho", "hoy", "hya",
    "aamaa", "aama", "amma", "aam", "sari", "seri", "avunu", "sare", "houdu", "howdu", "athe", "hyan", "hyaan",
    "हाँ", "हां", "हा", "ठीक", "होय", "हो", "হ্যাঁ", "হাঁ", "ঠিক",
    "ஆமா", "ஆமாம்", "சரி", "అవును", "సరే", "ಹೌದು", "ಸರಿ", "അതെ", "ശരി",
)

private fun words(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}' ]"), " ")
    .split(' ').filter { it.isNotBlank() }

private fun has(text: String, list: List<String>): Boolean {
    val w = words(text)
    val joined = " " + w.joinToString(" ") + " "
    return list.any { k -> if (' ' in k) " $k " in joined else k in w }
}

/** Deny wins over allow ("no, don't allow" is a no). Anything unclear is Unknown, never Allow. */
fun parseVoiceAnswer(text: String): VoiceAnswer = when {
    text.isBlank() -> VoiceAnswer.Unknown
    has(text, DenyWords) -> VoiceAnswer.Deny
    has(text, AllowWords) -> VoiceAnswer.Allow
    else -> VoiceAnswer.Unknown
}

/** What the card does after hearing an answer. */
enum class VoiceStep { SendAllow, SendDeny, ConfirmOnScreen, AskAgain }

/**
 * A spoken "yes" sends only for low-risk actions. Medium, high or unknown risk shows an
 * on-screen confirm: voice alone never approves a risky action. A spoken "no" always sends
 * (denying is safe).
 */
fun voiceApprovalStep(answer: VoiceAnswer, risk: String?): VoiceStep = when (answer) {
    VoiceAnswer.Deny -> VoiceStep.SendDeny
    VoiceAnswer.Unknown -> VoiceStep.AskAgain
    VoiceAnswer.Allow -> if (risk == "low") VoiceStep.SendAllow else VoiceStep.ConfirmOnScreen
}

/**
 * The note added to a task's prompt: code always in English; explanations in the user's language
 * only when they turned on "Explain in my language". Null when nothing needs saying.
 */
fun voiceTaskNote(language: VoiceLanguage, explainInMyLanguage: Boolean, spoken: Boolean): String? {
    if (language.isEnglish) return if (spoken) "(I said this by voice, so expect small transcription mistakes.)" else null
    val said = if (spoken) "I said this by voice in ${language.label}, so expect small transcription mistakes. " else ""
    return if (explainInMyLanguage) {
        "($said" + "Write all code, file names, identifiers, comments and commit messages in English. " +
            "Explain your steps, questions and the final summary to me in ${language.label} (${language.native}), in simple words.)"
    } else if (spoken) {
        "($said" + "Reply in English, and write code in English.)"
    } else null
}

/** The goal as sent to the agent. */
fun withVoiceNote(goal: String, note: String?): String = if (note == null || goal.isBlank()) goal else "$goal\n\n$note"

/** Friendly words for a recognizer error code (SpeechRecognizer.ERROR_*). */
fun voiceErrorMessage(code: Int, language: VoiceLanguage): String = when (code) {
    // ERROR_LANGUAGE_NOT_SUPPORTED (12), ERROR_LANGUAGE_UNAVAILABLE (13)
    12, 13 -> "${language.label} voice isn't on this phone yet. Add it in Android Settings › System › Languages › Speech, or pick another language in You."
    9 -> "Hypurr needs the microphone to hear you. Allow it in Android settings."
    6, 7 -> "Didn't catch that. Tap the mic and try again."
    1, 2, 4 -> "Voice needs a connection on this phone right now. Try again, or type it."
    else -> "Voice didn't work this time. Tap the mic and try again, or type it."
}

const val NoRecognizerMessage = "This phone has no speech recognizer. You can still type."
