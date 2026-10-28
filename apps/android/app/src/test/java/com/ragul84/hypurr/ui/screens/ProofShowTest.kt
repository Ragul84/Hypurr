package com.ragul84.hypurr.ui.screens

import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.Proof
import org.junit.Assert.assertEquals
import org.junit.Test

class ProofShowTest {
    private fun e(kind: String, turn: Long, tool: String? = null) =
        Entry(id = "$kind$turn$tool", botId = "b", kind = kind, turn = turn, data = EntryData(toolKind = tool))

    @Test fun readOnlyQuestionShowsNothing() {
        val chat = listOf(e("user", 1), e("tool", 1, "read"), e("agent", 1))
        assertEquals(ProofShow.None, proofShow(chat, null, working = false))
    }

    @Test fun turnThatEditedFilesOffersShowMe() {
        val chat = listOf(e("user", 1), e("tool", 1, "edit"), e("agent", 1))
        assertEquals(ProofShow.ShowMe, proofShow(chat, null, working = false))
        assertEquals(ProofShow.None, proofShow(chat, null, working = true))
    }

    @Test fun proofBelongsToItsTurn() {
        val made = Proof(status = "ready", turn = 1)
        val one = listOf(e("user", 1), e("tool", 1, "edit"), e("agent", 1))
        assertEquals(ProofShow.Card, proofShow(one, made, working = false))
        // A later read-only question hides it.
        assertEquals(ProofShow.None, proofShow(one + e("user", 2) + e("agent", 2), made, working = false))
        // A later edit offers a fresh Show me rather than the stale proof.
        assertEquals(ProofShow.ShowMe, proofShow(one + e("user", 2) + e("tool", 2, "edit"), made, working = false))
        assertEquals(ProofShow.Card, proofShow(one + e("user", 2), Proof(status = "running"), working = true))
    }
}
