package com.ragul84.hypurr.model

import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.usableBackends
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real host output (docs/reference/fixtures/chat-wire.json, from host/tests/chat_e2e.rs) decodes as the chat needs. */
class ChatWireTest {
    private val wire = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.chatWire")!!).readText()).jsonObject
    private inline fun <reified T> get(key: String): T = HypurrJson.decodeFromJsonElement(wire.getValue(key))

    @Test
    fun groupsAndAuthors() {
        val bot = get<Bot>("bot")
        val group = get<Bot>("group")
        assertTrue(group.isGroup)
        assertFalse(bot.isGroup)
        assertEquals(bot.id, group.members.first())
        assertEquals("Ship the shop", group.description)
        val reply = get<Entry>("groupReply")
        assertEquals(group.id, reply.botId)
        assertEquals(bot.id, reply.data.author)
        assertTrue(reply.isChat)
    }

    @Test
    fun threadsAndReactions() {
        val root = get<Entry>("root")
        val thread = get<List<Entry>>("thread")
        assertNull(root.threadId)
        assertEquals(2, root.data.thread!!.count)
        assertEquals(listOf("user", root.data.author), root.data.thread!!.authors)
        assertEquals(0, root.data.thread!!.unread)
        assertEquals(listOf("👍"), root.data.reactions)
        assertTrue(thread.all { it.threadId == root.id })
        assertEquals(listOf("user", "agent"), thread.map { it.kind })
    }

    @Test
    fun filesAndFolders() {
        val sent = get<Entry>("fileMessage")
        val a = sent.data.attachments!!.single()
        assertEquals("shot.png", a.name)
        assertTrue(a.isImage)
        assertEquals("1 KB", sizeLabel(a.size))
        assertEquals("999 B", sizeLabel(999))
        assertEquals("1.5 MB", sizeLabel(1_500_000))
        val back = wire.getValue("readUpload").jsonObject
        assertEquals(1000, java.util.Base64.getDecoder().decode(back.getValue("data").jsonPrimitive.content).size)
        val dirs = get<DirListing>("dirs")
        assertEquals("/Users/priya", dirs.path)
        assertTrue(dirs.dirs.any { it.name == "alice" && !it.isGit })
    }

    @Test
    fun newBotAndGroupBodies() {
        val backends = get<List<Backend>>("backends")
        assertEquals(listOf("claude"), usableBackends(backends, listOf("claude")).map { it.id })
        assertEquals(2, usableBackends(backends, emptyList()).size)

        val bot = BotEditorState(backend = "claude", name = " Reviewer ", permission = "auto").toJson()
        assertEquals("claude", bot.str("backend"))
        assertEquals("Reviewer", bot.str("name"))
        assertEquals("", bot.str("cwd"))
        assertEquals("auto", bot.str("permission"))
        assertNull(bot["id"])
        assertNull(bot["kind"])

        val group = get<Bot>("group")
        val edit = BotEditorState.of(group).copy(name = "Shop crew 2")
        assertTrue(edit.group)
        val patch = edit.toJson(group)
        assertEquals(group.id, patch.str("id"))
        assertNull(patch["kind"])
        assertEquals(group.members, patch.getValue("members").jsonArray.map { it.jsonPrimitive.content })
        val fresh = BotEditorState.newGroup().copy(name = "Room", members = group.members).toJson()
        assertEquals("group", fresh.str("kind"))
        assertFalse(BotEditorState.newGroup().copy(name = "Room").canSave)

        val existing = get<Bot>("bot")
        val rename = BotEditorState.of(existing).copy(name = "Alicia").toJson(existing)
        assertEquals("Alicia", rename.str("name"))
        assertNull("the folder stays", rename["cwd"])
        assertNull("the agent didn't change", rename["backend"])
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}
