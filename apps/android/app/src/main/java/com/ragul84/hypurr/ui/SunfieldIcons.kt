package com.ragul84.hypurr.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import com.ragul84.hypurr.R

/** Custom Sunfield icon set — chunky 2.2dp stroke, no Material stock / no sparkles. */
object SunfieldIcons {
    val Bots get() = R.drawable.ic_sf_bots
    val Tasks get() = R.drawable.ic_sf_tasks
    val Spend get() = R.drawable.ic_sf_spend
    val You get() = R.drawable.ic_sf_you
    val Settings get() = R.drawable.ic_sf_settings
    val Plus get() = R.drawable.ic_sf_plus
    val Back get() = R.drawable.ic_sf_back
    val Send get() = R.drawable.ic_sf_send
    val Allow get() = R.drawable.ic_sf_allow
    val Deny get() = R.drawable.ic_sf_deny
    val Host get() = R.drawable.ic_sf_host
    val New get() = R.drawable.ic_sf_new
    val Qr get() = R.drawable.ic_sf_qr
    val Search get() = R.drawable.ic_sf_search
    val More get() = R.drawable.ic_sf_more
    val Team get() = R.drawable.ic_sf_team
    val Activity get() = R.drawable.ic_sf_activity
    val Credits get() = R.drawable.ic_sf_credits
    val NeedsYou get() = R.drawable.ic_sf_needs_you
    val Tests get() = R.drawable.ic_sf_tests
    val FixError get() = R.drawable.ic_sf_fix_error
    val Review get() = R.drawable.ic_sf_review
    val UpdateDeps get() = R.drawable.ic_sf_update_deps
    val Explain get() = R.drawable.ic_sf_explain
    val Docs get() = R.drawable.ic_sf_docs
    val Close get() = R.drawable.ic_sf_close
    val Stop get() = R.drawable.ic_sf_stop
    val Paste get() = R.drawable.ic_sf_paste
    val Link get() = R.drawable.ic_sf_link
    val Check get() = R.drawable.ic_sf_check
    val Shield get() = R.drawable.ic_sf_shield
    val Folder get() = R.drawable.ic_sf_folder
    val Image get() = R.drawable.ic_sf_image
    val Play get() = R.drawable.ic_sf_play
    val Refresh get() = R.drawable.ic_sf_refresh
}

@Composable
fun sunfieldPainter(@DrawableRes id: Int): Painter = painterResource(id)

@Composable
fun sunfieldVector(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)

/** Template icon id → Sunfield drawable (never AutoAwesome/sparkles). */
fun templateIconRes(id: String): Int = when (id) {
    "test" -> SunfieldIcons.Tests
    "bug" -> SunfieldIcons.FixError
    "review" -> SunfieldIcons.Review
    "deps" -> SunfieldIcons.UpdateDeps
    "explain" -> SunfieldIcons.Explain
    else -> SunfieldIcons.Docs
}
