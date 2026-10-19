package com.ragul84.hypurr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp

/** The Hypurr cat as a 9×9 dot matrix (docs/brand/cat-dots.svg, tools/signal-icons/dotcat.py). */
internal val CAT_BITMAP = listOf(
    "X.......X",
    "XX.....XX",
    "XXXXXXXXX",
    "XXXXXXXXX",
    "XX.XXX.XX",
    "XXXXXXXXX",
    "XXXX.XXXX",
    ".XXXXXXX.",
    "..XXXXX..",
)

/** Eye holes (row, col): filled briefly for a blink, or permanently for a happy squint. */
private val EYES = listOf(4 to 2, 4 to 6)

/** Deterministic scatter order for the assemble animation, 0..1 per dot. */
private fun order(x: Int, y: Int): Float {
    var h = x * 73856093 xor y * 19349663
    h = h xor (h ushr 13)
    h *= 0x5bd1e995
    return ((h ushr 8) and 0xFFFF) / 65535f
}

/**
 * Draws the dot cat.
 * @param reveal 0..1, dots fly in from a loose scatter to the grid (bot create, pull to refresh).
 * @param blink 0..1, fills the eye holes (closed eyes).
 * @param off colour for the unlit dots of the 9×9 grid (Nothing-style matrix); transparent hides them.
 */
fun DrawScope.drawDotCat(color: Color, off: Color = Color.Transparent, reveal: Float = 1f, blink: Float = 0f) {
    val s = size.minDimension
    val cell = s / 9f
    val r = cell * 0.36f
    val cx = s / 2f
    CAT_BITMAP.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch ->
            val home = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
            if (ch != 'X') {
                if (off.alpha > 0f) drawCircle(off, r, home)
                return@forEachIndexed
            }
            val local = if (reveal >= 1f) 1f else ((reveal - order(x, y) * 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            val away = (1f - local)
            val dir = Offset(home.x - cx, home.y - cx)
            val pos = home + dir * (away * 0.9f) + Offset(0f, -away * cell * 2f)
            drawCircle(color.copy(alpha = color.alpha * local), r * (0.55f + 0.45f * local), pos)
        }
    }
    if (blink > 0f) EYES.forEach { (y, x) ->
        drawCircle(color.copy(alpha = color.alpha * blink), r, Offset((x + 0.5f) * cell, (y + 0.5f) * cell))
    }
}

@Composable
fun DotCat(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    off: Color = Color.Transparent,
    reveal: Float = 1f,
    blink: Float = 0f,
) {
    Canvas(modifier.size(size)) { drawDotCat(color, off, reveal, blink) }
}
