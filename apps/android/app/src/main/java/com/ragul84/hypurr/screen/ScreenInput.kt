package com.ragul84.hypurr.screen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A point on the computer's display, in its points (the coordinate space of screen input). */
data class DisplayPoint(val x: Double, val y: Double)

/**
 * Maps touches on the phone to the computer's display and builds the input events the screen helpers
 * read (the same JSON the iPhone's `ScreenCanvas` sends).
 */
object ScreenInput {
    /**
     * The video is fitted (letterboxed) into the view. Returns null for a touch outside the picture.
     * `frame` is the video's size in pixels; `display` the display's size in points (null = the frame's).
     */
    fun toDisplay(x: Float, y: Float, viewW: Float, viewH: Float, frameW: Int, frameH: Int,
                  displayW: Double?, displayH: Double?): DisplayPoint? {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) return null
        val scale = minOf(viewW / frameW, viewH / frameH)
        val w = frameW * scale
        val h = frameH * scale
        val left = (viewW - w) / 2
        val top = (viewH - h) / 2
        val u = (x - left) / w
        val v = (y - top) / h
        if (u < 0 || u > 1 || v < 0 || v > 1) return null
        val dw = displayW?.takeIf { it > 0 } ?: frameW.toDouble()
        val dh = displayH?.takeIf { it > 0 } ?: frameH.toDouble()
        return DisplayPoint(u * dw, v * dh)
    }

    fun click(p: DisplayPoint, button: String = "left", count: Int = 1): JsonObject = buildJsonObject {
        put("type", "click")
        put("x", p.x)
        put("y", p.y)
        put("button", button)
        put("count", count)
        put("modifiers", JsonArray(emptyList()))
    }

    fun move(p: DisplayPoint): JsonObject = buildJsonObject {
        put("type", "move")
        put("x", p.x)
        put("y", p.y)
    }

    /** Positive `dy` scrolls the content up (finger moving up), as on the iPhone. */
    fun scroll(p: DisplayPoint, dx: Double, dy: Double): JsonObject = buildJsonObject {
        put("type", "scroll")
        put("x", p.x)
        put("y", p.y)
        put("dx", dx)
        put("dy", dy)
        put("units", "pixel")
    }

    fun text(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    /** A key named like a Mac keyboard (`return`, `delete` = backspace, `tab`, `escape`, `left`…) with modifiers (`cmd`, `option`, `ctrl`, `shift`). */
    fun key(key: String, modifiers: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("type", "key")
        put("key", key)
        put("modifiers", JsonArray(modifiers.map(::JsonPrimitive)))
    }
}
