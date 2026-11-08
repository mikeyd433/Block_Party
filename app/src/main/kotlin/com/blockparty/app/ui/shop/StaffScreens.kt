package com.blockparty.app.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blockparty.app.DrawPurpose
import com.blockparty.app.GameViewModel
import com.blockparty.app.Screen
import com.blockparty.app.ui.common.SpriteThumbnail
import com.blockparty.app.ui.street.creditLine
import com.blockparty.core.model.Employee
import com.blockparty.core.model.SpriteKind
import com.blockparty.core.model.WorldState
import com.blockparty.core.npc.Applicant
import com.blockparty.core.npc.Staffing

/** Staff section for the shop editor: employees with raise/fire, and the way to the job board. */
@Composable
fun StaffSection(vm: GameViewModel, employees: List<Employee>) {
    var firing by remember { mutableStateOf<Employee?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (employees.isEmpty()) {
            Text(
                "No employees yet. Hire from the job board: cashiers, stockers, servers and greeters.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (e in employees) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpriteThumbnail(vm.sprites.images[e.spriteId], Modifier.size(52.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${e.name} · ${e.role.label}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Level ${e.level} · ${e.wagePerDay}¢/day" +
                                    if (e.traits.isNotEmpty()) " · " + e.traits.joinToString(", ") { it.label } else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Morale", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
                                LinearProgressIndicator(
                                    progress = { e.morale / 100f },
                                    modifier = Modifier.weight(1f),
                                    color = if (e.morale < 35) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { vm.giveRaise(e.id) }) { Text("Give a raise") }
                        TextButton(onClick = { firing = e }) { Text("Fire") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { vm.navigate(Screen.JobBoard) }, modifier = Modifier.fillMaxWidth()) {
            Text("Job board")
        }
    }
    firing?.let { e ->
        AlertDialog(
            onDismissRequest = { firing = null },
            title = { Text("Fire ${e.name}?") },
            text = { Text("They'll leave the shop right away.") },
            confirmButton = { TextButton(onClick = { vm.fire(e.id); firing = null }) { Text("Fire") } },
            dismissButton = { TextButton(onClick = { firing = null }) { Text("Keep") } },
        )
    }
}

/** Today's applicants. Refreshes daily; rare applicants are highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobBoardScreen(vm: GameViewModel, world: WorldState) {
    val business = vm.currentLot?.business
    var message by remember { mutableStateOf<String?>(null) }
    val applicants = vm.applicants()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job board") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "New applicants every day. Hiring for ${business?.name?.ifBlank { "your shop" } ?: "your shop"} " +
                    "(${business?.employees?.size ?: 0}/${Staffing.MAX_EMPLOYEES} staff). Wages are paid daily.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (applicants.isEmpty()) {
                Text("Nobody's applied today. Draw some employees to fill the job board!")
            }
            for (a in applicants) ApplicantCard(vm, world, a) { message = vm.hire(a) ?: "Hired ${a.name}!" }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = { vm.navigate(Screen.Draw(SpriteKind.CHARACTER, DrawPurpose.EMPLOYEE)) }) {
                Icon(Icons.Default.Brush, null); Spacer(Modifier.width(6.dp)); Text("Draw an employee")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ApplicantCard(vm: GameViewModel, world: WorldState, a: Applicant, onHire: () -> Unit) {
    val colors = if (a.rare) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SpriteThumbnail(vm.sprites.images[a.spriteId], Modifier.size(64.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (a.rare) "★ " else "") + "${a.name} · ${a.role.label}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                Text(a.role.description, style = MaterialTheme.typography.bodySmall)
                for (t in a.traits) Text("${t.label}: ${t.description}", style = MaterialTheme.typography.bodySmall)
                Text("Asks ${a.wageAsk}¢ a day", style = MaterialTheme.typography.labelLarge)
                Text(
                    creditLine(world.sprite(a.spriteId)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onHire) { Text("Hire") }
        }
    }
}
