package com.blockparty.app.ui.street

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blockparty.app.ui.theme.Paper
import com.blockparty.core.model.Business

/** The shop's numbers and what customers are saying. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopStatsSheet(business: Business, coins: Long, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(business.name.ifBlank { "My Shop" }, style = MaterialTheme.typography.titleLarge)
            val rating = business.rating
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stars(rating?.let { Math.round(it) } ?: 0),
                    color = Paper.Star, style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.padding(4.dp))
                Text(
                    if (rating == null) "No reviews yet" else "%.1f from %d review%s".format(
                        rating, business.reviews.size, if (business.reviews.size == 1) "" else "s",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Coins", "$coins", Modifier.weight(1f))
                Stat("Earned", "${business.revenueTotal}", Modifier.weight(1f))
                Stat("Customers", "${business.customersServed}", Modifier.weight(1f))
                Stat("Sold", "${business.itemsSold}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("Reviews", style = MaterialTheme.typography.titleMedium)
            if (business.reviews.isEmpty()) {
                Text(
                    "Customers leave reviews after they visit. Tags they like, fair prices and short queues earn more stars.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(business.reviews, key = { it.id }) { r ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stars(r.stars), color = Paper.Star)
                            Spacer(Modifier.padding(4.dp))
                            Text(r.npcName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun stars(n: Int) = "★".repeat(n.coerceIn(0, 5)) + "☆".repeat(5 - n.coerceIn(0, 5))
