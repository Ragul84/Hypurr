package com.ragul84.hypurr.voice

import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallTest {
    private fun e(id: String, kind: String, data: EntryData) = Entry(id = id, botId = "b", kind = kind, data = data)

    @Test fun approvalsComeFirstThenTheNewestReply() {
        val reply = e("r1", "agent", EntryData(text = "Done. Tests pass.", final = true))
        val ask = e("p1", "permission", EntryData(title = "run npm test", status = "pending"))
        assertEquals(CallCue.Ask(ask), callCue(listOf(reply, ask), emptySet(), working = true))
        assertEquals(CallCue.Say("say:r1", "Done. Tests pass."), callCue(listOf(reply, ask), setOf("ask:p1"), working = false))
        assertEquals(CallCue.Listen, callCue(listOf(reply), setOf("say:r1"), working = false))
        assertEquals(CallCue.Wait, callCue(listOf(reply), setOf("say:r1"), working = true))
    }

    @Test fun halfWrittenRepliesAreNotRead() {
        val partial = e("r2", "agent", EntryData(text = "Working on", final = false))
        assertEquals(CallCue.Listen, callCue(listOf(partial), emptySet(), working = false))
    }

    @Test fun endWordsAndLines() {
        assertTrue(isEndOfCall("okay end call now"))
        assertTrue(isEndOfCall("Hang up"))
        assertFalse(isEndOfCall("add a call button"))
        assertEquals("It wants to run npm test. Say yes or no.", askLine(e("p", "permission", EntryData(title = "run npm test"))))
        assertEquals("1:05", callLength(65))
    }
}
