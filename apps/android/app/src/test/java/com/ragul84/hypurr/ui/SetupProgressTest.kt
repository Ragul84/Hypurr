package com.ragul84.hypurr.ui

import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.ui.screens.SetupProgress
import com.ragul84.hypurr.ui.screens.setupChecklist
import com.ragul84.hypurr.ui.screens.setupProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty-state checklist reads pairing and agent status from the computer, so it never asks twice. */
class SetupProgressTest {
    private val agent = Backend(id = "hypurr-agent", builtin = true, installed = false, available = true, needsInstall = true)
    private val paired = Hello(hostId = "h", name = "RAGUL-PC", os = "windows", builtinAgent = agent, backends = listOf(agent))

    @Test fun notPaired() = assertEquals(SetupProgress(false, false), setupProgress(null))

    @Test fun pairedButNoAgent() = assertEquals(SetupProgress(true, false), setupProgress(paired))

    @Test fun pairedAndInstalledIsReady() {
        val done = agent.copy(installed = true, needsInstall = false)
        val p = setupProgress(paired.copy(builtinAgent = done, backends = listOf(done)))
        assertTrue(p.ready)
        val lines = setupChecklist(p, windows = true, computerName = "RAGUL-PC")
        assertEquals("Paired with RAGUL-PC" to true, lines[0])
        assertTrue(lines[1].second)
        assertFalse(lines.any { it.first.startsWith("Install") })
    }

    @Test fun anotherAgentCountsAsReady() {
        val claude = Backend(id = "claude", installed = true, available = true)
        assertTrue(setupProgress(paired.copy(backends = listOf(agent, claude))).ready)
    }
}
