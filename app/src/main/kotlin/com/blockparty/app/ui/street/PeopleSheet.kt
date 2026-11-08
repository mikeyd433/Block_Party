package com.blockparty.app.ui.street

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blockparty.app.GameViewModel
import com.blockparty.app.ui.common.SpriteThumbnail
import com.blockparty.core.model.NpcProfile
import com.blockparty.core.model.TagLibrary
import com.blockparty.core.model.WorldState
import com.blockparty.core.npc.NpcPool

/** What a customer likes and can spend; assigned by the game, not the drawer. */
@Composable
fun CustomerDetails(npc: NpcProfile) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        val likes = npc.tagIds.mapNotNull { TagLibrary.get(it)?.label }
        Text("Likes: " + likes.joinToString(", ").ifBlank { "anything" }, style = MaterialTheme.typography.bodyMedium)
        Text("Budget: about ${npc.budgetCoins} coins", style = MaterialTheme.typography.bodyMedium)
    }
}

/** The street's customer pool: everyone who can walk by, plus a button to draw more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(world: WorldState, vm: GameViewModel, onDismiss: () -> Unit, onDraw: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<NpcProfile?>(null) }
    val starterIds = world.sprites.filter { it.isStarter }.map { it.id }.toSet()
    val drawnCount = world.npcs.count { it.spriteId !in starterIds }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("People on ${world.street.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    drawnCount == 0 -> "Only the starter crowd so far. Draw customers and they'll start walking the street."
                    drawnCount < NpcPool.STARTER_PHASE_OUT ->
                        "Your drawings are taking over from the starter crowd (${drawnCount}/${NpcPool.STARTER_PHASE_OUT})."
                    else -> "The street is all yours: only drawn customers walk by now."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onDraw) {
                Icon(Icons.Default.Brush, null); Spacer(Modifier.width(6.dp)); Text("Draw a customer")
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                // Player drawings first, newest first.
                val sorted = world.npcs.sortedWith(
                    compareBy<NpcProfile> { it.spriteId in starterIds }
                        .thenByDescending { world.sprite(it.spriteId)?.createdAtMillis ?: 0L }
                )
                items(sorted, key = { it.id }) { npc ->
                    val sprite = world.sprite(npc.spriteId)
                    val isStarter = npc.spriteId in starterIds
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpriteThumbnail(vm.sprites.images[npc.spriteId], Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(npc.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                npc.tagIds.mapNotNull { TagLibrary.get(it)?.label }.joinToString(", ") + " · ${npc.budgetCoins} coins",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                            Text(
                                if (isStarter && drawnCount >= NpcPool.STARTER_PHASE_OUT) "Starter set · retired" else creditLine(sprite),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!isStarter) {
                            IconButton(onClick = { confirmDelete = npc }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmDelete?.let { npc ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${npc.name}?") },
            text = { Text("Their drawing is deleted and they won't walk the street any more.") },
            confirmButton = { TextButton(onClick = { vm.deleteSprite(npc.spriteId); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
