package com.ragul84.hypurr.screen

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Touches on the phone land on the right spot of the computer's display. */
class ScreenInputTest {
    @Test
    fun letterboxedVideoMapsToDisplayPoints() {
        // A 1920×1080 frame of a 1440×810 pt display, fitted into a 1080×2000 portrait view:
        // 1080×607.5 picture, 696.25 px of black above it.
        val centre = ScreenInput.toDisplay(540f, 1000f, 1080f, 2000f, 1920, 1080, 1440.0, 810.0)!!
        assertEquals(720.0, centre.x, 0.01)
        assertEquals(405.0, centre.y, 0.01)
        val corner = ScreenInput.toDisplay(1080f, 696.25f + 607.5f, 1080f, 2000f, 1920, 1080, 1440.0, 810.0)!!
        assertEquals(1440.0, corner.x, 0.01)
        assertEquals(810.0, corner.y, 0.01)
        assertNull(ScreenInput.toDisplay(540f, 100f, 1080f, 2000f, 1920, 1080, 1440.0, 810.0))
        assertNull(ScreenInput.toDisplay(1f, 1f, 1080f, 2000f, 0, 0, null, null))
    }

    @Test
    fun withoutDisplayInfoUsesFramePixels() {
        val p = ScreenInput.toDisplay(500f, 250f, 1000f, 500f, 2000, 1000, null, null)!!
        assertEquals(1000.0, p.x, 0.01)
        assertEquals(500.0, p.y, 0.01)
    }

    @Test
    fun eventsMatchTheHelpersProtocol() {
        val click = ScreenInput.click(DisplayPoint(10.0, 20.0), button = "right", count = 2)
        assertEquals("click", click["type"]!!.jsonPrimitive.content)
        assertEquals("right", click["button"]!!.jsonPrimitive.content)
        assertEquals(2, click["count"]!!.jsonPrimitive.int)
        assertEquals(20.0, click["y"]!!.jsonPrimitive.double, 0.0)
        val scroll = ScreenInput.scroll(DisplayPoint(1.0, 2.0), 0.0, -30.0)
        assertEquals("pixel", scroll["units"]!!.jsonPrimitive.content)
        assertEquals("delete", ScreenInput.key("delete")["key"]!!.jsonPrimitive.content)
        assertEquals("hi", ScreenInput.text("hi")["text"]!!.jsonPrimitive.content)
    }
}
