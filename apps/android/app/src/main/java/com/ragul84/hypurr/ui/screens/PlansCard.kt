package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Plan
import com.ragul84.hypurr.model.Pricing
import com.ragul84.hypurr.model.planPrice
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * The plans from pricing/plans.json, in rupees, exactly as configured. [current] marks the
 * plan in use. Payments are not live until keys are set up, and the card says so.
 */
@Composable
fun PlansCard(pricing: Pricing, current: String? = null, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    SignalCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Eyebrow("Plans · ${pricing.currency}")
            pricing.plans.forEachIndexed { i, p ->
                if (i > 0) HairlineDivider(Modifier.padding(vertical = 2.dp))
                PlanRow(p, p.id == current)
            }
            Text(pricing.gstNote, color = c.tertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
            if (!pricing.payments.ready) {
                Text("Billing isn't set up yet, so nothing can be charged.", color = c.tertiary,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun PlanRow(p: Plan, current: Boolean) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = c.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (current) Pill("Your plan", c.text, Modifier.padding(end = 8.dp))
            Text(planPrice(p), color = if (p.priceInr == null) c.tertiary else c.text, style = MaterialTheme.typography.titleSmall)
        }
        Text(p.summary, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        p.features.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Check), null, tint = c.tertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(f, color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
