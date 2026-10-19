package com.ragul84.hypurr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.ragul84.hypurr.net.FakeHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeHost(scope)
    private val identity = DeviceIdentity.load(MemorySecrets())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun profileAndFolderPersistLocallyAndCanBeDeleted() {
        val persistence = MemoryPersistence()
        val store = HypurrStore(scope, persistence, identity, "Pixel", host.dialer)
        assertFalse("first launch shows onboarding", store.userProfile.value.completed)

        val answers = UserProfile(completed = true, role = "Tester/QA", experience = "New", goals = listOf("Write tests"))
        store.setUserProfile(answers)
        store.setProjectFolder("/Users/priya/shop")

        val reopened = HypurrStore(scope, persistence, identity, "Pixel", host.dialer)
        assertEquals(answers, reopened.userProfile.value)
        assertEquals("/Users/priya/shop", reopened.projectFolder.value)

        reopened.clearUserProfile()
        val cleared = HypurrStore(scope, persistence, identity, "Pixel", host.dialer).userProfile.value
        assertTrue("deleting doesn't bring onboarding back", cleared.completed)
        assertFalse(cleared.hasAnswers)

        reopened.setProjectFolder(null)
        assertNull(HypurrStore(scope, persistence, identity, "Pixel", host.dialer).projectFolder.value)
    }

    @Test
    fun skippedProfileHasNoAnswers() {
        val p = UserProfile(completed = true, skipped = true)
        assertFalse(p.hasAnswers)
        assertEquals(listOf("a", "b"), p.preferTemplateIds(listOf("a", "b")))
    }

    @Test
    fun testerSeesTestTemplatesFirst() {
        val p = UserProfile(role = "Tester/QA")
        assertEquals("write-tests", p.preferTemplateIds(listOf("fix-error", "review-pr", "write-tests")).first())
    }

    @Test
    fun noSensitiveFields() {
        val names = UserProfile::class.java.declaredFields.map { it.name.lowercase() }
        listOf("employee", "company", "salary", "email", "phone").forEach { bad ->
            assertTrue("$bad must never be collected", names.none { it.contains(bad) })
        }
    }
}
