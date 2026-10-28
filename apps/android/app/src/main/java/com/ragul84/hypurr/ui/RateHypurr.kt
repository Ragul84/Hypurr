package com.ragul84.hypurr.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.play.core.review.ReviewManagerFactory
import com.ragul84.hypurr.ui.theme.Hypurr

/** The tiny card shown after a successful task. */
@Composable
fun RateHypurrCard(onRate: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Task done. Enjoying Hypurr?", color = c.text, style = MaterialTheme.typography.titleSmall)
            Text("A quick rating helps other people find it.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Not now", Modifier.weight(1f), onClick = onLater)
                PrimaryButton("Rate Hypurr", Modifier.weight(1f), onClick = onRate)
            }
        }
    }
}

/** Google Play's in-app review sheet; if Play can't show it, opens the store page. */
fun launchPlayReview(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnCompleteListener { req ->
        if (req.isSuccessful) {
            manager.launchReviewFlow(activity, req.result)
        } else {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")))
            }
        }
    }
}
