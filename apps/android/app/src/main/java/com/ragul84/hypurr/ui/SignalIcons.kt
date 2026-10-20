package com.ragul84.hypurr.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.ragul84.hypurr.R

/**
 * Hypurr Signal icon set (tools/signal-icons/icons.py → res/drawable/ic_sg_*.xml):
 * 24 grid, 1.6 stroke, round caps and joins. No stock Material icons, no sparkle.
 */
object SignalIcons {
    val Bots get() = R.drawable.ic_sg_bots
    val Tasks get() = R.drawable.ic_sg_tasks
    val Spend get() = R.drawable.ic_sg_spend
    val You get() = R.drawable.ic_sg_you
    val Settings get() = R.drawable.ic_sg_settings
    val Plus get() = R.drawable.ic_sg_plus
    val Back get() = R.drawable.ic_sg_back
    val Chevron get() = R.drawable.ic_sg_chev
    val Send get() = R.drawable.ic_sg_send
    val Allow get() = R.drawable.ic_sg_allow
    val Deny get() = R.drawable.ic_sg_deny
    val Host get() = R.drawable.ic_sg_host
    val New get() = R.drawable.ic_sg_new
    val Qr get() = R.drawable.ic_sg_qr
    val Search get() = R.drawable.ic_sg_search
    val More get() = R.drawable.ic_sg_more
    val Team get() = R.drawable.ic_sg_team
    val Activity get() = R.drawable.ic_sg_activity
    val Credits get() = R.drawable.ic_sg_credits
    val NeedsYou get() = R.drawable.ic_sg_needs_you
    val Tests get() = R.drawable.ic_sg_tests
    val FixError get() = R.drawable.ic_sg_fix_error
    val Review get() = R.drawable.ic_sg_review
    val UpdateDeps get() = R.drawable.ic_sg_update_deps
    val Explain get() = R.drawable.ic_sg_explain
    val Docs get() = R.drawable.ic_sg_docs
    val File get() = R.drawable.ic_sg_file
    val Close get() = R.drawable.ic_sg_close
    val Stop get() = R.drawable.ic_sg_stop
    val Pause get() = R.drawable.ic_sg_pause
    val Paste get() = R.drawable.ic_sg_paste
    val Link get() = R.drawable.ic_sg_link
    val Check get() = R.drawable.ic_sg_check
    val Shield get() = R.drawable.ic_sg_shield
    val Folder get() = R.drawable.ic_sg_folder
    val Branch get() = R.drawable.ic_sg_branch
    val Image get() = R.drawable.ic_sg_image
    val Play get() = R.drawable.ic_sg_play
    val Refresh get() = R.drawable.ic_sg_refresh
    val Mic get() = R.drawable.ic_sg_mic
    val Trash get() = R.drawable.ic_sg_trash
    val Undo get() = R.drawable.ic_sg_undo
    val Clock get() = R.drawable.ic_sg_clock
    val Terminal get() = R.drawable.ic_sg_terminal
    val Trend get() = R.drawable.ic_sg_trend
    val Limit get() = R.drawable.ic_sg_limit
    val Bell get() = R.drawable.ic_sg_bell
    val Lock get() = R.drawable.ic_sg_lock
    val Sun get() = R.drawable.ic_sg_sun
    val Moon get() = R.drawable.ic_sg_moon
    val Trace get() = R.drawable.ic_sg_trace
    val Map get() = R.drawable.ic_sg_map
}

@Composable
fun signalIcon(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)

/** Template icon id → Signal drawable (never a sparkle). */
fun templateIconRes(id: String): Int = when (id) {
    "test" -> SignalIcons.Tests
    "bug" -> SignalIcons.FixError
    "review" -> SignalIcons.Review
    "deps" -> SignalIcons.UpdateDeps
    "explain" -> SignalIcons.Explain
    else -> SignalIcons.Docs
}
