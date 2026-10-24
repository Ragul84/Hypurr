package com.ragul84.hypurr.ui

import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.ui.screens.breadcrumbs
import com.ragul84.hypurr.ui.screens.folderName
import com.ragul84.hypurr.ui.screens.isDriveRoot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderBrowserTest {
    @Test fun driveRoots() {
        assertTrue(isDriveRoot("C:\\"))
        assertTrue(isDriveRoot("D:"))
        assertTrue(isDriveRoot("/"))
        assertFalse(isDriveRoot("C:\\work"))
        assertFalse(isDriveRoot("/home/a"))
    }

    @Test fun windowsBreadcrumbs() {
        assertEquals(listOf("C:" to "C:\\", "work" to "C:\\work", "shop" to "C:\\work\\shop"), breadcrumbs("C:\\work\\shop"))
        assertEquals(listOf("C:" to "C:\\"), breadcrumbs("C:\\"))
    }

    @Test fun unixBreadcrumbs() {
        assertEquals(listOf("/" to "/", "home" to "/home", "a" to "/home/a"), breadcrumbs("/home/a"))
        assertEquals(emptyList<Pair<String, String>>(), breadcrumbs(""))
    }

    @Test fun folderNames() {
        assertEquals("shop", folderName("C:\\work\\shop"))
        assertEquals("shop", folderName("/home/a/shop/"))
    }

    @Test fun parsesProjectAndRootFlags() {
        val l = Json { ignoreUnknownKeys = true }.decodeFromString(DirListing.serializer(),
            """{"path":"C:\\","parent":"","isRoot":true,"dirs":[{"name":"shop","path":"C:\\shop","isProject":true}]}""")
        assertTrue(l.isRoot)
        assertTrue(l.dirs.single().isProject)
    }
}
