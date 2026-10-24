package com.ragul84.hypurr.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceTest {
    @Test fun yesInEveryLanguageIsAllow() {
        listOf("yes", "Haan", "haan ji", "हाँ", "aamaa", "ஆமா", "allow it", "avunu", "ಹೌದು", "അതെ", "होय", "হ্যাঁ", "okay go ahead")
            .forEach { assertEquals(it, VoiceAnswer.Allow, parseVoiceAnswer(it)) }
    }

    @Test fun noInEveryLanguageIsDeny() {
        listOf("no", "Nahi", "नहीं", "illa", "இல்லை", "stop", "vaddu", "ಬೇಡ", "വേണ്ട", "नको", "না", "don't")
            .forEach { assertEquals(it, VoiceAnswer.Deny, parseVoiceAnswer(it)) }
    }

    @Test fun noWinsOverYesAndUnclearIsNeverAllow() {
        assertEquals(VoiceAnswer.Deny, parseVoiceAnswer("no don't allow"))
        assertEquals(VoiceAnswer.Deny, parseVoiceAnswer("haan... nahi nahi"))
        assertEquals(VoiceAnswer.Unknown, parseVoiceAnswer("what is this"))
        assertEquals(VoiceAnswer.Unknown, parseVoiceAnswer(""))
        // Substrings don't count: "know" is not "no", "shallow" is not "allow".
        assertEquals(VoiceAnswer.Unknown, parseVoiceAnswer("I know"))
        assertEquals(VoiceAnswer.Unknown, parseVoiceAnswer("shallow"))
    }

    @Test fun voiceNeverApprovesRiskyAlone() {
        assertEquals(VoiceStep.SendAllow, voiceApprovalStep(VoiceAnswer.Allow, "low"))
        assertEquals(VoiceStep.ConfirmOnScreen, voiceApprovalStep(VoiceAnswer.Allow, "medium"))
        assertEquals(VoiceStep.ConfirmOnScreen, voiceApprovalStep(VoiceAnswer.Allow, "high"))
        assertEquals(VoiceStep.ConfirmOnScreen, voiceApprovalStep(VoiceAnswer.Allow, null))
        assertEquals(VoiceStep.SendDeny, voiceApprovalStep(VoiceAnswer.Deny, "high"))
        assertEquals(VoiceStep.AskAgain, voiceApprovalStep(VoiceAnswer.Unknown, "low"))
    }

    @Test fun taskNoteKeepsCodeInEnglish() {
        val explain = voiceTaskNote(VoiceLanguage.Tamil, explainInMyLanguage = true, spoken = true)!!
        assertTrue(explain.contains("in English"))
        assertTrue(explain.contains("to me in Tamil"))
        val englishReply = voiceTaskNote(VoiceLanguage.Hindi, explainInMyLanguage = false, spoken = true)!!
        assertTrue(englishReply.contains("Reply in English"))
        assertFalse(englishReply.contains("to me in Hindi"))
        assertNull(voiceTaskNote(VoiceLanguage.Hindi, explainInMyLanguage = false, spoken = false))
        assertNull(voiceTaskNote(VoiceLanguage.EnglishIndia, explainInMyLanguage = true, spoken = false))
        assertEquals("Fix it\n\n(note)", withVoiceNote("Fix it", "(note)"))
        assertEquals("", withVoiceNote("", "(note)"))
    }

    @Test fun languagesAndMissingPacks() {
        assertEquals(8, VoiceLanguage.entries.size)
        assertEquals(VoiceLanguage.Kannada, VoiceLanguage.of("kn-IN"))
        assertEquals(VoiceLanguage.EnglishIndia, VoiceLanguage.of("xx"))
        assertTrue(voiceErrorMessage(13, VoiceLanguage.Malayalam).startsWith("Malayalam voice isn't on this phone"))
        assertTrue(voiceErrorMessage(12, VoiceLanguage.Bengali).contains("pick another language"))
    }
}
