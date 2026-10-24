package com.ragul84.hypurr.push

import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.data.HypurrStore
import com.ragul84.hypurr.data.MemoryPersistence
import com.ragul84.hypurr.data.MemorySecrets
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.net.HostEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAlertsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun bot(status: String) = Bot(id = "b1", name = "Tutor fix", status = status)

    @Test
    fun statusChangesMapToTheThreeAlerts() {
        assertEquals(LocalAlerts.Kind.NeedsYou, LocalAlerts.detect(bot("working"), bot("needsInput")))
        assertEquals(LocalAlerts.Kind.Done, LocalAlerts.detect(bot("working"), bot("idle")))
        assertEquals(LocalAlerts.Kind.Failed, LocalAlerts.detect(bot("working"), bot("error")))
        assertNull(LocalAlerts.detect(null, bot("needsInput")))
        assertNull(LocalAlerts.detect(bot("idle"), bot("idle")))
        assertNull(LocalAlerts.detect(bot("idle"), bot("working")))
    }

    @Test
    fun alertTextIsPlainAndNamesTheComputer() {
        val a = LocalAlerts.alert(LocalAlerts.Kind.NeedsYou, bot("needsInput"), "raavan")
        assertEquals("Hypurr needs you on raavan", a.title)
        assertEquals("b1", a.botId)
        assertEquals("Task failed", LocalAlerts.alert(LocalAlerts.Kind.Failed, bot("error"), "").title)
    }

    @Test
    fun storeRaisesLocalAlertsOnlyWhenNotificationsAreOn() {
        val store = HypurrStore(scope, MemoryPersistence(), DeviceIdentity.load(MemorySecrets()), "Pixel")
        val seen = mutableListOf<LocalAlerts.Kind>()
        store.onLocalAlert = { kind, _ -> seen += kind }
        store.apply(HostEvent.BotChanged(bot("working")))
        store.apply(HostEvent.BotChanged(bot("needsInput")))
        store.apply(HostEvent.BotChanged(bot("working")))
        store.apply(HostEvent.BotChanged(bot("idle")))
        assertEquals(listOf(LocalAlerts.Kind.NeedsYou, LocalAlerts.Kind.Done), seen)
        store.notifications.value = false
        store.apply(HostEvent.BotChanged(bot("working")))
        store.apply(HostEvent.BotChanged(bot("error")))
        assertFalse(LocalAlerts.Kind.Failed in seen)
    }
}
