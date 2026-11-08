package com.blockparty.app.ui.online

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blockparty.app.GameViewModel
import com.blockparty.core.model.Lot
import com.blockparty.core.model.WorldState
import com.blockparty.core.net.ChatMessage
import com.blockparty.core.net.RemoteMember
import com.blockparty.core.net.Transfer

/** Street chat for the open shared street. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: GameViewModel, world: WorldState) {
    val social = vm.social ?: return
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ChatMessage?>(null) }
    val list = rememberLazyListState()
    val messages = social.messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
        social.markRead()
    }
    fun send() {
        if (text.isBlank() || sending) return
        sending = true
        error = null
        vm.sendChat(text) { e ->
            sending = false
            error = e
            if (e == null) text = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Street chat"); Text(world.street.name, style = MaterialTheme.typography.labelMedium) } },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            social.problem?.let { "Chat isn't available right now: $it" }
                                ?: "No messages yet. Say hi to the street!",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(messages, key = { it.id }) { m ->
                    val mine = m.authorId == social.myUserId
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .combinedClickable(onClick = {}, onLongClick = { if (mine) deleting = m }),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (!mine) Text(m.authorName.ifBlank { "Someone" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(m.body, style = MaterialTheme.typography.bodyMedium)
                                Text(timeLabel(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(500) },
                    placeholder = { Text("Message the street") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                )
                IconButton(onClick = ::send, enabled = text.isNotBlank() && !sending) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this message?") },
            text = { Text(m.body) },
            confirmButton = { TextButton(onClick = { vm.deleteChat(m.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Money between players: give, lend, and pay back loans. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneyScreen(vm: GameViewModel, world: WorldState) {
    val social = vm.social ?: return
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadMoney { loadError = it } }
    // What the send dialog is for: (recipient, kind, loan being repaid, suggested amount).
    var sending by remember { mutableStateOf<SendTarget?>(null) }
    val others = social.members.filter { it.userId != social.myUserId }
    val names = social.members.associate { it.userId to it.displayName.ifBlank { "Someone" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Money") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("You have ${world.coins} coins on ${world.street.name}.", style = MaterialTheme.typography.titleMedium)
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Text("Give or lend", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (social.members.isEmpty() && loadError == null) CircularProgressIndicator(Modifier.size(24.dp))
            else if (others.isEmpty()) Text("Nobody else is on this street yet. Share the invite code!", style = MaterialTheme.typography.bodyMedium)
            for (m in others) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.displayName.ifBlank { "Someone" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { sending = SendTarget(m, Transfer.GIFT) }) { Text("Give") }
                        TextButton(onClick = { sending = SendTarget(m, Transfer.LOAN) }) { Text("Lend") }
                    }
                }
            }

            val loans = social.loans
            if (!loans.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Loans", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                for (b in loans) {
                    val other = if (b.borrowed) b.loan.fromId else b.loan.toId
                    val who = names[other] ?: b.loan.fromName.takeIf { b.borrowed && it.isNotBlank() } ?: "someone"
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = if (b.borrowed && b.outstanding > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (b.borrowed) "Borrowed ${b.loan.amount} from $who" else "Lent ${b.loan.amount} to $who",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    if (b.outstanding == 0L) "Paid back" else "${b.outstanding} still to pay back",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (b.borrowed && b.outstanding > 0 && other != null) {
                                FilledTonalButton(onClick = {
                                    sending = SendTarget(RemoteMember(other, who), Transfer.REPAYMENT, b.loan.id, b.outstanding)
                                }) { Text("Pay back") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Money sent to you arrives the next time your street syncs (usually within seconds). " +
                    "Loans are on trust: nothing makes anyone pay back, so lend to friends.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    sending?.let { target ->
        SendMoneyDialog(vm, world, target) { sending = null }
    }
}

private data class SendTarget(val to: RemoteMember, val kind: String, val loanId: String? = null, val suggested: Long? = null)

@Composable
private fun SendMoneyDialog(vm: GameViewModel, world: WorldState, target: SendTarget, onClose: () -> Unit) {
    var amount by remember { mutableStateOf(target.suggested?.coerceAtMost(world.coins)?.toString().orEmpty()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val name = target.to.displayName.ifBlank { "them" }
    val value = amount.toLongOrNull() ?: 0L
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = {
            Text(
                when (target.kind) {
                    Transfer.LOAN -> "Lend to $name"
                    Transfer.REPAYMENT -> "Pay back $name"
                    else -> "Give coins to $name"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${world.coins} coins.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() }.take(9) },
                    label = { Text("Coins") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (target.kind != Transfer.REPAYMENT) {
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(140) },
                        label = { Text("Note (optional)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    error = null
                    vm.sendMoney(target.to.userId, target.kind, value, note, target.loanId) { e ->
                        busy = false
                        if (e == null) onClose() else error = e
                    }
                },
                enabled = !busy && value > 0 && value <= world.coins,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (target.kind == Transfer.LOAN) "Lend" else if (target.kind == Transfer.REPAYMENT) "Pay back" else "Give")
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}

/** Notes left at a shop's door: read them, leave one, and remove your own (or any, on your shop). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorNotesSheet(vm: GameViewModel, world: WorldState, lot: Lot, onClose: () -> Unit) {
    val social = vm.social ?: return
    val notes = social.notesFor(lot.id)
    val myShop = lot.isMine(world)
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shopName = lot.business?.name?.ifBlank { null } ?: "this shop"
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Notes on the door of $shopName", style = MaterialTheme.typography.titleMedium)
            if (notes.isEmpty()) {
                Text(
                    if (myShop) "No notes yet. Friends can leave them when they visit." else "No notes yet. Leave one for ${lot.ownerName.ifBlank { "the owner" }}!",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (n in notes) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Note)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(n.body, style = MaterialTheme.typography.bodyMedium)
                            Text("— ${n.authorName.ifBlank { "someone" }}, ${timeLabel(n.createdAt)}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (myShop || n.authorId == social.myUserId) {
                            IconButton(onClick = { vm.deleteDoorNote(n.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "Remove note", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (!myShop) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(280) },
                    label = { Text("Leave a note") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        busy = true
                        error = null
                        vm.leaveDoorNote(lot.id, text) { e -> busy = false; if (e == null) text = "" else error = e }
                    },
                    enabled = text.isNotBlank() && !busy,
                ) { Text("Pin it to the door") }
            }
        }
    }
}

private object Color {
    val Note = androidx.compose.ui.graphics.Color(0xFFFFF4C2)
}

/** "10:42" for today, otherwise "9 Nov 10:42", in the phone's time zone. */
internal fun timeLabel(createdAt: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(createdAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
    val time = "%02d:%02d".format(t.hour, t.minute)
    if (t.toLocalDate() == today) time else "${t.dayOfMonth} ${t.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} $time"
}.getOrDefault("")
