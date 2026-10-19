package com.ragul84.hypurr.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentNameTest {
    @Test fun legacyOpencodeIdShowsHypurrAgent() {
        assertEquals("Hypurr Agent", agentName("opencode"))
        assertEquals("Hypurr Agent", agentName("hypurr-agent"))
    }
}
